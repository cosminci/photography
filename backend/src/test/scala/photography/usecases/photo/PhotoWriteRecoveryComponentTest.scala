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
      val refs       = Refs()
      val pending    = PhotoRepository.ListUnfinishedWritesResult.Listed(intents = Vector(intent)).pure[IO]
      val recovery   = buildRecovery(refs, pending = pending, listed = PhotoRepository.ListResult.Listed(photos = photos).pure[IO])
      val superseded = new Transaction[IO, IO]:
        override def run[A](program: IO[A]): IO[A]     = new AssertionError("Superseded import cannot start guarded writes").raiseError[IO, A]
        override def resolve[A](program: IO[A]): IO[A] = program

      for
        first     <- recovery.recover
        resolved  <- buildRecovery(refs, listed = PhotoRepository.ListResult.Listed(photos = photos).pure[IO])(using superseded).resolve(intent)
        second    <- buildRecovery(refs).recover
        deletions <- refs.removed.get
        completed <- refs.completed.get
        reads     <- refs.chapterReads.get
      yield
        assertEquals(first, RecoveryResult.Recovered)
        assertEquals(resolved, RecoveryResult.Recovered)
        assertEquals(second, RecoveryResult.Recovered)
        assertEquals(deletions, removed ++ removed)
        assertEquals(completed, Vector(intent.id, intent.id))
        assertEquals(reads, Vector(intent.chapter, intent.chapter))
    }

  test("should fail startup explicitly when evidence cannot be listed"):
    Vector(PhotoRepository.ListUnfinishedWritesResult.Failed.pure[IO], error.raiseError[IO, PhotoRepository.ListUnfinishedWritesResult])
      .traverse_ { pending =>
        val refs = Refs()

        for
          result  <- buildRecovery(refs, pending = pending).recover
          removed <- refs.removed.get
          writes  <- refs.completed.get
        yield
          assertEquals(result, RecoveryResult.Failed(issues = Vector(Issue.Listing)))
          assertEquals(removed, Vector.empty[ThumbnailLocation])
          assertEquals(writes, Vector.empty)
      }

  test("should retain evidence and avoid deletion when current references cannot be read"):
    Vector(PhotoRepository.ListResult.Failed.pure[IO], error.raiseError[IO, PhotoRepository.ListResult]).traverse_ { listed =>
      val refs = Refs()

      for
        result  <- buildRecovery(refs, listed = listed).resolve(publication)
        removed <- refs.removed.get
        writes  <- refs.completed.get
      yield
        assertEquals(result, RecoveryResult.Failed(issues = Vector(Issue.References(writeId))))
        assertEquals(removed, Vector.empty[ThumbnailLocation])
        assertEquals(writes, Vector.empty)
    }

  test("should report cleanup or completion failure and retry supplied unfinished evidence"):
    Vector(false, true).traverse_ { failCompletion =>
      Vector(false, true).traverse_ { throwError =>
        val refs     = Refs()
        val pending  = PhotoRepository.ListUnfinishedWritesResult.Listed(intents = Vector(publication)).pure[IO]
        val cleaning =
          if failCompletion then ThumbnailStorage.RemoveResult.Removed.pure[IO]
          else if throwError then error.raiseError[IO, ThumbnailStorage.RemoveResult]
          else ThumbnailStorage.RemoveResult.Failed(error).pure[IO]
        val completing =
          if !failCompletion then PhotoRepository.CompleteWriteResult.Completed.pure[IO]
          else if throwError then error.raiseError[IO, PhotoRepository.CompleteWriteResult]
          else PhotoRepository.CompleteWriteResult.Failed.pure[IO]
        val issue = if failCompletion then Issue.Completion(writeId) else Issue.Cleanup(writeId, old)

        for
          failed    <- buildRecovery(refs, pending = pending, cleaning = cleaning, completing = completing).recover
          attempted <- refs.completed.get
          retried   <- buildRecovery(refs, pending = pending).recover
          completed <- refs.completed.get
        yield
          assertEquals(failed, RecoveryResult.Failed(issues = Vector(issue)))
          assertEquals(attempted, if failCompletion then Vector(writeId) else Vector.empty[PhotoWriteIntentId])
          assertEquals(retried, RecoveryResult.Recovered)
          assertEquals(completed, attempted :+ writeId)
      }
    }

  test("should retain partially cleaned evidence while resolving other writes and safely retry known locations"):
    val otherId       = PhotoWriteIntentId(new UUID(0L, 7L))
    val otherLocation = ThumbnailLocation(new UUID(0L, 8L))
    val otherWrite    = PhotoWriteIntent.Retirement(otherId, chapter, photoId, otherLocation)
    val refs          = Refs()
    val pending       = PhotoRepository.ListUnfinishedWritesResult.Listed(intents = Vector(publication, otherWrite)).pure[IO]
    val remaining     = PhotoRepository.ListUnfinishedWritesResult.Listed(intents = Vector(publication)).pure[IO]
    val responses     = Ref.unsafe[IO, List[ThumbnailStorage.RemoveResult]](List(
      ThumbnailStorage.RemoveResult.Failed(error),
      ThumbnailStorage.RemoveResult.Removed,
      ThumbnailStorage.RemoveResult.Removed
    ))
    val cleaning = responses.getAndUpdate(_.drop(1)).flatMap(_.headOption.liftTo[IO](new AssertionError("Unexpected cleanup")))
    val empty    = PhotoRepository.ListResult.Listed(photos = Vector.empty[Photo]).pure[IO]

    for
      failed    <- buildRecovery(refs, pending = pending, listed = empty, cleaning = cleaning).recover
      attempted <- refs.completed.get
      removed   <- refs.removed.get
      retried   <- buildRecovery(refs, pending = remaining, listed = empty).recover
      completed <- refs.completed.get
    yield
      assertEquals(failed, RecoveryResult.Failed(issues = Vector(Issue.Cleanup(writeId, fresh))))
      assertEquals(attempted, Vector(otherId))
      assertEquals(removed, Vector(fresh, old, otherLocation))
      assertEquals(retried, RecoveryResult.Recovered)
      assertEquals(completed, Vector(otherId, writeId))

  test("should retain evidence when recovery is interrupted after deletion and complete it on retry"):
    (Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap { case (deleted, release) =>
      val refs     = Refs()
      val pending  = PhotoRepository.ListUnfinishedWritesResult.Listed(intents = Vector(publication)).pure[IO]
      val cleaning = deleted.complete(()).void >> release.get.as(ThumbnailStorage.RemoveResult.Removed)
      val recovery = buildRecovery(refs, pending = pending, cleaning = cleaning)
      val program  =
        for
          worker    <- recovery.recover.start
          _         <- deleted.get
          _         <- worker.cancel
          outcome   <- worker.join
          attempted <- refs.completed.get
          retried   <- buildRecovery(refs, pending = pending).recover
          completed <- refs.completed.get
        yield
          assert(outcome.isCanceled)
          assertEquals(attempted, Vector.empty)
          assertEquals(retried, RecoveryResult.Recovered)
          assertEquals(completed, Vector(writeId))

      TestControl.executeEmbed(program)
    }

  final private case class Refs():
    val completed: Ref[IO, Vector[PhotoWriteIntentId]] = Ref.unsafe(Vector.empty)
    val chapterReads: Ref[IO, Vector[ChapterId]]       = Ref.unsafe(Vector.empty)
    val removed: Ref[IO, Vector[ThumbnailLocation]]    = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildRecovery(
      refs: Refs,
      listed: IO[PhotoRepository.ListResult] = PhotoRepository.ListResult.Listed(photos = Vector(photo)).pure[IO],
      pending: IO[PhotoRepository.ListUnfinishedWritesResult] =
        PhotoRepository.ListUnfinishedWritesResult.Listed(intents = Vector.empty[PhotoWriteIntent]).pure[IO],
      cleaning: IO[ThumbnailStorage.RemoveResult] = ThumbnailStorage.RemoveResult.Removed.pure[IO],
      completing: IO[PhotoRepository.CompleteWriteResult] = PhotoRepository.CompleteWriteResult.Completed.pure[IO]
  )(using Transaction[IO, IO]) =
    val photos = new PhotoRepository[IO]:
      override def find(photo: PhotoId): IO[PhotoRepository.FindResult] =
        new AssertionError("Unexpected photo read").raiseError[IO, PhotoRepository.FindResult]
      override def saveCaption(chapter: ChapterId, id: PhotoId, text: String): IO[PhotoRepository.SaveCaptionResult] =
        new AssertionError("Unexpected caption mutation").raiseError[IO, PhotoRepository.SaveCaptionResult]
      override def listChapter(id: ChapterId): IO[PhotoRepository.ListResult] = refs.chapterReads.update(_ :+ id) >> listed
      override def save(value: Photo): IO[PhotoRepository.WriteResult]        =
        new AssertionError("Unexpected publication").raiseError[IO, PhotoRepository.WriteResult]
      override def remove(id: PhotoId): IO[PhotoRepository.WriteResult] =
        new AssertionError("Unexpected photo removal").raiseError[IO, PhotoRepository.WriteResult]
      override def recordWrite(intent: PhotoWriteIntent): IO[PhotoRepository.RecordWriteResult] =
        new AssertionError("Unexpected evidence creation").raiseError[IO, PhotoRepository.RecordWriteResult]
      override def completeWrite(id: PhotoWriteIntentId): IO[PhotoRepository.CompleteWriteResult] =
        refs.completed.update(_ :+ id) >> completing
      override def listUnfinishedWrites: IO[PhotoRepository.ListUnfinishedWritesResult] =
        pending
    val storage = new ThumbnailStorage[IO]:
      override def readThumbnail(location: ThumbnailLocation, version: String :| Not[Blank]): IO[ThumbnailStorage.ReadResult] =
        new AssertionError("Unexpected read").raiseError[IO, ThumbnailStorage.ReadResult]
      override def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): IO[ThumbnailStorage.StoreResult] =
        new AssertionError("Unexpected thumbnail storage").raiseError[IO, ThumbnailStorage.StoreResult]
      override def removeThumbnail(location: ThumbnailLocation): IO[ThumbnailStorage.RemoveResult] =
        refs.removed.update(_ :+ location) >> cleaning
    PhotoWriteRecovery.make(photos, storage)
  // scalafix:on DisableSyntax.defaultArgs
