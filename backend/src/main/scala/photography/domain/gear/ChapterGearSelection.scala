package photography.domain.gear

import cats.data.NonEmptyVector
import cats.syntax.all.*
import photography.domain.album.ChapterId

enum CameraUse:
  case FixedLens
  case Interchangeable(lenses: NonEmptyVector[LensId])

final case class CameraSelection(camera: CameraId, use: CameraUse)

type GearSelection = Vector[CameraSelection]

final case class GearCatalog(cameras: Vector[Camera], lenses: Vector[Lens]):
  import GearCatalog.*

  def validateSelection(selection: GearSelection): Either[Failure, Unit] =
    val unique = selection.map(_.camera).distinct.size === selection.size
    Either.cond(unique, (), Failure.DuplicateCamera) >> selection.traverse_(validateCamera)

  private def validateCamera(entry: CameraSelection) =
    cameras.find(_.id.value === entry.camera.value).toRight(Failure.UnknownCamera).flatMap: camera =>
      (camera.kind, entry.use) match
        case (CameraKind.FixedLens, CameraUse.FixedLens)                                 => ().asRight[Failure]
        case (kind: CameraKind.InterchangeableLens, CameraUse.Interchangeable(selected)) =>
          val unique = selected.toVector.distinct.size === selected.length
          Either.cond(unique, (), Failure.DuplicateLens) >> selected.traverse_(id => validateLens(kind, id))
        case _ => Failure.CameraKindMismatch.asLeft[Unit]

  private def validateLens(kind: CameraKind.InterchangeableLens, id: LensId) =
    lenses.find(_.id.value === id.value).toRight(Failure.UnknownLens).flatMap: lens =>
      val compatible = kind.mount === lens.mount && (kind.sensor === SensorFormat.ApsC || lens.coverage === SensorFormat.FullFrame)
      Either.cond(compatible, (), Failure.IncompatibleLens)

object GearCatalog:
  enum Failure derives CanEqual:
    case DuplicateCamera, UnknownCamera, CameraKindMismatch, DuplicateLens, UnknownLens, IncompatibleLens

final case class ChapterGearSelection(chapter: ChapterId, selection: GearSelection)
