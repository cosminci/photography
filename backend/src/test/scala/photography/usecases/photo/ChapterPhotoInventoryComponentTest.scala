package photography.usecases.photo

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import photography.domain.album.*
import photography.usecases.photo.ChapterPhotoImport.*
import java.util.UUID

final class ChapterPhotoInventoryComponentTest extends CatsEffectSuite:
  private val chapter   = ChapterId(new UUID(0L, 1L))
  private val inventory = PhotoInventory.Inventory(Vector.empty[Photo], Set.empty[PhotoId])
  private val execution = ExecutionOutcome(Status.Running, Vector.empty[PhotoWriteRecovery.Issue])

  test("inventory owns chapter absence and storage failure; processing joins only successful reads"):
    val cases = Vector(
      PhotoInventory.ListResult.Listed(inventory) -> ChapterPhotoInventory.Result.Found(ChapterPhotoInventory.Snapshot(inventory, execution)),
      PhotoInventory.ListResult.ChapterNotFound   -> ChapterPhotoInventory.Result.ChapterNotFound,
      PhotoInventory.ListResult.Failed            -> ChapterPhotoInventory.Result.Failed
    )
    cases.traverse_ { case (read, expected) =>
      val photos = new PhotoInventory[IO]:
        override def listChapter(id: ChapterId): IO[PhotoInventory.ListResult] =
          IO(assertEquals(id.value, chapter.value)).as(read)
      val imports = new ChapterPhotoImport[IO]:
        override def status(id: ChapterId): IO[ExecutionOutcome] =
          IO(assertEquals(id.value, chapter.value)).as(execution)
        override def prepare(album: AlbumId, chapter: ChapterId, source: PhotoSource): IO[Request] =
          IO.raiseError[Request](new AssertionError("Unexpected preparation"))
        override def launch(request: Request): IO[Scheduling] = IO.raiseError[Scheduling](new AssertionError("Unexpected scheduling"))
        override def retire(album: AlbumId, retirements: Vector[PhotoWriteIntent.Retirement]): IO[Unit] =
          IO.raiseError[Unit](new AssertionError("Unexpected retirement"))
        override def suspend(chapter: ChapterId): IO[Unit] = IO.raiseError[Unit](new AssertionError("Unexpected suspension"))
      ChapterPhotoInventory.make(photos, imports).read(chapter).map(result => assertEquals(result, expected))
    }
