package photography.ports.photo

import photography.domain.album.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

trait ThumbnailStorage[F[_]]:
  def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): F[ThumbnailStorage.StoreResult]
  def readThumbnail(location: ThumbnailLocation, version: String :| Not[Blank]): F[ThumbnailStorage.ReadResult]
  def removeThumbnail(location: ThumbnailLocation): F[ThumbnailStorage.RemoveResult]

object ThumbnailStorage:
  enum StoreResult:
    case Stored(thumbnail: Thumbnail)
    case Failed(cause: Throwable)

  enum RemoveResult:
    case Removed
    case Failed(cause: Throwable)

  enum ReadResult:
    case Read(bytes: ThumbnailBytes)
    case NotFound, Failed
