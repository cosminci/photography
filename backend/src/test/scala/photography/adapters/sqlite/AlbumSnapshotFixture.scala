package photography.adapters.sqlite

import cats.effect.IO
import cats.syntax.all.*
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import photography.adapters.sqlite.album.{SqliteAlbumCompositionRepository, SqliteAlbumRepository}
import photography.adapters.sqlite.chapter.SqliteChapterRepository
import photography.domain.album.*
import photography.domain.layout.Spread
import photography.domain.gear.GearSelection
import photography.ports.album.AlbumRepository
import photography.ports.album.AlbumCompositionRepository.{CompositionResult, Located, SpreadResult}
import photography.ports.chapter.ChapterRepository

object AlbumSnapshotFixture:
  def readAlbum(xa: Transactor[IO], album: AlbumId)(using Logger[ConnectionIO]): IO[Result] =
    val albums      = SqliteAlbumRepository.makeRead
    val composition = SqliteAlbumCompositionRepository.make
    val chapters    = SqliteChapterRepository.make
    val read        = albums.metadata(album).flatMap:
      case AlbumRepository.MetadataResult.NotFound        => (Result.NotFound: Result).pure[ConnectionIO]
      case AlbumRepository.MetadataResult.Failed(_)       => (Result.Failed: Result).pure[ConnectionIO]
      case AlbumRepository.MetadataResult.Found(metadata) =>
        for
          saved <- albums.chapters(album).flatMap:
            case AlbumRepository.ChaptersResult.Found(values) => values.pure[ConnectionIO]
            case _ => new IllegalStateException("Missing chapters").raiseError[ConnectionIO, Vector[AlbumRepository.ChapterMetadata]]
          members <- composition.composition(album).flatMap:
            case CompositionResult.Found(values) => values.toList.traverse(member =>
                composition.findSpread(album, member.id).flatMap:
                  case SpreadResult.Found(value) => value.pure[ConnectionIO]
                  case _                         => new IllegalStateException("Missing spread").raiseError[ConnectionIO, Located]
              )
            case _ => new IllegalStateException("Missing composition").raiseError[ConnectionIO, List[Located]]
          opening <- members.collectFirst { case Located.AlbumOpening(value) => value }
            .liftTo[ConnectionIO](new IllegalStateException("Missing opening"))
          loaded <- saved.traverse { chapter =>
            val owned = members.collect:
              case Located.ChapterOpening(owner, value) if owner.value === chapter.id.value => value
              case Located.Photo(owner, value) if owner.value === chapter.id.value          => value
            for
              start <- owned.collectFirst { case value: Spread.ChapterStart => value }
                .liftTo[ConnectionIO](new IllegalStateException("Missing chapter opening"))
              gear <- chapters.readGear(album, chapter.id).flatMap:
                case ChapterRepository.GearReadResult.Found(value) => value.pure[ConnectionIO]
                case _ => new IllegalStateException("Missing gear").raiseError[ConnectionIO, GearSelection]
            yield Chapter(
              chapter.id,
              chapter.destination,
              chapter.period,
              chapter.description,
              gear,
              chapter.sourceFolder,
              start,
              owned.collect { case value: Spread.PhotosOnly => value }.toVector
            )
          }
        yield Result.Found(Album(
          metadata.id,
          metadata.year,
          metadata.title,
          metadata.synopsis,
          metadata.authors,
          metadata.createdAt,
          opening,
          loaded
        )): Result
    read.transact(xa).handleError(_ => Result.Failed)

  enum Result:
    case Found(album: Album)
    case NotFound, Failed
