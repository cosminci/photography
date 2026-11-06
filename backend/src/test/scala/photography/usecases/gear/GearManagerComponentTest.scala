package photography.usecases.gear

import cats.data.{NonEmptyList, NonEmptyVector}
import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.capabilities.{IdGenerator, Transaction}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.domain.album.*
import photography.domain.gear.*
import photography.ports.chapter.ChapterRepository
import photography.ports.gear.GearRepository
import photography.usecases.gear.GearManager.*
import java.util.UUID
import scala.concurrent.duration.*

final class GearManagerComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val camera       = Camera(
    CameraId(new UUID(0L, 1L)),
    code = "camera",
    friendlyName = "Camera",
    CameraKind.InterchangeableLens(mount = LensMount.SonyE, sensor = SensorFormat.ApsC)
  )
  private val second   = camera.copy(id = CameraId(new UUID(0L, 2L)), code = "second")
  private val fixed    = camera.copy(id = CameraId(new UUID(0L, 3L)), code = "fixed", kind = CameraKind.FixedLens)
  private val lens     = Lens(LensId(new UUID(0L, 4L)), code = "lens", friendlyName = "Lens", mount = LensMount.SonyE, coverage = SensorFormat.ApsC)
  private val chapter  = ChapterId(new UUID(0L, 5L))
  private val selected = CameraSelection(camera.id, CameraUse.Interchangeable(lenses = NonEmptyVector.one(lens.id)))
  private val unrelatedLens = lens.copy(id = LensId(new UUID(0L, 7L)), code = "unrelated", mount = LensMount.NikonZ)
  private val catalog       = GearCatalog(cameras = Vector(camera, second, fixed), lenses = Vector(lens, unrelatedLens))
  private val saved         = Vector(ChapterGearSelection(chapter, selection = Vector(selected)))

  test("should protect real saved camera selections rather than all possible catalog combinations"):
    val fullFrame    = camera.copy(kind = CameraKind.InterchangeableLens(mount = LensMount.SonyE, sensor = SensorFormat.FullFrame))
    val nikon        = camera.copy(kind = CameraKind.InterchangeableLens(mount = LensMount.NikonZ, sensor = SensorFormat.ApsC))
    val fixedUse     = Vector(ChapterGearSelection(chapter, selection = Vector(CameraSelection(camera.id, CameraUse.FixedLens))))
    val mixedCatalog = catalog.copy(lenses = Vector(lens, unrelatedLens.copy(mount = LensMount.SonyE, coverage = SensorFormat.FullFrame)))
    val mixedUse     = selected.copy(use = CameraUse.Interchangeable(lenses = NonEmptyVector.of(unrelatedLens.id, lens.id)))
    val mixedSaved   = Vector(ChapterGearSelection(chapter, selection = Vector(mixedUse)))
    val cases        = Vector(
      (camera, catalog, saved, true),
      (fullFrame, catalog, saved, false),
      (fullFrame, mixedCatalog, mixedSaved, false),
      (fullFrame, catalog.copy(lenses = Vector(lens.copy(coverage = SensorFormat.FullFrame))), saved, true),
      (nikon, catalog, saved, false),
      (camera.copy(kind = CameraKind.FixedLens), catalog, saved, false),
      (camera.copy(kind = CameraKind.FixedLens), catalog, fixedUse, true),
      (camera, catalog, fixedUse, false),
      (fullFrame, catalog, Vector.empty[ChapterGearSelection], true),
      (camera, catalog, saved :+ ChapterGearSelection(ChapterId(new UUID(0L, 6L)), selection = Vector(selected)), true)
    )

    cases.traverse_ { case (candidate, current, selections, allowed) =>
      val refs     = Refs()
      val expected = if allowed then CameraUpdateResult.Updated else CameraUpdateResult.InvalidatesSelection

      for
        manager <- buildManager(
          refs,
          catalogRead = GearRepository.CatalogReadResult.Found(current),
          selections = ChapterRepository.SelectionsReadResult.Found(selections)
        )
        result <- manager.updateCamera(candidate)
        writes <- refs.cameras.get
      yield
        assertEquals(result, expected)
        assertEquals(writes, if allowed then Vector(candidate) else Vector.empty[Camera])
    }

  test("should protect a lens shared across saved bodies but allow edits incompatible with unused bodies"):
    val fullFrame = second.copy(kind = CameraKind.InterchangeableLens(mount = LensMount.SonyE, sensor = SensorFormat.FullFrame))
    val current   = catalog.copy(cameras = Vector(camera, fullFrame, fixed), lenses = Vector(lens.copy(coverage = SensorFormat.FullFrame)))
    val shared    = Vector(ChapterGearSelection(chapter, selection = Vector(selected, selected.copy(camera = second.id))))
    val cases     = Vector(
      (lens.copy(coverage = SensorFormat.FullFrame), shared, true),
      (lens, shared, false),
      (lens.copy(mount = LensMount.NikonZ), shared, false),
      (lens, saved, true),
      (lens, saved :+ ChapterGearSelection(ChapterId(new UUID(0L, 8L)), selection = Vector(CameraSelection(fixed.id, CameraUse.FixedLens))), true),
      (lens.copy(mount = LensMount.NikonZ), Vector.empty[ChapterGearSelection], true)
    )

    cases.traverse_ { case (candidate, selections, allowed) =>
      val refs     = Refs()
      val expected = if allowed then LensUpdateResult.Updated else LensUpdateResult.InvalidatesSelection

      for
        manager <- buildManager(
          refs,
          catalogRead = GearRepository.CatalogReadResult.Found(current),
          selections = ChapterRepository.SelectionsReadResult.Found(selections)
        )
        result <- manager.updateLens(candidate)
        writes <- refs.lenses.get
      yield
        assertEquals(result, expected)
        assertEquals(writes, if allowed then Vector(candidate) else Vector.empty[Lens])
    }

  test("should distinguish missing catalog targets, failed catalogs and failed saved-selection reads without writes"):
    val cases = Vector(
      (
        GearRepository.CatalogReadResult.Found(GearCatalog(cameras = Vector.empty[Camera], lenses = Vector.empty[Lens])),
        ChapterRepository.SelectionsReadResult.Found(saved),
        CameraUpdateResult.NotFound,
        LensUpdateResult.NotFound
      ),
      (
        GearRepository.CatalogReadResult.Failed,
        ChapterRepository.SelectionsReadResult.Found(saved),
        CameraUpdateResult.Failed,
        LensUpdateResult.Failed
      ),
      (
        GearRepository.CatalogReadResult.Found(catalog),
        ChapterRepository.SelectionsReadResult.Failed,
        CameraUpdateResult.Failed,
        LensUpdateResult.Failed
      )
    )

    cases.traverse_ { case (current, selections, expectedCamera, expectedLens) =>
      val refs = Refs()

      for
        manager      <- buildManager(refs, catalogRead = current, selections = selections)
        cameraResult <- manager.updateCamera(camera)
        lensResult   <- manager.updateLens(lens)
        cameras      <- refs.cameras.get
        lenses       <- refs.lenses.get
      yield
        assertEquals(cameraResult, expectedCamera)
        assertEquals(lensResult, expectedLens)
        assertEquals(cameras, Vector.empty[Camera])
        assertEquals(lenses, Vector.empty[Lens])
    }

  test("should create cameras and lenses with generated identity and preserve every persistence outcome"):
    val cameras = Vector(
      GearRepository.CameraCreateResult.Created   -> CameraCreateResult.Created(camera),
      GearRepository.CameraCreateResult.Conflict  -> CameraCreateResult.Conflict,
      GearRepository.CameraCreateResult.Failed    -> CameraCreateResult.Failed,
      GearRepository.CameraCreateResult.Uncertain -> CameraCreateResult.Uncertain
    )
    val lenses = Vector(
      GearRepository.LensCreateResult.Created   -> LensCreateResult.Created(lens),
      GearRepository.LensCreateResult.Conflict  -> LensCreateResult.Conflict,
      GearRepository.LensCreateResult.Failed    -> LensCreateResult.Failed,
      GearRepository.LensCreateResult.Uncertain -> LensCreateResult.Uncertain
    )

    cameras.traverse_ { case (response, expected) =>
      val refs = Refs()
      for
        manager <- buildManager(refs, cameraCreate = response)
        result  <- manager.createCamera(camera.code, camera.friendlyName, camera.kind)
        writes  <- refs.cameras.get
      yield
        assertEquals(result, expected)
        assertEquals(writes, Vector(camera))
    } >> lenses.traverse_ { case (response, expected) =>
      val refs = Refs()
      for
        manager <- buildManager(refs, generatedId = lens.id.value, lensCreate = response)
        result  <- manager.createLens(lens.code, lens.friendlyName, lens.mount, lens.coverage)
        writes  <- refs.lenses.get
      yield
        assertEquals(result, expected)
        assertEquals(writes, Vector(lens))
    }

  test("should expose all camera and lens update outcomes without repeating writes"):
    val cameras = Vector(
      GearRepository.CameraUpdateResult.Updated   -> CameraUpdateResult.Updated,
      GearRepository.CameraUpdateResult.NotFound  -> CameraUpdateResult.NotFound,
      GearRepository.CameraUpdateResult.Conflict  -> CameraUpdateResult.Conflict,
      GearRepository.CameraUpdateResult.Failed    -> CameraUpdateResult.Failed,
      GearRepository.CameraUpdateResult.Uncertain -> CameraUpdateResult.Uncertain
    )
    val lenses = Vector(
      GearRepository.LensUpdateResult.Updated   -> LensUpdateResult.Updated,
      GearRepository.LensUpdateResult.NotFound  -> LensUpdateResult.NotFound,
      GearRepository.LensUpdateResult.Conflict  -> LensUpdateResult.Conflict,
      GearRepository.LensUpdateResult.Failed    -> LensUpdateResult.Failed,
      GearRepository.LensUpdateResult.Uncertain -> LensUpdateResult.Uncertain
    )

    cameras.traverse_ { case (response, expected) =>
      val refs = Refs()
      for
        manager <- buildManager(refs, cameraUpdate = response)
        result  <- manager.updateCamera(camera)
        writes  <- refs.cameras.get
      yield
        assertEquals(result, expected)
        assertEquals(writes, Vector(camera))
    } >> lenses.traverse_ { case (response, expected) =>
      val refs = Refs()
      for
        manager <- buildManager(refs, lensUpdate = response)
        result  <- manager.updateLens(lens)
        writes  <- refs.lenses.get
      yield
        assertEquals(result, expected)
        assertEquals(writes, Vector(lens))
    }

  test("should preserve independent deletion outcomes including actual database references"):
    val cameras = Vector(
      GearRepository.CameraDeleteResult.Deleted   -> CameraDeleteResult.Deleted,
      GearRepository.CameraDeleteResult.NotFound  -> CameraDeleteResult.NotFound,
      GearRepository.CameraDeleteResult.Used      -> CameraDeleteResult.Used,
      GearRepository.CameraDeleteResult.Failed    -> CameraDeleteResult.Failed,
      GearRepository.CameraDeleteResult.Uncertain -> CameraDeleteResult.Uncertain
    )
    val lenses = Vector(
      GearRepository.LensDeleteResult.Deleted   -> LensDeleteResult.Deleted,
      GearRepository.LensDeleteResult.NotFound  -> LensDeleteResult.NotFound,
      GearRepository.LensDeleteResult.Used      -> LensDeleteResult.Used,
      GearRepository.LensDeleteResult.Failed    -> LensDeleteResult.Failed,
      GearRepository.LensDeleteResult.Uncertain -> LensDeleteResult.Uncertain
    )

    cameras.traverse_ { case (response, expected) =>
      val refs = Refs()
      for
        manager <- buildManager(refs, cameraDelete = response)
        result  <- manager.deleteCamera(camera.id)
        writes  <- refs.deletedCameras.get
      yield
        assertEquals(result, expected)
        assertEquals(writes, Vector(camera.id))
    } >> lenses.traverse_ { case (response, expected) =>
      val refs = Refs()
      for
        manager <- buildManager(refs, lensDelete = response)
        result  <- manager.deleteLens(lens.id)
        writes  <- refs.deletedLenses.get
      yield
        assertEquals(result, expected)
        assertEquals(writes, Vector(lens.id))
    }

  test("should return current catalog and explicit read failure"):
    val cases = Vector(
      GearRepository.CatalogReadResult.Found(catalog) -> CatalogResult.Found(catalog),
      GearRepository.CatalogReadResult.Failed         -> CatalogResult.Failed
    )

    cases.traverse_ { case (response, expected) =>
      buildManager(Refs(), catalogRead = response).flatMap(_.readCatalog).map(assertEquals(_, expected))
    }

  test("should keep waiting mutations cancelable and finish an acquired catalog decision before cancellation"):
    val mutations = Vector[GearManager[IO] => IO[Unit]](
      _.updateCamera(camera).void,
      _.deleteCamera(camera.id).void,
      _.updateLens(lens).void,
      _.deleteLens(lens.id).void
    )

    mutations.traverse_ { mutate =>
      TestControl.executeEmbed:
        val refs = Refs()
        for
          mutex         <- Mutex[IO]
          reading       <- Deferred[IO, Unit]
          release       <- Deferred[IO, Unit]
          first         <- buildManager(refs, maybeMutex = mutex.some, beforeCatalog = reading.complete(()).void >> release.get)
          second        <- buildManager(refs, maybeMutex = mutex.some)
          pending       <- first.updateCamera(camera).start
          _             <- reading.get
          waiting       <- mutate(second).start
          _             <- IO.sleep(1.millis)
          _             <- waiting.cancel
          cancelling    <- pending.cancel.start
          before        <- refs.cameras.get
          _             <- release.complete(())
          _             <- pending.join
          _             <- cancelling.joinWithNever
          after         <- refs.cameras.get
          cameraDeletes <- refs.deletedCameras.get
          lenses        <- refs.lenses.get
          lensDeletes   <- refs.deletedLenses.get
        yield
          assertEquals(before, Vector.empty[Camera])
          assertEquals(after, Vector(camera))
          assertEquals(cameraDeletes, Vector.empty[CameraId])
          assertEquals(lenses, Vector.empty[Lens])
          assertEquals(lensDeletes, Vector.empty[LensId])
    }

  test("should fail protected catalog edits before writes when saved selection transaction acquisition fails"):
    val refs    = Refs()
    val failure = new IllegalStateException("Selection acquisition failed").raiseError[IO, Unit]

    for
      manager      <- buildManager(refs, beforeTransaction = failure)
      cameraResult <- manager.updateCamera(camera)
      lensResult   <- manager.updateLens(lens)
      cameras      <- refs.cameras.get
      lenses       <- refs.lenses.get
    yield
      assertEquals(cameraResult, CameraUpdateResult.Failed)
      assertEquals(lensResult, LensUpdateResult.Failed)
      assertEquals(cameras, Vector.empty[Camera])
      assertEquals(lenses, Vector.empty[Lens])

  final private case class Refs():
    val cameras: Ref[IO, Vector[Camera]]          = Ref.unsafe(Vector.empty)
    val lenses: Ref[IO, Vector[Lens]]             = Ref.unsafe(Vector.empty)
    val deletedCameras: Ref[IO, Vector[CameraId]] = Ref.unsafe(Vector.empty)
    val deletedLenses: Ref[IO, Vector[LensId]]    = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      generatedId: UUID = camera.id.value,
      catalogRead: GearRepository.CatalogReadResult = GearRepository.CatalogReadResult.Found(catalog),
      selections: ChapterRepository.SelectionsReadResult = ChapterRepository.SelectionsReadResult.Found(saved),
      cameraCreate: GearRepository.CameraCreateResult = GearRepository.CameraCreateResult.Created,
      cameraUpdate: GearRepository.CameraUpdateResult = GearRepository.CameraUpdateResult.Updated,
      cameraDelete: GearRepository.CameraDeleteResult = GearRepository.CameraDeleteResult.Deleted,
      lensCreate: GearRepository.LensCreateResult = GearRepository.LensCreateResult.Created,
      lensUpdate: GearRepository.LensUpdateResult = GearRepository.LensUpdateResult.Updated,
      lensDelete: GearRepository.LensDeleteResult = GearRepository.LensDeleteResult.Deleted,
      maybeMutex: Option[Mutex[IO]] = None,
      beforeCatalog: IO[Unit] = IO.unit,
      beforeTransaction: IO[Unit] = IO.unit
  ) =
    given Transaction[IO, IO] with
      override def run[A](program: IO[A]): IO[A]     = beforeTransaction >> program
      override def resolve[A](program: IO[A]): IO[A] = unexpected
    given IdGenerator[IO] with
      override def generateId: IO[UUID] = generatedId.pure[IO]
    val repository = new GearRepository[IO]:
      override def createCamera(value: Camera): IO[GearRepository.CameraCreateResult] = refs.cameras.update(_ :+ value).as(cameraCreate)
      override def updateCamera(value: Camera): IO[GearRepository.CameraUpdateResult] = refs.cameras.update(_ :+ value).as(cameraUpdate)
      override def deleteCamera(id: CameraId): IO[GearRepository.CameraDeleteResult]  = refs.deletedCameras.update(_ :+ id).as(cameraDelete)
      override def createLens(value: Lens): IO[GearRepository.LensCreateResult]       = refs.lenses.update(_ :+ value).as(lensCreate)
      override def updateLens(value: Lens): IO[GearRepository.LensUpdateResult]       = refs.lenses.update(_ :+ value).as(lensUpdate)
      override def deleteLens(id: LensId): IO[GearRepository.LensDeleteResult]        = refs.deletedLenses.update(_ :+ id).as(lensDelete)
      override def readCatalog: IO[GearRepository.CatalogReadResult]                  = beforeCatalog.as(catalogRead)
    val chapters = new ChapterRepository[IO]:
      override def readSelections: IO[ChapterRepository.SelectionsReadResult]                                                  = selections.pure[IO]
      override def readGear(album: AlbumId, id: ChapterId): IO[ChapterRepository.GearReadResult]                               = unexpected
      override def replaceGear(album: AlbumId, id: ChapterId, selection: GearSelection): IO[ChapterRepository.GearWriteResult] = unexpected
      override def saveDescription(album: AlbumId, id: ChapterId, text: String): IO[ChapterRepository.SaveDescriptionResult]   = unexpected
      override def findSource(id: ChapterId): IO[ChapterRepository.SourceResult]                                               = unexpected
      override def create(album: AlbumId, value: ChapterRepository.NewChapter, order: NonEmptyList[ChapterId]): IO[ChapterRepository.CreateResult] =
        unexpected
      override def update(command: ChapterRepository.Update): IO[ChapterRepository.UpdateResult] = unexpected
    maybeMutex.fold(Mutex[IO])(_.pure[IO]).map { mutex =>
      given Mutex[IO] = mutex
      GearManager.make(repository, chapters)
    }
  // scalafix:on DisableSyntax.defaultArgs

  private def unexpected[A]: IO[A] = new AssertionError("Unexpected chapter operation").raiseError[IO, A]
