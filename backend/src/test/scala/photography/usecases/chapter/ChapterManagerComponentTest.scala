package photography.usecases.chapter

import cats.data.NonEmptyList
import cats.data.NonEmptyVector
import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.domain.album.*
import photography.capabilities.Transaction
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.domain.gear.*
import photography.ports.chapter.ChapterRepository
import photography.ports.gear.GearRepository
import photography.domain.gear.GearCatalog.Failure
import photography.usecases.chapter.ChapterManager.*
import java.util.UUID
import scala.concurrent.duration.*

final class ChapterManagerComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val album        = AlbumId(new UUID(0L, 1L))
  private val chapter      = ChapterId(new UUID(0L, 2L))
  private val camera       = Camera(CameraId(new UUID(0L, 3L)), code = "camera", friendlyName = "Camera", kind = CameraKind.FixedLens)
  private val lens      = Lens(LensId(new UUID(0L, 4L)), code = "lens", friendlyName = "Lens", mount = LensMount.SonyE, coverage = SensorFormat.ApsC)
  private val selection = Vector(CameraSelection(camera.id, CameraUse.FixedLens))
  private val catalog   = GearCatalog(cameras = Vector(camera), lenses = Vector(lens))

  test("should read saved chapter gear without disguising missing or failed reads as empty selections"):
    val cases = Vector(
      ChapterRepository.GearReadResult.Found(selection = selection)                     -> GearReadResult.Found(selection = selection),
      ChapterRepository.GearReadResult.Found(selection = Vector.empty[CameraSelection]) ->
        GearReadResult.Found(selection = Vector.empty[CameraSelection]),
      ChapterRepository.GearReadResult.NotFound -> GearReadResult.NotFound,
      ChapterRepository.GearReadResult.Failed   -> GearReadResult.Failed
    )

    cases.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, read = response)
        result  <- manager.readGear(album, chapter)
        calls   <- refs.reads.get
      yield
        assertEquals(result, expected)
        assertEquals(calls, Vector((album = album, chapter = chapter)))
    }

  test("should replace complete ordered gear once after validating the loaded catalog"):
    val cases = Vector(
      ChapterRepository.GearWriteResult.Saved     -> GearReplaceResult.Saved,
      ChapterRepository.GearWriteResult.NotFound  -> GearReplaceResult.NotFound,
      ChapterRepository.GearWriteResult.Failed    -> GearReplaceResult.Failed,
      ChapterRepository.GearWriteResult.Uncertain -> GearReplaceResult.Uncertain
    )

    cases.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, write = response)
        result  <- manager.replaceGear(album, chapter, selection = selection)
        events  <- refs.events.get
        writes  <- refs.writes.get
      yield
        assertEquals(result, expected)
        assertEquals(events, Vector("catalog", "replace"))
        assertEquals(writes, Vector((album = album, chapter = chapter, selection = selection)))
    }

  test("should accept empty selections and preserve order when compatible bodies share lenses"):
    val second =
      camera.copy(id = CameraId(new UUID(0L, 5L)), kind = CameraKind.InterchangeableLens(mount = LensMount.SonyE, sensor = SensorFormat.ApsC))
    val third   = second.copy(id = CameraId(new UUID(0L, 6L)))
    val current = GearRepository.CatalogReadResult.Found(catalog.copy(cameras = Vector(camera, second, third)))
    val cases   = Vector(
      Vector.empty[CameraSelection],
      selection,
      Vector(
        CameraSelection(second.id, CameraUse.Interchangeable(NonEmptyVector.one(lens.id))),
        CameraSelection(third.id, CameraUse.Interchangeable(NonEmptyVector.one(lens.id)))
      ) ++ selection
    )

    cases.traverse_ { values =>
      val refs = Refs()

      for
        manager <- buildManager(refs, current = current)
        result  <- manager.replaceGear(album, chapter, values)
        writes  <- refs.writes.get
      yield
        assertEquals(result, GearReplaceResult.Saved)
        assertEquals(writes, Vector((album = album, chapter = chapter, selection = values)))
    }

  test("should require matching mounts and sufficient coverage for every sensor format"):
    val cases: Vector[(CameraKind.InterchangeableLens, Lens)] =
      for
        mount     <- LensMount.values.toVector
        sensor    <- SensorFormat.values.toVector
        lensMount <- LensMount.values.toVector
        coverage  <- SensorFormat.values.toVector
      yield (CameraKind.InterchangeableLens(mount, sensor), lens.copy(mount = lensMount, coverage = coverage))
    val selected = Vector(CameraSelection(camera.id, CameraUse.Interchangeable(NonEmptyVector.one(lens.id))))

    cases.traverse_ { case (kind, lens) =>
      val current  = GearCatalog(cameras = Vector(camera.copy(kind = kind)), lenses = Vector(lens))
      val accepted = kind.mount === lens.mount && (kind.sensor === SensorFormat.ApsC || lens.coverage === SensorFormat.FullFrame)
      val expected = if accepted then GearReplaceResult.Saved else GearReplaceResult.Rejected(Failure.IncompatibleLens)
      buildManager(Refs(), current = GearRepository.CatalogReadResult.Found(current))
        .flatMap(_.replaceGear(album, chapter, selected)).map(assertEquals(_, expected))
    }

  test("should reject invalid selections and unavailable catalogs before persistence"):
    val interchangeable = camera.copy(kind = CameraKind.InterchangeableLens(mount = LensMount.SonyE, sensor = SensorFormat.FullFrame))
    val selected        = CameraSelection(camera.id, CameraUse.Interchangeable(NonEmptyVector.one(lens.id)))
    val current         = GearRepository.CatalogReadResult.Found(catalog.copy(cameras = Vector(interchangeable)))
    val cases           = Vector(
      (current, selection ++ selection, GearReplaceResult.Rejected(Failure.DuplicateCamera)),
      (current, Vector(selected.copy(camera = CameraId(new UUID(0L, 99L)))), GearReplaceResult.Rejected(Failure.UnknownCamera)),
      (current, selection, GearReplaceResult.Rejected(Failure.CameraKindMismatch)),
      (GearRepository.CatalogReadResult.Found(catalog), Vector(selected), GearReplaceResult.Rejected(Failure.CameraKindMismatch)),
      (
        current,
        Vector(selected.copy(use = CameraUse.Interchangeable(NonEmptyVector.of(lens.id, lens.id)))),
        GearReplaceResult.Rejected(Failure.DuplicateLens)
      ),
      (
        current,
        Vector(selected.copy(use = CameraUse.Interchangeable(NonEmptyVector.one(LensId(new UUID(0L, 99L)))))),
        GearReplaceResult.Rejected(Failure.UnknownLens)
      ),
      (current, Vector(selected), GearReplaceResult.Rejected(Failure.IncompatibleLens)),
      (GearRepository.CatalogReadResult.Failed, selection, GearReplaceResult.Failed)
    )

    cases.traverse_ { case (current, values, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, current = current)
        result  <- manager.replaceGear(album, chapter, selection = values)
        events  <- refs.events.get
      yield
        assertEquals(result, expected)
        assertEquals(events, Vector("catalog"))
    }

  test("should coordinate catalog validation and replacement while preserving cancellation-safe handoff"):
    TestControl.executeEmbed:
      val refs = Refs()

      for
        mutex      <- Mutex[IO]
        reading    <- Deferred[IO, Unit]
        release    <- Deferred[IO, Unit]
        first      <- buildManager(refs, maybeMutex = mutex.some, beforeCatalog = reading.complete(()).void >> release.get)
        second     <- buildManager(refs, maybeMutex = mutex.some)
        pending    <- first.replaceGear(album, chapter, selection = selection).start
        _          <- reading.get
        cancelling <- pending.cancel.start
        waiting    <- second.replaceGear(album, chapter, selection = selection).start
        _          <- IO.sleep(1.millis)
        _          <- waiting.cancel
        before     <- refs.events.get
        _          <- release.complete(())
        _          <- pending.join
        _          <- cancelling.joinWithNever
        result     <- second.replaceGear(album, chapter, selection = selection)
        after      <- refs.events.get
      yield
        assertEquals(before, Vector("catalog"))
        assertEquals(result, GearReplaceResult.Saved)
        assertEquals(after, Vector("catalog", "replace", "catalog", "replace"))

  test("should report failed transaction reads and uncertain write acknowledgements without replay"):
    val failure = new IllegalStateException("Transaction failed").raiseError[IO, Unit]

    for
      manager <- buildManager(Refs(), beforeTransaction = failure)
      read    <- manager.readGear(album, chapter)
      write   <- manager.replaceGear(album, chapter, selection = selection)
    yield
      assertEquals(read, GearReadResult.Failed)
      assertEquals(write, GearReplaceResult.Uncertain)

  final private case class Refs():
    val events: Ref[IO, Vector[String]]                                                         = Ref.unsafe(Vector.empty)
    val reads: Ref[IO, Vector[(album: AlbumId, chapter: ChapterId)]]                            = Ref.unsafe(Vector.empty)
    val writes: Ref[IO, Vector[(album: AlbumId, chapter: ChapterId, selection: GearSelection)]] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      read: ChapterRepository.GearReadResult = ChapterRepository.GearReadResult.Found(selection = selection),
      write: ChapterRepository.GearWriteResult = ChapterRepository.GearWriteResult.Saved,
      current: GearRepository.CatalogReadResult = GearRepository.CatalogReadResult.Found(catalog),
      maybeMutex: Option[Mutex[IO]] = None,
      beforeCatalog: IO[Unit] = IO.unit,
      beforeTransaction: IO[Unit] = IO.unit
  ) =
    given Transaction[IO, IO] with
      override def run[A](program: IO[A]): IO[A]     = beforeTransaction >> program
      override def resolve[A](program: IO[A]): IO[A] = unexpected
    val chapters = new ChapterRepository[IO]:
      override def readGear(owner: AlbumId, id: ChapterId): IO[ChapterRepository.GearReadResult] =
        refs.reads.update(_.appended((album = owner, chapter = id))).as(read)
      override def replaceGear(owner: AlbumId, id: ChapterId, values: GearSelection): IO[ChapterRepository.GearWriteResult] =
        refs.writes.update(_.appended((album = owner, chapter = id, selection = values))) >> refs.events.update(_ :+ "replace").as(write)
      override def readSelections: IO[ChapterRepository.SelectionsReadResult]                                                = unexpected
      override def saveDescription(owner: AlbumId, id: ChapterId, text: String): IO[ChapterRepository.SaveDescriptionResult] = unexpected
      override def findSource(id: ChapterId): IO[ChapterRepository.SourceResult]                                             = unexpected
      override def create(owner: AlbumId, value: ChapterRepository.NewChapter, order: NonEmptyList[ChapterId]): IO[ChapterRepository.CreateResult] =
        unexpected
      override def update(command: ChapterRepository.Update): IO[ChapterRepository.UpdateResult] = unexpected
    val gear = new GearRepository[IO]:
      override def readCatalog: IO[GearRepository.CatalogReadResult] = refs.events.update(_ :+ "catalog") >> beforeCatalog.as(current)
      override def createCamera(value: Camera): IO[GearRepository.CameraCreateResult] = unexpected
      override def updateCamera(value: Camera): IO[GearRepository.CameraUpdateResult] = unexpected
      override def deleteCamera(id: CameraId): IO[GearRepository.CameraDeleteResult]  = unexpected
      override def createLens(value: Lens): IO[GearRepository.LensCreateResult]       = unexpected
      override def updateLens(value: Lens): IO[GearRepository.LensUpdateResult]       = unexpected
      override def deleteLens(id: LensId): IO[GearRepository.LensDeleteResult]        = unexpected
    maybeMutex.fold(Mutex[IO])(_.pure[IO]).map { mutex =>
      given Mutex[IO] = mutex
      ChapterManager.make(chapters, gear)
    }
  // scalafix:on DisableSyntax.defaultArgs

  private def unexpected[A]: IO[A] = new AssertionError("Unexpected operation").raiseError[IO, A]
