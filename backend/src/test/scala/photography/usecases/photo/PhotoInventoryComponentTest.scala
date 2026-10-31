package photography.usecases.photo

import cats.data.{NonEmptyList, StateT}
import cats.effect.IO
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.Transaction
import photography.domain.album.*
import photography.ports.chapter.ChapterRepository
import photography.ports.photo.{PhotoPlacementRepository, PhotoRepository}
import java.time.LocalDateTime
import java.util.UUID

final class PhotoInventoryComponentTest extends CatsEffectSuite:
  private type Db[A] = StateT[IO, Int, A]
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val chapter      = ChapterId(new UUID(0L, 1L))
  private val metadata     = PhotoMetadata(hash = "hash", maybeCapturedLocal = None, PhotoDimensions(width = 10, height = 10))
  private val thumbnail    =
    Thumbnail(ThumbnailLocation(new UUID(0L, 2L)), version = "v", ThumbnailDimensions(width = 5, height = 5), ThumbnailQuality.Low, bytes = 1)
  private val photo =
    Photo(PhotoId(new UUID(0L, 3L)), chapter, OriginalPhotoLocation(source = "/photos", filename = "z.jpg"), metadata, thumbnail, maybeCaption = None)

  test("should read ordered photos and placements in one type-separated database snapshot"):
    val dated     = photo.copy(id = PhotoId(new UUID(0L, 4L)), metadata = metadata.copy(maybeCapturedLocal = LocalDateTime.of(2025, 1, 1, 0, 0).some))
    val alpha     = photo.copy(id = PhotoId(new UUID(0L, 5L)), original = photo.original.copy(filename = "a.jpg"))
    val upper     = photo.copy(id = PhotoId(new UUID(0L, 6L)), original = photo.original.copy(filename = "A.jpg"))
    val inventory = PhotoInventory.Inventory(photos = Vector(dated, upper, alpha, photo), placed = Set(photo.id))
    val listed    = PhotoRepository.ListResult.Listed(photos = Vector(photo, alpha, dated, upper))
    val placed    = PhotoPlacementRepository.FindResult.Found(photos = Set(photo.id))
    val manager   = buildInventory(listed, placed)

    manager.listChapter(chapter).map: actual =>
      assertEquals(actual, PhotoInventory.ListResult.Listed(inventory))
      assertEquals(inventory.total, 4)
      assertEquals(inventory.countUnplaced, 3)

  test("should report either inventory boundary failure"):
    Vector(
      (PhotoRepository.ListResult.Failed, PhotoPlacementRepository.FindResult.Found(Set.empty[PhotoId])),
      (PhotoRepository.ListResult.Listed(Vector.empty[Photo]), PhotoPlacementRepository.FindResult.Failed)
    ).traverse_ { case (photos, placed) =>
      buildInventory(photos, placed).listChapter(chapter).map(actual => assertEquals(actual, PhotoInventory.ListResult.Failed))
    }

  test("should report a failed transaction as inventory failure"):
    val manager = buildInventory(
      PhotoRepository.ListResult.Listed(Vector(photo)),
      PhotoPlacementRepository.FindResult.Found(Set.empty[PhotoId]),
      maybeTransactionError = new RuntimeException("transaction failed").some
    )
    manager.listChapter(chapter).map(actual => assertEquals(actual, PhotoInventory.ListResult.Failed))

  test("should distinguish a missing chapter from an existing empty inventory and failed source lookup"):
    Vector(
      (ChapterRepository.SourceResult.ChapterNotFound, PhotoInventory.ListResult.ChapterNotFound),
      (ChapterRepository.SourceResult.Failed, PhotoInventory.ListResult.Failed),
      (
        ChapterRepository.SourceResult.Found(AlbumId(new UUID(0L, 99L)), source = "/photos"),
        PhotoInventory.ListResult.Listed(PhotoInventory.Inventory(photos = Vector.empty[Photo], placed = Set.empty[PhotoId]))
      )
    ).traverse_ { case (source, expected) =>
      val manager = buildInventory(
        PhotoRepository.ListResult.Listed(photos = Vector.empty[Photo]),
        PhotoPlacementRepository.FindResult.Found(photos = Set.empty[PhotoId]),
        source = source
      )

      manager.listChapter(chapter).map(actual => assertEquals(actual, expected))
    }

  // scalafix:off DisableSyntax.defaultArgs
  private def buildInventory(
      listed: PhotoRepository.ListResult,
      placed: PhotoPlacementRepository.FindResult,
      maybeTransactionError: Option[Throwable] = None,
      source: ChapterRepository.SourceResult = ChapterRepository.SourceResult.Found(AlbumId(new UUID(0L, 99L)), source = "/photos")
  ) =
    given Transaction[Db, IO] with
      override def run[A](program: Db[A]): IO[A] =
        val expectedSteps = source match
          case _: ChapterRepository.SourceResult.Found => 3
          case _                                       => 1
        maybeTransactionError.fold(
          program.run(0).flatMap { case (steps, result) => IO(assertEquals(steps, expectedSteps)).as(result) }
        )(IO.raiseError[A])
      override def resolve[A](program: Db[A]): IO[A] = program.runA(0)
    val photos = new PhotoRepository[Db]:
      override def saveCaption(chapter: ChapterId, id: PhotoId, text: String): Db[PhotoRepository.SaveCaptionResult] =
        StateT.liftF(IO.raiseError[PhotoRepository.SaveCaptionResult](new AssertionError("Unexpected caption mutation")))
      override def listChapter(id: ChapterId): Db[PhotoRepository.ListResult] =
        StateT.modify[IO, Int](_ + 1).as(listed)
      override def save(value: Photo): Db[PhotoRepository.WriteResult] =
        StateT.liftF(IO.raiseError[PhotoRepository.WriteResult](new RuntimeException("unexpected write")))
      override def remove(value: PhotoId): Db[PhotoRepository.WriteResult] =
        StateT.liftF(IO.raiseError[PhotoRepository.WriteResult](new RuntimeException("unexpected removal")))
      override def recordWrite(intent: PhotoWriteIntent): Db[PhotoRepository.RecordWriteResult] =
        StateT.liftF(IO.raiseError[PhotoRepository.RecordWriteResult](new AssertionError("Unexpected evidence creation")))
      override def completeWrite(id: PhotoWriteIntentId): Db[PhotoRepository.CompleteWriteResult] =
        StateT.liftF(IO.raiseError[PhotoRepository.CompleteWriteResult](new AssertionError("Unexpected evidence completion")))
      override def listUnfinishedWrites: Db[PhotoRepository.ListUnfinishedWritesResult] =
        StateT.liftF(IO.raiseError[PhotoRepository.ListUnfinishedWritesResult](new AssertionError("Unexpected evidence listing")))
    val placements = new PhotoPlacementRepository[Db]:
      override def findPlacedPhotos(id: ChapterId): Db[PhotoPlacementRepository.FindResult] =
        StateT.modify[IO, Int](_ + 1).as(placed)
      override def inspect(album: AlbumId, maybeChapter: Option[ChapterId], photo: PhotoId): Db[PhotoPlacementRepository.InspectResult] =
        StateT.liftF(IO.raiseError[PhotoPlacementRepository.InspectResult](new AssertionError("Unexpected photo inspection")))
    val chapters = new ChapterRepository.Metadata[Db]:
      override def saveDescription(album: AlbumId, chapter: ChapterId, text: String): Db[ChapterRepository.SaveDescriptionResult] =
        StateT.liftF(IO.raiseError[ChapterRepository.SaveDescriptionResult](new AssertionError("Unexpected description mutation")))
      override def findSource(id: ChapterId): Db[ChapterRepository.SourceResult] = StateT.modify[IO, Int](_ + 1).as(source)
      override def create(album: AlbumId, chapter: ChapterRepository.NewChapter, order: NonEmptyList[ChapterId]) =
        StateT.liftF(IO.raiseError[ChapterRepository.CreateResult](new AssertionError("Unexpected chapter creation")))
      override def update(command: ChapterRepository.Update) =
        StateT.liftF(IO.raiseError[ChapterRepository.UpdateResult](new AssertionError("Unexpected chapter update")))
    PhotoInventory.make[IO, Db](photos, placements, chapters)
  // scalafix:on DisableSyntax.defaultArgs
