package photography.adapters.http

import cats.Monad
import cats.data.{Kleisli, OptionT}
import cats.syntax.all.*
import org.http4s.{HttpRoutes, Request, Response, Status}
import org.http4s.circe.CirceEntityCodec.*
import photography.adapters.http.EditorCredentials.*
import photography.usecases.editing.EditingAccess
import photography.usecases.editing.EditingAccess.Protection
import photography.domain.editing.GrantId

trait Routes[F[_]]:
  def routes: PartialFunction[Request[F], Routes.Route[F, ?]]

object Routes:
  enum Access:
    case Public
    case Editor(protection: Protection)

  final case class Route[F[_], A](access: Access, decode: F[Either[Response[F], A]], handle: A => F[Response[F]])

  object Route:
    def public[F[_]: Monad](response: => F[Response[F]]): Route[F, Unit] =
      Route(Access.Public, ().asRight[Response[F]].pure[F], _ => response)
    def write[F[_]: Monad](protection: Protection)(response: => F[Response[F]]): Route[F, Unit] =
      Route(Access.Editor(protection), ().asRight[Response[F]].pure[F], _ => response)

  def make[F[_]: Monad](access: EditingAccess[F])(definitions: Routes[F]): HttpRoutes[F] =
    def serve[A](request: Request[F], route: Route[F, A]): F[Response[F]] =
      def decoded: F[Response[F]] = route.decode.flatMap(_.fold(_.pure[F], route.handle))
      route.access match
        case Access.Public             => decoded
        case Access.Editor(protection) =>
          val grant = protection match
            case Protection.ActiveTab                                    => none[GrantId].asRight[String]
            case Protection.OwnSpread(_) | Protection.OwnAlbumOpening(_) =>
              requiredId(request, "X-Editor-Grant").map(value => GrantId(value).some)
            case Protection.ChangeSpreads(_) | Protection.OwnPhotoSpread(_, _) => optionalId(request, "X-Editor-Grant").map(_.map(GrantId(_)))
          (readConnection(request), grant).tupled.fold(
            message => Response[F](Status.BadRequest).withEntity(Map("code" -> message)).pure[F],
            { case (connection, maybeGrant) =>
              route.decode.flatMap:
                case Left(response) => response.pure[F]
                case Right(value)   =>
                  access.run(connection, protection, maybeGrant)(value.pure[F].flatMap(route.handle)).map:
                    case EditingAccess.Result.Completed(response) => response
                    case EditingAccess.Result.Rejected(reason)    =>
                      Response[F](Status.Conflict).withEntity(Map("code" -> encodeFailure(reason)))
                    case EditingAccess.Result.AlbumNotFound() =>
                      Response[F](Status.NotFound).withEntity(Map("code" -> "album_not_found"))
                    case EditingAccess.Result.PhotoNotFound() =>
                      Response[F](Status.NotFound).withEntity(Map("code" -> "photo_not_found"))
                    case EditingAccess.Result.Failed() =>
                      Response[F](Status.InternalServerError).withEntity(Map("code" -> "service_failure"))
                    case EditingAccess.Result.Blocked(owner, alias) =>
                      Response[F](Status.Conflict).withEntity(Map[String, String](
                        "code"    -> "target_locked",
                        "ownerId" -> owner.value.toString,
                        "alias"   -> alias
                      ))
            }
          ).map(_.putHeaders("Cache-Control" -> "no-store"))
    Kleisli(request => OptionT(definitions.routes.lift(request).traverse(route => serve(request, route))))
