package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.{Request, Response}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.adapters.http.Routes.Route
import photography.domain.album.AlbumId
import photography.usecases.album.BookExport

object BookExportApi:
  def make[F[_]: Async](exports: BookExport[F]): Routes[F] = new LiveBookExportApi(exports)

  final private class LiveBookExportApi[F[_]: Async](exports: BookExport[F]) extends Routes[F] with Http4sDsl[F]:
    override def routes: PartialFunction[Request[F], Route[F, ?]] = {
      case GET -> Root / "api" / "albums" / CanonicalId(id) / "indesign-export" => Route.public(exportBook(AlbumId(id)))
      case GET -> Root / "api" / "albums" / _ / "indesign-export"               =>
        Route.public(NotFound(Json.obj("code" -> Json.fromString("album_not_found"))))
    }

    private def exportBook(album: AlbumId): F[Response[F]] =
      exports.exportBook(album).flatMap {
        case BookExport.Result.Ready(json, filename) =>
          Ok(json).map(_.putHeaders("Content-Disposition" -> s"""attachment; filename="$filename""""))
        case BookExport.Result.Blocked(issues) =>
          UnprocessableContent(Json.obj("code" -> Json.fromString("export_blocked"), "issues" -> issues.asJson))
        case BookExport.Result.NotFound => NotFound(Json.obj("code" -> Json.fromString("album_not_found")))
        case BookExport.Result.Failed   => InternalServerError(Json.obj("code" -> Json.fromString("service_failure")))
      }
