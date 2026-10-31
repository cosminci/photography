package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import org.http4s.{CacheDirective, HttpRoutes}
import org.http4s.headers.`Cache-Control`
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.PhotoSource

object PhotoSourceApi:
  def make[F[_]: Async](root: PhotoSource): HttpRoutes[F] = new LivePhotoSourceApi(root).routes

  final private class LivePhotoSourceApi[F[_]: Async](root: PhotoSource) extends Http4sDsl[F]:
    val routes: HttpRoutes[F] = HttpRoutes.of[F] {
      case GET -> Root / "api" / "photo-source" =>
        Ok(Json.obj("photoRoot" -> Json.fromString(root))).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-store`)))
    }
