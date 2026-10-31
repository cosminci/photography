package photography.usecases.photo

import cats.Monad
import cats.syntax.all.*
import photography.domain.album.ChapterId

trait ChapterPhotoInventory[F[_]]:
  def read(chapter: ChapterId): F[ChapterPhotoInventory.Result]

object ChapterPhotoInventory:
  def make[F[_]: Monad](photos: PhotoInventory[F], imports: ChapterPhotoImport[F]): ChapterPhotoInventory[F] =
    new LiveChapterPhotoInventory(photos, imports)

  final private class LiveChapterPhotoInventory[F[_]: Monad](photos: PhotoInventory[F], imports: ChapterPhotoImport[F])
      extends ChapterPhotoInventory[F]:
    override def read(chapter: ChapterId): F[Result] =
      photos.listChapter(chapter).flatMap:
        case PhotoInventory.ListResult.Listed(inventory) =>
          imports.status(chapter).map(execution => Result.Found(Snapshot(inventory, execution)))
        case PhotoInventory.ListResult.ChapterNotFound => Result.ChapterNotFound.pure[F]
        case PhotoInventory.ListResult.Failed          => Result.Failed.pure[F]

  final case class Snapshot(inventory: PhotoInventory.Inventory, execution: ChapterPhotoImport.ExecutionOutcome)

  enum Result:
    case Found(snapshot: Snapshot)
    case ChapterNotFound, Failed
