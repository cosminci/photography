package photography.adapters.http

import photography.adapters.http.Routes.Route
import org.http4s.Request

import cats.effect.Async
import io.circe.Json
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.PhotoSource

object PhotoSourceApi:
  def make[F[_]: Async](root: PhotoSource): Routes[F] = new LivePhotoSourceApi(root)

  final private class LivePhotoSourceApi[F[_]: Async](root: PhotoSource) extends Routes[F] with Http4sDsl[F]:
    override def routes: PartialFunction[Request[F], Route[F, ?]] = {
      case GET -> Root / "api" / "photo-source" => Route.public {
          Ok(Json.obj("photoRoot" -> Json.fromString(root)))
        }
    }
