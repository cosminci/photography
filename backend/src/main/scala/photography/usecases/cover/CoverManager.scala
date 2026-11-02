package photography.usecases.cover

import cats.MonadThrow
import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import org.typelevel.log4cats.Logger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.{AlbumId, ChapterId, PhotoId}
import photography.domain.layout.*
import photography.ports.cover.CoverRepository
import photography.ports.cover.CoverRepository.Composition
import photography.ports.photo.PhotoPlacementRepository
import photography.usecases.cover.CoverManager.*

trait CoverManager[F[_]]:
  def read(album: AlbumId): F[ReadResult]
  def edit(album: AlbumId, request: Edit): F[Result]

object CoverManager:
  def make[F[_]: MonadCancelThrow: IdGenerator: Logger, Db[_]: MonadThrow](
      covers: CoverRepository[Db],
      photos: PhotoPlacementRepository[Db]
  )(using Transaction[Db, F]): CoverManager[F] = new LiveCoverManager(covers, photos)

  final private class LiveCoverManager[F[_]: MonadCancelThrow: IdGenerator: Logger, Db[_]: MonadThrow](
      covers: CoverRepository[Db],
      photos: PhotoPlacementRepository[Db]
  )(using Transaction[Db, F]) extends CoverManager[F]:
    override def read(album: AlbumId): F[ReadResult] =
      val read = covers.read(album).map:
        case CoverRepository.ReadResult.Found(value) => ReadResult.Found(value)
        case CoverRepository.ReadResult.NotFound     => ReadResult.CoverNotFound
      coordinate(ReadResult.Failed)(Transaction[Db, F].run(read))

    override def edit(album: AlbumId, request: Edit): F[Result] =
      coordinate(Result.Failed):
        prepareEdit(request).flatMap: change =>
          val mutation = covers.read(album).flatMap:
            case CoverRepository.ReadResult.NotFound       => Result.CoverNotFound.pure[Db]
            case CoverRepository.ReadResult.Found(current) =>
              checkPhoto(album, request).flatMap: permission =>
                val slots = Set(current.cover.front.id, current.cover.back.id)
                permission.flatMap(_ => change(slots, current.placements)) match
                  case Left(reason)  => Result.Rejected(reason).pure[Db]
                  case Right(values) =>
                    covers.replace(album, placements = values).map[Result]:
                      case CoverRepository.ReplaceResult.Replaced => Result.Saved(current.copy(placements = values))
                      case CoverRepository.ReplaceResult.NotFound => Result.CoverNotFound
          Transaction[Db, F].run(mutation)

    private type Change = (Set[CoverSlotId], Vector[CoverPlacement]) => Either[Failure, Vector[CoverPlacement]]

    private def prepareEdit(request: Edit) = request match
      case Edit.Place(slot, photo) =>
        IdGenerator[F].generateId.map: id =>
          val change: Change = (slots, values) =>
            requireSlot(slots = slots, slot).map(_ =>
              values.filterNot(_.slot.value === slot.value) :+ CoverPlacement(CoverPlacementId(id), slot, photo, initial)
            )
          change
      case Edit.Remove(id) =>
        val change: Change = (_, values) => findPlacement(values = values, id).map(_ => values.filterNot(_.id.value === id.value))
        change.pure[F]
      case Edit.Adjust(id, transform) =>
        val change: Change = (_, values) => adjust(values = values, id, transform)
        change.pure[F]
      case Edit.Move(source, destination) =>
        val change: Change = (slots, values) => move(slots = slots, values = values, source, destination)
        change.pure[F]

    private def checkPhoto(album: AlbumId, request: Edit) = request match
      case Edit.Place(_, photo) =>
        photos.inspect(album, maybeChapter = none[ChapterId], photo).map:
          case PhotoPlacementRepository.InspectResult.Found(inCollection, placed) =>
            if !inCollection then Failure.PhotoNotInCollection.asLeft[Unit]
            else if placed then Failure.PhotoAlreadyUsed.asLeft[Unit]
            else ().asRight[Failure]
          case PhotoPlacementRepository.InspectResult.NotFound => Failure.PhotoNotInCollection.asLeft[Unit]
      case _ => ().asRight[Failure].pure[Db]

    private def adjust(values: Vector[CoverPlacement], id: CoverPlacementId, transform: PhotoTransform) =
      for
        _ <- findPlacement(values = values, id)
        _ <- Either.cond(transform.zoom >= 1.0 && transform.zoom <= 5.0, (), Failure.InvalidTransform)
      yield values.map(value => if value.id.value === id.value then value.copy(transform = transform) else value)

    private def move(slots: Set[CoverSlotId], values: Vector[CoverPlacement], source: CoverPlacementId, destination: CoverSlotId) =
      for
        placed <- findPlacement(values = values, source)
        _      <- requireSlot(slots = slots, destination)
      yield values.map: value =>
        if value.id.value === source.value then value.copy(slot = destination)
        else if value.slot.value === destination.value then value.copy(slot = placed.slot)
        else value

    private def requireSlot(slots: Set[CoverSlotId], slot: CoverSlotId) =
      Either.cond(slots.contains(slot), (), Failure.SlotNotFound)

    private def findPlacement(values: Vector[CoverPlacement], id: CoverPlacementId) =
      values.find(_.id.value === id.value).toRight(Failure.PlacementNotFound)

    private def coordinate[A](failed: A)(body: F[A]) =
      MonadCancelThrow[F].uncancelable: _ =>
        body.handleErrorWith(error => Logger[F].error(error)("Cover action failed").as(failed))

    private val initial = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)

  enum Edit:
    case Place(slot: CoverSlotId, photo: PhotoId)
    case Remove(id: CoverPlacementId)
    case Adjust(id: CoverPlacementId, transform: PhotoTransform)
    case Move(source: CoverPlacementId, destination: CoverSlotId)

  enum ReadResult:
    case Found(composition: Composition)
    case CoverNotFound, Failed

  enum Result:
    case Saved(composition: Composition)
    case Rejected(reason: Failure)
    case CoverNotFound, Failed

  enum Failure derives CanEqual:
    case PhotoNotInCollection, PhotoAlreadyUsed, SlotNotFound, PlacementNotFound, InvalidTransform
