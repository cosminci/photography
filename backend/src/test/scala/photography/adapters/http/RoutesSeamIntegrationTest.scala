package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{Header, Headers, Method, Request, Response, Status, Uri}
import org.http4s.circe.CirceEntityCodec.*
import org.typelevel.ci.CIString
import photography.domain.album.AlbumId
import photography.domain.editing.*
import photography.domain.layout.SpreadId
import photography.usecases.editing.EditingAccess
import photography.usecases.editing.EditingAccess.{Protection, Result}
import photography.usecases.editing.EditorSessionManager.Failure

import java.util.UUID

final class RoutesSeamIntegrationTest extends CatsEffectSuite:
  private val connection = EditorConnection(SessionId(new UUID(0L, 1L)), TabId(new UUID(0L, 2L)), ConnectionGeneration(new UUID(0L, 3L)))
  private val grant      = GrantId(new UUID(0L, 4L))
  private val headers    = Vector(
    Header.Raw(CIString("X-Editor-Session"), connection.session.value.toString),
    Header.Raw(CIString("X-Editor-Tab"), connection.tab.value.toString),
    Header.Raw(CIString("X-Editor-Generation"), connection.generation.value.toString),
    Header.Raw(CIString("X-Editor-Grant"), grant.value.toString)
  )
  private val request = Request[IO](Method.PUT, Uri.unsafeFromString("/write")).withHeaders(Headers(headers))

  test("should validate canonical headers and decoding before forwarding an admission"):
    val invalid = headers.flatMap { header =>
      val remaining = headers.filterNot(_.name === header.name)
      Vector(
        (remaining, "missing_credentials"),
        (remaining :+ header.copy(value = "invalid"), "invalid_credentials"),
        (remaining :+ header.copy(value = "00000000-0000-0000-0000-00000000000A"), "invalid_credentials"),
        (remaining ++ Vector(header, header), "invalid_credentials"),
        (remaining :+ header.copy(value = s"${header.value},${header.value}"), "invalid_credentials")
      )
    }
    for
      decoded <- Ref.of[IO, Int](0)
      calls   <- Ref.of[IO, Vector[(EditorConnection, Protection, Option[GrantId])]](Vector.empty)
      protection = Protection.OwnSpread(EditingTarget.Spread(SpreadId(new UUID(0L, 5L))))
      access     = buildAccess(calls, Result.Completed(()))
      server     = Routes.make(access)(buildRoutes(protection, decoded.update(_ + 1).as(().asRight[Response[IO]]))).orNotFound
      _ <- invalid.traverse_ { case (values, code) =>
        for
          response <- server.run(request.withHeaders(Headers(values)))
          body     <- response.as[Json]
        yield
          assertEquals(response.status, Status.BadRequest)
          assertEquals(body, Json.obj("code" -> Json.fromString(code)))
          assertEquals(response.headers.get(CIString("Cache-Control")).map(_.head.value), Some("no-store"))
      }
      before <- decoded.get
      invalidBody = Routes.make(access)(buildRoutes(protection, Response[IO](Status.UnprocessableContent).asLeft[Unit].pure[IO])).orNotFound
      rejected  <- invalidBody.run(request)
      untouched <- calls.get
      saved     <- server.run(request)
      admitted  <- calls.get
    yield
      assertEquals(before, 0)
      assertEquals(rejected.status, Status.UnprocessableContent)
      assertEquals(untouched, Vector.empty)
      assertEquals(saved.status, Status.Ok)
      assertEquals(admitted, Vector((connection, protection, Some(grant))))
      assertEquals(rejected.headers.get(CIString("Cache-Control")).map(_.head.value), Some("no-store"))
      assertEquals(saved.headers.get(CIString("Cache-Control")).map(_.head.value), Some("no-store"))

  Vector(
    (Protection.ActiveTab, none[GrantId], Some("invalid"), Status.Ok),
    (Protection.ChangeSpreads(Set.empty), Some(grant), Some(grant.value.toString), Status.Ok),
    (Protection.ChangeSpreads(Set.empty), none[GrantId], none[String], Status.Ok),
    (Protection.ChangeSpreads(Set.empty), none[GrantId], Some("invalid"), Status.BadRequest),
    (Protection.OwnAlbumOpening(AlbumId(new UUID(0L, 5L))), Some(grant), Some(grant.value.toString), Status.Ok)
  ).foreach { case (protection, maybeGrant, supplied, status) =>
    test(s"should forward $protection with grant header $supplied without implementing its authorization policy"):
      for
        calls <- Ref.of[IO, Vector[(EditorConnection, Protection, Option[GrantId])]](Vector.empty)
        server       = Routes.make(buildAccess(calls, Result.Completed(())))(buildRoutes(protection, ().asRight[Response[IO]].pure[IO])).orNotFound
        withoutGrant = request.withHeaders(Headers(headers.filterNot(_.name === CIString("X-Editor-Grant"))))
        response <- server.run(supplied.fold(withoutGrant)(value => withoutGrant.putHeaders("X-Editor-Grant" -> value)))
        admitted <- calls.get
      yield
        assertEquals(response.status, status)
        assertEquals(
          admitted,
          if status === Status.Ok then Vector((connection, protection, maybeGrant)) else Vector.empty[(EditorConnection, Protection, Option[GrantId])]
        )
  }

  Vector(
    (result = Result.Rejected[Unit](Failure.StaleSession), status = Status.Conflict, body = Json.obj("code" -> Json.fromString("stale_session"))),
    (
      result = Result.Rejected[Unit](Failure.ObsoleteConnection),
      status = Status.Conflict,
      body = Json.obj("code" -> Json.fromString("obsolete_connection"))
    ),
    (result = Result.Rejected[Unit](Failure.StaleGrant), status = Status.Conflict, body = Json.obj("code" -> Json.fromString("stale_grant"))),
    (
      result = Result.Rejected[Unit](Failure.StaleTransition),
      status = Status.Conflict,
      body = Json.obj("code" -> Json.fromString("stale_transition"))
    ),
    (result = Result.AlbumNotFound[Unit](), status = Status.NotFound, body = Json.obj("code" -> Json.fromString("album_not_found"))),
    (result = Result.Failed[Unit](), status = Status.InternalServerError, body = Json.obj("code" -> Json.fromString("service_failure"))),
    (
      result = Result.Blocked[Unit](EditorId(new UUID(0L, 6L)), "Other"),
      status = Status.Conflict,
      body = Json.obj(
        "code"    -> Json.fromString("target_locked"),
        "ownerId" -> Json.fromString(new UUID(0L, 6L).toString),
        "alias"   -> Json.fromString("Other")
      )
    )
  ).foreach { case (result, status, expected) =>
    test(s"should encode $result without executing its handler"):
      for
        calls <- Ref.of[IO, Vector[(EditorConnection, Protection, Option[GrantId])]](Vector.empty)
        definitions = new Routes[IO]:
          override def routes: PartialFunction[Request[IO], Routes.Route[IO, ?]] =
            case value if value.uri.path.renderString === "/write" =>
              Routes.Route.write(Protection.ActiveTab)(fail("Rejected admission constructed its handler"))
        server = Routes.make(buildAccess(calls, result))(definitions).orNotFound
        response <- server.run(request)
        body     <- response.as[Json]
      yield
        assertEquals(response.status, status)
        assertEquals(body, expected)
        assertEquals(response.headers.get(CIString("Cache-Control")).map(_.head.value), Some("no-store"))
  }

  test("should leave public and unmatched routes outside admission, preserving public decode failures"):
    for
      calls <- Ref.of[IO, Vector[(EditorConnection, Protection, Option[GrantId])]](Vector.empty)
      definitions = new Routes[IO]:
        override def routes: PartialFunction[Request[IO], Routes.Route[IO, ?]] =
          case value if value.uri.path.renderString === "/read"    => Routes.Route.public(Response[IO](Status.Ok).pure[IO])
          case value if value.uri.path.renderString === "/invalid" =>
            Routes.Route(
              Routes.Access.Public,
              Response[IO](Status.BadRequest).asLeft[Unit].pure[IO],
              (_: Unit) => IO.raiseError[Response[IO]](new AssertionError("Invalid body reached handler"))
            )
      server = Routes.make(buildAccess(calls, Result.Failed[Unit]()))(definitions).orNotFound
      read     <- server.run(Request[IO](uri = Uri.unsafeFromString("/read")))
      invalid  <- server.run(Request[IO](uri = Uri.unsafeFromString("/invalid")))
      absent   <- server.run(request)
      admitted <- calls.get
    yield
      assertEquals(read.status, Status.Ok)
      assertEquals(invalid.status, Status.BadRequest)
      assertEquals(absent.status, Status.NotFound)
      assertEquals(admitted, Vector.empty)

  private def buildRoutes(protection: Protection, decode: IO[Either[Response[IO], Unit]]) = new Routes[IO]:
    override def routes: PartialFunction[Request[IO], Routes.Route[IO, ?]] =
      case value if value.uri.path.renderString === "/write" =>
        Routes.Route(Routes.Access.Editor(protection), decode, (_: Unit) => Response[IO](Status.Ok).pure[IO])

  private def buildAccess(calls: Ref[IO, Vector[(EditorConnection, Protection, Option[GrantId])]], result: Result[Unit]) = new EditingAccess[IO]:
    override def run[A](editor: EditorConnection, protection: Protection, maybeGrant: Option[GrantId])(write: IO[A]): IO[Result[A]] =
      calls.update(_ :+ (editor, protection, maybeGrant)) >>
        (result match
          case Result.Completed(_)          => write.map(Result.Completed(_))
          case Result.Rejected(reason)      => Result.Rejected[A](reason).pure[IO]
          case Result.Blocked(owner, alias) => Result.Blocked[A](owner, alias).pure[IO]
          case Result.AlbumNotFound()       => Result.AlbumNotFound[A]().pure[IO]
          case Result.Failed()              => Result.Failed[A]().pure[IO])
