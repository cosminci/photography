package photography.adapters.http

import photography.adapters.http.Routes.Route
import org.http4s.Request

import cats.effect.Async
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import org.http4s.ServerSentEvent
import org.http4s.dsl.Http4sDsl
import org.http4s.circe.CirceEntityCodec.*
import photography.domain.album.AlbumId
import photography.usecases.photo.AlbumPhotoEvents
import photography.usecases.photo.AlbumPhotoEvents.{Event, SubscribeResult}
import scala.concurrent.duration.*

trait AlbumPhotoEventsApi[F[_]] extends Routes[F]

object AlbumPhotoEventsApi:
  def make[F[_]: Async](events: AlbumPhotoEvents[F]): AlbumPhotoEventsApi[F] = new LiveAlbumPhotoEventsApi(events)

  final private class LiveAlbumPhotoEventsApi[F[_]: Async](events: AlbumPhotoEvents[F]) extends AlbumPhotoEventsApi[F] with Http4sDsl[F]:
    override def routes: PartialFunction[Request[F], Route[F, ?]] = {
      case GET -> Root / "api" / "albums" / CanonicalId(id) / "photo-events" => Route.public {
          events.subscribe(AlbumId(id)).flatMap:
            case SubscribeResult.Opened(notifications) =>
              val heartbeat = Stream.awakeEvery[F](15.seconds).as(ServerSentEvent(comment = "heartbeat".some))
              Ok(notifications.map(encodeEvent).merge(heartbeat)).map(_.putHeaders("X-Accel-Buffering" -> "no"))
            case SubscribeResult.AlbumNotFound() => NotFound(Map("code" -> "album_not_found"))
            case SubscribeResult.Failed()        => InternalServerError(Map("code" -> "service_failure"))
        }
      case GET -> Root / "api" / "albums" / _ / "photo-events" => Route.public(NotFound(Map("code" -> "album_not_found")))
    }

    private def encodeEvent(event: Event) = event match
      case Event.Subscribed                => ServerSentEvent(data = "{}".some, eventType = "subscribed".some)
      case Event.InventoryChanged(chapter) =>
        val data = Json.obj("chapterId" -> Json.fromString(chapter.value.toString)).noSpaces
        ServerSentEvent(data = data.some, eventType = "inventory-changed".some)
