package photography.ports.album

import cats.data.NonEmptyList
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.layout.{Spread, SpreadId}

trait AlbumCompositionRepository[Db[_]]:
  def hasAlbum(album: AlbumId): Db[Boolean]
  def countInteriorSpreads(album: AlbumId): Db[Int]
  def findSpread(album: AlbumId, spread: SpreadId): Db[Option[AlbumCompositionRepository.Located]]
  def findChapterOrder(album: AlbumId, chapter: ChapterId): Db[Option[NonEmptyList[SpreadId]]]

object AlbumCompositionRepository:
  trait MembershipRead[Db[_]]:
    def composition(album: AlbumId): Db[CompositionResult]

  enum CompositionResult:
    case Found(members: NonEmptyList[Member])
    case AlbumNotFound
    case Failed(reason: Failure)

  enum Failure:
    case Unavailable

  final case class Member(id: SpreadId, maybeChapter: Option[ChapterId])

  enum Located:
    case AlbumOpening(value: Spread.AlbumStart)
    case ChapterOpening(chapter: ChapterId, value: Spread.ChapterStart)
    case Photo(chapter: ChapterId, value: Spread.PhotosOnly)

    def spread: Spread = this match
      case AlbumOpening(value)      => value
      case ChapterOpening(_, value) => value
      case Photo(_, value)          => value
