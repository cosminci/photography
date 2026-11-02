package photography.adapters.http

import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.data.NonEmptyList
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import io.circe.Json
import org.http4s.circe.CirceEntityCodec.*
import munit.CatsEffectSuite
import org.http4s.{Header, Headers, Method, Request, Response, Status, Uri}
import org.typelevel.ci.CIString
import photography.capabilities.IdGenerator
import photography.domain.album.AlbumId
import photography.domain.editing.*
import photography.domain.layout.SpreadId
import photography.usecases.editing.{EditingAccess, EditorSessionManager}
import photography.usecases.editing.EditorSessionManager.{BindResult, RegisterResult, SelectResult}
import photography.ports.album.AlbumCompositionRepository
import photography.ports.album.AlbumCompositionRepository.{CompositionResult, Member}
import java.util.UUID
import scala.concurrent.duration.*

final class RoutesSeamIntegrationTest extends CatsEffectSuite:
  private given IdGenerator[IO] with
    override def generateId: IO[UUID] = IO(UUID.randomUUID())
  private val request    = Request[IO](Method.PUT, Uri.unsafeFromString("/write"))
  private val membership = new AlbumCompositionRepository.MembershipRead[IO]:
    override def composition(album: AlbumId): IO[CompositionResult] =
      IO.raiseError[CompositionResult](new AssertionError("Unexpected composition read"))

  test("should resolve the current album opening under admission and preserve missing or failed relationships without writing"):
    Mutex[IO].flatMap: mutex =>
      given Mutex[IO] = mutex
      val album       = AlbumId(new UUID(0L, 1L))
      val opening     = SpreadId(new UUID(0L, 3L))
      val replacement = SpreadId(new UUID(0L, 4L))
      val found       = CompositionResult.Found(NonEmptyList.one(Member(opening, maybeChapter = None)))
      for
        sessions <- EditorSessionManager.make[IO](lease = 30L)
        owner    <- bind(sessions, owner = 1L)
        selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Spread(opening))
        lock = selected match
          case SelectResult.Owned(value, _) => value
          case other                        => fail(s"Expected owned opening, obtained $other")
        current <- Ref.of[IO, CompositionResult](found)
        written <- Ref.of[IO, Int](0)
        reads   <- Ref.of[IO, Vector[AlbumId]](Vector.empty)
        membership = new AlbumCompositionRepository.MembershipRead[IO]:
          override def composition(id: AlbumId): IO[CompositionResult] =
            reads.update(_ :+ id) >> current.get
        server = Routes.make(EditingAccess.make(sessions, membership))(
          buildRoutes(IO.unit, written.update(_ + 1), protection = EditingAccess.Protection.OwnAlbumOpening(album))
        ).orNotFound
        call = request.withHeaders(Headers(credentials(owner))).putHeaders("X-Editor-Grant" -> lock.grant.value.toString)
        saved        <- server.run(call)
        _            <- current.set(CompositionResult.Found(NonEmptyList.one(Member(replacement, maybeChapter = None))))
        stale        <- server.run(call)
        staleBody    <- stale.as[Json]
        _            <- current.set(CompositionResult.AlbumNotFound)
        missing      <- server.run(call)
        missingBody  <- missing.as[Json]
        _            <- current.set(CompositionResult.Failed(AlbumCompositionRepository.Failure.Unavailable))
        failed       <- server.run(call)
        failedBody   <- failed.as[Json]
        _            <- sessions.bind(owner.session, owner.tab)
        obsolete     <- server.run(call)
        obsoleteBody <- obsolete.as[Json]
        count        <- written.get
        acquired     <- reads.get
      yield
        assertEquals(saved.status, Status.Ok)
        assertEquals(stale.status, Status.Conflict)
        assertEquals(staleBody, Json.obj("code" -> Json.fromString("stale_grant")))
        assertEquals(missing.status, Status.NotFound)
        assertEquals(missingBody, Json.obj("code" -> Json.fromString("album_not_found")))
        assertEquals(failed.status, Status.InternalServerError)
        assertEquals(failedBody, Json.obj("code" -> Json.fromString("service_failure")))
        assertEquals(obsolete.status, Status.Conflict)
        assertEquals(obsoleteBody, Json.obj("code" -> Json.fromString("obsolete_connection")))
        Vector(saved, stale, missing, failed, obsolete).foreach: response =>
          assertEquals(response.headers.get(CIString("Cache-Control")).map(_.head.value), Some("no-store"))
        assertEquals(count, 1)
        assertEquals(acquired, Vector.fill(4)(album))

  Vector(
    (failure = EditorSessionManager.Failure.StaleSession, code = "stale_session"),
    (failure = EditorSessionManager.Failure.StaleGrant, code = "stale_grant")
  ).foreach { case (failure, code) =>
    test(s"should reject $failure expiring during current opening resolution and release coordination without writing"):
      TestControl.executeEmbed:
        Mutex[IO].flatMap: mutex =>
          given Mutex[IO] = mutex
          val album       = AlbumId(new UUID(0L, 1L))
          val opening     = SpreadId(new UUID(0L, 3L))
          for
            sessions <- EditorSessionManager.make[IO](lease = 30L)
            owner    <- bind(sessions, owner = 1L)
            selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Spread(opening))
            lock = selected match
              case SelectResult.Owned(value, _) => value
              case other                        => fail(s"Expected owned opening, obtained $other")
            _ <- IO.sleep(20.seconds)
            _ <- failure match
              case EditorSessionManager.Failure.StaleGrant => sessions.heartbeat(owner).void
              case _                                       => IO.unit
            entered <- Deferred[IO, Unit]
            finish  <- Deferred[IO, Unit]
            written <- Ref.of[IO, Int](0)
            membership = new AlbumCompositionRepository.MembershipRead[IO]:
              override def composition(id: AlbumId): IO[CompositionResult] =
                IO(assertEquals(id, album)) >> entered.complete(()).void >>
                  finish.get
                    .as(CompositionResult.Found(NonEmptyList.one(Member(opening, maybeChapter = None))))
            server = Routes.make(EditingAccess.make(sessions, membership))(
              buildRoutes(IO.unit, written.update(_ + 1), protection = EditingAccess.Protection.OwnAlbumOpening(album))
            ).orNotFound
            call = request.withHeaders(Headers(credentials(owner))).putHeaders("X-Editor-Grant" -> lock.grant.value.toString)
            writing  <- server.run(call).start
            _        <- entered.get
            _        <- IO.sleep(10.seconds)
            _        <- finish.complete(())
            rejected <- writing.joinWithNever
            body     <- rejected.as[Json]
            before   <- written.get
            fresh    <- bind(sessions, owner = 2L)
            selected <- sessions.select(fresh, TargetRevision.initial, EditingTarget.Spread(opening))
            grant = selected match
              case SelectResult.Owned(value, _) => value.grant
              case other                        => fail(s"Expected fresh ownership, obtained $other")
            saved <- server.run(request.withHeaders(Headers(credentials(fresh))).putHeaders("X-Editor-Grant" -> grant.value.toString))
            after <- written.get
          yield
            assertEquals(rejected.status, Status.Conflict)
            assertEquals(body, Json.obj("code" -> Json.fromString(code)))
            assertEquals(rejected.headers.get(CIString("Cache-Control")).map(_.head.value), Some("no-store"))
            assertEquals(before, 0)
            assertEquals(saved.status, Status.Ok)
            assertEquals(after, 1)
  }

  test("should require canonical credentials before decoding and leave explicitly public routes accessible"):
    Mutex[IO].flatMap: mutex =>
      given Mutex[IO] = mutex
      for
        sessions <- EditorSessionManager.make[IO](lease = 30L)
        editor   <- bind(sessions, owner = 1L)
        decoded  <- Ref.of[IO, Int](0)
        written  <- Ref.of[IO, Int](0)
        server  = Routes.make(EditingAccess.make(sessions, membership))(buildRoutes(decoded.update(_ + 1), written.update(_ + 1))).orNotFound
        headers = credentials(editor)
        invalid = headers.flatMap { header =>
          val remaining = headers.filterNot(_.name === header.name)
          Vector(
            (remaining, "missing_credentials"),
            (remaining :+ header.copy(value = "invalid"), "invalid_credentials"),
            (remaining :+ header.copy(value = "00000000-0000-0000-0000-00000000000A"), "invalid_credentials"),
            (remaining ++ Vector(header, header), "invalid_credentials"),
            (remaining :+ header.copy(value = s"${header.value},${header.value}"), "invalid_credentials")
          )
        }
        _ <- invalid.traverse_ { case (values, code) =>
          for
            response <- server.run(request.withHeaders(Headers(values)))
            body     <- response.as[Json]
          yield
            assertEquals(response.status, Status.BadRequest)
            assertEquals(body, Json.obj("code" -> Json.fromString(code)))
            assertEquals(response.headers.get(CIString("Cache-Control")).map(_.head.value), Some("no-store"))
        }
        public <- server.run(Request[IO](Method.GET, Uri.unsafeFromString("/read")))
        invalidPublic = new Routes[IO]:
          override def routes: PartialFunction[Request[IO], Routes.Route[IO, ?]] =
            case value if value.uri.path.renderString === "/invalid" =>
              Routes.Route(
                Routes.Access.Public,
                Response[IO](Status.UnprocessableContent).asLeft[Unit].pure[IO],
                (_: Unit) => written.update(_ + 1).as(Response[IO](Status.Ok))
              )
        failedPublic <- Routes.make(EditingAccess.make(sessions, membership))(invalidPublic).orNotFound
          .run(Request[IO](Method.GET, Uri.unsafeFromString("/invalid")))
        absent <- server.run(Request[IO](Method.PUT, Uri.unsafeFromString("/undeclared")))
        reads  <- decoded.get
        writes <- written.get
      yield
        assertEquals(public.status, Status.Ok)
        assertEquals(failedPublic.status, Status.UnprocessableContent)
        assertEquals(absent.status, Status.NotFound)
        assertEquals(reads, 0)
        assertEquals(writes, 0)

  test("should decode before coordination, preserve decode failures and reject obsolete, restarted and expired editors before writing"):
    TestControl.executeEmbed:
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        for
          sessions <- EditorSessionManager.make[IO](lease = 30L)
          editor   <- bind(sessions, owner = 1L)
          written  <- Ref.of[IO, Int](0)
          server  = Routes.make(EditingAccess.make(sessions, membership))(buildRoutes(IO.unit, written.update(_ + 1))).orNotFound
          invalid = Routes.make(EditingAccess.make(sessions, membership))(buildRoutes(IO.unit, written.update(_ + 1), valid = false)).orNotFound
          decoded   <- mutex.lock.surround(invalid.run(request.withHeaders(Headers(credentials(editor)))))
          _         <- sessions.bind(editor.session, editor.tab)
          obsolete  <- server.run(request.withHeaders(Headers(credentials(editor))))
          body      <- obsolete.as[Json]
          current   <- bind(sessions, owner = 2L)
          restarted <- EditorSessionManager.make[IO](lease = 30L)
          fresh = Routes.make(EditingAccess.make(restarted, membership))(buildRoutes(IO.unit, written.update(_ + 1))).orNotFound
          stale   <- fresh.run(request.withHeaders(Headers(credentials(current))))
          _       <- IO.sleep(30.seconds)
          expired <- server.run(request.withHeaders(Headers(credentials(current))))
          writes  <- written.get
        yield
          assertEquals(decoded.status, Status.UnprocessableContent)
          assertEquals(obsolete.status, Status.Conflict)
          assertEquals(body, Json.obj("code" -> Json.fromString("obsolete_connection")))
          assertEquals(stale.status, Status.Conflict)
          assertEquals(expired.status, Status.Conflict)
          assertEquals(writes, 0)

  test("should complete an admitted write across expiry and cancellation before acquisition while expired queued writes reject"):
    TestControl.executeEmbed:
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        for
          sessions <- EditorSessionManager.make[IO](lease = 30L)
          editor   <- bind(sessions, owner = 1L)
          _        <- IO.sleep(20.seconds)
          next     <- bind(sessions, owner = 2L)
          entered  <- Deferred[IO, Unit]
          finish   <- Deferred[IO, Unit]
          canceled <- Deferred[IO, Unit]
          acquired <- Deferred[IO, (SelectResult, Int)]
          written  <- Ref.of[IO, Int](0)
          server = Routes.make(EditingAccess.make(sessions, membership))(
            buildRoutes(IO.unit, entered.complete(()).void >> finish.get >> written.update(_ + 1))
          ).orNotFound
          call = request.withHeaders(Headers(credentials(editor)))
          writing   <- server.run(call).start
          _         <- entered.get
          waiter    <- server.run(call).start
          _         <- IO.sleep(1.second)
          _         <- waiter.cancel
          waiting   <- waiter.join
          queued    <- server.run(call).start
          canceling <- (writing.cancel >> canceled.complete(())).start
          _         <- IO.sleep(10.seconds)
          acquiring <- sessions.select(next, TargetRevision.initial, EditingTarget.Cover(AlbumId(new UUID(0L, 1L))))
            .flatMap(result => written.get.flatMap(count => acquired.complete((result, count)).void)).start
          _             <- IO.sleep(1.second)
          maybeCanceled <- canceled.tryGet
          maybeAcquired <- acquired.tryGet
          _             <- IO(assertEquals(maybeCanceled, None))
          _             <- IO(assertEquals(maybeAcquired, None))
          _             <- finish.complete(())
          _             <- canceling.joinWithNever
          saved         <- writing.joinWithNever
          rejected      <- queued.joinWithNever
          _             <- acquiring.joinWithNever
          observed      <- acquired.get
        yield
          assertEquals(saved.status, Status.Ok)
          assertEquals(rejected.status, Status.Conflict)
          waiting match
            case Outcome.Canceled() => ()
            case other              => fail(s"Expected canceled waiter, obtained $other")
          observed match
            case (SelectResult.Owned(lock, revision), count) =>
              assertEquals(lock.expiresAt, 62.seconds)
              assertEquals(lock.session, next.session)
              assertEquals(revision, TargetRevision.initial.next)
              assertEquals(count, 1)
            case other => fail(s"Expected acquisition after persistence, obtained $other")

  test("should enforce spread ownership and shared changes identically for interior and exterior spreads without replay"):
    val spreads = Vector(EditingTarget.Spread(SpreadId(new UUID(0L, 3L))), EditingTarget.Cover(AlbumId(new UUID(0L, 1L))))
    TestControl.executeEmbed(spreads.traverse_ { spread =>
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        for
          sessions <- EditorSessionManager.make[IO](lease = 30L)
          owner    <- bind(sessions, owner = 1L)
          other    <- bind(sessions, owner = 2L)
          selected <- sessions.select(owner, TargetRevision.initial, spread)
          (lock, revision) = selected match
            case SelectResult.Owned(value, current) => (value, current)
            case other                              => fail(s"Expected owned spread, obtained $other")
          written <- Ref.of[IO, Int](0)
          server = Routes.make(EditingAccess.make(sessions, membership))(
            buildRoutes(IO.unit, written.update(_ + 1), protection = EditingAccess.Protection.OwnSpread(spread))
          ).orNotFound
          shared = Routes.make(EditingAccess.make(sessions, membership))(
            buildRoutes(IO.unit, written.update(_ + 1), protection = EditingAccess.Protection.ChangeSpreads(Set(spread)))
          ).orNotFound
          ownedRequest = request.withHeaders(Headers(credentials(owner))).putHeaders("X-Editor-Grant" -> lock.grant.value.toString)
          otherRequest = request.withHeaders(Headers(credentials(other)))
          malformed    = Vector(
            Vector("invalid"),
            Vector("00000000-0000-0000-0000-00000000000A"),
            Vector(s"${lock.grant.value},${lock.grant.value}"),
            Vector(lock.grant.value.toString, lock.grant.value.toString)
          )
          _ <- malformed.traverse_ { values =>
            val headers = Headers(credentials(owner) ++ values.map(value => Header.Raw(CIString("X-Editor-Grant"), value)))
            Vector(server, shared).traverse_ { app =>
              for
                response <- app.run(request.withHeaders(headers))
                body     <- response.as[Json]
              yield
                assertEquals(response.status, Status.BadRequest)
                assertEquals(body, Json.obj("code" -> Json.fromString("invalid_credentials")))
            }
          }
          missing  <- server.run(request.withHeaders(Headers(credentials(owner))))
          blocked  <- shared.run(otherRequest)
          body     <- blocked.as[Json]
          unproved <- shared.run(request.withHeaders(Headers(credentials(owner))))
          direct   <-
            EditingAccess.make(sessions, membership).run(owner, EditingAccess.Protection.OwnSpread(spread), maybeGrant = None)(written.update(_ + 1))
          borrowed <- server.run(otherRequest.putHeaders("X-Editor-Grant" -> lock.grant.value.toString))
          saved    <- server.run(ownedRequest)
          _        <- sessions.release(owner, revision, lock.grant)
          selected <- sessions.select(owner, revision.next, spread)
          successor = selected match
            case SelectResult.Owned(value, _) => value
            case other                        => fail(s"Expected reacquisition, obtained $other")
          stale       <- server.run(ownedRequest)
          _           <- IO.sleep(20.seconds)
          _           <- sessions.heartbeat(owner)
          _           <- IO.sleep(10.seconds)
          expired     <- server.run(ownedRequest.putHeaders("X-Editor-Grant" -> successor.grant.value.toString))
          expiredBody <- expired.as[Json]
          count       <- written.get
        yield
          assertEquals(missing.status, Status.BadRequest)
          assertEquals(blocked.status, Status.Conflict)
          assertEquals(
            body,
            Json.obj(
              "code"    -> Json.fromString("target_locked"),
              "ownerId" -> Json.fromString(new UUID(0L, 1L).toString),
              "alias"   -> Json.fromString("Owner")
            )
          )
          assertEquals(unproved.status, Status.Conflict)
          assertEquals(direct, EditingAccess.Result.Rejected[Unit](EditorSessionManager.Failure.StaleGrant))
          assertEquals(borrowed.status, Status.Conflict)
          assertEquals(saved.status, Status.Ok)
          assertEquals(stale.status, Status.Conflict)
          assertEquals(expired.status, Status.Conflict)
          assertEquals(expiredBody, Json.obj("code" -> Json.fromString("stale_grant")))
          Vector(missing, blocked, unproved, borrowed, saved, stale, expired).foreach(response =>
            assertEquals(response.headers.get(CIString("Cache-Control")).map(_.head.value), Some("no-store"))
          )
          assertEquals(count, 1)
    })

  test("should permit changes to unlocked spreads, reject supplied obsolete proof and release coordination after failure"):
    Mutex[IO].flatMap: mutex =>
      given Mutex[IO] = mutex
      val spread      = EditingTarget.Cover(AlbumId(new UUID(0L, 1L)))
      for
        sessions <- EditorSessionManager.make[IO](lease = 30L)
        owner    <- bind(sessions, owner = 1L)
        written  <- Ref.of[IO, Int](0)
        server = Routes.make(EditingAccess.make(sessions, membership))(
          buildRoutes(IO.unit, written.update(_ + 1), protection = EditingAccess.Protection.ChangeSpreads(Set(spread)))
        ).orNotFound
        failing = Routes.make(EditingAccess.make(sessions, membership))(
          buildRoutes(IO.unit, IO.raiseError[Unit](new IllegalStateException("Write failed")))
        ).orNotFound
        call = request.withHeaders(Headers(credentials(owner)))
        stale  <- server.run(call.putHeaders("X-Editor-Grant" -> new UUID(0L, 99L).toString))
        failed <- failing.run(call).attempt
        saved  <- server.run(call)
        count  <- written.get
      yield
        assertEquals(stale.status, Status.Conflict)
        assert(failed.isLeft)
        assertEquals(saved.status, Status.Ok)
        assertEquals(count, 1)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildRoutes(
      decode: IO[Unit],
      write: IO[Unit],
      valid: Boolean = true,
      protection: EditingAccess.Protection = EditingAccess.Protection.ActiveTab
  ) = new Routes[IO]:
    override def routes: PartialFunction[Request[IO], Routes.Route[IO, ?]] =
      case value if value.method === Method.GET =>
        Routes.Route.public(Response[IO](Status.Ok).pure[IO])
      case value if value.uri.path.renderString === "/write" =>
        val parsed = decode.as(
          if valid then ().asRight[Response[IO]] else Response[IO](Status.UnprocessableContent).asLeft[Unit]
        )
        Routes.Route(Routes.Access.Editor(protection), parsed, (_: Unit) => write.as(Response[IO](Status.Ok)))
  // scalafix:on DisableSyntax.defaultArgs

  private def credentials(connection: EditorConnection) = Vector(
    Header.Raw(CIString("X-Editor-Session"), connection.session.value.toString),
    Header.Raw(CIString("X-Editor-Tab"), connection.tab.value.toString),
    Header.Raw(CIString("X-Editor-Generation"), connection.generation.value.toString)
  )

  private def bind(sessions: EditorSessionManager[IO], owner: Long) =
    for
      registered <- sessions.register(EditorId(new UUID(0L, owner)), "Owner", maybeSession = None)
      session = registered match
        case RegisterResult.Ready(value) => value.id
        case other                       => fail(s"Expected session, obtained $other")
      bound <- sessions.bind(session, TabId(new UUID(0L, owner)))
    yield bound match
      case BindResult.Active(value) => value
      case other                    => fail(s"Expected binding, obtained $other")
