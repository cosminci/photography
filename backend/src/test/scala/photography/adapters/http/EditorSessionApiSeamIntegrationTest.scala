package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.{Stream, text}
import io.circe.Json
import io.circe.jawn.parse
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.http4s.{Header, Headers, MediaType, Method, Request, ServerSentEvent, Status, Uri}
import org.http4s.circe.CirceEntityCodec.*
import org.typelevel.ci.CIString
import photography.domain.editing.*
import photography.usecases.editing.EditorSessionManager
import photography.usecases.editing.EditorSessionManager.*

import java.util.UUID
import scala.concurrent.duration.*

final class EditorSessionApiSeamIntegrationTest extends CatsEffectSuite:
  private val owner             = new UUID(0L, 1L).toString
  private val tab               = new UUID(0L, 2L).toString
  private val base              = "/api/editor-session"
  private val activeConnection  = EditorConnection(SessionId(new UUID(0L, 10L)), TabId(UUID.fromString(tab)), ConnectionGeneration(new UUID(0L, 11L)))
  private val registeredSession = EditorSession(
    activeConnection.session,
    EditorId(UUID.fromString(owner)),
    "Cosmin",
    0.seconds,
    30.seconds,
    Some(activeConnection),
    TargetRevision.initial
  )

  test("should encode registration contention and binding rejection from the session port"):
    for
      refs <- Ref.of[IO, Calls](Calls(Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty))
      service = buildService(refs, registeredSession, RegisterResult.CredentialRequired, BindResult.StaleSession)
      app     = PublicRoutes(EditorSessionApi.make(service)).orNotFound
      registration <- app.run(register("Cosmin"))
      body         <- registration.as[Json]
      stream       <- app.run(bind(activeConnection.session, tab))
      events       <- stream.body.through(ServerSentEvent.decoder[IO]).compile.toVector
    yield
      assertEquals(registration.status, Status.Conflict)
      assertEquals(body, Json.obj("code" -> Json.fromString("credential_required")))
      assertEquals(
        events.filter(_.eventType.nonEmpty),
        Vector(ServerSentEvent(data = Some("""{"code":"stale_session"}"""), eventType = Some("session_lost")))
      )

  test("should encode a blocked binding without exposing credentials or acquiring a stream"):
    for
      refs <- Ref.of[IO, Calls](Calls(Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty))
      service = buildService(
        refs,
        registeredSession,
        RegisterResult.Ready(registeredSession),
        BindResult.Blocked(registeredSession.owner, registeredSession.alias)
      )
      stream <- PublicRoutes(EditorSessionApi.make(service)).orNotFound.run(bind(activeConnection.session, tab))
      events <- stream.body.through(ServerSentEvent.decoder[IO]).compile.toVector
      calls  <- refs.get
    yield
      assertEquals(events.flatMap(_.eventType), Vector("tab_blocked"))
      assertEquals(events.flatMap(_.data), Vector(s"""{"ownerId":"$owner","alias":"Cosmin"}"""))
      assertEquals(calls.bindings, Vector((activeConnection.session, activeConnection.tab)))
      assertEquals(calls.reads, Vector.empty)
      assertEquals(calls.releases, Vector.empty)

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
      app = PublicRoutes(EditorSessionApi.make(manager)).orNotFound
      results <- invalidBodies.traverse(body =>
        app.run(Request[IO](Method.POST, Uri.unsafeFromString(base)).withEntity(body)).flatMap(response =>
          response.as[Json].tupleLeft(response.status)
        )
      )
      malformed     <- app.run(register("Cosmin").putHeaders("X-Editor-Session" -> "invalid"))
      malformedJson <- malformed.as[Json]
      malformedBody <- app.run(Request[IO](Method.POST, Uri.unsafeFromString(base))
        .withBodyStream(Stream.emit("{").covary[IO].through(text.utf8.encode)).putHeaders("Content-Type" -> "application/json"))
      missingStream     <- app.run(Request[IO](uri = Uri.unsafeFromString(s"$base/events")))
      malformedBodyJson <- malformedBody.as[Json]
      missingStreamJson <- missingStream.as[Json]
      duplicate         <- app.run(register("Cosmin").withHeaders(Headers(
        Header.Raw(CIString("X-Editor-Session"), owner),
        Header.Raw(CIString("X-Editor-Session"), owner)
      )))
      duplicateJson <- duplicate.as[Json]
    yield
      val invalidRegistration = Json.obj("code" -> Json.fromString("invalid_registration"))
      val invalidCredentials  = Json.obj("code" -> Json.fromString("invalid_credentials"))
      assertEquals(results, Vector.fill(invalidBodies.size)((Status.BadRequest, invalidRegistration)))
      assertEquals(malformed.status, Status.BadRequest)
      assertEquals(malformedJson, invalidCredentials)
      assertEquals(malformedBody.status, Status.BadRequest)
      assertEquals(malformedBodyJson, Json.obj("code" -> Json.fromString("invalid_registration")))
      assertEquals(missingStream.status, Status.BadRequest)
      assertEquals(missingStreamJson, Json.obj("code" -> Json.fromString("missing_credentials")))
      assertEquals(missingStream.headers.get(CIString("Cache-Control")).map(_.head.value), Some("no-store"))
      assertEquals(duplicate.status, Status.BadRequest)
      assertEquals(duplicateJson, invalidCredentials)

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
          app     = PublicRoutes(EditorSessionApi.make(service)).orNotFound
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

  test("should translate requests and scope exact-generation cleanup through the substituted session port"):
    TestControl.executeEmbed {
      val sessionId  = SessionId(new UUID(0L, 10L))
      val connection = EditorConnection(sessionId, TabId(UUID.fromString(tab)), ConnectionGeneration(new UUID(0L, 11L)))
      val session    =
        EditorSession(sessionId, EditorId(UUID.fromString(owner)), alias = "Cosmin", 0.seconds, 30.seconds, Some(connection), TargetRevision.initial)
      val binding = Json.obj(
        "sessionId"  -> Json.fromString(sessionId.value.toString),
        "tabId"      -> Json.fromString(tab),
        "generation" -> Json.fromString(connection.generation.value.toString)
      )

      for
        refs <- Ref.of[IO, Calls](Calls(Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty))
        app =
          PublicRoutes(EditorSessionApi.make(buildService(refs, session, RegisterResult.Ready(session), BindResult.Active(connection)))).orNotFound
        registered <- app.run(register("Cosmin").putHeaders("X-Editor-Session" -> sessionId.value.toString))
        response   <- app.run(bind(sessionId, tab))
        before     <- refs.get
        events     <- response.body.through(ServerSentEvent.decoder[IO]).take(2).compile.toVector
        elapsed    <- IO.monotonic
        read       <- app.run(connected(Method.GET, "", binding))
        heartbeat  <- app.run(connected(Method.POST, "/heartbeat", binding))
        after      <- refs.get
      yield
        val expectedBefore = Calls(Vector((session.owner, session.alias, Some(sessionId))), Vector.empty, Vector.empty, Vector.empty, Vector.empty)
        val expectedAfter  = expectedBefore.copy(
          bindings = Vector((sessionId, connection.tab)),
          reads = Vector(connection, connection),
          heartbeats = Vector(connection),
          releases = Vector(connection)
        )
        assertEquals(registered.status, Status.Ok)
        assertEquals(before, expectedBefore)
        assertEquals(after, expectedAfter)
        assertEquals(read.status, Status.Ok)
        assertEquals(heartbeat.status, Status.Ok)
        assertEquals(elapsed, 10.seconds)
        assertEquals(response.contentType.map(_.mediaType), Some(MediaType.`text/event-stream`))
        assertEquals(response.headers.get(CIString("Cache-Control")).map(_.head.value), Some("no-store"))
        assertEquals(response.headers.get(CIString("X-Accel-Buffering")).map(_.head.value), Some("no"))
        assertEquals(
          events,
          Vector(
            ServerSentEvent(data = Some(binding.noSpaces), eventType = Some("bound")),
            ServerSentEvent(comment = Some("heartbeat"))
          )
        )
    }

  private case class Calls(
      registrations: Vector[(EditorId, EditorAlias, Option[SessionId])],
      bindings: Vector[(SessionId, TabId)],
      reads: Vector[EditorConnection],
      heartbeats: Vector[EditorConnection],
      releases: Vector[EditorConnection]
  )

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
      yield Headers(
        Header.Raw(CIString("X-Editor-Session"), session),
        Header.Raw(CIString("X-Editor-Tab"), document),
        Header.Raw(CIString("X-Editor-Generation"), generation)
      )
    Request[IO](method, Uri.unsafeFromString(base + suffix)).withHeaders(headers.fold(error => fail(error.message), identity))

  private def registerSession(manager: EditorSessionManager[IO]) =
    manager.register(EditorId(UUID.fromString(owner)), alias = "Cosmin", maybeSession = None).flatMap {
      case RegisterResult.Ready(session) => IO.pure(session)
      case _                             => IO.raiseError[EditorSession](new AssertionError("Expected registered session"))
    }

  private def buildManager =
    Ref.of[IO, Calls](Calls(Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty))
      .map(refs => buildService(refs, registeredSession, RegisterResult.Ready(registeredSession), BindResult.Active(activeConnection)))

  private def rejectRequests(manager: EditorSessionManager[IO], failure: Failure) = new EditorSessionManager[IO]:
    override def authorizeConnection(connection: EditorConnection): IO[ConnectionAuthorizationResult] = manager.authorizeConnection(connection)
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

  private def buildService(refs: Ref[IO, Calls], session: EditorSession, registration: RegisterResult, binding: BindResult) =
    new EditorSessionManager[IO]:
      override def authorizeConnection(connection: EditorConnection): IO[ConnectionAuthorizationResult] =
        IO.raiseError[ConnectionAuthorizationResult](new AssertionError("Unexpected connection admission"))
      override def register(owner: EditorId, alias: EditorAlias, maybeSession: Option[SessionId]): IO[RegisterResult] =
        refs.update(calls => calls.copy(registrations = calls.registrations :+ (owner, alias, maybeSession))).as(registration)
      override def bind(session: SessionId, tab: TabId): IO[BindResult] =
        refs.update(calls => calls.copy(bindings = calls.bindings :+ (session, tab))).as(binding)
      override def unbind(connection: EditorConnection): IO[UnbindResult] =
        refs.update(calls => calls.copy(releases = calls.releases :+ connection)).as(UnbindResult.Released)
      override def heartbeat(connection: EditorConnection): IO[HeartbeatResult] =
        refs.update(calls => calls.copy(heartbeats = calls.heartbeats :+ connection)).as(HeartbeatResult.Alive(session))
      override def read(connection: EditorConnection): IO[ReadResult] =
        refs.update(calls => calls.copy(reads = calls.reads :+ connection)).as(ReadResult.Current(session))
      override def select(connection: EditorConnection, revision: TargetRevision, target: EditingTarget): IO[SelectResult] =
        IO.raiseError[SelectResult](new AssertionError("Unexpected target selection"))
      override def release(connection: EditorConnection, revision: TargetRevision, grant: GrantId): IO[ReleaseResult] =
        IO.raiseError[ReleaseResult](new AssertionError("Unexpected target release"))
      override def renewTarget(connection: EditorConnection, grant: GrantId): IO[RenewTargetResult] =
        IO.raiseError[RenewTargetResult](new AssertionError("Unexpected target renewal"))
      override def readTarget(connection: EditorConnection): IO[ReadTargetResult] =
        IO.raiseError[ReadTargetResult](new AssertionError("Unexpected target read"))
      override def authorizeTarget(connection: EditorConnection, target: EditingTarget, grant: GrantId): IO[AuthorizationResult] =
        IO.raiseError[AuthorizationResult](new AssertionError("Unexpected target admission"))
      override def authorizeAffected(
          connection: EditorConnection,
          targets: Set[EditingTarget],
          maybeGrant: Option[GrantId]
      ): IO[AuthorizationResult] = IO.raiseError[AuthorizationResult](new AssertionError("Unexpected shared admission"))
