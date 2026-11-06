package photography.ports.chapter

import cats.data.NonEmptyList

import photography.domain.album.*
import photography.domain.layout.Spread
import photography.domain.gear.*

trait ChapterRepository[F[_]] extends ChapterRepository.Metadata[F]:
  def readGear(album: AlbumId, chapter: ChapterId): F[ChapterRepository.GearReadResult]
  def readSelections: F[ChapterRepository.SelectionsReadResult]
  def replaceGear(album: AlbumId, chapter: ChapterId, selection: GearSelection): F[ChapterRepository.GearWriteResult]

object ChapterRepository:
  trait Metadata[F[_]]:
    def saveDescription(album: AlbumId, chapter: ChapterId, text: String): F[SaveDescriptionResult]
    def findSource(chapter: ChapterId): F[SourceResult]
    def create(album: AlbumId, chapter: NewChapter, order: NonEmptyList[ChapterId]): F[CreateResult]
    def update(command: Update): F[UpdateResult]

  enum GearReadResult:
    case Found(selection: GearSelection)
    case NotFound, Failed

  enum SelectionsReadResult:
    case Found(selections: Vector[ChapterGearSelection])
    case Failed

  enum GearWriteResult:
    case Saved, NotFound, Failed, Uncertain

  enum SaveDescriptionResult:
    case Saved, AlbumNotFound, ChapterNotFound, Uncertain
    case Failed(cause: Throwable)

  enum SourceResult:
    case Found(album: AlbumId, source: PhotoSource)
    case ChapterNotFound, Failed

  final case class Update(
      album: AlbumId,
      chapter: ChapterId,
      name: DestinationName,
      countries: NonEmptyList[CountryId],
      period: VisitPeriod,
      sourceFolder: PhotoSource,
      order: NonEmptyList[ChapterId]
  )
  final case class NewChapter(
      id: ChapterId,
      name: DestinationName,
      countries: NonEmptyList[CountryId],
      period: VisitPeriod,
      description: String,
      sourceFolder: PhotoSource,
      opening: Spread.ChapterStart
  )

  enum CreateResult:
    case Saved
    case AlbumNotFound, UnknownCountries, Failed

  enum UpdateResult:
    case Saved
    case ChapterNotFound, AlbumNotFound, UnknownCountries, Failed
