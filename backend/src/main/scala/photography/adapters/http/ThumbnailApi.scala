package photography.adapters.http

import photography.adapters.http.Routes.Route
import org.http4s.Request

import cats.Monad
import cats.syntax.all.*
import fs2.Stream
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.http4s.{EntityTag, Method}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`If-None-Match`
import photography.domain.album.ThumbnailLocation
import photography.ports.photo.ThumbnailStorage
import photography.ports.photo.ThumbnailStorage.ReadResult
import java.util.UUID

trait ThumbnailApi[F[_]] extends Routes[F]

object ThumbnailApi:
  def make[F[_]: Monad](storage: ThumbnailStorage[F]): ThumbnailApi[F] = new LiveThumbnailApi(storage)

  final private class LiveThumbnailApi[F[_]: Monad](storage: ThumbnailStorage[F]) extends ThumbnailApi[F] with Http4sDsl[F]:
    override def routes: PartialFunction[Request[F], Route[F, ?]] = {
      case request @ (GET | HEAD) -> Root / "thumbnails" / Location(location) / Version(version) => Route.public {
          storage.readThumbnail(location, version = version).flatMap:
            case ReadResult.Read(bytes) =>
              val matches  = request.headers.get[`If-None-Match`].exists(_.tags.forall(_.exists(_.tag === version)))
              val response = if matches then NotModified() else Ok(bytes.toArray)
              response.map: value =>
                val cached = value.putHeaders("Cache-Control" -> "private, max-age=31536000, immutable", "ETag" -> EntityTag(version).renderString)
                if matches then cached
                else
                  val jpeg = cached.putHeaders("Content-Type" -> "image/jpeg")
                  if request.method === Method.HEAD then jpeg.copy(body = Stream.empty) else jpeg
            case ReadResult.NotFound => NotFound()
            case ReadResult.Failed   => InternalServerError("""{"code":"service_failure"}""").map(_.putHeaders("Content-Type" -> "application/json"))
        }
      case request if request.pathInfo.segments.headOption.exists(_.decoded() === "thumbnails") => Route.public(NotFound())
    }

    private object Location:
      def unapply(value: String): Option[ThumbnailLocation] =
        Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value).map(ThumbnailLocation(_))

    private object Version:
      def unapply(value: String): Option[String :| Not[Blank]] =
        Option.when(value.matches("[0-9a-f]{64}\\.jpg"))(value.stripSuffix(".jpg")).flatMap(_.refineEither[Not[Blank]].toOption)
