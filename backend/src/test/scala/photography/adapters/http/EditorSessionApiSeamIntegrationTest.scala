package photography.adapters.http

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.circe.{DecodingFailure, Json}
import io.circe.jawn.parse
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.http4s.{Method, Request, ServerSentEvent, Status, Uri}
import org.http4s.circe.CirceEntityCodec.*
import photography.capabilities.IdGenerator
import photography.domain.editing.*
import photography.usecases.editing.EditorSessionManager
import photography.usecases.editing.EditorSessionManager.*

import java.util.UUID
import scala.concurrent.duration.*

final class EditorSessionApiSeamIntegrationTest extends CatsEffectSuite:
  private val owner    = new UUID(0L, 1L).toString
  private val tab      = new UUID(0L, 2L).toString
  private val otherTab = new UUID(0L, 3L).toString
  private val base     = "/api/editor-session"

  test("should register and privately resume a remembered browser without returning a tab credential"):
    for
      manager <- buildManager
      app = EditorSessionApi.make(manager).orNotFound
      first       <- app.run(register("Cosmin"))
      session     <- first.as[Json]
      credential  <- IO.fromEither(session.hcursor.get[String]("sessionId"))
      resumed     <- app.run(register("Changed").putHeaders("X-Editor-Session" -> credential))
      resumedBody <- resumed.as[Json]
      blocked     <- app.run(register("Cosmin"))
      blockedBody <- blocked.as[Json]
    yield
      assertEquals(first.status, Status.Ok)
      assertEquals(session.hcursor.get[String]("alias"), "Cosmin".asRight[DecodingFailure])
      assertEquals(session.hcursor.get[Long]("leaseSeconds"), 30L.asRight[DecodingFailure])
      assertEquals(session.hcursor.get[Long]("heartbeatSeconds"), 10L.asRight[DecodingFailure])
      assertEquals(session.hcursor.downField("generation").focus, None)
      assertEquals(resumed.status, Status.Ok)
      assertEquals(resumedBody.hcursor.get[String]("sessionId"), credential.asRight[DecodingFailure])
      assertEquals(resumedBody.hcursor.get[String]("alias"), "Cosmin".asRight[DecodingFailure])
      assertEquals(blocked.status, Status.Conflict)
      assertEquals(blockedBody, Json.obj("code" -> Json.fromString("credential_required")))

  test("should reject malformed identity, blank alias and malformed or duplicate private headers"):
    val invalidBodies = Vector(
      Json.obj("editorId" -> Json.fromString("invalid"), "alias"                              -> Json.fromString("Cosmin")),
      Json.obj("editorId" -> Json.fromString("00000000-0000-0000-0000-00000000000A"), "alias" -> Json.fromString("Cosmin")),
      Json.obj("editorId" -> Json.fromString(owner), "alias"                                  -> Json.fromString("  ")),
      Json.obj("editorId" -> Json.fromString(owner), "alias"                                  -> Json.fromInt(1)),
      Json.obj("editorId" -> Json.fromString(owner)),
      Json.obj("editorId" -> Json.fromString(owner), "alias"                                  -> Json.fromString("Cosmin"), "extra" -> Json.True)
    )

    for
      manager <- buildManager
      app = EditorSessionApi.make(manager).orNotFound
      results       <- invalidBodies.traverse(body => app.run(Request[IO](Method.POST, Uri.unsafeFromString(base)).withEntity(body)).map(_.status))
      malformed     <- app.run(register("Cosmin").putHeaders("X-Editor-Session" -> "invalid"))
      malformedBody <- app.run(Request[IO](Method.POST, Uri.unsafeFromString(base)).withEntity("{"))
      missingStream <- app.run(Request[IO](uri = Uri.unsafeFromString(s"$base/events")))
      duplicate     <- app.run(register("Cosmin").withHeaders(org.http4s.Headers(
        org.http4s.Header.Raw(org.typelevel.ci.CIString("X-Editor-Session"), owner),
        org.http4s.Header.Raw(org.typelevel.ci.CIString("X-Editor-Session"), owner)
      )))
    yield
      assertEquals(results, Vector.fill(invalidBodies.size)(Status.BadRequest))
      assertEquals(malformed.status, Status.BadRequest)
      assertEquals(malformedBody.status, Status.BadRequest)
      assertEquals(missingStream.status, Status.BadRequest)
      assertEquals(duplicate.status, Status.BadRequest)

  test("should bind only while consuming SSE, block a different document and release on cancellation"):
    for
      manager <- buildManager
      app = EditorSessionApi.make(manager).orNotFound
      session <- registerSession(manager)
      ready   <- Deferred[IO, Json]
      stream  <- app.run(bind(session.id, tab))
      before  <- manager.bind(session.id, TabId(UUID.fromString(otherTab)))
      _       <- before match
        case BindResult.Active(connection) => manager.unbind(connection).void
        case _                             => IO.raiseError[Unit](new AssertionError("Response construction bound a tab"))
      consuming <- stream.body.through(ServerSentEvent.decoder[IO]).evalMap(event =>
        event.data.traverse_(data => IO.fromEither(parse(data)).flatMap(ready.complete).void)
      ).compile.drain.start
      bound           <- ready.get
      duplicate       <- app.run(bind(session.id, otherTab))
      duplicateEvents <- duplicate.body.through(ServerSentEvent.decoder[IO]).compile.toVector
      _               <- consuming.cancel
      replacement     <- manager.bind(session.id, TabId(UUID.fromString(otherTab)))
    yield
      assertEquals(stream.status, Status.Ok)
      assertEquals(bound.hcursor.get[String]("sessionId"), session.id.value.toString.asRight[DecodingFailure])
      assertEquals(bound.hcursor.get[String]("tabId"), tab.asRight[DecodingFailure])
      assertEquals(duplicateEvents.flatMap(_.eventType), Vector("tab_blocked"))
      assertEquals(duplicateEvents.flatMap(_.data), Vector(s"""{"ownerId":"$owner","alias":"Cosmin"}"""))
      replacement match
        case BindResult.Active(_) => ()
        case result               => fail(s"Expected released binding, received $result")

  test("should fence obsolete stream cleanup and requests after same-document reconnect"):
    for
      manager <- buildManager
      app = EditorSessionApi.make(manager).orNotFound
      session    <- registerSession(manager)
      firstReady <- Deferred[IO, Json]
      first      <- app.run(bind(session.id, tab))
      firstFiber <- first.body.through(ServerSentEvent.decoder[IO]).evalMap(event =>
        event.data.traverse_(data => IO.fromEither(parse(data)).flatMap(firstReady.complete).void)
      ).compile.drain.start
      firstBinding <- firstReady.get
      secondReady  <- Deferred[IO, Json]
      second       <- app.run(bind(session.id, tab))
      secondFiber  <- second.body.through(ServerSentEvent.decoder[IO]).evalMap(event =>
        event.data.traverse_(data => IO.fromEither(parse(data)).flatMap(secondReady.complete).void)
      ).compile.drain.start
      secondBinding <- secondReady.get
      _             <- firstFiber.cancel
      stale         <- app.run(connected(Method.POST, "/heartbeat", firstBinding))
      current       <- app.run(connected(Method.GET, "", secondBinding))
      currentBody   <- current.as[Json]
      _             <- secondFiber.cancel
    yield
      assertEquals(stale.status, Status.Conflict)
      assertEquals(current.status, Status.Ok)
      assertEquals(currentBody.hcursor.get[String]("ownerId"), owner.asRight[DecodingFailure])

  test("should accept header-only heartbeats, expire idle streams and reject restart credentials"):
    TestControl.executeEmbed {
      for
        manager <- buildManager
        app = EditorSessionApi.make(manager).orNotFound
        session  <- registerSession(manager)
        ready    <- Deferred[IO, Json]
        events   <- Ref.of[IO, Vector[String]](Vector.empty)
        response <- app.run(bind(session.id, tab))
        fiber    <- response.body.through(ServerSentEvent.decoder[IO]).evalMap(event =>
          events.update(_ ++ event.eventType) >> event.data.traverse_(data =>
            IO.fromEither(parse(data)).flatMap(value =>
              if event.eventType.contains("bound") then ready.complete(value).void else IO.unit
            )
          )
        ).compile.drain.start
        binding   <- ready.get
        _         <- IO.sleep(20.seconds)
        alive     <- app.run(connected(Method.POST, "/heartbeat", binding))
        _         <- IO.sleep(10.seconds)
        current   <- app.run(connected(Method.GET, "", binding))
        queryOnly <- app.run(Request[IO](uri = Uri.unsafeFromString(s"$base?sessionId=${session.id.value}")))
        restarted <- buildManager
        old       <- EditorSessionApi.make(restarted).orNotFound.run(connected(Method.GET, "", binding))
        _         <- IO.sleep(20.seconds)
        _         <- fiber.joinWithNever
        seen      <- events.get
        expired   <- app.run(connected(Method.POST, "/heartbeat", binding))
      yield
        assertEquals(alive.status, Status.Ok)
        assertEquals(current.status, Status.Ok)
        assertEquals(queryOnly.status, Status.BadRequest)
        assertEquals(old.status, Status.Conflict)
        assertEquals(seen, Vector("bound", "session_lost"))
        assertEquals(expired.status, Status.Conflict)
    }

  test("should report a stale session on stream acquisition without opening application access"):
    for
      manager  <- buildManager
      response <- EditorSessionApi.make(manager).orNotFound.run(bind(SessionId(UUID.fromString(owner)), tab))
      events   <- response.body.through(ServerSentEvent.decoder[IO]).compile.toVector
    yield assertEquals(
      events.filter(_.eventType.nonEmpty),
      Vector(ServerSentEvent(data = Some("""{"code":"stale_session"}"""), eventType = Some("session_lost")))
    )

  Vector(
    Failure.StaleSession       -> "stale_session",
    Failure.ObsoleteConnection -> "obsolete_connection",
    Failure.StaleTransition    -> "stale_transition",
    Failure.StaleGrant         -> "stale_grant"
  ).foreach { case (failure, code) =>
    test(s"should preserve $failure from the session port in readback, heartbeat and stream loss"):
      TestControl.executeEmbed {
        for
          manager <- buildManager
          session <- registerSession(manager)
          service = rejectRequests(manager, failure)
          app     = EditorSessionApi.make(service).orNotFound
          response  <- app.run(bind(session.id, tab))
          events    <- response.body.through(ServerSentEvent.decoder[IO]).compile.toVector
          bound     <- IO.fromEither(events.flatMap(_.data).headOption.toRight[Throwable](new AssertionError("Missing bound event")).flatMap(parse))
          read      <- app.run(connected(Method.GET, "", bound))
          readBody  <- read.as[Json]
          heartbeat <- app.run(connected(Method.POST, "/heartbeat", bound))
          heartbeatBody <- heartbeat.as[Json]
        yield
          val expected = Json.obj("code" -> Json.fromString(code))
          assertEquals(read.status, Status.Conflict)
          assertEquals(readBody, expected)
          assertEquals(heartbeat.status, Status.Conflict)
          assertEquals(heartbeatBody, expected)
          assertEquals(events.flatMap(_.eventType), Vector("bound", "session_lost"))
          assertEquals(events.flatMap(_.data).lastOption, Some(expected.noSpaces))
      }
  }

  private def register(alias: String) =
    Request[IO](Method.POST, Uri.unsafeFromString(base))
      .withEntity(Json.obj("editorId" -> Json.fromString(owner), "alias" -> Json.fromString(alias)))

  private def bind(session: SessionId, document: String) =
    Request[IO](uri = Uri.unsafeFromString(s"$base/events"))
      .putHeaders("X-Editor-Session" -> session.value.toString, "X-Editor-Tab" -> document)

  private def connected(method: Method, suffix: String, binding: Json) =
    val cursor  = binding.hcursor
    val headers =
      for
        session    <- cursor.get[String]("sessionId")
        document   <- cursor.get[String]("tabId")
        generation <- cursor.get[String]("generation")
      yield org.http4s.Headers(
        org.http4s.Header.Raw(org.typelevel.ci.CIString("X-Editor-Session"), session),
        org.http4s.Header.Raw(org.typelevel.ci.CIString("X-Editor-Tab"), document),
        org.http4s.Header.Raw(org.typelevel.ci.CIString("X-Editor-Generation"), generation)
      )
    Request[IO](method, Uri.unsafeFromString(base + suffix)).withHeaders(headers.fold(error => fail(error.message), identity))

  private def registerSession(manager: EditorSessionManager[IO]) =
    manager.register(EditorId(UUID.fromString(owner)), alias = "Cosmin", maybeSession = None).flatMap {
      case RegisterResult.Ready(session) => IO.pure(session)
      case _                             => IO.raiseError[EditorSession](new AssertionError("Expected registered session"))
    }

  private def buildManager =
    given IdGenerator[IO] = IdGenerator.make[IO]
    Mutex[IO].flatMap { mutex =>
      given Mutex[IO] = mutex
      EditorSessionManager.make[IO](lease = 30L)
    }

  private def rejectRequests(manager: EditorSessionManager[IO], failure: Failure) = new EditorSessionManager[IO]:
    override def register(owner: EditorId, alias: EditorAlias, maybeSession: Option[SessionId]): IO[RegisterResult] =
      manager.register(owner, alias, maybeSession)
    override def bind(session: SessionId, tab: TabId): IO[BindResult]         = manager.bind(session, tab)
    override def unbind(connection: EditorConnection): IO[UnbindResult]       = manager.unbind(connection)
    override def heartbeat(connection: EditorConnection): IO[HeartbeatResult] = IO.pure(HeartbeatResult.Rejected(failure))
    override def read(connection: EditorConnection): IO[ReadResult]           = IO.pure(ReadResult.Rejected(failure))
    override def select(connection: EditorConnection, revision: TargetRevision, target: EditingTarget): IO[SelectResult] =
      manager.select(connection, revision, target)
    override def release(connection: EditorConnection, revision: TargetRevision, grant: GrantId): IO[ReleaseResult] =
      manager.release(connection, revision, grant)
    override def renewTarget(connection: EditorConnection, grant: GrantId): IO[RenewTargetResult] = manager.renewTarget(connection, grant)
    override def readTarget(connection: EditorConnection): IO[ReadTargetResult]                   = manager.readTarget(connection)
    override def authorizeTarget(connection: EditorConnection, target: EditingTarget, grant: GrantId): IO[AuthorizationResult] =
      manager.authorizeTarget(connection, target, grant)
    override def authorizeAffected(
        connection: EditorConnection,
        targets: Set[EditingTarget],
        maybeGrant: Option[GrantId]
    ): IO[AuthorizationResult] = manager.authorizeAffected(connection, targets, maybeGrant)
