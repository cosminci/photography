package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import org.http4s.{HttpRoutes, MessageBodyFailure, Request}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.PhotoId
import photography.usecases.photo.PhotoManager
import photography.usecases.photo.PhotoManager.SaveCaptionResult
import java.util.UUID

trait PhotoApi[F[_]]:
  def routes: HttpRoutes[F]

object PhotoApi:
  def make[F[_]: Async](photos: PhotoManager[F]): PhotoApi[F] = new LivePhotoApi(photos)

  final private class LivePhotoApi[F[_]: Async](photos: PhotoManager[F]) extends PhotoApi[F] with Http4sDsl[F]:
    override def routes: HttpRoutes[F] = HttpRoutes.of[F] {
      case request @ PUT -> Root / "api" / "photos" / CanonicalId(photo) / "caption" => saveCaption(PhotoId(photo), request)
      case PUT -> Root / "api" / "photos" / _ / "caption"                            => NotFound(body = Map("code" -> "photo_not_found"))
    }

    private def saveCaption(photo: PhotoId, request: Request[F]) =
      request.as[Json].flatMap { value =>
        value.hcursor.get[String](k = "text").fold(
          _ => BadRequest(body = Map("message" -> "Supply a text string")),
          text =>
            photos.saveCaption(photo, text = text).flatMap {
              case SaveCaptionResult.Saved         => NoContent()
              case SaveCaptionResult.PhotoNotFound => NotFound(body = Map("code" -> "photo_not_found"))
              case SaveCaptionResult.Failed        => InternalServerError(body = Map("code" -> "service_failure"))
              case SaveCaptionResult.Uncertain     => InternalServerError(body = Map("code" -> "mutation_uncertain"))
            }
        )
      }.recoverWith { case _: MessageBodyFailure => BadRequest(body = Map("message" -> "Supply a text string")) }

    private object CanonicalId:
      def unapply(value: String): Option[UUID] =
        Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value)
