package photography.usecases.photo

import photography.ports.photo.PhotoThumbnailGenerator

import cats.data.Kleisli
import cats.Id
import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.effect.std.{Mutex, Queue}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MinLength
import io.github.iltotore.iron.constraint.all.{Blank, Not}
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.ports.photo.{PhotoRepository, PhotoStorage, ThumbnailStorage}
import photography.usecases.photo.ChapterPhotoImport.{Scheduling, Status}
import java.util.UUID

final class ChapterPhotoImportComponentTest extends CatsEffectSuite:
  private type Db[A] = Kleisli[IO, Unit, A]
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given Transaction[Db, IO] with
    override def run[A](program: Db[A]): IO[A]     = program.run(())
    override def resolve[A](program: Db[A]): IO[A] = program.run(())
  private val chapter             = ChapterId(new UUID(0L, 1L))
  private val album               = AlbumId(new UUID(0L, 99L))
  private val source: PhotoSource = "/photos/a"
  private val encoded             = Vector(1.toByte).refineEither[ThumbnailBytesConstraint].fold(message => fail(message), identity)
  private val image               = EncodedThumbnail(encoded, ThumbnailDimensions(width = 1, height = 1), ThumbnailQuality.Standard)
  private val content             = PhotoContent(Vector(1.toByte).refineEither[MinLength[1]].fold(message => fail(message), identity))
  private val metadata            = PhotoMetadata(hash = "source", maybeCapturedLocal = None, PhotoDimensions(width = 1, height = 1))

  test("should coalesce overlapping requests and allow another pass after completion"):
    val refs            = Refs()
    val queuedOutcome   = ChapterPhotoImport.ExecutionOutcome(Status.Queued, retirementIssues = Vector.empty[PhotoWriteRecovery.Issue])
    val failure         = PhotoManager.RefreshResult(unsupportedCount = 0, issues = Vector(PhotoManager.Issue.Discovery))
    val finishedOutcome = queuedOutcome.copy(status = Status.Finished(failure))

    (Mutex[IO], Deferred[IO, Unit], Deferred[IO, Unit], Queue.bounded[IO, Unit](capacity = 10)).tupled.flatMap {
      case (mutex, entered, release, signals) =>
        val storage = buildStorage(refs, _ => entered.complete(()).void >> release.get.as(PhotoStorage.DiscoveryResult.Failed))

        buildImport(refs, storage, notification = signals.offer(()))(using mutex).use: imports =>
          for
            initial   <- mutex.lock.surround(imports.prepare(album, chapter, source))
            _         <- signals.take
            preparing <- imports.status(chapter)
            queued    <- imports.launch(initial)
            _         <- entered.get
            _         <- signals.take
            overlap   <- mutex.lock.surround(imports.prepare(album, chapter, source))
            shared    <- imports.launch(overlap)
            active    <- imports.status(chapter).map(_.status)
            _         <- release.complete(())
            _         <- signals.take
            finished  <- imports.status(chapter)
            _         <- awaitFinished(imports)
            retry     <- mutex.lock.surround(imports.prepare(album, chapter, source))
            retried   <- imports.launch(retry)
            _         <- awaitFinished(imports)
            actual    <- refs.scans.get
          yield
            assertEquals(queued, Scheduling.Queued)
            assertEquals(shared, Scheduling.Coalesced)
            assertEquals(active, Status.Running)
            assertEquals(preparing, queuedOutcome)
            assertEquals(finished, finishedOutcome)
            assertEquals(retried, Scheduling.Queued)
            assertEquals(actual, Vector(source, source))
    }

  test("should supersede A to B to A without waiting for the old scan or accepting its completion"):
    val refs = Refs()

    (Mutex[IO], Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap { case (mutex, entered, release) =>
      val storage = buildStorage(refs, _ => IO.uncancelable(_ => entered.complete(()).void >> release.get.as(PhotoStorage.DiscoveryResult.Failed)))

      buildImport(refs, storage)(using mutex).use: imports =>
        for
          original  <- mutex.lock.surround(imports.prepare(album, chapter, source))
          _         <- imports.launch(original)
          _         <- entered.get
          changed   <- mutex.lock.surround(imports.prepare(album, chapter, source = "/photos/b"))
          latest    <- mutex.lock.surround(imports.prepare(album, chapter, source))
          obsolete  <- imports.launch(changed)
          queued    <- imports.launch(latest)
          duplicate <- imports.launch(original)
          _         <- release.complete(())
          _         <- awaitFinished(imports)
          actual    <- refs.scans.get
        yield
          assertEquals(obsolete, Scheduling.Coalesced)
          assertEquals(duplicate, Scheduling.Coalesced)
          assertEquals(queued, Scheduling.Queued)
          assertEquals(actual, Vector(source, source))
    }

  test("should authorize current writes and compensate an old stored thumbnail after A to B to A"):
    Vector((false, false), (true, false), (false, true)).traverse_ { case (supersede, notificationFails) =>
      val refs                 = Refs()
      val completed            = Status.Finished(PhotoManager.RefreshResult(unsupportedCount = 0, issues = Vector.empty[PhotoManager.Issue]))
      val expectedBeforeLaunch = if supersede then Status.Queued else completed
      val synchronization      = (Mutex[IO], Deferred[IO, Unit], Deferred[IO, Unit], Deferred[IO, Unit]).tupled

      synchronization.flatMap { case (mutex, stored, release, launchLatest) =>
        val storage = buildPublicationStorage(refs, stored = stored, release = release)
        val notify  = if notificationFails then IO.raiseError[Unit](new IllegalStateException("notification")) else IO.unit
        val program = buildImport(refs, storage, notification = notify)(using mutex).use: imports =>
          val changeSource = mutex.lock.surround(imports.prepare(album, chapter, source = "/photos/b") >> imports.prepare(album, chapter, source))

          for
            idle         <- imports.status(chapter).map(_.status)
            request      <- mutex.lock.surround(imports.prepare(album, chapter, source))
            _            <- imports.launch(request)
            _            <- stored.get
            latest       <- if supersede then changeSource else request.pure[IO]
            _            <- launchLatest.get
            beforeLaunch <- imports.status(chapter).map(_.status)
            _            <- if supersede then imports.launch(latest).void else IO.unit
            _            <- awaitFinished(imports)
            status       <- imports.status(chapter).map(_.status)
            written      <- refs.saves.get
            deleted      <- refs.removed.get
            invalidated  <- refs.invalidations.get
          yield
            assertEquals(idle, Status.Idle)
            assertEquals(beforeLaunch, expectedBeforeLaunch)
            assertEquals(written.map(_.original.source), Vector(source))
            assertEquals(deleted.size, if supersede then 1 else 0)
            assertEquals(status, completed)
            assert(invalidated.exists { case (owner, id, published) => owner.value === album.value && id.value === chapter.value && published === 1 })

        for
          control <- TestControl.execute(program)
          _       <- control.tick
          _       <- release.complete(())
          _       <- control.tick
          _       <- launchLatest.complete(())
          _       <- control.tick
          result  <- control.results
        yield assertEquals(result, Outcome.succeeded[Id, Throwable, Unit](()).some)
      }
    }

  test("should report launch failure after its application resource is closed"):
    val refs                                    = Refs()
    val storage                                 = buildStorage(refs, _ => PhotoStorage.DiscoveryResult.Failed.pure[IO])
    val retirement: PhotoWriteIntent.Retirement = PhotoWriteIntent.Retirement(
      PhotoWriteIntentId(new UUID(0L, 70L)),
      chapter,
      PhotoId(new UUID(0L, 71L)),
      ThumbnailLocation(new UUID(0L, 72L))
    )

    for
      mutex   <- Mutex[IO]
      imports <- buildImport(refs, storage)(using mutex).use(_.pure[IO])
      request <- mutex.lock.surround(imports.prepare(album, chapter, source))
      result  <- imports.launch(request)
      cleanup <- imports.retire(album, retirements = Vector(retirement)).attempt
      status  <- imports.status(chapter).map(_.status)
    yield
      assertEquals(result, Scheduling.Failed)
      assert(cleanup.isLeft)
      assertEquals(status, Status.LaunchFailed)

  test("should block an uncertain source generation until saved metadata prepares another pass"):
    val refs = Refs()

    (Mutex[IO], Deferred[IO, Unit], Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap { case (mutex, stored, release, inspect) =>
      val storage = buildPublicationStorage(refs, stored = stored, release = release)
      val program = buildImport(refs, storage)(using mutex).use: imports =>
        for
          original <- mutex.lock.surround(imports.prepare(album, chapter, source))
          _        <- imports.launch(original)
          _        <- stored.get
          _        <- mutex.lock.surround(imports.suspend(chapter))
          _        <- inspect.get
          status   <- imports.status(chapter).map(_.status)
          saved    <- refs.saves.get
          deleted  <- refs.removed.get
          latest   <- mutex.lock.surround(imports.prepare(album, chapter, source))
          _        <- imports.launch(latest)
          _        <- awaitFinished(imports)
        yield
          assertEquals(status, Status.Uncertain)
          assertEquals(saved, Vector.empty[Photo])
          assertEquals(deleted.size, 1)

      for
        control <- TestControl.execute(program)
        _       <- control.tick
        _       <- release.complete(())
        _       <- control.tick
        _       <- inspect.complete(())
        _       <- control.tick
        result  <- control.results
      yield assertEquals(result, Outcome.succeeded[Id, Throwable, Unit](()).some)
    }

  test("should supervise targeted retirement beyond caller cancellation without holding the chapter lock"):
    Vector(false, true).traverse_ { fails =>
      val refs                                = Refs()
      val retired                             = ThumbnailLocation(new UUID(0L, 70L))
      val intent: PhotoWriteIntent.Retirement =
        PhotoWriteIntent.Retirement(PhotoWriteIntentId(new UUID(0L, 71L)), chapter, PhotoId(new UUID(0L, 72L)), retired)
      (Mutex[IO], Deferred[IO, Unit], Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap { case (mutex, entered, release, inspect) =>
        val cleanup = (_: ThumbnailLocation) =>
          entered.complete(()).void >> release.get.as(if fails then ThumbnailStorage.RemoveResult.Failed else ThumbnailStorage.RemoveResult.Removed)
        val discovery = PhotoStorage.DiscoveryResult.Complete(originals = Vector.empty[OriginalPhotoLocation], unsupportedCount = 0).pure[IO]
        val storage   = buildStorage(refs, _ => discovery, cleanup = cleanup)
        val program   = buildImport(refs, storage)(using mutex).use: imports =>
          for
            caller  <- (imports.retire(album, retirements = Vector(intent)) >> IO.never[Unit]).start
            _       <- entered.get
            _       <- caller.cancel
            _       <- mutex.lock.surround(imports.prepare(album, ChapterId(new UUID(0L, 73L)), source))
            before  <- refs.completed.get
            _       <- inspect.get
            after   <- refs.completed.get
            request <- mutex.lock.surround(imports.prepare(album, chapter, source))
            _       <- imports.launch(request)
            _       <- awaitFinished(imports)
            outcome <- imports.status(chapter)
          yield
            assertEquals(before, Vector.empty[PhotoWriteIntentId])
            assertEquals(after, if fails then Vector.empty[PhotoWriteIntentId] else Vector(intent.id))
            val issue          = PhotoWriteRecovery.Issue.Cleanup(intent.id, retired)
            val expectedIssues = if fails then Vector(issue) else Vector.empty[PhotoWriteRecovery.Issue]
            val successful     = Status.Finished(PhotoManager.RefreshResult(unsupportedCount = 0, issues = Vector.empty[PhotoManager.Issue]))
            assertEquals(outcome, ChapterPhotoImport.ExecutionOutcome(successful, retirementIssues = expectedIssues))

        for
          control <- TestControl.execute(program)
          _       <- control.tick
          _       <- release.complete(())
          _       <- control.tick
          _       <- inspect.complete(())
          _       <- control.tick
          result  <- control.results
        yield assertEquals(result, Outcome.succeeded[Id, Throwable, Unit](()).some)
      }
    }

  test("should invalidate committed removal independently of subscribers"):
    val refs      = Refs()
    val thumbnail = Thumbnail(ThumbnailLocation(new UUID(0L, 80L)), version = "version", image.dimensions, image.quality, bytes = 1)
    val photo     =
      Photo(PhotoId(new UUID(0L, 81L)), chapter, OriginalPhotoLocation(source, filename = "gone.jpg"), metadata, thumbnail, maybeCaption = None)
    val complete = PhotoStorage.DiscoveryResult.Complete(originals = Vector.empty[OriginalPhotoLocation], unsupportedCount = 0)
    val storage  = buildStorage(refs, _ => complete.pure[IO], cleanup = _ => ThumbnailStorage.RemoveResult.Removed.pure[IO])

    Mutex[IO].flatMap: mutex =>
      buildImport(refs, storage)(using mutex).use: imports =>
        for
          _           <- refs.saves.set(Vector(photo))
          request     <- mutex.lock.surround(imports.prepare(album, chapter, source))
          _           <- imports.launch(request)
          _           <- awaitFinished(imports)
          remaining   <- refs.saves.get
          invalidated <- refs.invalidations.get
        yield
          assertEquals(remaining, Vector.empty[Photo])
          assert(invalidated.contains((album, chapter, 0)))

  private def awaitFinished(imports: ChapterPhotoImport[IO]) =
    val finished = (IO.cede >> imports.status(chapter).map(_.status)).map:
      case _: Status.Finished => true
      case _                  => false
    finished.iterateUntil(identity).void

  final private case class Refs():
    val invalidations: Ref[IO, Vector[(AlbumId, ChapterId, Int)]] = Ref.unsafe(Vector.empty)
    val completed: Ref[IO, Vector[PhotoWriteIntentId]]            = Ref.unsafe(Vector.empty)
    val scans: Ref[IO, Vector[PhotoSource]]                       = Ref.unsafe(Vector.empty)
    val saves: Ref[IO, Vector[Photo]]                             = Ref.unsafe(Vector.empty)
    val removed: Ref[IO, Vector[ThumbnailLocation]]               = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildImport(refs: Refs, storage: PhotoStorage[IO], notification: IO[Unit] = IO.unit)(using Mutex[IO]) =
    given IdGenerator[IO] with
      private val next                  = Ref.unsafe[IO, Long](10L)
      override def generateId: IO[UUID] = next.getAndUpdate(_ + 1L).map(index => new UUID(0L, index))
    val photos = new PhotoRepository[Db]:
      override def saveCaption(chapter: ChapterId, id: PhotoId, text: String): Db[PhotoRepository.SaveCaptionResult] =
        Kleisli.liftF[
          IO,
          Unit,
          PhotoRepository.SaveCaptionResult
        ](IO.raiseError[PhotoRepository.SaveCaptionResult](new AssertionError("Unexpected caption mutation")))
      override def listChapter(id: ChapterId): Db[PhotoRepository.ListResult] =
        val listed = refs.saves.get.map(photos => PhotoRepository.ListResult.Listed(photos = photos): PhotoRepository.ListResult)
        Kleisli.liftF[IO, Unit, PhotoRepository.ListResult](listed)
      override def save(photo: Photo): Db[PhotoRepository.WriteResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.WriteResult](refs.saves.update(_ :+ photo).as(PhotoRepository.WriteResult.Applied))
      override def remove(id: PhotoId): Db[PhotoRepository.WriteResult] =
        val removal = refs.saves.update(_.filterNot(_.id.value === id.value)).as(PhotoRepository.WriteResult.Applied)
        Kleisli.liftF[IO, Unit, PhotoRepository.WriteResult](removal)
      override def recordWrite(intent: PhotoWriteIntent): Db[PhotoRepository.RecordWriteResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.RecordWriteResult](PhotoRepository.RecordWriteResult.Recorded.pure[IO])
      override def completeWrite(id: PhotoWriteIntentId): Db[PhotoRepository.CompleteWriteResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.CompleteWriteResult](refs.completed.update(_ :+ id).as(PhotoRepository.CompleteWriteResult.Completed))
      override def listUnfinishedWrites: Db[PhotoRepository.ListUnfinishedWritesResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.ListUnfinishedWritesResult](PhotoRepository.ListUnfinishedWritesResult.Failed.pure[IO])
    val generator = new PhotoThumbnailGenerator[IO]:
      override def quality: ThumbnailQuality                                                     = ThumbnailQuality.Standard
      override def generate(content: PhotoContent): IO[PhotoThumbnailGenerator.GenerationResult] =
        PhotoThumbnailGenerator.GenerationResult.Generated(image).pure[IO]
    val events = new AlbumPhotoEvents[IO]:
      override def subscribe(id: AlbumId): IO[AlbumPhotoEvents.SubscribeResult[IO]] =
        IO.raiseError[AlbumPhotoEvents.SubscribeResult[IO]](new AssertionError("Unexpected subscription"))
      override def invalidate(owner: AlbumId, id: ChapterId): IO[Unit] =
        refs.saves.get.flatMap(saved => refs.invalidations.update(_ :+ (owner, id, saved.size))) >> notification
    ChapterPhotoImport.make(storage, photos, generator, events)
  // scalafix:on DisableSyntax.defaultArgs

  // scalafix:off DisableSyntax.defaultArgs
  private def buildStorage(
      refs: Refs,
      discover: PhotoSource => IO[PhotoStorage.DiscoveryResult],
      cleanup: ThumbnailLocation => IO[ThumbnailStorage.RemoveResult] =
        _ => IO.raiseError[ThumbnailStorage.RemoveResult](new AssertionError("Unexpected removal"))
  ) =
    new PhotoStorage[IO]:
      override def readThumbnail(location: ThumbnailLocation, version: String :| Not[Blank]): IO[ThumbnailStorage.ReadResult] =
        IO.raiseError[ThumbnailStorage.ReadResult](new AssertionError("Unexpected thumbnail read"))
      override def discoverOriginals(value: PhotoSource): IO[PhotoStorage.DiscoveryResult]    = refs.scans.update(_ :+ value) >> discover(value)
      override def readOriginal(location: OriginalPhotoLocation): IO[PhotoStorage.ReadResult] =
        IO.raiseError[PhotoStorage.ReadResult](new AssertionError("Unexpected read"))
      override def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): IO[ThumbnailStorage.StoreResult] =
        IO.raiseError[ThumbnailStorage.StoreResult](new AssertionError("Unexpected store"))
      override def removeThumbnail(location: ThumbnailLocation): IO[ThumbnailStorage.RemoveResult] =
        cleanup(location)
  // scalafix:on DisableSyntax.defaultArgs

  private def buildPublicationStorage(refs: Refs, stored: Deferred[IO, Unit], release: Deferred[IO, Unit]) =
    new PhotoStorage[IO]:
      override def readThumbnail(location: ThumbnailLocation, version: String :| Not[Blank]): IO[ThumbnailStorage.ReadResult] =
        IO.raiseError[ThumbnailStorage.ReadResult](new AssertionError("Unexpected thumbnail read"))
      override def discoverOriginals(value: PhotoSource): IO[PhotoStorage.DiscoveryResult] =
        val originals = Vector(OriginalPhotoLocation(value, filename = "one.jpg"))
        refs.scans.update(_ :+ value).as(PhotoStorage.DiscoveryResult.Complete(originals = originals, unsupportedCount = 0))
      override def readOriginal(location: OriginalPhotoLocation): IO[PhotoStorage.ReadResult] =
        PhotoStorage.ReadResult.Read(content, metadata).pure[IO]
      override def storeThumbnail(location: ThumbnailLocation, value: EncodedThumbnail): IO[ThumbnailStorage.StoreResult] =
        val thumbnail = Thumbnail(location, version = "version", value.dimensions, value.quality, bytes = 1)
        stored.complete(()).void >> release.get.as(ThumbnailStorage.StoreResult.Stored(thumbnail))
      override def removeThumbnail(location: ThumbnailLocation): IO[ThumbnailStorage.RemoveResult] =
        refs.removed.update(_ :+ location).as(ThumbnailStorage.RemoveResult.Removed)
