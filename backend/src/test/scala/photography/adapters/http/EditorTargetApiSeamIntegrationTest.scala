package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.{Stream, text}
import io.circe.Json
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityCodec.*
import org.typelevel.ci.CIString
import photography.capabilities.IdGenerator
import photography.domain.album.AlbumId
import photography.domain.editing.*
import photography.domain.layout.SpreadId
import photography.usecases.editing.EditorSessionManager
import photography.usecases.editing.EditorSessionManager.*

import java.util.UUID
import scala.concurrent.duration.*

final class EditorTargetApiSeamIntegrationTest extends CatsEffectSuite:
  private val connection = EditorConnection(SessionId(new UUID(0L, 1L)), TabId(new UUID(0L, 2L)), ConnectionGeneration(new UUID(0L, 3L)))
  private val grant      = GrantId(new UUID(0L, 4L))
  private val owner      = EditorId(new UUID(0L, 5L))
  private val targetId   = new UUID(0L, 6L)
  private val base       = "/api/editor-session/target"

  List(
    ("spread", EditingTarget.Spread(SpreadId(targetId))),
    ("cover", EditingTarget.Cover(AlbumId(targetId)))
  ).foreach { case (kind, target) =>
    test(s"should carry exact credentials and $kind identity across target selection, reconciliation, renewal and release"):
      val lock     = EditingLock(target, connection.session, grant, 30.seconds)
      val revision = TargetRevision.initial.next
      val expected = Json.obj(
        "revision" -> Json.fromString("1"),
        "grant"    -> encodeGrant(kind, grant.value, remainingMillis = 30000L)
      )

      TestControl.executeEmbed {
        for
          refs <- Ref.of[IO, Calls](Calls(Vector.empty, Vector.empty, Vector.empty, Vector.empty))
          service = buildService(
            refs,
            SelectResult.Owned(lock, revision),
            ReadTargetResult.Current(revision, Some(lock)),
            RenewTargetResult.Renewed(lock),
            ReleaseResult.Released(revision.next)
          )
          app = PublicRoutes(EditorSessionApi.make(service)).orNotFound
          selected     <- app.run(request(Method.PUT).withEntity(selection(kind, revision = "0")))
          selectedBody <- selected.as[Json]
          read         <- app.run(request(Method.GET))
          readBody     <- read.as[Json]
          renewed      <- app.run(request(Method.POST).putHeaders("X-Editor-Grant" -> grant.value.toString))
          renewedBody  <- renewed.as[Json]
          released     <- app.run(request(Method.DELETE).putHeaders("X-Editor-Grant" -> grant.value.toString)
            .withEntity(Json.obj("revision" -> Json.fromString("1"))))
          releasedBody <- released.as[Json]
          calls        <- refs.get
        yield
          assertEquals(Vector(selected.status, read.status, renewed.status, released.status), Vector.fill(4)(Status.Ok))
          assertEquals(selectedBody, expected)
          assertEquals(readBody, expected)
          assertEquals(renewedBody, encodeGrant(kind, grant.value, remainingMillis = 30000L))
          assertEquals(releasedBody, Json.obj("revision" -> Json.fromString("2")))
          assertEquals(
            calls,
            Calls(
              Vector((connection, TargetRevision.initial, target)),
              Vector(connection),
              Vector((connection, grant)),
              Vector((connection, revision, grant))
            )
          )
          assertEquals(selected.headers.get(CIString("Cache-Control")).map(_.head.value), Some("no-store"))
      }
  }

  test("should expose public contention and the advanced transition without another owner's private grant"):
    TestControl.executeEmbed {
      for
        refs <- Ref.of[IO, Calls](Calls(Vector.empty, Vector.empty, Vector.empty, Vector.empty))
        service = buildService(
          refs,
          SelectResult.Blocked(owner, alias = "Other browser", 12.seconds, TargetRevision.initial.next),
          ReadTargetResult.Current(TargetRevision.initial.next, None),
          RenewTargetResult.Rejected(Failure.StaleGrant),
          ReleaseResult.Rejected(Failure.StaleTransition)
        )
        app = PublicRoutes(EditorSessionApi.make(service)).orNotFound
        response <- app.run(request(Method.PUT).withEntity(selection("spread", revision = "0")))
        body     <- response.as[Json]
        read     <- app.run(request(Method.GET))
        current  <- read.as[Json]
      yield
        assertEquals(response.status, Status.Conflict)
        assertEquals(
          body,
          Json.obj(
            "code"            -> Json.fromString("target_locked"),
            "ownerId"         -> Json.fromString(owner.value.toString),
            "alias"           -> Json.fromString("Other browser"),
            "remainingMillis" -> Json.fromLong(12000L),
            "revision"        -> Json.fromString("1")
          )
        )
        assertEquals(current, Json.obj("revision" -> Json.fromString("1"), "grant" -> Json.Null))
    }

  List(
    (Failure.StaleSession, "stale_session"),
    (Failure.ObsoleteConnection, "obsolete_connection"),
    (Failure.StaleTransition, "stale_transition"),
    (Failure.StaleGrant, "stale_grant")
  ).foreach { case (failure, code) =>
    test(s"should preserve $failure for every target operation"):
      for
        refs <- Ref.of[IO, Calls](Calls(Vector.empty, Vector.empty, Vector.empty, Vector.empty))
        service = buildService(
          refs,
          SelectResult.Rejected(failure),
          ReadTargetResult.Rejected(failure),
          RenewTargetResult.Rejected(failure),
          ReleaseResult.Rejected(failure)
        )
        app = PublicRoutes(EditorSessionApi.make(service)).orNotFound
        responses <- Vector(
          request(Method.GET),
          request(Method.PUT).withEntity(selection("cover", revision = "0")),
          request(Method.POST).putHeaders("X-Editor-Grant" -> grant.value.toString),
          request(Method.DELETE).putHeaders("X-Editor-Grant" -> grant.value.toString).withEntity(Json.obj("revision" -> Json.fromString("0")))
        ).traverse(req =>
          app.run(req).flatMap(response =>
            response.as[Json].map(body =>
              (response.status, body, response.headers.get(CIString("Cache-Control")).map(_.head.value))
            )
          )
        )
      yield assertEquals(responses, Vector.fill(4)((Status.Conflict, Json.obj("code" -> Json.fromString(code)), Some("no-store"))))
  }

  test("should reject invalid target representations and noncanonical or missing credentials before calling authority"):
    val invalidBodies = Vector(
      selection("unknown", revision = "0"),
      selection("spread", revision = "-1"),
      selection("spread", revision = "01"),
      selection("spread", revision = "+1"),
      selection("spread", revision = "1.0"),
      selection("spread", revision = ""),
      Json.obj("revision" -> Json.fromInt(0), "target" -> Json.obj("kind" -> Json.fromString("cover"), "id" -> Json.fromString(targetId.toString))),
      selection("spread", revision = "0").deepMerge(Json.obj("extra" -> Json.True)),
      selection("spread", revision = "0").deepMerge(Json.obj("target" -> Json.obj("extra" -> Json.True))),
      selection("spread", revision = "0").deepMerge(Json.obj("target" -> Json.obj("id" -> Json.fromString("invalid")))),
      Json.obj("revision" -> Json.fromString("0"))
    )

    for
      refs <- Ref.of[IO, Calls](Calls(Vector.empty, Vector.empty, Vector.empty, Vector.empty))
      service = buildService(
        refs,
        SelectResult.Rejected(Failure.StaleSession),
        ReadTargetResult.Rejected(Failure.StaleSession),
        RenewTargetResult.Rejected(Failure.StaleSession),
        ReleaseResult.Rejected(Failure.StaleSession)
      )
      app = PublicRoutes(EditorSessionApi.make(service)).orNotFound
      invalid   <- invalidBodies.traverse(body => app.run(request(Method.PUT).withEntity(body)).flatMap(_.as[Json]))
      malformed <- app.run(request(Method.PUT).withBodyStream(Stream.emit("{").covary[IO].through(text.utf8.encode))
        .putHeaders("Content-Type" -> "application/json"))
      malformedBody     <- malformed.as[Json]
      missing           <- app.run(Request[IO](uri = Uri.unsafeFromString(base)))
      missingBody       <- missing.as[Json]
      invalidHeader     <- app.run(request(Method.GET).putHeaders("X-Editor-Generation" -> "invalid"))
      invalidHeaderBody <- invalidHeader.as[Json]
      missingGrant      <- app.run(request(Method.POST))
      missingGrantBody  <- missingGrant.as[Json]
      invalidGrant      <- app.run(request(Method.POST).putHeaders("X-Editor-Grant" -> "invalid"))
      invalidGrantBody  <- invalidGrant.as[Json]
      badRelease        <- app.run(request(Method.DELETE).putHeaders("X-Editor-Grant" -> grant.value.toString)
        .withEntity(Json.obj("revision" -> Json.fromString("0"), "extra" -> Json.True)))
      badReleaseBody <- badRelease.as[Json]
      calls          <- refs.get
    yield
      assertEquals(invalid, Vector.fill(invalidBodies.size)(Json.obj("code" -> Json.fromString("invalid_target_request"))))
      assertEquals(malformedBody, Json.obj("code" -> Json.fromString("invalid_target_request")))
      assertEquals(missingBody, Json.obj("code" -> Json.fromString("missing_credentials")))
      assertEquals(invalidHeaderBody, Json.obj("code" -> Json.fromString("invalid_credentials")))
      assertEquals(missingGrantBody, Json.obj("code" -> Json.fromString("missing_credentials")))
      assertEquals(invalidGrantBody, Json.obj("code" -> Json.fromString("invalid_credentials")))
      assertEquals(badReleaseBody, Json.obj("code" -> Json.fromString("invalid_target_request")))
      assertEquals(
        Vector(malformed.status, missing.status, invalidHeader.status, missingGrant.status, invalidGrant.status, badRelease.status),
        Vector.fill(6)(Status.BadRequest)
      )
      assertEquals(calls, Calls(Vector.empty, Vector.empty, Vector.empty, Vector.empty))

  test("should reconcile lost transition acknowledgements and reject stale retries without releasing the current grant"):
    TestControl.executeEmbed {
      given IdGenerator[IO] = IdGenerator.make[IO]
      for
        mutex   <- Mutex[IO]
        manager <- {
          given Mutex[IO] = mutex
          EditorSessionManager.make[IO](lease = 30L)
        }
        registered <- manager.register(owner, alias = "Cosmin", maybeSession = None)
        session    <- registered match
          case RegisterResult.Ready(value) => IO.pure(value)
          case _                           => IO.raiseError[EditorSession](new AssertionError("Expected registered session"))
        bound  <- manager.bind(session.id, connection.tab)
        active <- bound match
          case BindResult.Active(value) => IO.pure(value)
          case _                        => IO.raiseError[EditorConnection](new AssertionError("Expected tab binding"))
        app    = PublicRoutes(EditorSessionApi.make(manager)).orNotFound
        select = connectedRequest(Method.PUT, active).withEntity(selection("cover", revision = "0"))
        selected  <- app.run(select)
        _         <- selected.body.compile.drain
        read      <- app.run(connectedRequest(Method.GET, active))
        saved     <- read.as[Json]
        privateId <- IO.fromEither(saved.hcursor.downField("grant").get[UUID]("grantId"))
        late      <- app.run(select)
        lateBody  <- late.as[Json]
        released  <- app.run(connectedRequest(Method.DELETE, active).putHeaders("X-Editor-Grant" -> privateId.toString)
          .withEntity(Json.obj("revision" -> Json.fromString("1"))))
        _            <- released.body.compile.drain
        afterRelease <- app.run(connectedRequest(Method.GET, active)).flatMap(_.as[Json])
        _            <- IO.sleep(10.seconds)
        _            <- manager.heartbeat(active)
        acquired     <- app.run(connectedRequest(Method.PUT, active).withEntity(selection("cover", revision = "2")))
        acquiredBody <- acquired.as[Json]
        successorId  <- IO.fromEither(acquiredBody.hcursor.downField("grant").get[UUID]("grantId"))
        lateRenew    <- app.run(connectedRequest(Method.POST, active).putHeaders("X-Editor-Grant" -> privateId.toString))
        renewalBody  <- lateRenew.as[Json]
        lateRelease  <- app.run(connectedRequest(Method.DELETE, active).putHeaders("X-Editor-Grant" -> privateId.toString)
          .withEntity(Json.obj("revision" -> Json.fromString("3"))))
        releaseBody <- lateRelease.as[Json]
        current     <- app.run(connectedRequest(Method.GET, active)).flatMap(_.as[Json])
      yield
        val expectedInitial   = Json.obj("revision" -> Json.fromString("1"), "grant" -> encodeGrant("cover", privateId, remainingMillis = 30000L))
        val expectedSuccessor = Json.obj("revision" -> Json.fromString("3"), "grant" -> encodeGrant("cover", successorId, remainingMillis = 30000L))
        assertEquals(Vector(selected.status, read.status, released.status, acquired.status), Vector.fill(4)(Status.Ok))
        assertEquals(saved, expectedInitial)
        assertEquals(late.status, Status.Conflict)
        assertEquals(lateBody, Json.obj("code" -> Json.fromString("stale_transition")))
        assertEquals(afterRelease, Json.obj("revision" -> Json.fromString("2"), "grant" -> Json.Null))
        assertEquals(Vector(lateRenew.status, lateRelease.status), Vector.fill(2)(Status.Conflict))
        assertEquals(Vector(renewalBody, releaseBody), Vector.fill(2)(Json.obj("code" -> Json.fromString("stale_grant"))))
        assertEquals(acquiredBody, expectedSuccessor)
        assertEquals(current, expectedSuccessor)
        assertNotEquals(successorId, privateId)
    }

  test("should preserve unbounded revisions and report elapsed leases as zero rather than exposing backend deadlines"):
    val decimal = "1234567890123456789012345678901234567890"
    val target  = EditingTarget.Cover(AlbumId(targetId))
    val lock    = EditingLock(target, connection.session, grant, 1.second)

    TestControl.executeEmbed {
      for
        revision <- IO.fromOption(TargetRevision.from(BigInt(decimal)))(new AssertionError("Expected valid revision"))
        refs     <- Ref.of[IO, Calls](Calls(Vector.empty, Vector.empty, Vector.empty, Vector.empty))
        service = buildService(
          refs,
          SelectResult.Owned(lock, revision.next),
          ReadTargetResult.Current(revision, Some(lock)),
          RenewTargetResult.Renewed(lock),
          ReleaseResult.Released(revision.next)
        )
        app = PublicRoutes(EditorSessionApi.make(service)).orNotFound
        _        <- IO.sleep(2.seconds)
        response <- app.run(request(Method.PUT).withEntity(selection("cover", revision = decimal)))
        body     <- response.as[Json]
        calls    <- refs.get
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(
          body,
          Json.obj(
            "revision" -> Json.fromString((BigInt(decimal) + 1).toString),
            "grant"    -> encodeGrant("cover", grant.value, remainingMillis = 0L)
          )
        )
        assertEquals(calls, Calls(Vector((connection, revision, target)), Vector.empty, Vector.empty, Vector.empty))
    }

  test("should expire target authority independently of accepted session heartbeats and allow a competing browser to acquire"):
    TestControl.executeEmbed {
      given IdGenerator[IO] = IdGenerator.make[IO]
      for
        mutex   <- Mutex[IO]
        manager <- {
          given Mutex[IO] = mutex
          EditorSessionManager.make[IO](lease = 30L)
        }
        first  <- registerConnection(manager, owner, connection.tab)
        second <- registerConnection(manager, EditorId(new UUID(0L, 7L)), TabId(new UUID(0L, 8L)))
        app = PublicRoutes(EditorSessionApi.make(manager)).orNotFound
        (firstResponse, secondResponse) <- (
          app.run(connectedRequest(Method.PUT, first).withEntity(selection("spread", revision = "0"))),
          app.run(connectedRequest(Method.PUT, second).withEntity(selection("spread", revision = "0")))
        ).parTupled
        (winner, loser, won, blocked) =
          if firstResponse.status === Status.Ok then (first, second, firstResponse, secondResponse)
          else (second, first, secondResponse, firstResponse)
        wonBody     <- won.as[Json]
        blockedBody <- blocked.as[Json]
        privateId   <- IO.fromEither(wonBody.hcursor.downField("grant").get[UUID]("grantId"))
        _           <- IO.sleep(20.seconds)
        _           <- manager.heartbeat(first)
        _           <- manager.heartbeat(second)
        _           <- IO.sleep(10.seconds)
        late        <- app.run(connectedRequest(Method.POST, winner).putHeaders("X-Editor-Grant" -> privateId.toString))
        lateBody    <- late.as[Json]
        caughtUp    <- app.run(connectedRequest(Method.GET, winner)).flatMap(_.as[Json])
        acquired    <- app.run(connectedRequest(Method.PUT, loser).withEntity(selection("spread", revision = "1")))
        newBody     <- acquired.as[Json]
        successorId <- IO.fromEither(newBody.hcursor.downField("grant").get[UUID]("grantId"))
        readback    <- app.run(connectedRequest(Method.GET, loser))
        current     <- readback.as[Json]
      yield
        val expectedInitial   = Json.obj("revision" -> Json.fromString("1"), "grant" -> encodeGrant("spread", privateId, remainingMillis = 30000L))
        val expectedSuccessor = Json.obj("revision" -> Json.fromString("2"), "grant" -> encodeGrant("spread", successorId, remainingMillis = 30000L))
        assertEquals(Vector(won.status, blocked.status), Vector(Status.Ok, Status.Conflict))
        assertEquals(wonBody, expectedInitial)
        assertEquals(blockedBody.hcursor.get[String]("code").toOption, Some("target_locked"))
        assertEquals(blockedBody.hcursor.downField("grantId").focus, None)
        assertEquals(late.status, Status.Conflict)
        assertEquals(lateBody, Json.obj("code" -> Json.fromString("stale_grant")))
        assertEquals(caughtUp, Json.obj("revision" -> Json.fromString("2"), "grant" -> Json.Null))
        assertEquals(acquired.status, Status.Ok)
        assertEquals(readback.status, Status.Ok)
        assertEquals(newBody, expectedSuccessor)
        assertEquals(current, expectedSuccessor)
        assertNotEquals(successorId, privateId)
    }

  private case class Calls(
      selections: Vector[(EditorConnection, TargetRevision, EditingTarget)],
      reads: Vector[EditorConnection],
      renewals: Vector[(EditorConnection, GrantId)],
      releases: Vector[(EditorConnection, TargetRevision, GrantId)]
  )

  private def selection(kind: String, revision: String) = Json.obj(
    "revision" -> Json.fromString(revision),
    "target"   -> Json.obj("kind" -> Json.fromString(kind), "id" -> Json.fromString(targetId.toString))
  )

  private def encodeGrant(kind: String, credential: UUID, remainingMillis: Long) = Json.obj(
    "target"          -> Json.obj("kind" -> Json.fromString(kind), "id" -> Json.fromString(targetId.toString)),
    "grantId"         -> Json.fromString(credential.toString),
    "remainingMillis" -> Json.fromLong(remainingMillis)
  )

  private def request(method: Method) = connectedRequest(method, connection)

  private def connectedRequest(method: Method, active: EditorConnection) =
    Request[IO](method, Uri.unsafeFromString(base)).putHeaders(
      "X-Editor-Session"    -> active.session.value.toString,
      "X-Editor-Tab"        -> active.tab.value.toString,
      "X-Editor-Generation" -> active.generation.value.toString
    )

  private def registerConnection(manager: EditorSessionManager[IO], browser: EditorId, document: TabId) =
    manager.register(browser, alias = "Browser", maybeSession = None).flatMap {
      case RegisterResult.Ready(session) => manager.bind(session.id, document).flatMap {
          case BindResult.Active(active) => IO.pure(active)
          case _                         => IO.raiseError[EditorConnection](new AssertionError("Expected binding"))
        }
      case _ => IO.raiseError[EditorConnection](new AssertionError("Expected registration"))
    }

  private def buildService(
      refs: Ref[IO, Calls],
      selected: SelectResult,
      current: ReadTargetResult,
      renewed: RenewTargetResult,
      released: ReleaseResult
  ) = new EditorSessionManager[IO]:
    override def authorizeConnection(connection: EditorConnection): IO[ConnectionAuthorizationResult] =
      IO.raiseError[ConnectionAuthorizationResult](new AssertionError("Unexpected connection admission"))
    override def select(connection: EditorConnection, revision: TargetRevision, target: EditingTarget): IO[SelectResult] =
      refs.update(calls => calls.copy(selections = calls.selections :+ (connection, revision, target))).as(selected)
    override def readTarget(connection: EditorConnection): IO[ReadTargetResult] =
      refs.update(calls => calls.copy(reads = calls.reads :+ connection)).as(current)
    override def renewTarget(connection: EditorConnection, grant: GrantId): IO[RenewTargetResult] =
      refs.update(calls => calls.copy(renewals = calls.renewals :+ (connection, grant))).as(renewed)
    override def release(connection: EditorConnection, revision: TargetRevision, grant: GrantId): IO[ReleaseResult] =
      refs.update(calls => calls.copy(releases = calls.releases :+ (connection, revision, grant))).as(released)
    override def register(owner: EditorId, alias: EditorAlias, maybeSession: Option[SessionId]): IO[RegisterResult] =
      IO.raiseError[RegisterResult](new AssertionError("Unexpected registration"))
    override def bind(session: SessionId, tab: TabId): IO[BindResult] =
      IO.raiseError[BindResult](new AssertionError("Unexpected binding"))
    override def unbind(connection: EditorConnection): IO[UnbindResult] =
      IO.raiseError[UnbindResult](new AssertionError("Unexpected unbind"))
    override def read(connection: EditorConnection): IO[ReadResult] =
      IO.raiseError[ReadResult](new AssertionError("Unexpected session read"))
    override def heartbeat(connection: EditorConnection): IO[HeartbeatResult] =
      IO.raiseError[HeartbeatResult](new AssertionError("Unexpected heartbeat"))
    override def authorizeTarget(connection: EditorConnection, target: EditingTarget, grant: GrantId): IO[AuthorizationResult] =
      IO.raiseError[AuthorizationResult](new AssertionError("Unexpected authorization"))
    override def authorizeAffected(
        connection: EditorConnection,
        targets: Set[EditingTarget],
        maybeGrant: Option[GrantId]
    ): IO[AuthorizationResult] = IO.raiseError[AuthorizationResult](new AssertionError("Unexpected authorization"))
