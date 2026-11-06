package photography.ports.photo

import photography.domain.album.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.GreaterEqual

trait PhotoStorage[F[_]]:
  def discoverOriginals(source: PhotoSource): F[PhotoStorage.DiscoveryResult]
  def readOriginal(location: OriginalPhotoLocation): F[PhotoStorage.ReadResult]

object PhotoStorage:
  enum DiscoveryResult:
    case Complete(originals: Vector[OriginalPhotoLocation], unsupportedCount: Int :| GreaterEqual[0])
    case Failed(cause: Throwable)

  enum ReadResult:
    case Read(content: PhotoContent, metadata: PhotoMetadata)
    case Undecodable, Unstable
    case Failed(cause: Throwable)
