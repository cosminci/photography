package photography.adapters.http

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Decoder, Json}
import io.github.iltotore.iron.*
import org.http4s.{HttpRoutes, Request, Response, ServerSentEvent}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci.CIString
import photography.domain.editing.*
import photography.usecases.editing.EditorSessionManager
import photography.usecases.editing.EditorSessionManager.*

import java.util.UUID
import scala.concurrent.duration.*

object EditorSessionApi:
  def make[F[_]: Async](sessions: EditorSessionManager[F]): HttpRoutes[F] = new LiveEditorSessionApi(sessions).routes

  final private class LiveEditorSessionApi[F[_]: Async](sessions: EditorSessionManager[F]) extends Http4sDsl[F]:
    def routes: HttpRoutes[F] = HttpRoutes.of[F]:
      case request @ POST -> Root / "api" / "editor-session" =>
        register(request)
      case request @ GET -> Root / "api" / "editor-session" / "events" =>
        (requiredId(request, "X-Editor-Session"), requiredId(request, "X-Editor-Tab")).tupled match
          case Left(message)         => BadRequest(code(message)).map(_.putHeaders("Cache-Control" -> "no-store"))
          case Right((session, tab)) =>
            Ok(bind(SessionId(session), TabId(tab))).map(_.putHeaders("Cache-Control" -> "no-store", "X-Accel-Buffering" -> "no"))
      case request @ GET -> Root / "api" / "editor-session" =>
        withConnection(request)(connection =>
          sessions.read(connection).flatMap {
            case ReadResult.Current(session) => Ok(encodeSession(session))
            case ReadResult.Rejected(reason) => Conflict(code(encodeFailure(reason)))
          }
        )
      case request @ POST -> Root / "api" / "editor-session" / "heartbeat" =>
        withConnection(request)(connection =>
          sessions.heartbeat(connection).flatMap {
            case HeartbeatResult.Alive(session)   => Ok(encodeSession(session))
            case HeartbeatResult.Rejected(reason) => Conflict(code(encodeFailure(reason)))
          }
        )

    private def register(request: Request[F]) =
      request.attemptAs[Json].value.flatMap {
        case Left(_)     => BadRequest(code("invalid_registration"))
        case Right(body) =>
          (registration.decodeJson(body).leftMap(_ => "invalid_registration"), optionalId(request, "X-Editor-Session")).tupled match
            case Left(message)                         => BadRequest(code(message))
            case Right(((owner, alias), maybeSession)) =>
              sessions.register(owner, alias, maybeSession.map(SessionId(_))).flatMap {
                case RegisterResult.Ready(session)     => Ok(encodeSession(session))
                case RegisterResult.CredentialRequired => Conflict(code("credential_required"))
              }
      }.map(_.putHeaders("Cache-Control" -> "no-store"))

    private def withConnection(request: Request[F])(run: EditorConnection => F[Response[F]]) =
      val parsed = (
        requiredId(request, "X-Editor-Session"),
        requiredId(request, "X-Editor-Tab"),
        requiredId(request, "X-Editor-Generation")
      ).mapN((session, tab, generation) => EditorConnection(SessionId(session), TabId(tab), ConnectionGeneration(generation)))
      parsed.fold(message => BadRequest(code(message)), run).map(_.putHeaders("Cache-Control" -> "no-store"))

    private def bind(session: SessionId, tab: TabId) =
      val binding = Resource.make(sessions.bind(session, tab)) {
        case BindResult.Active(connection) => sessions.unbind(connection).void
        case _                             => Async[F].unit
      }
      Stream.resource(binding).flatMap {
        case BindResult.Active(connection) =>
          val data = Json.obj(
            "sessionId"  -> Json.fromString(connection.session.value.toString),
            "tabId"      -> Json.fromString(connection.tab.value.toString),
            "generation" -> Json.fromString(connection.generation.value.toString)
          )
          Stream.emit(event("bound", data)) ++ monitor(connection)
        case BindResult.Blocked(owner, alias) =>
          Stream.emit(event("tab_blocked", Json.obj("ownerId" -> Json.fromString(owner.value.toString), "alias" -> Json.fromString(alias))))
        case BindResult.StaleSession => Stream.emit(event("session_lost", code("stale_session")))
      }

    private def monitor(connection: EditorConnection) =
      Stream.awakeEvery[F](10.seconds).evalMap(_ => sessions.read(connection)).map {
        case ReadResult.Current(_)       => (ServerSentEvent(comment = Some("heartbeat")), false)
        case ReadResult.Rejected(reason) => (event("session_lost", code(encodeFailure(reason))), true)
      }.takeThrough { case (_, lost) => !lost }.map { case (notification, _) => notification }

    private val registration = Decoder.forProduct2("editorId", "alias")((owner: String, alias: String) => (owner, alias))
      .emap { case (owner, alias) =>
        (parseId(owner), alias.refineEither[EditorAliasConstraint]).mapN((id, label) => (EditorId(id), label))
      }.validate(_.keys.exists(_.toSet === Set("editorId", "alias")), "Invalid registration")

    private def requiredId(request: Request[F], name: String) =
      optionalId(request, name).flatMap(_.toRight("missing_credentials"))

    private def optionalId(request: Request[F], name: String) =
      request.headers.headers.filter(_.name === CIString(name)).map(_.value) match
        case Nil          => none[UUID].asRight[String]
        case value :: Nil => parseId(value).map(_.some)
        case _            => "invalid_credentials".asLeft[Option[UUID]]

    private def parseId(value: String) =
      Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value).toRight("invalid_credentials")

    private def encodeSession(session: EditorSession) = Json.obj(
      "sessionId"        -> Json.fromString(session.id.value.toString),
      "ownerId"          -> Json.fromString(session.owner.value.toString),
      "alias"            -> Json.fromString(session.alias),
      "leaseSeconds"     -> Json.fromLong((session.expiresAt - session.lastHeartbeat).toSeconds),
      "heartbeatSeconds" -> Json.fromLong(10L)
    )

    private def encodeFailure(reason: Failure) = reason match
      case Failure.StaleSession       => "stale_session"
      case Failure.ObsoleteConnection => "obsolete_connection"
      case Failure.StaleTransition    => "stale_transition"
      case Failure.StaleGrant         => "stale_grant"

    private def code(value: String) = Json.obj("code" -> Json.fromString(value))

    private def event(name: String, data: Json) = ServerSentEvent(data = Some(data.noSpaces), eventType = Some(name))
