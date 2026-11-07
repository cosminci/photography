package photography.ports.album

import photography.domain.album.*
import photography.domain.layout.Cover
import java.time.Instant

trait AlbumRepository[F[_]]:
  def list(offset: Int, limit: Int): F[AlbumRepository.ListResult]
  def create(album: Album, cover: Cover): F[AlbumRepository.CreateResult]
  def readPlanning(id: AlbumId): F[AlbumRepository.PlanningResult]
  def rename(id: AlbumId, title: AlbumTitle): F[AlbumRepository.RenameResult]
  def saveSynopsis(album: AlbumId, text: String): F[AlbumRepository.SaveSynopsisResult]
  def saveAuthors(album: AlbumId, authors: AlbumAuthors): F[AlbumRepository.SaveAuthorsResult]

object AlbumRepository:
  trait MetadataRead[F[_]]:
    def metadata(album: AlbumId): F[MetadataResult]

  trait ChapterRead[F[_]]:
    def chapters(album: AlbumId): F[ChaptersResult]

  enum MetadataResult:
    case Found(metadata: Metadata)
    case NotFound
    case Failed(reason: Failure)

  enum ChaptersResult:
    case Found(chapters: Vector[ChapterMetadata])
    case AlbumNotFound
    case Failed(reason: Failure)

  final case class Metadata(id: AlbumId, year: AlbumYear, title: AlbumTitle, synopsis: String, authors: AlbumAuthors, createdAt: Instant)
  final case class PlanningSnapshot(year: AlbumYear, chapters: Vector[ChapterMetadata], spreadCount: InteriorSpreadCount)
  final case class ChapterMetadata(
      id: ChapterId,
      destination: Destination,
      period: VisitPeriod,
      sourceFolder: PhotoSource,
      description: String
  )

  enum SaveSynopsisResult derives CanEqual:
    case Saved(album: Metadata)
    case NotFound
    case Failed(cause: Throwable)
    case Uncertain(cause: Throwable)

  enum SaveAuthorsResult derives CanEqual:
    case Saved(album: Metadata)
    case NotFound
    case Failed(cause: Throwable)
    case Uncertain(cause: Throwable)

  enum ListResult derives CanEqual:
    case Listed(slice: AlbumPageSlice)
    case Failed(reason: Failure)

  enum CreateResult derives CanEqual:
    case Created
    case Failed(reason: Failure)

  enum PlanningResult derives CanEqual:
    case Found(snapshot: PlanningSnapshot)
    case NotFound
    case Failed(reason: Failure)

  enum RenameResult derives CanEqual:
    case Renamed(album: Metadata)
    case NotFound
    case Failed(cause: Throwable)

  final case class AlbumPageSlice(totalAlbums: Long, albums: Vector[Metadata])

  enum Failure derives CanEqual:
    case YearAlreadyUsed, DatabaseFailure
