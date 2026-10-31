package photography.ports.album

import photography.domain.album.*
import photography.domain.layout.Cover
import java.time.Instant

trait AlbumRepository[F[_]]:
  def list(offset: Int, limit: Int): F[AlbumRepository.ListResult]
  def create(album: Album, cover: Cover): F[AlbumRepository.CreateResult]
  def get(id: AlbumId): F[AlbumRepository.GetResult]
  def rename(id: AlbumId, title: AlbumTitle): F[AlbumRepository.RenameResult]
  def saveSynopsis(album: AlbumId, text: String): F[AlbumRepository.SaveSynopsisResult]
  def saveAuthors(album: AlbumId, authors: AlbumAuthors): F[AlbumRepository.SaveAuthorsResult]
  def delete(id: AlbumId): F[AlbumRepository.DeleteResult]

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

  final case class Metadata(id: AlbumId, year: AlbumYear, title: AlbumTitle, synopsis: String, createdAt: Instant)
  final case class ChapterMetadata(
      id: ChapterId,
      destination: Destination,
      period: VisitPeriod,
      sourceFolder: PhotoSource,
      description: String
  )

  enum SaveSynopsisResult derives CanEqual:
    case Saved(album: Album)
    case NotFound, Uncertain
    case Failed(reason: Failure)

  enum SaveAuthorsResult derives CanEqual:
    case Saved(album: Album)
    case NotFound, Uncertain
    case Failed(reason: Failure)

  enum ListResult derives CanEqual:
    case Listed(slice: AlbumPageSlice)
    case Failed(reason: Failure)

  enum CreateResult derives CanEqual:
    case Created
    case Failed(reason: Failure)

  enum GetResult derives CanEqual:
    case Found(album: Album)
    case NotFound
    case Failed(reason: Failure)

  enum RenameResult derives CanEqual:
    case Renamed(album: Album)
    case NotFound
    case Failed(reason: Failure)

  enum DeleteResult derives CanEqual:
    case Deleted
    case NotFound
    case Failed(reason: Failure)

  final case class AlbumPageSlice(totalAlbums: Long, albums: Vector[Album])

  enum Failure derives CanEqual:
    case YearAlreadyUsed, DatabaseFailure
