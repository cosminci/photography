package photography.usecases.photo

import photography.ports.photo.PhotoThumbnailGenerator

import cats.data.Kleisli
import cats.Id
import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.collection.MinLength
import io.github.iltotore.iron.constraint.all.{Blank, Not}
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.ports.photo.{PhotoRepository, PhotoStorage, ThumbnailStorage}
import photography.usecases.photo.PhotoManager.*
import java.util.UUID

final class PhotoManagerComponentTest extends CatsEffectSuite:
  private type Db[A] = Kleisli[IO, Unit, A]
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val chapter      = ChapterId(new UUID(0L, 1L))
  private val original     = OriginalPhotoLocation(source = "/photos/trip", filename = "one.jpg")
  private val metadata     = PhotoMetadata(hash = "original", maybeCapturedLocal = None, PhotoDimensions(width = 120, height = 80))
  private val content      = PhotoContent(Vector(1.toByte).refineEither[MinLength[1]].fold[PhotoBytes](message => fail(message), identity))
  private val encoded      = Vector(1.toByte).refineEither[ThumbnailBytesConstraint].fold[ThumbnailBytes](message => fail(message), identity)
  private val image        = EncodedThumbnail(encoded, ThumbnailDimensions(width = 60, height = 40), ThumbnailQuality.Standard)
  private val thumbnail    = Thumbnail(ThumbnailLocation(new UUID(0L, 3L)), version = "version", image.dimensions, image.quality, bytes = 1)
  private val stored       = thumbnail.copy(location = ThumbnailLocation(new UUID(0L, 9L)))
  private val existing     = Photo(PhotoId(new UUID(0L, 4L)), chapter, original, metadata, thumbnail, maybeCaption = "Retained".some)
  private val complete     = PhotoStorage.DiscoveryResult.Complete(Vector(original), unsupportedCount = 2)
  private val absent       = PhotoStorage.DiscoveryResult.Complete(Vector.empty[OriginalPhotoLocation], unsupportedCount = 0)
  private val read         = PhotoStorage.ReadResult.Read(content, metadata)
  private val error        = new RuntimeException("boundary failed")

  test("should read the complete individual photo and distinguish missing, rejected and unavailable repository reads"):
    val cases = Vector(
      PhotoRepository.FindResult.Found(existing).pure[IO] -> ReadResult.Found(existing),
      PhotoRepository.FindResult.NotFound.pure[IO]        -> ReadResult.NotFound,
      PhotoRepository.FindResult.Failed(error).pure[IO]   -> ReadResult.Failed,
      error.raiseError[IO, PhotoRepository.FindResult]    -> ReadResult.Failed
    )

    cases.traverse_ { case (response, expected) =>
      Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
        val manager = buildManager(events, maybeFound = response.some, resolving = events.update(_ :+ "resolve"))

        for
          result <- manager.read(existing.id)
          calls  <- events.get
        yield
          assertEquals(result, expected)
          assertEquals(calls, Vector("resolve", s"find:${existing.id.value}"))
    }

  test("should save exact captions and clear through the photo owner without import or read work"):
    val cases = Vector(
      PhotoRepository.SaveCaptionResult.Saved         -> SaveCaptionResult.Saved,
      PhotoRepository.SaveCaptionResult.PhotoNotFound -> SaveCaptionResult.PhotoNotFound,
      PhotoRepository.SaveCaptionResult.Failed(error) -> SaveCaptionResult.Failed,
      PhotoRepository.SaveCaptionResult.Uncertain     -> SaveCaptionResult.Uncertain
    )

    cases.traverse_ { case (response, expected) =>
      Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
        val caption        = (id: PhotoId, text: String) => events.update(_ :+ s"caption:${id.value}:$text").as(response)
        val manager        = buildManager(events, caption = caption)
        val texts          = Vector("日本\nVacanță", "", " ")
        val expectedEvents = texts.flatMap(text => Vector("transaction", s"caption:${existing.id.value}:$text"))

        for
          results <- texts.traverse(text => manager.saveCaption(chapter, existing.id, text = text))
          actual  <- events.get
        yield
          assertEquals(results, Vector.fill(texts.size)(expected))
          assertEquals(actual, expectedEvents)
    }

  test("should expose uncertain caption acknowledgement without reading or retrying"):
    Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
      val caption = (_: PhotoId, _: String) => events.update(_ :+ "caption") >> error.raiseError[IO, PhotoRepository.SaveCaptionResult]
      val manager = buildManager(events, caption = caption)

      for
        result <- manager.saveCaption(chapter, existing.id, text = "Saved?")
        actual <- events.get
      yield
        assertEquals(result, SaveCaptionResult.Uncertain)
        assertEquals(actual, Vector("transaction", "caption"))

  test("should finish the caption transaction handoff before honoring cancellation"):
    TestControl.executeEmbed:
      val signals = (Ref.of[IO, Vector[String]](Vector.empty), Deferred[IO, Unit], Deferred[IO, Unit], Deferred[IO, Unit], Deferred[IO, Unit]).tupled

      signals.flatMap { case (events, entered, release, committed, cancelStarted) =>
        val caption = (_: PhotoId, _: String) =>
          entered.complete(()).void >> release.get >> committed.complete(()).as(PhotoRepository.SaveCaptionResult.Saved)
        val manager = buildManager(events, caption = caption)

        for
          worker       <- manager.saveCaption(chapter, existing.id, text = "Caption").start
          _            <- entered.get
          cancellation <- (cancelStarted.complete(()).void >> worker.cancel).start
          _            <- cancelStarted.get >> IO.cede
          _            <- release.complete(())
          _            <- cancellation.join
          completed    <- committed.tryGet
        yield assertEquals(completed, ().some)
      }

  test("should await publication, preserve identity and caption, and hand both thumbnail locations to recovery"):
    Vector(
      (metadata.copy(hash = "changed"), ThumbnailQuality.Standard),
      (metadata, ThumbnailQuality.Low)
    ).traverse_ { case (changed, previousQuality) =>
      Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
        val save = (photo: Photo) =>
          IO(assertEquals(photo, existing.copy(metadata = changed, thumbnail = stored))).as(PhotoRepository.WriteResult.Applied)
        val expectedEvents = Vector(
          "transaction",
          "read",
          "generate",
          "transaction",
          "record",
          "store",
          "transaction",
          "resolve"
        )
        val previous = existing.copy(thumbnail = thumbnail.copy(quality = previousQuality))
        val manager  = buildManager(
          events,
          readResult = PhotoStorage.ReadResult.Read(content, changed).pure[IO],
          existingPhotos = Vector(previous),
          save = save,
          finalization = intent =>
            val expected =
              PhotoWriteIntent.Publication(PhotoWriteIntentId(new UUID(0L, 9L)), chapter, existing.id, stored.location, thumbnail.location.some)
            IO(assertEquals(intent, expected)).as(PhotoWriteRecovery.RecoveryResult.Recovered)
        )
        manager.refresh(chapter, original.source).flatMap: result =>
          events.get.map: actual =>
            assertEquals(result.issues, Vector.empty[Issue])
            assertEquals(result.unsupportedCount, 2)
            assertEquals(actual, expectedEvents)
    }

  test("should generate new identities and skip unchanged hashes at the selected quality"):
    Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
      val save = (photo: Photo) =>
        IO(assertEquals(photo, Photo(PhotoId(new UUID(0L, 9L)), chapter, original, metadata, stored, maybeCaption = None)))
          .as(PhotoRepository.WriteResult.Applied)
      for
        fresh     <- buildManager(events, existingPhotos = Vector.empty[Photo], save = save).refresh(chapter, original.source)
        _         <- events.set(Vector.empty)
        unchanged <- buildManager(events).refresh(chapter, original.source)
        actual    <- events.get
      yield
        assertEquals(fresh.issues, Vector.empty[Issue])
        assertEquals(unchanged.issues, Vector.empty[Issue])
        assertEquals(actual, Vector("transaction", "read"))

  test("should attempt absence retirement only after completed enumeration and hand its evidence to recovery"):
    Vector(
      (PhotoRepository.WriteResult.Applied, Vector.empty[Issue]),
      (PhotoRepository.WriteResult.Failed, Vector(Issue.Persistence))
    ).traverse_ { case (result, issues) =>
      Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
        val remove       = (id: PhotoId) => IO(assertEquals(id, existing.id)).as(result)
        val finalization = (intent: PhotoWriteIntent) =>
          val expected = PhotoWriteIntent.Retirement(PhotoWriteIntentId(new UUID(0L, 9L)), chapter, existing.id, thumbnail.location)
          IO(assertEquals(intent, expected)).as(PhotoWriteRecovery.RecoveryResult.Recovered)
        buildManager(events, discovery = absent.pure[IO], remove = remove, finalization = finalization).refresh(chapter, original.source).flatMap:
          actual =>
            events.get.map: recorded =>
              assertEquals(actual.issues, issues)
              assertEquals(recorded, Vector("transaction", "transaction", "record", "resolve"))
    }

  test("should retain inventory when discovery or inventory reading fails"):
    Vector(
      (PhotoStorage.DiscoveryResult.Failed.pure[IO], PhotoRepository.ListResult.Listed(Vector(existing)).pure[IO], Issue.Discovery),
      (IO.raiseError[PhotoStorage.DiscoveryResult](error), PhotoRepository.ListResult.Failed.pure[IO], Issue.Unexpected),
      (complete.pure[IO], PhotoRepository.ListResult.Failed.pure[IO], Issue.Persistence),
      (complete.pure[IO], IO.raiseError[PhotoRepository.ListResult](error), Issue.Persistence)
    ).traverse_ { case (discovery, listed, issue) =>
      Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
        buildManager(events, discovery = discovery, maybeListed = listed.some).refresh(chapter, original.source).flatMap: actual =>
          events.get.map: recorded =>
            assertEquals(actual.issues, Vector(issue))
            assert(!recorded.exists(_.startsWith("remove:")))
    }

  test("should retain previous photos and continue after individual read or generation failures"):
    Vector(
      (PhotoStorage.ReadResult.Failed.pure[IO], PhotoThumbnailGenerator.GenerationResult.Generated(image).pure[IO], Issue.Read(original.filename)),
      (
        PhotoStorage.ReadResult.Unstable.pure[IO],
        PhotoThumbnailGenerator.GenerationResult.Generated(image).pure[IO],
        Issue.Unstable(original.filename)
      ),
      (
        PhotoStorage.ReadResult.Undecodable.pure[IO],
        PhotoThumbnailGenerator.GenerationResult.Generated(image).pure[IO],
        Issue.Undecodable(original.filename)
      ),
      (IO.raiseError[PhotoStorage.ReadResult](error), PhotoThumbnailGenerator.GenerationResult.Generated(image).pure[IO], Issue.Unexpected),
      (read.pure[IO], PhotoThumbnailGenerator.GenerationResult.Failed.pure[IO], Issue.Generation(original.filename))
    ).traverse_ { case (reading, generation, issue) =>
      Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
        val discovery = PhotoStorage.DiscoveryResult.Complete(Vector(original, original.copy(filename = "two.jpg")), unsupportedCount = 0)
        buildManager(events, discovery = discovery.pure[IO], readResult = reading, generation = generation, existingPhotos = Vector.empty[Photo])
          .refresh(chapter, original.source).flatMap: actual =>
            events.get.map: recorded =>
              assertEquals(actual.issues.size, 2)
              assertEquals(actual.issues.headOption, issue.some)
              assertEquals(recorded.count(_ === "read"), 2)
              assert(!recorded.contains("store"))
    }

  test("should preserve publication failures alongside explicit finalization issues"):
    val id          = PhotoWriteIntentId(new UUID(0L, 9L))
    val storedImage = ThumbnailStorage.StoreResult.Stored(stored).pure[IO]
    val rejected    = PhotoRepository.WriteResult.Failed.pure[IO]
    val failures    = Vector(
      (storage = ThumbnailStorage.StoreResult.Failed.pure[IO], writing = rejected, primary = Issue.Storage),
      (storage = IO.raiseError[ThumbnailStorage.StoreResult](error), writing = rejected, primary = Issue.Storage),
      (storage = storedImage, writing = rejected, primary = Issue.Persistence),
      (storage = storedImage, writing = IO.raiseError[PhotoRepository.WriteResult](error), primary = Issue.Persistence)
    )
    val outcomes = Vector(
      Vector.empty[PhotoWriteRecovery.Issue],
      Vector(PhotoWriteRecovery.Issue.References(id)),
      Vector(PhotoWriteRecovery.Issue.Cleanup(id, stored.location)),
      Vector(PhotoWriteRecovery.Issue.Completion(id))
    )

    failures.traverse_ { case (storage, writing, primary) =>
      outcomes.traverse_ { issues =>
        Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
          val outcome = if issues.isEmpty then PhotoWriteRecovery.RecoveryResult.Recovered else PhotoWriteRecovery.RecoveryResult.Failed(issues)
          val manager =
            buildManager(events, existingPhotos = Vector.empty[Photo], storage = storage, save = _ => writing, finalization = _ => outcome.pure[IO])

          for
            result <- manager.refresh(chapter, original.source)
            calls  <- events.get
          yield
            assertEquals(result.issues, primary +: issues.map(Issue.Finalization(_)))
            assertEquals(calls.lastOption, "resolve".some)
      }
    }

  test("should finish publication and absent-photo retirement handoffs before honoring cancellation"):
    Vector((false, false), (false, true), (true, false), (true, true)).traverse_ { case (retiring, committed) =>
      (Ref.of[IO, Vector[String]](Vector.empty), Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap:
        case (events, saving, release) =>
          val writeResult  = if committed then PhotoRepository.WriteResult.Applied else PhotoRepository.WriteResult.Failed
          val handoff      = saving.complete(()).void >> release.get
          val save         = (_: Photo) => handoff.as(writeResult)
          val remove       = (_: PhotoId) => if committed then writeResult.pure[IO] else handoff.as(writeResult)
          val finalization = (_: PhotoWriteIntent) =>
            (if retiring && committed then handoff else IO.unit).as(PhotoWriteRecovery.RecoveryResult.Recovered)
          val reading   = PhotoStorage.ReadResult.Read(content, metadata.copy(hash = "changed")).pure[IO]
          val discovery = if retiring then absent else complete
          val manager   =
            buildManager(events, discovery = discovery.pure[IO], save = save, remove = remove, finalization = finalization, readResult = reading)
          val program =
            for
              refresh      <- manager.refresh(chapter, original.source).flatTap(_ => IO.cede).start
              _            <- saving.get
              cancellation <- refresh.cancel.start
              _            <- cancellation.join
              result       <- refresh.join
              recorded     <- events.get
            yield
              assert(result.isCanceled)
              assertEquals(recorded.count(_ === "resolve"), 1)
              assertEquals(recorded.lastOption, "resolve".some)

          for
            control <- TestControl.execute(program)
            _       <- control.tick
            waiting <- control.results
            _       <- release.complete(())
            _       <- control.tick
            result  <- control.results
          yield
            assertEquals(waiting, None)
            assertEquals(result, Outcome.succeeded[Id, Throwable, Unit](()).some)
    }

  test("should preserve absent photos when a retirement identity cannot be allocated"):
    Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
      val manager = buildManager(events, discovery = absent.pure[IO], nextId = IO.raiseError[UUID](error))

      for
        result <- manager.refresh(chapter, original.source)
        actual <- events.get
      yield
        assertEquals(result.issues, Vector(Issue.Persistence))
        assertEquals(actual, Vector("transaction"))

  test("should reject publication before storing media when its durable evidence cannot be recorded"):
    Vector(PhotoRepository.RecordWriteResult.Failed.pure[IO], IO.raiseError[PhotoRepository.RecordWriteResult](error)).traverse_ { recording =>
      Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
        val noEvidence = buildManager(events, existingPhotos = Vector.empty[Photo], recording = recording)

        for
          rejected <- noEvidence.refresh(chapter, original.source)
          before   <- events.get
        yield
          assertEquals(rejected.issues, Vector(Issue.Persistence))
          assert(!before.contains("store"))
    }

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      events: Ref[IO, Vector[String]],
      discovery: IO[PhotoStorage.DiscoveryResult] = complete.pure[IO],
      readResult: IO[PhotoStorage.ReadResult] = read.pure[IO],
      generation: IO[PhotoThumbnailGenerator.GenerationResult] = PhotoThumbnailGenerator.GenerationResult.Generated(image).pure[IO],
      storage: IO[ThumbnailStorage.StoreResult] = ThumbnailStorage.StoreResult.Stored(stored).pure[IO],
      existingPhotos: Vector[Photo] = Vector(existing),
      maybeListed: Option[IO[PhotoRepository.ListResult]] = None,
      quality: ThumbnailQuality = ThumbnailQuality.Standard,
      save: Photo => IO[PhotoRepository.WriteResult] = _ => PhotoRepository.WriteResult.Applied.pure[IO],
      remove: PhotoId => IO[PhotoRepository.WriteResult] = _ => PhotoRepository.WriteResult.Applied.pure[IO],
      recording: IO[PhotoRepository.RecordWriteResult] = PhotoRepository.RecordWriteResult.Recorded.pure[IO],
      finalization: PhotoWriteIntent => IO[PhotoWriteRecovery.RecoveryResult] = _ => PhotoWriteRecovery.RecoveryResult.Recovered.pure[IO],
      resolving: IO[Unit] = IO.unit,
      nextId: IO[UUID] = new UUID(0L, 9L).pure[IO],
      maybeFound: Option[IO[PhotoRepository.FindResult]] = None,
      caption: (PhotoId, String) => IO[PhotoRepository.SaveCaptionResult] =
        (_, _) => IO.raiseError[PhotoRepository.SaveCaptionResult](new AssertionError("Unexpected caption mutation"))
  ) =
    given IdGenerator[IO] with
      override def generateId: IO[UUID] = nextId
    given Transaction[Db, IO] with
      override def run[A](program: Db[A]): IO[A]     = events.update(_ :+ "transaction") >> program.run(())
      override def resolve[A](program: Db[A]): IO[A] = resolving >> program.run(())
    val photoStorage = new PhotoStorage[IO]:
      override def readThumbnail(location: ThumbnailLocation, version: String :| Not[Blank]): IO[ThumbnailStorage.ReadResult] =
        IO.raiseError[ThumbnailStorage.ReadResult](new AssertionError("Unexpected thumbnail read"))
      override def discoverOriginals(source: PhotoSource): IO[PhotoStorage.DiscoveryResult] = IO(assertEquals(source, original.source)) >>
        discovery
      override def readOriginal(location: OriginalPhotoLocation): IO[PhotoStorage.ReadResult] = events.update(_ :+ "read") >> readResult
      override def storeThumbnail(location: ThumbnailLocation, value: EncodedThumbnail): IO[ThumbnailStorage.StoreResult] =
        IO(assertEquals(location, stored.location)) >> IO(assertEquals(value, image)) >> events.update(_ :+ "store") >> storage
      override def removeThumbnail(location: ThumbnailLocation): IO[ThumbnailStorage.RemoveResult] =
        IO.raiseError[ThumbnailStorage.RemoveResult](new AssertionError("Unexpected cleanup outside recovery"))
    val writePhoto  = save
    val removePhoto = remove
    val repository  = new PhotoRepository[Db]:
      override def find(photo: PhotoId): Db[PhotoRepository.FindResult] =
        val found = maybeFound.getOrElse(existingPhotos.find(_.id.value === photo.value)
          .fold[PhotoRepository.FindResult](PhotoRepository.FindResult.NotFound)(PhotoRepository.FindResult.Found(_)).pure[IO])
        Kleisli.liftF[IO, Unit, PhotoRepository.FindResult](events.update(_ :+ s"find:${photo.value}") >> found)
      override def saveCaption(chapter: ChapterId, id: PhotoId, text: String): Db[PhotoRepository.SaveCaptionResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.SaveCaptionResult](caption(id, text))
      override def listChapter(id: ChapterId): Db[PhotoRepository.ListResult] =
        val listed = maybeListed.getOrElse(PhotoRepository.ListResult.Listed(existingPhotos).pure[IO])
        Kleisli.liftF[IO, Unit, PhotoRepository.ListResult](IO(assertEquals(id, chapter)) >> listed)
      override def save(photo: Photo): Db[PhotoRepository.WriteResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.WriteResult](writePhoto(photo))
      override def remove(photo: PhotoId): Db[PhotoRepository.WriteResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.WriteResult](removePhoto(photo))
      override def recordWrite(intent: PhotoWriteIntent): Db[PhotoRepository.RecordWriteResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.RecordWriteResult](events.update(_ :+ "record") >> recording)
      override def completeWrite(id: PhotoWriteIntentId): Db[PhotoRepository.CompleteWriteResult] =
        Kleisli.liftF[
          IO,
          Unit,
          PhotoRepository.CompleteWriteResult
        ](IO.raiseError[PhotoRepository.CompleteWriteResult](new AssertionError("Unexpected completion outside recovery")))
      override def listUnfinishedWrites: Db[PhotoRepository.ListUnfinishedWritesResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.ListUnfinishedWritesResult](PhotoRepository.ListUnfinishedWritesResult.Failed.pure[IO])
    val selectedQuality = quality
    val generator       = new PhotoThumbnailGenerator[IO]:
      override def quality: ThumbnailQuality                                                   = selectedQuality
      override def generate(value: PhotoContent): IO[PhotoThumbnailGenerator.GenerationResult] =
        IO(assertEquals(value, content)) >> events.update(_ :+ "generate") >> generation
    val recovery = new PhotoWriteRecovery[IO]:
      override def recover: IO[PhotoWriteRecovery.RecoveryResult] =
        IO.raiseError[PhotoWriteRecovery.RecoveryResult](new AssertionError("Unexpected startup recovery"))
      override def resolve(intent: PhotoWriteIntent): IO[PhotoWriteRecovery.RecoveryResult] =
        events.update(_ :+ "resolve") >> finalization(intent)
    PhotoManager.make(photoStorage, repository, generator, recovery)
  // scalafix:on DisableSyntax.defaultArgs
