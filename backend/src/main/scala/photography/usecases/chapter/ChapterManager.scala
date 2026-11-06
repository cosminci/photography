package photography.usecases.chapter

import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.gear.{GearCatalog, GearSelection}
import photography.capabilities.Transaction
import org.typelevel.log4cats.Logger
import photography.ports.chapter.ChapterRepository
import photography.ports.gear.GearRepository
import photography.usecases.chapter.ChapterManager.*

trait ChapterManager[F[_]]:
  def readGear(album: AlbumId, chapter: ChapterId): F[GearReadResult]
  def replaceGear(album: AlbumId, chapter: ChapterId, selection: GearSelection): F[GearReplaceResult]

object ChapterManager:
  def make[F[_]: MonadCancelThrow: Mutex: Logger, Db[_]](chapters: ChapterRepository[Db], gear: GearRepository[F])(using
      Transaction[Db, F]
  ): ChapterManager[F] =
    new LiveChapterManager(chapters, gear)

  final private class LiveChapterManager[F[_]: MonadCancelThrow: Mutex: Logger, Db[_]](
      chapters: ChapterRepository[Db],
      gear: GearRepository[F]
  )(using Transaction[Db, F]) extends ChapterManager[F]:
    override def readGear(album: AlbumId, chapter: ChapterId): F[GearReadResult] =
      Transaction[Db, F].run(chapters.readGear(album, chapter)).map:
        case ChapterRepository.GearReadResult.Found(selection) => GearReadResult.Found(selection = selection)
        case ChapterRepository.GearReadResult.NotFound         => GearReadResult.NotFound
        case ChapterRepository.GearReadResult.Failed           => GearReadResult.Failed
      .handleErrorWith(error => Logger[F].error(error)("Chapter gear read failed").as(GearReadResult.Failed))

    override def replaceGear(album: AlbumId, chapter: ChapterId, selection: GearSelection): F[GearReplaceResult] =
      summon[Mutex[F]].lock.surround:
        MonadCancelThrow[F].uncancelable: _ =>
          gear.readCatalog.flatMap:
            case GearRepository.CatalogReadResult.Failed         => GearReplaceResult.Failed.pure[F]
            case GearRepository.CatalogReadResult.Found(catalog) =>
              catalog.validateSelection(selection) match
                case Left(reason) => GearReplaceResult.Rejected(reason).pure[F]
                case Right(())    =>
                  Transaction[Db, F].run(chapters.replaceGear(album, chapter, selection = selection)).map:
                    case ChapterRepository.GearWriteResult.Saved     => GearReplaceResult.Saved
                    case ChapterRepository.GearWriteResult.NotFound  => GearReplaceResult.NotFound
                    case ChapterRepository.GearWriteResult.Failed    => GearReplaceResult.Failed
                    case ChapterRepository.GearWriteResult.Uncertain => GearReplaceResult.Uncertain
                  .handleErrorWith(error => Logger[F].error(error)("Chapter gear commit acknowledgement failed").as(GearReplaceResult.Uncertain))

  enum GearReadResult:
    case Found(selection: GearSelection)
    case NotFound, Failed

  enum GearReplaceResult:
    case Saved, NotFound, Failed, Uncertain
    case Rejected(reason: GearCatalog.Failure)
