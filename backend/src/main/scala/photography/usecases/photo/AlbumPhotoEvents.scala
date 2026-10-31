package photography.usecases.photo

import photography.ports.album.AlbumRepository

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import org.typelevel.log4cats.Logger
import photography.capabilities.IdGenerator
import photography.domain.album.{AlbumId, ChapterId}
import photography.usecases.photo.AlbumPhotoEvents.*
import java.util.UUID

trait AlbumPhotoEvents[F[_]]:
  def subscribe(album: AlbumId): F[SubscribeResult[F]]
  def invalidate(album: AlbumId, chapter: ChapterId): F[Unit]

object AlbumPhotoEvents:
  def make[F[_]: Async: IdGenerator: Logger](albums: AlbumRepository.MetadataRead[F]): F[AlbumPhotoEvents[F]] =
    Ref.of[F, Map[AlbumId, Map[UUID, Subscriber[F]]]](Map.empty).map(new LiveAlbumPhotoEvents(albums, _))

  final private class LiveAlbumPhotoEvents[F[_]: Async: IdGenerator: Logger](
      albums: AlbumRepository.MetadataRead[F],
      subscribers: Ref[F, Map[AlbumId, Map[UUID, Subscriber[F]]]]
  ) extends AlbumPhotoEvents[F]:
    override def subscribe(album: AlbumId): F[SubscribeResult[F]] =
      albums.metadata(album).map:
        case AlbumRepository.MetadataResult.Found(_)  => SubscribeResult.Opened(Stream.resource(register(album)).flatMap(readEvents))
        case AlbumRepository.MetadataResult.NotFound  => SubscribeResult.AlbumNotFound()
        case AlbumRepository.MetadataResult.Failed(_) => SubscribeResult.Failed()
      .handleErrorWith(error => Logger[F].error(error)("Album photo subscription failed").as(SubscribeResult.Failed()))

    override def invalidate(album: AlbumId, chapter: ChapterId): F[Unit] =
      subscribers.get.flatMap: current =>
        current.getOrElse(album, Map.empty[UUID, Subscriber[F]]).values.toVector.traverse_ { subscriber =>
          subscriber.pending.update(_ + chapter) >> subscriber.wakeup.tryOffer(()).void
        }

    private def register(album: AlbumId) =
      val allocate =
        for
          id      <- IdGenerator[F].generateId
          pending <- Ref.of[F, Set[ChapterId]](Set.empty)
          wakeup  <- Queue.bounded[F, Unit](capacity = 1)
        yield (id, Subscriber(pending, wakeup))
      Resource.eval(allocate).flatMap { case (id, subscriber) =>
        val acquire =
          subscribers.update(current => current.updated(album, current.getOrElse(album, Map.empty[UUID, Subscriber[F]]).updated(id, subscriber)))
        val release = subscribers.update(_.updatedWith(album)(_.map(_ - id).filter(_.nonEmpty)))
        Resource.make(acquire)(_ => release).as(subscriber)
      }

    private def readEvents(subscriber: Subscriber[F]) =
      val changes = Stream.repeatEval(subscriber.wakeup.take >> subscriber.pending.getAndSet(Set.empty))
        .flatMap(chapters => Stream.emits(chapters.toVector.sortBy(_.value.toString).map(Event.InventoryChanged(_))))
      Stream.emit(Event.Subscribed) ++ changes

  enum Event:
    case Subscribed
    case InventoryChanged(chapter: ChapterId)

  enum SubscribeResult[F[_]]:
    case Opened(events: Stream[F, Event])
    case AlbumNotFound()
    case Failed()

  final private case class Subscriber[F[_]](pending: Ref[F, Set[ChapterId]], wakeup: Queue[F, Unit])
