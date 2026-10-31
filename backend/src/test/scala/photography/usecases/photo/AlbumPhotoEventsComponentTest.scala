package photography.usecases.photo

import photography.ports.album.AlbumRepository.{MetadataRead, Metadata, MetadataResult, Failure}

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.usecases.photo.AlbumPhotoEvents.{Event, SubscribeResult}
import java.util.UUID
import java.time.Instant
import io.github.iltotore.iron.autoRefine

final class AlbumPhotoEventsComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given IdGenerator[IO] with
    private val next                  = Ref.unsafe[IO, Long](100L)
    override def generateId: IO[UUID] = next.getAndUpdate(_ + 1).map(value => new UUID(0L, value))
  private val album   = AlbumId(new UUID(0L, 1L))
  private val chapter = ChapterId(new UUID(0L, 2L))
  private val other   = ChapterId(new UUID(0L, 3L))
  private val saved   = Metadata(album, year = 2026, title = "Delivery", synopsis = "", Instant.EPOCH)

  Vector(
    (metadata = MetadataResult.NotFound, expected = SubscribeResult.AlbumNotFound[IO]()),
    (metadata = MetadataResult.Failed(Failure.DatabaseFailure), expected = SubscribeResult.Failed[IO]())
  ).foreach { case (metadata, expected) =>
    test(s"should return $expected for $metadata before allocating a subscription"):
      for
        events     <- AlbumPhotoEvents.make(buildAlbums(metadata.pure[IO]))
        subscribed <- events.subscribe(album)
      yield assertEquals(subscribed, expected)
  }

  test("should expose unexpected metadata acquisition failure before allocating a subscription"):
    AlbumPhotoEvents.make(buildAlbums(IO.raiseError[MetadataResult](new IllegalStateException("lookup")))).flatMap(_.subscribe(album))
      .map(result => assertEquals(result, SubscribeResult.Failed[IO]()))

  test("should register before acknowledgement, coalesce chapter changes, isolate albums and release canceled subscribers"):
    for
      events       <- AlbumPhotoEvents.make(buildAlbums(MetadataResult.Found(saved).pure[IO]))
      stream       <- events.subscribe(album)
      received     <- Queue.unbounded[IO, Event]
      acknowledged <- Deferred[IO, Unit]
      release      <- Deferred[IO, Unit]
      pausedSubscriber = readEvents(stream).evalMap(event => received.offer(event) >> acknowledge(event, acknowledged, release))
      worker  <- pausedSubscriber.compile.drain.start
      _       <- acknowledged.get
      _       <- (1 to 100).toVector.traverse_(_ => events.invalidate(album, chapter))
      _       <- events.invalidate(album, other)
      _       <- events.invalidate(AlbumId(new UUID(0L, 9L)), ChapterId(new UUID(0L, 10L)))
      _       <- release.complete(())
      first   <- received.take
      pending <- Vector.fill(2)(()).traverse(_ => received.take)
      _       <- worker.cancel
      _       <- events.invalidate(album, chapter)
      extra   <- received.tryTake
    yield
      assertEquals(first, Event.Subscribed)
      assertEquals(pending.toSet, Set(Event.InventoryChanged(chapter), Event.InventoryChanged(other)))
      assertEquals(extra, None)

  test("should keep a fast subscriber responsive while another is blocked and after it disconnects"):
    for
      events          <- AlbumPhotoEvents.make(buildAlbums(MetadataResult.Found(saved).pure[IO]))
      slow            <- events.subscribe(album)
      fast            <- events.subscribe(album)
      blocked         <- Deferred[IO, Unit]
      ready           <- Deferred[IO, Unit]
      received        <- Queue.unbounded[IO, Event]
      stalled         <- readEvents(slow).evalMap(_ => blocked.complete(()).void >> IO.never[Unit]).compile.drain.start
      worker          <- readEvents(fast).evalMap(event => received.offer(event) >> ready.complete(()).void).compile.drain.start
      _               <- blocked.get
      _               <- ready.get
      _               <- events.invalidate(album, chapter)
      acknowledgement <- received.take
      first           <- received.take
      _               <- stalled.cancel
      _               <- events.invalidate(album, other)
      second          <- received.take
      _               <- worker.cancel
    yield
      assertEquals(acknowledgement, Event.Subscribed)
      assertEquals(first, Event.InventoryChanged(chapter))
      assertEquals(second, Event.InventoryChanged(other))

  private def readEvents(result: SubscribeResult[IO]) = result match
    case SubscribeResult.Opened(events) => events
    case _                              => Stream.raiseError[IO](new AssertionError("Expected subscription"))

  private def acknowledge(event: Event, acknowledged: Deferred[IO, Unit], release: Deferred[IO, Unit]) = event match
    case Event.Subscribed => acknowledged.complete(()).void >> release.get
    case _                => IO.unit

  private def buildAlbums(response: IO[MetadataResult]) =
    new MetadataRead[IO]:
      override def metadata(id: AlbumId): IO[MetadataResult] = response
