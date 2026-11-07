package photography.adapters.http

import photography.adapters.http.Routes.Route
import org.http4s.Request

import cats.effect.{Async, Clock, Resource}
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Decoder, Json}
import io.github.iltotore.iron.*
import org.http4s.{Response, ServerSentEvent}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.circe.jsonOf
import org.http4s.dsl.Http4sDsl
import photography.adapters.http.EditorCredentials.*
import photography.domain.editing.*
import photography.domain.album.AlbumId
import photography.domain.layout.SpreadId
import photography.usecases.editing.EditorSessionManager
import photography.usecases.editing.EditorSessionManager.*

import scala.concurrent.duration.*

object EditorSessionApi:
  def make[F[_]: Async](sessions: EditorSessionManager[F]): Routes[F] = new LiveEditorSessionApi(sessions)

  final private class LiveEditorSessionApi[F[_]: Async](sessions: EditorSessionManager[F]) extends Routes[F] with Http4sDsl[F]:
    override def routes: PartialFunction[Request[F], Route[F, ?]] = {
      case request @ POST -> Root / "api" / "editor-session" => Route.public {
          register(request)
        }
      case request @ GET -> Root / "api" / "editor-session" / "events" => Route.public {
          (requiredId(request, "X-Editor-Session"), requiredId(request, "X-Editor-Tab")).tupled match
            case Left(message)         => BadRequest(code(message))
            case Right((session, tab)) =>
              Ok(bind(SessionId(session), TabId(tab))).map(_.putHeaders("X-Accel-Buffering" -> "no"))
        }
      case request @ GET -> Root / "api" / "editor-session" => Route.public {
          withConnection(request) { connection =>
            sessions.read(connection).flatMap {
              case ReadResult.Current(session) => Ok(encodeSession(session))
              case ReadResult.Rejected(reason) => Conflict(code(encodeFailure(reason)))
            }
          }
        }
      case request @ PATCH -> Root / "api" / "editor-session" => Route.public {
          withConnection(request) { connection =>
            withBody(request, aliasChange) { alias =>
              sessions.rename(connection, alias).flatMap {
                case RenameResult.Renamed(session) => Ok(encodeSession(session))
                case RenameResult.Rejected(reason) => Conflict(code(encodeFailure(reason)))
              }
            }
          }
        }
      case request @ DELETE -> Root / "api" / "editor-session" / "binding" => Route.public {
          withConnection(request) { connection =>
            sessions.unbind(connection).flatMap {
              case UnbindResult.Released         => Ok(Json.obj("released" -> Json.True))
              case UnbindResult.Rejected(reason) => Conflict(code(encodeFailure(reason)))
            }
          }
        }
      case request @ POST -> Root / "api" / "editor-session" / "heartbeat" => Route.public {
          withConnection(request) { connection =>
            sessions.heartbeat(connection).flatMap {
              case HeartbeatResult.Alive(session)   => Ok(encodeSession(session))
              case HeartbeatResult.Rejected(reason) => Conflict(code(encodeFailure(reason)))
            }
          }
        }
      case request @ GET -> Root / "api" / "editor-session" / "target" => Route.public {
          withConnection(request) { connection =>
            sessions.readTarget(connection).flatMap {
              case ReadTargetResult.Current(revision, maybeLock) =>
                maybeLock.traverse(encodeGrant).flatMap(lock =>
                  Ok(encodeRevision(revision).deepMerge(Json.obj("grant" -> lock.getOrElse(Json.Null))))
                )
              case ReadTargetResult.Rejected(reason) => Conflict(code(encodeFailure(reason)))
            }
          }
        }
      case request @ PUT -> Root / "api" / "editor-session" / "target" => Route.public {
          withConnection(request) { connection =>
            withBody(request, selection) { case (revision, target) =>
              sessions.select(connection, revision, target).flatMap {
                case SelectResult.Owned(lock, current) =>
                  encodeGrant(lock).flatMap(grant => Ok(encodeRevision(current).deepMerge(Json.obj("grant" -> grant))))
                case SelectResult.Blocked(owner, alias, expiresAt, current) =>
                  remaining(expiresAt).flatMap(millis =>
                    Conflict(encodeRevision(current).deepMerge(Json.obj(
                      "code"            -> Json.fromString("target_locked"),
                      "ownerId"         -> Json.fromString(owner.value.toString),
                      "alias"           -> Json.fromString(alias),
                      "remainingMillis" -> Json.fromLong(millis)
                    )))
                  )
                case SelectResult.Rejected(reason) => Conflict(code(encodeFailure(reason)))
              }
            }
          }
        }
      case request @ POST -> Root / "api" / "editor-session" / "target" => Route.public {
          withConnection(request) { connection =>
            withGrant(request) { grant =>
              sessions.renewTarget(connection, grant).flatMap {
                case RenewTargetResult.Renewed(lock)    => encodeGrant(lock).flatMap(Ok(_))
                case RenewTargetResult.Rejected(reason) => Conflict(code(encodeFailure(reason)))
              }
            }
          }
        }
      case request @ DELETE -> Root / "api" / "editor-session" / "target" => Route.public {
          withConnection(request) { connection =>
            withGrant(request) { grant =>
              withBody(request, releaseRevision) { revision =>
                sessions.release(connection, revision, grant).flatMap {
                  case ReleaseResult.Released(current) => Ok(encodeRevision(current))
                  case ReleaseResult.Rejected(reason)  => Conflict(code(encodeFailure(reason)))
                }
              }
            }
          }
        }
    }

    private def register(request: Request[F]) =
      request.attemptAs[(EditorId, EditorAlias)](using jsonOf[F, (EditorId, EditorAlias)](using Async[F], registration)).value.flatMap {
        case Left(_)               => BadRequest(code("invalid_registration"))
        case Right((owner, alias)) =>
          optionalId(request, "X-Editor-Session") match
            case Left(message)       => BadRequest(code(message))
            case Right(maybeSession) =>
              sessions.register(owner, alias, maybeSession.map(SessionId(_))).flatMap {
                case RegisterResult.Ready(session)     => Ok(encodeSession(session))
                case RegisterResult.CredentialRequired => Conflict(code("credential_required"))
              }
      }

    private def withConnection(request: Request[F])(run: EditorConnection => F[Response[F]]) =
      readConnection(request).fold(message => BadRequest(code(message)), run)

    private def withGrant(request: Request[F])(run: GrantId => F[Response[F]]) =
      requiredId(request, "X-Editor-Grant").fold(message => BadRequest(code(message)), id => run(GrantId(id)))

    private def withBody[A](request: Request[F], decoder: Decoder[A])(run: A => F[Response[F]]) =
      request.attemptAs[A](using jsonOf[F, A](using Async[F], decoder)).value.flatMap {
        case Left(_)     => BadRequest(code("invalid_target_request"))
        case Right(body) => run(body)
      }

    private val revision = Decoder.decodeString.emap(value =>
      Either.catchOnly[NumberFormatException](BigInt(value)).toOption.flatMap(TargetRevision.from)
        .filter(_.value.toString === value).toRight("Invalid target revision")
    )

    private val target = Decoder.forProduct2("kind", "id")((kind: String, id: String) => (kind, id)).emap { case (kind, id) =>
      parseId(id).flatMap { parsed =>
        kind match
          case "spread" => EditingTarget.Spread(SpreadId(parsed)).asRight[String]
          case "cover"  => EditingTarget.Cover(AlbumId(parsed)).asRight[String]
          case _        => "Invalid target kind".asLeft[EditingTarget]
      }
    }.validate(_.keys.exists(_.toSet === Set("kind", "id")), "Invalid target")

    private val selection = Decoder.forProduct2("revision", "target")((current: TargetRevision, destination: EditingTarget) =>
      (current, destination)
    )(using revision, target).validate(_.keys.exists(_.toSet === Set("revision", "target")), "Invalid selection")

    private val releaseRevision = Decoder.forProduct1("revision")((current: TargetRevision) => current)(using revision)
      .validate(_.keys.exists(_.toSet === Set("revision")), "Invalid release")

    private def encodeRevision(current: TargetRevision) = Json.obj("revision" -> Json.fromString(current.value.toString))

    private def encodeGrant(lock: EditingLock) =
      val (kind, id) = lock.target match
        case EditingTarget.Spread(spread) => ("spread", spread.value)
        case EditingTarget.Cover(album)   => ("cover", album.value)
      remaining(lock.expiresAt).map(millis =>
        Json.obj(
          "target"          -> Json.obj("kind" -> Json.fromString(kind), "id" -> Json.fromString(id.toString)),
          "grantId"         -> Json.fromString(lock.grant.value.toString),
          "remainingMillis" -> Json.fromLong(millis)
        )
      )

    private def remaining(expiresAt: FiniteDuration) =
      Clock[F].monotonic.map(now => (expiresAt - now).toMillis.max(0L))

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
      val updates = sessions.ownershipChanges(connection).map {
        case OwnershipChangeResult.Changed          => (event("ownership_changed", Json.obj()), false)
        case OwnershipChangeResult.Rejected(reason) => (event("session_lost", code(encodeFailure(reason))), true)
      }
      val heartbeat = Stream.awakeEvery[F](1.second).as((ServerSentEvent(comment = "heartbeat".some), false))
      updates.merge(heartbeat).takeThrough { case (_, lost) => !lost }.map { case (notification, _) => notification }

    private val aliasChange = Decoder.forProduct1("alias")((alias: String) => alias)
      .emap(_.refineEither[EditorAliasConstraint]).validate(_.keys.exists(_.toSet === Set("alias")), "Invalid alias")

    private val registration = Decoder.forProduct2("editorId", "alias")((owner: String, alias: String) => (owner, alias))
      .emap { case (owner, alias) =>
        (parseId(owner), alias.refineEither[EditorAliasConstraint]).mapN((id, label) => (EditorId(id), label))
      }.validate(_.keys.exists(_.toSet === Set("editorId", "alias")), "Invalid registration")

    private def encodeSession(session: EditorSession) = Json.obj(
      "sessionId"        -> Json.fromString(session.id.value.toString),
      "ownerId"          -> Json.fromString(session.owner.value.toString),
      "alias"            -> Json.fromString(session.alias),
      "leaseSeconds"     -> Json.fromLong((session.expiresAt - session.lastHeartbeat).toSeconds),
      "heartbeatSeconds" -> Json.fromLong(10L)
    )

    private def code(value: String) = Json.obj("code" -> Json.fromString(value))

    private def event(name: String, data: Json) = ServerSentEvent(data = data.noSpaces.some, eventType = name.some)
