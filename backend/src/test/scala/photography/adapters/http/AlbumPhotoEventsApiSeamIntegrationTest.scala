package photography.adapters.http

import cats.effect.{IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import org.http4s.{MediaType, Request, ServerSentEvent, Status, Uri}
import org.typelevel.ci.CIString
import photography.domain.album.{AlbumId, ChapterId}
import photography.usecases.photo.AlbumPhotoEvents
import photography.usecases.photo.AlbumPhotoEvents.{Event, SubscribeResult}
import java.util.UUID

final class AlbumPhotoEventsApiSeamIntegrationTest extends CatsEffectSuite:
  private val album   = AlbumId(new UUID(0L, 1L))
  private val chapter = ChapterId(new UUID(0L, 2L))
  private val uri     = Uri.unsafeFromString(s"/api/albums/${album.value}/photo-events")

  test("should encode exact events, heartbeat and uncached unbuffered headers and release the stream on disconnect"):
    val program =
      for
        released <- Ref.of[IO, Boolean](false)
        lifecycle = Resource.make(IO.unit)(_ => released.set(true))
        events    = Stream.resource(lifecycle) >> (Stream.emits(Vector(Event.Subscribed, Event.InventoryChanged(chapter))) ++ Stream.never[IO])
        service   = buildEvents(SubscribeResult.Opened(events))
        response <- PublicRoutes(AlbumPhotoEventsApi.make(service)).orNotFound.run(Request[IO](uri = uri))
        received <- response.body.through(ServerSentEvent.decoder[IO]).take(3).compile.toVector
        closed   <- released.get
      yield
        assertEquals(response.status, Status.Ok)
        assertEquals(response.contentType.map(_.mediaType), MediaType.`text/event-stream`.some)
        assertEquals(response.headers.get(CIString("Cache-Control")).map(_.head.value), "no-store".some)
        assertEquals(response.headers.get(CIString("X-Accel-Buffering")).map(_.head.value), "no".some)
        assertEquals(received.flatMap(_.eventType), Vector("subscribed", "inventory-changed"))
        assertEquals(received.flatMap(_.data), Vector("{}", s"""{"chapterId":"${chapter.value}"}"""))
        assert(received.exists(_.comment.nonEmpty))
        assert(closed)

    TestControl.executeEmbed(program)

  test("should reject invalid albums and failed validation before opening a stream"):
    Vector(
      SubscribeResult.AlbumNotFound[IO]() -> Status.NotFound,
      SubscribeResult.Failed[IO]()        -> Status.InternalServerError
    ).traverse_ { case (result, expected) =>
      val app = PublicRoutes(AlbumPhotoEventsApi.make(buildEvents(result))).orNotFound

      for
        response  <- app.run(Request[IO](uri = uri))
        body      <- response.as[String]
        malformed <- app.run(Request[IO](uri = Uri.unsafeFromString("/api/albums/not-an-id/photo-events")))
      yield
        assertEquals(response.status, expected)
        assertEquals(body, if expected === Status.NotFound then """{"code":"album_not_found"}""" else """{"code":"service_failure"}""")
        assertEquals(malformed.status, Status.NotFound)
    }

  private def buildEvents(result: SubscribeResult[IO]) =
    new AlbumPhotoEvents[IO]:
      override def subscribe(id: AlbumId): IO[SubscribeResult[IO]]       = IO(assertEquals(id, album)).as(result)
      override def invalidate(id: AlbumId, chapter: ChapterId): IO[Unit] = IO.raiseError[Unit](new AssertionError("Unexpected invalidation"))
