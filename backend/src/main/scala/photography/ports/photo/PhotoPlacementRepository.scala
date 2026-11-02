package photography.ports.photo

import photography.domain.album.{AlbumId, ChapterId, PhotoId}
import photography.domain.editing.EditingTarget

trait PhotoPlacementRepository[Db[_]]:
  def findPlacedPhotos(chapter: ChapterId): Db[PhotoPlacementRepository.FindResult]
  def inspect(album: AlbumId, maybeChapter: Option[ChapterId], photo: PhotoId): Db[PhotoPlacementRepository.InspectResult]

object PhotoPlacementRepository:
  trait TargetRead[F[_]]:
    def findTarget(chapter: ChapterId, photo: PhotoId): F[TargetResult]

  enum TargetResult:
    case Found(maybeTarget: Option[EditingTarget])
    case PhotoNotFound, Failed

  enum InspectResult:
    case Found(inCollection: Boolean, placed: Boolean)
    case NotFound

  enum FindResult:
    case Found(photos: Set[PhotoId])
    case Failed
