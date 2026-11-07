package photography.usecases.photo

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.domain.album.*
import photography.usecases.photo.ChapterPhotoImport.*
import java.util.UUID

final class ChapterPhotoInventoryComponentTest extends CatsEffectSuite:
  private val chapter   = ChapterId(new UUID(0L, 1L))
  private val inventory = PhotoInventory.Inventory(Vector.empty[Photo], Set.empty[PhotoId])
  private val execution = ExecutionOutcome(Status.Running, Vector.empty[PhotoWriteRecovery.Issue])

  test("should classify processing completion, skipped originals and unresolved retirement failures"):
    val skipped   = Vector(PhotoManager.Issue.Undecodable(filename = "bad.jpg"), PhotoManager.Issue.Unstable(filename = "changing.jpg"))
    val completed = Status.Finished(PhotoManager.RefreshResult(unsupportedCount = 3, issues = skipped))
    val failed    = Status.Finished(PhotoManager.RefreshResult(unsupportedCount = 3, issues = skipped :+ PhotoManager.Issue.Storage))
    val cases     = Vector(
      (status = Status.Idle, state = ChapterPhotoInventory.ProcessingState.Idle, unsupported = 0, skipped = 0),
      (status = Status.Queued, state = ChapterPhotoInventory.ProcessingState.Queued, unsupported = 0, skipped = 0),
      (status = Status.Running, state = ChapterPhotoInventory.ProcessingState.Running, unsupported = 0, skipped = 0),
      (status = Status.LaunchFailed, state = ChapterPhotoInventory.ProcessingState.Failed, unsupported = 0, skipped = 0),
      (status = Status.Uncertain, state = ChapterPhotoInventory.ProcessingState.Failed, unsupported = 0, skipped = 0),
      (status = completed, state = ChapterPhotoInventory.ProcessingState.Completed, unsupported = 3, skipped = 2),
      (status = failed, state = ChapterPhotoInventory.ProcessingState.Failed, unsupported = 3, skipped = 2)
    )
    cases.traverse_ { case (status, state, unsupported, skippedCount) =>
      val processing = execution.copy(status = status)
      val unresolved = processing.copy(retirementIssues = Vector(PhotoWriteRecovery.Issue.Listing))
      val response   = PhotoInventory.ListResult.Listed(inventory)

      for
        result <- buildInventory(Refs(), response, processing).read(chapter)
        failed <- buildInventory(Refs(), response, unresolved).read(chapter)
      yield
        result match
          case ChapterPhotoInventory.Result.Found(snapshot) =>
            assertEquals(snapshot.processingState, state)
            assertEquals(snapshot.unsupportedCount, unsupported)
            assertEquals(snapshot.skippedCount, skippedCount)
          case other => fail(s"Expected chapter inventory, got $other")
        failed match
          case ChapterPhotoInventory.Result.Found(snapshot) => assertEquals(snapshot.processingState, ChapterPhotoInventory.ProcessingState.Failed)
          case other                                        => fail(s"Expected unresolved chapter inventory, got $other")
    }

  test("should join processing only after successful inventory reads and preserve chapter absence or failure"):
    val cases = Vector(
      PhotoInventory.ListResult.Listed(inventory) -> ChapterPhotoInventory.Result.Found(ChapterPhotoInventory.Snapshot(inventory, execution)),
      PhotoInventory.ListResult.ChapterNotFound   -> ChapterPhotoInventory.Result.ChapterNotFound,
      PhotoInventory.ListResult.Failed            -> ChapterPhotoInventory.Result.Failed
    )
    cases.traverse_ { case (read, expected) =>
      val refs                = Refs()
      val expectedStatusReads = read match
        case PhotoInventory.ListResult.Listed(_) => Vector(chapter)
        case _                                   => Vector.empty[ChapterId]
      val manager = buildInventory(refs, read, execution)

      for
        result      <- manager.read(chapter)
        reads       <- refs.inventoryReads.get
        statusReads <- refs.statusReads.get
      yield
        assertEquals(result, expected)
        assertEquals(reads, Vector(chapter))
        assertEquals(statusReads, expectedStatusReads)
    }

  final private case class Refs():
    val inventoryReads: Ref[IO, Vector[ChapterId]] = Ref.unsafe(Vector.empty)
    val statusReads: Ref[IO, Vector[ChapterId]]    = Ref.unsafe(Vector.empty)

  private def buildInventory(refs: Refs, read: PhotoInventory.ListResult, processing: ExecutionOutcome) =
    val photos = new PhotoInventory[IO]:
      override def listChapter(id: ChapterId): IO[PhotoInventory.ListResult] =
        refs.inventoryReads.update(_ :+ id).as(read)
    val imports = new ChapterPhotoImport[IO]:
      override def status(id: ChapterId): IO[ExecutionOutcome] =
        refs.statusReads.update(_ :+ id).as(processing)
      override def prepare(album: AlbumId, chapter: ChapterId, source: PhotoSource): IO[Request] =
        new AssertionError("Unexpected preparation").raiseError[IO, Request]
      override def launch(request: Request): IO[Scheduling] = new AssertionError("Unexpected scheduling").raiseError[IO, Scheduling]
      override def retire(album: AlbumId, retirements: Vector[PhotoWriteIntent.Retirement]): IO[Unit] =
        new AssertionError("Unexpected retirement").raiseError[IO, Unit]
      override def suspend(chapter: ChapterId): IO[Unit] = new AssertionError("Unexpected suspension").raiseError[IO, Unit]
    ChapterPhotoInventory.make(photos, imports)
