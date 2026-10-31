package photography.usecases.photo

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.{Blank, Not}
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.Transaction
import photography.domain.album.*
import photography.ports.photo.{PhotoRepository, ThumbnailStorage}
import photography.usecases.photo.PhotoWriteRecovery.*
import java.util.UUID

final class PhotoWriteRecoveryComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given Transaction[IO, IO] with
    override def run[A](program: IO[A]): IO[A]     = program
    override def resolve[A](program: IO[A]): IO[A] = program
  private val chapter                                   = ChapterId(new UUID(0L, 1L))
  private val photoId                                   = PhotoId(new UUID(0L, 2L))
  private val writeId                                   = PhotoWriteIntentId(new UUID(0L, 3L))
  private val fresh                                     = ThumbnailLocation(new UUID(0L, 4L))
  private val old                                       = ThumbnailLocation(new UUID(0L, 5L))
  private val publication: PhotoWriteIntent.Publication =
    PhotoWriteIntent.Publication(writeId, chapter, photoId, fresh, maybePrevious = old.some)
  private val retirement = PhotoWriteIntent.Retirement(writeId, chapter, photoId, old)
  private val metadata   = PhotoMetadata(hash = "hash", maybeCapturedLocal = None, PhotoDimensions(width = 10, height = 10))
  private val thumbnail  = Thumbnail(fresh, version = "v", ThumbnailDimensions(width = 5, height = 5), ThumbnailQuality.Low, bytes = 1)
  private val photo      =
    Photo(photoId, chapter, OriginalPhotoLocation(source = "/photos", filename = "one.jpg"), metadata, thumbnail, maybeCaption = None)
  private val error = new RuntimeException("boundary failed")

  test("should resolve only known unused locations and preserve every current chapter reference"):
    Vector(
      (publication, Vector(photo), Vector(old)),
      (publication, Vector(photo.copy(thumbnail = thumbnail.copy(location = old))), Vector(fresh)),
      (publication, Vector.empty[Photo], Vector(fresh, old)),
      (publication.copy(maybePrevious = None), Vector.empty[Photo], Vector(fresh)),
      (
        publication,
        Vector(photo, photo.copy(id = PhotoId(new UUID(0L, 6L)), thumbnail = thumbnail.copy(location = old))),
        Vector.empty[ThumbnailLocation]
      ),
      (retirement, Vector.empty[Photo], Vector(old)),
      (retirement, Vector(photo.copy(thumbnail = thumbnail.copy(location = old))), Vector.empty[ThumbnailLocation])
    ).traverse_ { case (intent, photos, removed) =>
      val refs     = Refs(initial = Vector(intent))
      val recovery = buildRecovery(refs, listed = PhotoRepository.ListResult.Listed(photos = photos).pure[IO])

      for
        first     <- recovery.recover
        second    <- recovery.recover
        deletions <- refs.removed.get
        pending   <- refs.pending.get
      yield
        assertEquals(first, RecoveryResult.Recovered)
        assertEquals(second, RecoveryResult.Recovered)
        assertEquals(deletions, removed)
        assertEquals(pending, Vector.empty[PhotoWriteIntent])
    }

  test("should fail startup explicitly when evidence cannot be listed"):
    Vector(PhotoRepository.ListUnfinishedWritesResult.Failed.pure[IO], IO.raiseError[PhotoRepository.ListUnfinishedWritesResult](error))
      .traverse_ { pending =>
        val refs = Refs(initial = Vector(publication))

        for
          result  <- buildRecovery(refs, maybePending = pending.some).recover
          removed <- refs.removed.get
          writes  <- refs.pending.get
        yield
          assertEquals(result, RecoveryResult.Failed(issues = Vector(Issue.Listing)))
          assertEquals(removed, Vector.empty[ThumbnailLocation])
          assertEquals(writes, Vector(publication))
      }

  test("should retain evidence and avoid deletion when current references cannot be read"):
    Vector(PhotoRepository.ListResult.Failed.pure[IO], IO.raiseError[PhotoRepository.ListResult](error)).traverse_ { listed =>
      val refs = Refs(initial = Vector(publication))

      for
        result  <- buildRecovery(refs, listed = listed).recover
        removed <- refs.removed.get
        writes  <- refs.pending.get
      yield
        assertEquals(result, RecoveryResult.Failed(issues = Vector(Issue.References(writeId))))
        assertEquals(removed, Vector.empty[ThumbnailLocation])
        assertEquals(writes, Vector(publication))
    }

  test("should retain evidence after cleanup or completion failure and resolve it on retry"):
    Vector(false, true).traverse_ { failCompletion =>
      Vector(false, true).traverse_ { throwError =>
        val refs     = Refs(initial = Vector(publication))
        val cleaning =
          if failCompletion then ThumbnailStorage.RemoveResult.Removed.pure[IO]
          else if throwError then IO.raiseError[ThumbnailStorage.RemoveResult](error)
          else ThumbnailStorage.RemoveResult.Failed.pure[IO]
        val completing =
          if !failCompletion then PhotoRepository.CompleteWriteResult.Completed.pure[IO]
          else if throwError then IO.raiseError[PhotoRepository.CompleteWriteResult](error)
          else PhotoRepository.CompleteWriteResult.Failed.pure[IO]
        val issue = if failCompletion then Issue.Completion(writeId) else Issue.Cleanup(writeId, old)

        for
          failed  <- buildRecovery(refs, cleaning = _ => cleaning, completing = completing).recover
          pending <- refs.pending.get
          retried <- buildRecovery(refs).recover
          cleared <- refs.pending.get
        yield
          assertEquals(failed, RecoveryResult.Failed(issues = Vector(issue)))
          assertEquals(pending, Vector(publication))
          assertEquals(retried, RecoveryResult.Recovered)
          assertEquals(cleared, Vector.empty[PhotoWriteIntent])
      }
    }

  test("should retain partially cleaned evidence while resolving other writes and safely retry known locations"):
    val otherId       = PhotoWriteIntentId(new UUID(0L, 7L))
    val otherLocation = ThumbnailLocation(new UUID(0L, 8L))
    val otherWrite    = PhotoWriteIntent.Retirement(otherId, chapter, photoId, otherLocation)
    val refs          = Refs(initial = Vector(publication, otherWrite))
    val cleaning      = (location: ThumbnailLocation) =>
      (if location.value === fresh.value then ThumbnailStorage.RemoveResult.Failed else ThumbnailStorage.RemoveResult.Removed).pure[IO]
    val empty = PhotoRepository.ListResult.Listed(photos = Vector.empty[Photo]).pure[IO]

    for
      failed  <- buildRecovery(refs, listed = empty, cleaning = cleaning).recover
      pending <- refs.pending.get
      removed <- refs.removed.get
      retried <- buildRecovery(refs, listed = empty).recover
      cleared <- refs.pending.get
    yield
      assertEquals(failed, RecoveryResult.Failed(issues = Vector(Issue.Cleanup(writeId, fresh))))
      assertEquals(pending, Vector(publication))
      assertEquals(removed, Vector(fresh, old, otherLocation))
      assertEquals(retried, RecoveryResult.Recovered)
      assertEquals(cleared, Vector.empty[PhotoWriteIntent])

  test("should retain evidence when recovery is interrupted after deletion and complete it on retry"):
    (Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap { case (deleted, release) =>
      val refs     = Refs(initial = Vector(publication))
      val cleaning = (_: ThumbnailLocation) => deleted.complete(()).void >> release.get.as(ThumbnailStorage.RemoveResult.Removed)
      val recovery = buildRecovery(refs, cleaning = cleaning)
      val program  =
        for
          worker  <- recovery.recover.start
          _       <- deleted.get
          _       <- worker.cancel
          outcome <- worker.join
          pending <- refs.pending.get
          retried <- buildRecovery(refs).recover
          cleared <- refs.pending.get
        yield
          assert(outcome.isCanceled)
          assertEquals(pending, Vector(publication))
          assertEquals(retried, RecoveryResult.Recovered)
          assertEquals(cleared, Vector.empty[PhotoWriteIntent])

      TestControl.executeEmbed(program)
    }

  final private case class Refs(initial: Vector[PhotoWriteIntent]):
    val pending: Ref[IO, Vector[PhotoWriteIntent]]  = Ref.unsafe(initial)
    val removed: Ref[IO, Vector[ThumbnailLocation]] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildRecovery(
      refs: Refs,
      listed: IO[PhotoRepository.ListResult] = PhotoRepository.ListResult.Listed(photos = Vector(photo)).pure[IO],
      maybePending: Option[IO[PhotoRepository.ListUnfinishedWritesResult]] = None,
      cleaning: ThumbnailLocation => IO[ThumbnailStorage.RemoveResult] = _ => ThumbnailStorage.RemoveResult.Removed.pure[IO],
      completing: IO[PhotoRepository.CompleteWriteResult] = PhotoRepository.CompleteWriteResult.Completed.pure[IO]
  ) =
    val photos = new PhotoRepository[IO]:
      override def saveCaption(chapter: ChapterId, id: PhotoId, text: String): IO[PhotoRepository.SaveCaptionResult] =
        IO.raiseError[PhotoRepository.SaveCaptionResult](new AssertionError("Unexpected caption mutation"))
      override def listChapter(id: ChapterId): IO[PhotoRepository.ListResult] = IO(assertEquals(id, chapter)) >> listed
      override def save(value: Photo): IO[PhotoRepository.WriteResult]        =
        IO.raiseError[PhotoRepository.WriteResult](new AssertionError("Unexpected publication"))
      override def remove(id: PhotoId): IO[PhotoRepository.WriteResult] =
        IO.raiseError[PhotoRepository.WriteResult](new AssertionError("Unexpected photo removal"))
      override def recordWrite(intent: PhotoWriteIntent): IO[PhotoRepository.RecordWriteResult] =
        IO.raiseError[PhotoRepository.RecordWriteResult](new AssertionError("Unexpected evidence creation"))
      override def completeWrite(id: PhotoWriteIntentId): IO[PhotoRepository.CompleteWriteResult] =
        completing.flatTap:
          case PhotoRepository.CompleteWriteResult.Completed => refs.pending.update(_.filterNot(_.id.value === id.value))
          case PhotoRepository.CompleteWriteResult.Failed    => IO.unit
      override def listUnfinishedWrites: IO[PhotoRepository.ListUnfinishedWritesResult] =
        maybePending.getOrElse(refs.pending.get.map(intents => PhotoRepository.ListUnfinishedWritesResult.Listed(intents = intents)))
    val storage = new ThumbnailStorage[IO]:
      override def readThumbnail(location: ThumbnailLocation, version: String :| Not[Blank]): IO[ThumbnailStorage.ReadResult] =
        IO.raiseError[ThumbnailStorage.ReadResult](new AssertionError("Unexpected read"))
      override def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): IO[ThumbnailStorage.StoreResult] =
        IO.raiseError[ThumbnailStorage.StoreResult](new AssertionError("Unexpected thumbnail storage"))
      override def removeThumbnail(location: ThumbnailLocation): IO[ThumbnailStorage.RemoveResult] =
        refs.removed.update(_ :+ location) >> cleaning(location)
    PhotoWriteRecovery.make(photos, storage)
  // scalafix:on DisableSyntax.defaultArgs
