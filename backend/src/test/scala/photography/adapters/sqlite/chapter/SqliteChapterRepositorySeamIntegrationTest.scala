package photography.adapters.sqlite.chapter
import photography.domain.gear.*
import photography.adapters.sqlite.gear.SqliteGearRepository

import photography.adapters.sqlite.album.SqliteAlbumCompositionRepository

import photography.adapters.sqlite.FlywayMigrator
import photography.adapters.sqlite.SqliteConnection
import photography.adapters.sqlite.album.SqliteAlbumRepository
import photography.adapters.sqlite.photo.SqlitePhotoRepository
import photography.adapters.sqlite.spread.SqliteSpreadTemplateRepository

import cats.data.{NonEmptyList, NonEmptyVector}
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.sqlite.SqliteCodecs.given
import photography.app.config.SqliteConfig
import photography.adapters.filesystem.FileThumbnailStorage
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.album.AlbumRepository
import photography.ports.gear.GearRepository
import photography.ports.chapter.ChapterRepository
import photography.ports.photo.PhotoRepository
import photography.ports.spread.SpreadTemplateRepository
import photography.syntax.SyncSyntax.*
import photography.usecases.chapter.{ChapterManager, ChapterMetadataManager, ChapterPlanning}
import photography.usecases.gear.GearManager
import photography.usecases.photo.{ChapterPhotoImport, PhotoWriteRecovery}
import photography.usecases.photo.ChapterPhotoImport.{ExecutionOutcome, Request, Scheduling, Status}

import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate}
import java.util.UUID

final class SqliteChapterRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO]           = Slf4jLogger.getLogger[IO]
  private given Logger[ConnectionIO] = Slf4jLogger.getLogger[ConnectionIO]
  private val destination            = DestinationId(new UUID(99L, 1L))
  private val albumId                = AlbumId(new UUID(1L, 1L))
  private val period                 = VisitPeriod(firstDay = LocalDate.of(2026, 6, 1), days = 3L)
  private val country      = Country(CountryId(new UUID(9L, 1L)), code = "XX", name = "Test country", AbsoluteLocation(Path.of("/flags/xx.svg")))
  private val otherCountry = country.copy(id = CountryId(new UUID(9L, 2L)), code = "XY", name = "Other country")
  private val countRows    = sql"""SELECT (SELECT count(*) FROM chapter) + (SELECT count(*) FROM destination) +
    (SELECT count(*) FROM destination_country) + (SELECT count(*) FROM spread) +
    (SELECT count(*) FROM spread_photo_slot) + (SELECT count(*) FROM spread_text_slot)""".query[Long].unique

  private val body =
    Camera(CameraId(new UUID(12L, 1L)), code = "body", friendlyName = "Body", CameraKind.InterchangeableLens(LensMount.SonyE, SensorFormat.ApsC))
  private val fixedBody    = body.copy(id = CameraId(new UUID(12L, 2L)), code = "fixed", kind = CameraKind.FixedLens)
  private val selectedLens = Lens(LensId(new UUID(12L, 3L)), code = "lens", friendlyName = "Lens", LensMount.SonyE, SensorFormat.FullFrame)
  private val otherLens    = selectedLens.copy(id = LensId(new UUID(12L, 4L)), code = "other-lens")
  private val orderedGear  = Vector(
    CameraSelection(fixedBody.id, CameraUse.FixedLens),
    CameraSelection(body.id, CameraUse.Interchangeable(lenses = NonEmptyVector.of(otherLens.id, selectedLens.id)))
  )

  test("should acquire and completely replace ordered chapter selections including empty gear and retain aggregate state"):
    buildTransactor.use: xa =>
      val chapters = SqliteChapterRepository.make
      val albums   = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, chapters)
      val catalog  = SqliteGearRepository.make[IO](xa)

      for
        _        <- seedAlbum(xa)
        first    <- buildChapter(xa, index = 1)
        second   <- buildChapter(xa, index = 2)
        _        <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
        _        <- chapters.create(albumId, second, order = NonEmptyList.of(first.id, second.id)).transact(xa)
        _        <- Vector(body, fixedBody).traverse_(catalog.createCamera)
        _        <- Vector(selectedLens, otherLens).traverse_(catalog.createLens)
        before   <- albums.get(albumId)
        saved    <- chapters.replaceGear(albumId, first.id, selection = orderedGear).transact(xa)
        read     <- chapters.readGear(albumId, first.id).transact(xa)
        all      <- chapters.readSelections.transact(xa)
        after    <- albums.get(albumId)
        cleared  <- chapters.replaceGear(albumId, first.id, selection = Vector.empty[CameraSelection]).transact(xa)
        empty    <- chapters.readGear(albumId, first.id).transact(xa)
        restored <- albums.get(albumId)
      yield
        val expectedSelections =
          Vector(ChapterGearSelection(first.id, selection = orderedGear), ChapterGearSelection(second.id, selection = Vector.empty[CameraSelection]))
        val expectedAlbum = before match
          case AlbumRepository.GetResult.Found(album) =>
            AlbumRepository.GetResult.Found(album.copy(chapters =
              album.chapters.map(value => if value.id.value === first.id.value then value.copy(gear = orderedGear) else value)
            ))
          case result => fail(s"Expected complete album: $result")

        assertEquals(saved, ChapterRepository.GearWriteResult.Saved)
        assertEquals(read, ChapterRepository.GearReadResult.Found(selection = orderedGear))
        assertEquals(all, ChapterRepository.SelectionsReadResult.Found(selections = expectedSelections))
        assertEquals(after, expectedAlbum)
        assertEquals(cleared, ChapterRepository.GearWriteResult.Saved)
        assertEquals(empty, ChapterRepository.GearReadResult.Found(selection = Vector.empty[CameraSelection]))
        assertEquals(restored, before)

  test("should reject missing scoped targets and invalid references or cardinality without changing saved gear"):
    buildTransactor.use: xa =>
      val chapters         = SqliteChapterRepository.make
      val catalog          = SqliteGearRepository.make[IO](xa)
      val missingAlbum     = AlbumId(new UUID(99L, 99L))
      val missingChapter   = ChapterId(new UUID(99L, 99L))
      val insertOtherAlbum = sql"""INSERT INTO album (id, year, title, synopsis, created_at_ms)
        VALUES (${missingAlbum.value}, 2099, 'Other', '', 0)""".update.run
      val invalid = Vector(
        orderedGear ++ orderedGear,
        Vector(CameraSelection(CameraId(new UUID(99L, 99L)), CameraUse.FixedLens)),
        Vector(CameraSelection(body.id, CameraUse.Interchangeable(lenses = NonEmptyVector.one(LensId(new UUID(99L, 99L)))))),
        Vector(CameraSelection(body.id, CameraUse.Interchangeable(lenses = NonEmptyVector.of(selectedLens.id, selectedLens.id))))
      )

      for
        _          <- seedAlbum(xa)
        _          <- insertOtherAlbum.transact(xa)
        first      <- buildChapter(xa, index = 1)
        _          <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
        _          <- Vector(body, fixedBody).traverse_(catalog.createCamera)
        _          <- Vector(selectedLens, otherLens).traverse_(catalog.createLens)
        _          <- chapters.replaceGear(albumId, first.id, selection = orderedGear).transact(xa)
        wrongOwner <- chapters.replaceGear(missingAlbum, first.id, selection = orderedGear).transact(xa)
        wrongRead  <- chapters.readGear(missingAlbum, first.id).transact(xa)
        missing    <- chapters.readGear(albumId, missingChapter).transact(xa)
        rejected   <- invalid.traverse(selection => chapters.replaceGear(albumId, first.id, selection = selection).transact(xa))
        saved      <- chapters.readGear(albumId, first.id).transact(xa)
      yield
        assertEquals(wrongOwner, ChapterRepository.GearWriteResult.NotFound)
        assertEquals(wrongRead, ChapterRepository.GearReadResult.NotFound)
        assertEquals(missing, ChapterRepository.GearReadResult.NotFound)
        assertEquals(rejected, Vector.fill(invalid.size)(ChapterRepository.GearWriteResult.Failed))
        assertEquals(saved, ChapterRepository.GearReadResult.Found(selection = orderedGear))

  test("should roll back complete replacements after failed or ignored writes and caller transaction failures"):
    Vector("ABORT", "FAIL", "IGNORE").traverse_ { rejection =>
      buildTransactor.use: xa =>
        val chapters = SqliteChapterRepository.make
        val catalog  = SqliteGearRepository.make[IO](xa)
        val trigger  = s"""CREATE TRIGGER reject_gear BEFORE INSERT ON chapter_camera_lens BEGIN
          UPDATE chapter SET description = 'Partial'; SELECT RAISE($rejection${if rejection === "IGNORE" then "" else ", 'Rejected'"}); END"""

        (seedAlbum(xa) >> buildChapter(xa, index = 1)).flatMap: first =>
          val replacement    = chapters.replaceGear(albumId, first.id, selection = orderedGear)
          val abortCaller    = replacement >> new IllegalStateException("Abort caller").raiseError[ConnectionIO, Unit]
          val cancelCaller   = replacement >> MonadCancelThrow[ConnectionIO].canceled
          val fixedSelection = Vector(CameraSelection(fixedBody.id, CameraUse.FixedLens))

          for
            _               <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
            _               <- Vector(body, fixedBody).traverse_(catalog.createCamera)
            _               <- Vector(selectedLens, otherLens).traverse_(catalog.createLens)
            _               <- chapters.replaceGear(albumId, first.id, selection = fixedSelection).transact(xa)
            before          <- chapters.readSelections.transact(xa)
            _               <- Fragment.const(trigger).update.run.transact(xa)
            failed          <- replacement.transact(xa)
            after           <- chapters.readSelections.transact(xa)
            description     <- sql"SELECT description FROM chapter".query[String].unique.transact(xa)
            _               <- sql"DROP TRIGGER reject_gear".update.run.transact(xa)
            aborted         <- abortCaller.transact(xa).attempt
            canceled        <- cancelCaller.transact(xa).start
            cancellation    <- canceled.join
            finalSelections <- chapters.readSelections.transact(xa)
          yield
            assertEquals(failed, ChapterRepository.GearWriteResult.Failed)
            assertEquals(after, before)
            assertEquals(description, first.description)
            assert(aborted.isLeft)
            assert(cancellation.isCanceled)
            assertEquals(finalSelections, before)
    }

  test("should reject malformed saved use cardinality and missing references without empty or partial successful reads"):
    Vector(
      "DELETE FROM chapter_camera_lens",
      "UPDATE chapter_camera SET use_kind = 'fixed_lens'",
      "UPDATE chapter_camera SET use_kind = 'unknown'",
      "UPDATE chapter_camera SET camera_id = '00000000-0000-0000-0000-000000000099'",
      "UPDATE chapter_camera_lens SET lens_id = '00000000-0000-0000-0000-000000000099'"
    ).traverse_ { corrupt =>
      buildTransactor.use: xa =>
        val chapters       = SqliteChapterRepository.make
        val catalog        = SqliteGearRepository.make[IO](xa)
        val savedSelection = Vector(CameraSelection(body.id, CameraUse.Interchangeable(lenses = NonEmptyVector.one(selectedLens.id))))
        val corruption     = sql"PRAGMA ignore_check_constraints = ON".update.run >> Fragment.const(corrupt).update.run
        val unconstrained  = Transactor.before.set(xa, sql"PRAGMA foreign_keys = OFF".update.run >> xa.strategy.before)

        for
          _     <- seedAlbum(xa)
          first <- buildChapter(xa, index = 1)
          _     <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
          _     <- catalog.createCamera(body)
          _     <- catalog.createLens(selectedLens)
          _     <- chapters.replaceGear(albumId, first.id, selection = savedSelection).transact(xa)
          _     <- corruption.transact(unconstrained)
          read  <- chapters.readGear(albumId, first.id).transact(xa)
          all   <- chapters.readSelections.transact(xa)
        yield
          assertEquals(read, ChapterRepository.GearReadResult.Failed)
          assertEquals(all, ChapterRepository.SelectionsReadResult.Failed)
    }

  test("should protect catalog edits against actual shared saved selections through the same coordination capability"):
    buildTransactor.use: xa =>
      val chapters   = SqliteChapterRepository.make
      val catalog    = SqliteGearRepository.make[IO](xa)
      val secondBody = body.copy(id = CameraId(new UUID(12L, 5L)), code = "second-body")
      val unusedBody = body.copy(id = CameraId(new UUID(12L, 6L)), code = "unused-body")
      val shared     =
        Vector(body, secondBody).map(camera => CameraSelection(camera.id, CameraUse.Interchangeable(lenses = NonEmptyVector.one(selectedLens.id))))
      given Transaction[ConnectionIO, IO] with
        override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
        override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
      given IdGenerator[IO] with
        override def generateId: IO[UUID] = new AssertionError("Unexpected allocation").raiseError[IO, UUID]

      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        val gear        = GearManager.make(catalog, chapters)
        val manager     = ChapterManager.make(chapters, gear)

        for
          _          <- (sql"DELETE FROM lens".update.run >> sql"DELETE FROM camera".update.run).transact(xa)
          _          <- seedAlbum(xa)
          first      <- buildChapter(xa, index = 1)
          _          <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
          _          <- Vector(body, secondBody, unusedBody).traverse_(catalog.createCamera)
          _          <- Vector(selectedLens, otherLens).traverse_(catalog.createLens)
          saved      <- manager.replaceGear(albumId, first.id, selection = shared)
          wrongMount <- gear.updateLens(selectedLens.copy(mount = LensMount.NikonZ))
          wrongKind  <- gear.updateCamera(body.copy(kind = CameraKind.FixedLens))
          unused     <- gear.updateCamera(unusedBody.copy(kind = CameraKind.FixedLens))
          unusedLens <- gear.updateLens(otherLens.copy(mount = LensMount.NikonZ))
          narrowed   <- gear.updateLens(selectedLens.copy(coverage = SensorFormat.ApsC))
          fullFrame  <- gear.updateCamera(secondBody.copy(kind = CameraKind.InterchangeableLens(LensMount.SonyE, SensorFormat.FullFrame)))
          read       <- manager.readGear(albumId, first.id)
          used       <- gear.deleteLens(selectedLens.id)
          actual     <- catalog.readCatalog
        yield
          val expectedCatalog = GearCatalog(
            cameras = Vector(body, secondBody, unusedBody.copy(kind = CameraKind.FixedLens)),
            lenses = Vector(selectedLens.copy(coverage = SensorFormat.ApsC), otherLens.copy(mount = LensMount.NikonZ))
          )

          assertEquals(saved, ChapterManager.GearReplaceResult.Saved)
          assertEquals(wrongMount, GearManager.LensUpdateResult.InvalidatesSelection)
          assertEquals(wrongKind, GearManager.CameraUpdateResult.InvalidatesSelection)
          assertEquals(unused, GearManager.CameraUpdateResult.Updated)
          assertEquals(unusedLens, GearManager.LensUpdateResult.Updated)
          assertEquals(narrowed, GearManager.LensUpdateResult.Updated)
          assertEquals(fullFrame, GearManager.CameraUpdateResult.InvalidatesSelection)
          assertEquals(read, ChapterManager.GearReadResult.Found(selection = shared))
          assertEquals(used, GearManager.LensDeleteResult.Used)
          assertEquals(actual, GearRepository.CatalogReadResult.Found(expectedCatalog))

  test("should serialize concurrent catalog decisions with selection writes and read only complete committed selections"):
    buildTransactor.use: xa =>
      val chapters       = SqliteChapterRepository.make
      val catalog        = SqliteGearRepository.make[IO](xa)
      val savedSelection = Vector(CameraSelection(body.id, CameraUse.Interchangeable(lenses = NonEmptyVector.one(selectedLens.id))))
      given Transaction[ConnectionIO, IO] with
        override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
        override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
      given IdGenerator[IO] with
        override def generateId: IO[UUID] = new AssertionError("Unexpected allocation").raiseError[IO, UUID]

      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        val gear        = GearManager.make(catalog, chapters)
        val manager     = ChapterManager.make(chapters, gear)

        (seedAlbum(xa) >> buildChapter(xa, index = 1)).flatMap: first =>
          val decideAndWrite = (
            manager.replaceGear(albumId, first.id, selection = savedSelection),
            gear.updateLens(selectedLens.copy(mount = LensMount.NikonZ))
          ).parTupled
          val replaceAndRead = (
            chapters.replaceGear(albumId, first.id, selection = orderedGear.take(1)).transact(xa),
            Vector.fill(20)(()).traverse(_ => manager.readGear(albumId, first.id))
          ).parTupled

          for
            _        <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
            _        <- catalog.createCamera(body)
            _        <- catalog.createCamera(fixedBody)
            _        <- catalog.createLens(selectedLens)
            outcomes <- decideAndWrite
            read     <- manager.readGear(albumId, first.id)
            changes  <- replaceAndRead
          yield
            val validOutcomes = Vector(
              (ChapterManager.GearReplaceResult.Saved, GearManager.LensUpdateResult.InvalidatesSelection),
              (ChapterManager.GearReplaceResult.Rejected(GearManager.Failure.IncompatibleLens), GearManager.LensUpdateResult.Updated)
            )
            val expectedRead = outcomes match
              case (ChapterManager.GearReplaceResult.Saved, _) => savedSelection
              case _                                           => Vector.empty[CameraSelection]
            val (savedChange, concurrentReads) = changes
            val completeReads                  = Vector(read, ChapterManager.GearReadResult.Found(selection = orderedGear.take(1)))

            assert(validOutcomes.contains(outcomes))
            assertEquals(read, ChapterManager.GearReadResult.Found(selection = expectedRead))
            assertEquals(savedChange, ChapterRepository.GearWriteResult.Saved)
            concurrentReads.foreach(actual => assert(completeReads.contains(actual)))

  test("should expose uncertain commit acknowledgements and retain selections after reopening without replay"):
    Vector(false, true).traverse_ { committed =>
      buildDatabase.use: path =>
        val write = buildConnection(path).use: xa =>
          val chapters = SqliteChapterRepository.make
          val catalog  = SqliteGearRepository.make[IO](xa)
          given Transaction[ConnectionIO, IO] with
            override def run[A](program: ConnectionIO[A]): IO[A] =
              val lost = new IllegalStateException("Lost acknowledgement").raiseError[IO, A]
              if committed then program.transact(xa) >> lost else lost
            override def resolve[A](program: ConnectionIO[A]): IO[A] = new AssertionError("Unexpected retry").raiseError[IO, A]
          given IdGenerator[IO] with
            override def generateId: IO[UUID] = new AssertionError("Unexpected allocation").raiseError[IO, UUID]

          Mutex[IO].flatMap: mutex =>
            given Mutex[IO] = mutex
            val gear        = GearManager.make(catalog, chapters)
            val manager     = ChapterManager.make(chapters, gear)
            val selection   = Vector(CameraSelection(fixedBody.id, CameraUse.FixedLens))

            for
              _      <- seedAlbum(xa)
              first  <- buildChapter(xa, index = 1)
              _      <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
              _      <- catalog.createCamera(fixedBody)
              result <- manager.replaceGear(albumId, first.id, selection = selection)
            yield
              assertEquals(result, ChapterManager.GearReplaceResult.Uncertain)
              (first.id, if committed then selection else Vector.empty[CameraSelection])

        write.flatMap { case (chapter, expected) =>
          buildConnection(path).use: xa =>
            SqliteChapterRepository.make.readGear(albumId, chapter).transact(xa)
              .map(actual => assertEquals(actual, ChapterRepository.GearReadResult.Found(selection = expected)))
        }
    }

  test("should keep coordination acquisition cancelable and finish an acquired real SQLite replacement before releasing its lock"):
    buildTransactor.use: xa =>
      val chapters  = SqliteChapterRepository.make
      val catalog   = SqliteGearRepository.make[IO](xa)
      val selection = Vector(CameraSelection(fixedBody.id, CameraUse.FixedLens))
      given IdGenerator[IO] with
        override def generateId: IO[UUID] = new AssertionError("Unexpected allocation").raiseError[IO, UUID]

      val coordination = (Mutex[IO], Deferred[IO, Unit], Deferred[IO, Unit]).tupled
      val chapter      = seedAlbum(xa) >> buildChapter(xa, index = 1)

      (chapter, coordination).tupled.flatMap { case (first, (mutex, entered, release)) =>
        given Mutex[IO] = mutex
        given Transaction[ConnectionIO, IO] with
          override def run[A](program: ConnectionIO[A]): IO[A]     = entered.complete(()).void >> release.get >> program.transact(xa)
          override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
        val gear    = GearManager.make(catalog, chapters)
        val manager = ChapterManager.make(chapters, gear)

        for
          _              <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
          _              <- catalog.createCamera(fixedBody)
          pending        <- manager.replaceGear(albumId, first.id, selection = selection).start
          _              <- entered.get
          cancellation   <- pending.cancel.start
          waiting        <- gear.updateCamera(fixedBody.copy(friendlyName = "Waiting")).start
          _              <- waiting.cancel
          waitingOutcome <- waiting.join
          before         <- chapters.readGear(albumId, first.id).transact(xa)
          _              <- release.complete(())
          _              <- pending.join
          _              <- cancellation.joinWithNever
          saved          <- chapters.readGear(albumId, first.id).transact(xa)
          subsequent     <- gear.updateCamera(fixedBody.copy(friendlyName = "Updated"))
        yield
          assert(waitingOutcome.isCanceled)
          assertEquals(before, ChapterRepository.GearReadResult.Found(selection = Vector.empty[CameraSelection]))
          assertEquals(saved, ChapterRepository.GearReadResult.Found(selection = selection))
          assertEquals(subsequent, GearManager.CameraUpdateResult.Updated)
      }

  test("should save exact scoped chapter descriptions and clear while retaining all metadata and composition"):
    buildTransactor.use: xa =>
      val chapters = SqliteChapterRepository.make
      val albums   = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, chapters)
      val camera   = new UUID(12L, 1L)
      val lens     = new UUID(12L, 2L)
      val gear     = sql"""SELECT c.chapter_id, c.camera_id, c.use_kind, l.lens_id FROM chapter_camera c
        JOIN chapter_camera_lens l ON l.chapter_id = c.chapter_id AND l.camera_id = c.camera_id"""
        .query[(UUID, UUID, String, UUID)].to[Vector]
      val insertCamera = sql"""INSERT INTO camera (id, code, friendly_name, kind, mount, sensor_format)
        VALUES ($camera, 'camera', 'Camera', 'interchangeable_lens', 'sony_e', 'aps_c')""".update.run.transact(xa)
      val insertLens = sql"""INSERT INTO lens (id, code, friendly_name, mount, coverage)
        VALUES ($lens, 'lens', 'Lens', 'sony_e', 'aps_c')""".update.run.transact(xa)

      for
        _              <- seedAlbum(xa)
        first          <- buildChapter(xa, index = 1)
        second         <- buildChapter(xa, index = 2)
        _              <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
        _              <- chapters.create(albumId, second, order = NonEmptyList.of(second.id, first.id)).transact(xa)
        _              <- insertPhotoComposition(first.id, first.opening.id).transact(xa)
        _              <- insertCamera
        _              <- insertLens
        _              <- sql"INSERT INTO chapter_camera VALUES (${first.id.value}, $camera, 'interchangeable_lenses')".update.run.transact(xa)
        _              <- sql"INSERT INTO chapter_camera_lens VALUES (${first.id.value}, $camera, $lens)".update.run.transact(xa)
        before         <- albums.get(albumId)
        saved          <- chapters.saveDescription(albumId, first.id, text = "日本\nVacanță").transact(xa)
        after          <- albums.get(albumId)
        missingAlbum   <- chapters.saveDescription(AlbumId(new UUID(0L, 99L)), first.id, text = "wrong album").transact(xa)
        missingChapter <- chapters.saveDescription(albumId, ChapterId(new UUID(0L, 99L)), text = "missing").transact(xa)
        unchanged      <- albums.get(albumId)
        cleared        <- chapters.saveDescription(albumId, first.id, text = "").transact(xa)
        empty          <- albums.get(albumId)
        retainedGear   <- gear.transact(xa)
      yield
        val expectedText = before match
          case AlbumRepository.GetResult.Found(album) =>
            val savedChapters =
              album.chapters.map(value => if value.id.value === first.id.value then value.copy(description = "日本\nVacanță") else value)
            AlbumRepository.GetResult.Found(album.copy(chapters = savedChapters))
          case other => fail(s"Expected complete album: $other")
        val expectedEmpty = before match
          case AlbumRepository.GetResult.Found(album) =>
            val savedChapters = album.chapters.map(value => if value.id.value === first.id.value then value.copy(description = "") else value)
            AlbumRepository.GetResult.Found(album.copy(chapters = savedChapters))
          case other => fail(s"Expected complete album: $other")

        assertEquals(saved, ChapterRepository.SaveDescriptionResult.Saved)
        assertEquals(after, expectedText)
        assertEquals(missingAlbum, ChapterRepository.SaveDescriptionResult.AlbumNotFound)
        assertEquals(missingChapter, ChapterRepository.SaveDescriptionResult.ChapterNotFound)
        assertEquals(unchanged, expectedText)
        assertEquals(cleared, ChapterRepository.SaveDescriptionResult.Saved)
        assertEquals(empty, expectedEmpty)
        assertEquals(retainedGear, Vector((first.id.value, camera, "interchangeable_lenses", lens)))

  test("should reject cross-album description edits and roll back failed statements and surrounding transactions"):
    buildTransactor.use: xa =>
      val chapters   = SqliteChapterRepository.make
      val albums     = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, chapters)
      val otherAlbum = AlbumId(new UUID(0L, 99L))
      val reject     = sql"""CREATE TRIGGER reject_description BEFORE UPDATE OF description ON chapter BEGIN
        UPDATE chapter SET source_folder = '/partial' WHERE id = OLD.id;
        SELECT RAISE(FAIL, 'Rejected description');
      END""".update.run
      val ignore = sql"""CREATE TRIGGER reject_description BEFORE UPDATE OF description ON chapter BEGIN
        UPDATE chapter SET source_folder = '/partial' WHERE id = OLD.id;
        SELECT RAISE(IGNORE);
      END""".update.run
      val abortTransaction = sql"""CREATE TRIGGER reject_description BEFORE UPDATE OF description ON chapter BEGIN
        SELECT RAISE(ROLLBACK, 'Description transaction aborted');
      END""".update.run
      val insertOtherAlbum =
        sql"INSERT INTO album (id, year, title, synopsis, created_at_ms) VALUES (${otherAlbum.value}, 2099, 'Other', '', 0)".update.run
      val seededChapter = seedAlbum(xa) >> buildChapter(xa, index = 1)

      seededChapter.flatMap: first =>
        val abortedDescription = chapters.saveDescription(albumId, first.id, text = "") >>
          new IllegalStateException("Abort transaction").raiseError[ConnectionIO, Unit]

        for
          _            <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
          _            <- insertOtherAlbum.transact(xa)
          before       <- albums.get(albumId)
          scoped       <- chapters.saveDescription(otherAlbum, first.id, text = "Wrong owner").transact(xa)
          _            <- reject.transact(xa)
          failed       <- chapters.saveDescription(albumId, first.id, text = "Rejected").transact(xa)
          retained     <- albums.get(albumId)
          _            <- sql"DROP TRIGGER reject_description".update.run.transact(xa)
          _            <- ignore.transact(xa)
          ignored      <- chapters.saveDescription(albumId, first.id, text = "").transact(xa)
          ignoredAlbum <- albums.get(albumId)
          _            <- sql"DROP TRIGGER reject_description".update.run.transact(xa)
          aborted      <- abortedDescription.transact(xa).attempt
          finalAlbum   <- albums.get(albumId)
          _            <- abortTransaction.transact(xa)
          uncertain    <- chapters.saveDescription(albumId, first.id, text = "").transact(xa).attempt
        yield
          assertEquals(scoped, ChapterRepository.SaveDescriptionResult.ChapterNotFound)
          failed match
            case ChapterRepository.SaveDescriptionResult.Failed(cause) => assert(cause.getMessage.contains("Rejected description"))
            case result                                                => fail(s"Expected rejected description cause, obtained $result")
          assertEquals(retained, before)
          ignored match
            case ChapterRepository.SaveDescriptionResult.Failed(cause) =>
              assertEquals(cause.getMessage, "Description update did not change the existing chapter")
            case result => fail(s"Expected ignored description cause, obtained $result")
          assertEquals(ignoredAlbum, before)
          assert(aborted.isLeft)
          assertEquals(finalAlbum, before)
          uncertain match
            case Left(cause) =>
              assert(cause.getMessage.contains("Description transaction aborted"))
              assert(cause.getSuppressed.toVector.exists(_.getMessage.contains("savepoint")))
            case result => fail(s"Expected uncertain rollback acknowledgement with both causes, obtained $result")

  test("should expose uncertain description acknowledgements without retrying or conflating the actual SQLite commit"):
    Vector(false, true).traverse_ { committed =>
      buildTransactor.use: xa =>
        val chapters  = SqliteChapterRepository.make
        val albums    = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, chapters)
        val templates = SqliteSpreadTemplateRepository.make[IO](xa)
        val imports   = new ChapterPhotoImport[IO]:
          override def prepare(parent: AlbumId, chapter: ChapterId, source: PhotoSource): IO[Request] =
            IO.raiseError[Request](new AssertionError("Unexpected import preparation"))
          override def launch(request: Request): IO[Scheduling] = IO.raiseError[Scheduling](new AssertionError("Unexpected import launch"))
          override def retire(parent: AlbumId, retirements: Vector[PhotoWriteIntent.Retirement]): IO[Unit] =
            IO.raiseError[Unit](new AssertionError("Unexpected retirement"))
          override def suspend(chapter: ChapterId): IO[Unit]            = IO.raiseError[Unit](new AssertionError("Unexpected suspension"))
          override def status(chapter: ChapterId): IO[ExecutionOutcome] =
            IO.raiseError[ExecutionOutcome](new AssertionError("Unexpected import status"))
        given IdGenerator[IO] with
          override def generateId: IO[UUID] = IO.raiseError[UUID](new AssertionError("Unexpected identity allocation"))
        given Transaction[ConnectionIO, IO] with
          override def run[A](program: ConnectionIO[A]): IO[A] =
            val lost = new IllegalStateException("Lost acknowledgement").raiseError[IO, A]
            if committed then program.transact(xa) >> lost else lost
          override def resolve[A](program: ConnectionIO[A]): IO[A] =
            IO.raiseError[A](new AssertionError("Unexpected automatic reconciliation"))

        Mutex[IO].flatMap: mutex =>
          given Mutex[IO] = mutex
          val manager     = ChapterMetadataManager.make(albums, chapters, ChapterPlanning.make(templates), imports, SqlitePhotoRepository.make)

          for
            _      <- seedAlbum(xa)
            first  <- buildChapter(xa, index = 1)
            _      <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
            before <- albums.get(albumId)
            result <- manager.saveDescription(albumId, first.id, text = "日本\nVacanță")
            after  <- albums.get(albumId)
          yield
            val expected = before match
              case AlbumRepository.GetResult.Found(album) if committed =>
                AlbumRepository.GetResult.Found(album.copy(chapters = album.chapters.map(_.copy(description = "日本\nVacanță"))))
              case original => original

            assertEquals(result, ChapterMetadataManager.SaveDescriptionResult.Uncertain)
            assertEquals(after, expected)
    }

  test("should expose chapter writes as database programs that roll back with their caller"):
    buildTransactor.use: xa =>
      val chapters = SqliteChapterRepository.make
      val error    = new IllegalStateException("Abort composed transaction")

      for
        _      <- seedAlbum(xa)
        first  <- buildChapter(xa, index = 1)
        failed <- (chapters.create(albumId, first, order = NonEmptyList.one(first.id)) >> error.raiseError[ConnectionIO, Unit]).transact(xa).attempt
        rows   <- sql"SELECT count(*) FROM chapter".query[Int].unique.transact(xa)
      yield
        assert(failed.isLeft)
        assertEquals(rows, 0)

  test("should reject unknown countries explicitly without changing the saved chapter or creating partial rows"):
    buildTransactor.use: xa =>
      val chapters = SqliteChapterRepository.make
      val unknown  = NonEmptyList.one(CountryId(new UUID(9L, 99L)))

      (buildChapter(xa, index = 1), buildChapter(xa, index = 2)).tupled.flatMap { case (first, second) =>
        val command = ChapterRepository.Update(
          albumId,
          first.id,
          destination,
          first.name,
          countries = unknown,
          period,
          first.sourceFolder,
          order = NonEmptyList.one(first.id)
        )

        for
          _       <- seedAlbum(xa)
          _       <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
          before  <- countRows.transact(xa)
          created <- chapters.create(albumId, second.copy(countries = unknown), order = NonEmptyList.of(first.id, second.id)).transact(xa)
          updated <- chapters.update(command).transact(xa)
          after   <- countRows.transact(xa)
        yield
          assertEquals(created, ChapterRepository.CreateResult.UnknownCountries)
          assertEquals(updated, ChapterRepository.UpdateResult.UnknownCountries)
          assertEquals(after, before)
      }

  test(
    "should persist complete openings and supplied order, isolate shared destinations and preserve description, gear and composition on metadata edits"
  ):
    buildTransactor.use: xa =>
      val albums                   = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val chapters                 = SqliteChapterRepository.make
      val newName: DestinationName = "Changed"
      val newCountries             = NonEmptyList.of(otherCountry, country)
      val newFolder: PhotoSource   = "/photos/changed"
      val countryIds               = newCountries.map(_.id)
      val cameraId                 = CameraId(new UUID(0L, 991L))
      val selectedGear             = Vector(CameraSelection(cameraId, CameraUse.FixedLens))

      (buildChapter(xa, index = 1), buildChapter(xa, index = 2)).tupled.flatMap { case (first, second) =>
        val command = ChapterRepository.Update(
          albumId,
          first.id,
          destination,
          newName,
          countries = countryIds,
          period,
          newFolder,
          order = NonEmptyList.of(first.id, second.id)
        )

        val insertCamera = sql"""INSERT INTO camera(id, code, friendly_name, kind)
          VALUES (${cameraId.value}, 'retained-fixed', 'Retained fixed', 'fixed_lens')""".update.run.transact(xa)
        val selectCamera = sql"""INSERT INTO chapter_camera(chapter_id, camera_id, use_kind)
          VALUES (${first.id.value}, ${cameraId.value}, 'fixed_lens')""".update.run.transact(xa)

        for
          _       <- seedAlbum(xa)
          created <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
          added   <- chapters.create(albumId, second, order = NonEmptyList.of(second.id, first.id)).transact(xa)
          initial <- albums.get(albumId)
          _       <- sql"UPDATE chapter SET destination_id = ${first.destinationId.value} WHERE id = ${second.id.value}".update.run.transact(xa)
          _       <- insertPhotoComposition(first.id, first.opening.id).transact(xa)
          _       <- insertCamera
          _       <- selectCamera
          before  <- albums.get(albumId)
          updated <- chapters.update(command).transact(xa)
          loaded  <- albums.get(albumId)
        yield
          val originalDestination = Destination(first.destinationId, first.name, countries = NonEmptyList.of(country, otherCountry))
          val changedDestination  = originalDestination.copy(name = newName, countries = newCountries)
          val secondDestination   = Destination(second.destinationId, second.name, countries = NonEmptyList.of(country, otherCountry))
          val emptySpreads        = Vector.empty[Spread.PhotosOnly]
          val expectedFirst       =
            Chapter(
              first.id,
              originalDestination,
              first.period,
              first.description,
              gear = Vector.empty[CameraSelection],
              first.sourceFolder,
              first.opening,
              spreads = emptySpreads
            )
          val expectedSecond =
            Chapter(
              second.id,
              secondDestination,
              second.period,
              second.description,
              gear = Vector.empty[CameraSelection],
              second.sourceFolder,
              second.opening,
              spreads = emptySpreads
            )
          initial match
            case AlbumRepository.GetResult.Found(album) => assertEquals(album.chapters, Vector(expectedSecond, expectedFirst))
            case result                                 => fail(s"Expected complete created chapters, obtained $result")
          (before, loaded) match
            case (AlbumRepository.GetResult.Found(original), AlbumRepository.GetResult.Found(album)) =>
              val expected =
                original.chapters.find(_.id.value === first.id.value).map(_.copy(destination = changedDestination, sourceFolder = newFolder))
              val actual = album.chapters.headOption.map(chapter => chapter.copy(destination = chapter.destination.copy(id = first.destinationId)))

              assertEquals(created, ChapterRepository.CreateResult.Saved)
              assertEquals(added, ChapterRepository.CreateResult.Saved)
              assertEquals(updated, ChapterRepository.UpdateResult.Saved)
              assertEquals(album.chapters.map(_.id), Vector(first.id, second.id))
              assertEquals(actual, expected)
              assertEquals(original.chapters.find(_.id.value === first.id.value).map(_.gear), selectedGear.some)
              assertEquals(album.chapters.lastOption.map(_.destination), originalDestination.some)
              assertEquals(album.chapters.lastOption.map(_.opening), second.opening.some)
            case result => fail(s"Expected complete persisted album, obtained $result")
      }

  test("should leave all persisted rows unchanged after late opening, association or ordering failures"):
    Vector(
      "CREATE TRIGGER fail_write BEFORE INSERT ON spread_text_slot BEGIN SELECT RAISE(ABORT, 'opening failure'); END",
      "CREATE TRIGGER fail_write BEFORE INSERT ON destination_country WHEN NEW.position = 1 BEGIN SELECT RAISE(ABORT, 'association failure'); END",
      "CREATE TRIGGER fail_write BEFORE INSERT ON destination_country WHEN NEW.position = 1 BEGIN SELECT RAISE(IGNORE); END",
      "CREATE TRIGGER fail_write BEFORE INSERT ON spread_photo_slot_bleed BEGIN SELECT RAISE(IGNORE); END",
      "CREATE TRIGGER fail_write BEFORE UPDATE OF position ON chapter WHEN NEW.position = 0 BEGIN SELECT RAISE(ABORT, 'order failure'); END"
    ).traverse_ { trigger =>
      buildTransactor.use: xa =>
        val albums   = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
        val chapters = SqliteChapterRepository.make

        for
          _       <- seedAlbum(xa)
          first   <- buildChapter(xa, index = 1)
          _       <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
          before  <- albums.get(albumId)
          rows    <- countRows.transact(xa)
          second  <- buildChapter(xa, index = 2)
          _       <- Fragment.const(trigger).update.run.transact(xa)
          failed  <- chapters.create(albumId, second, order = NonEmptyList.of(second.id, first.id)).transact(xa).attempt
          after   <- albums.get(albumId)
          counted <- countRows.transact(xa)
        yield
          assert(failed.isLeft)
          assertEquals(after, before)
          assertEquals(counted, rows)
    }

  test("should reject incomplete openings, invalid orders and duplicate identities without partial writes"):
    buildTransactor.use: xa =>
      val chapters = SqliteChapterRepository.make

      (buildChapter(xa, index = 1), buildChapter(xa, index = 2)).tupled.flatMap { case (first, second) =>
        val mismatchedText = second.opening.textSlot.copy(box = SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0))
        val cases          = Vector(
          second.copy(opening = second.opening.copy(photoSlots = Vector.empty[SpreadSlot.Photo])) -> NonEmptyList.of(first.id, second.id),
          second.copy(opening = second.opening.copy(textSlot = mismatchedText))                   -> NonEmptyList.of(first.id, second.id),
          second                                                                                  -> NonEmptyList.one(second.id),
          second                                                                                  -> NonEmptyList.of(first.id, first.id, second.id),
          first                                                                                   -> NonEmptyList.one(first.id)
        )

        for
          _       <- seedAlbum(xa)
          _       <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
          before  <- countRows.transact(xa)
          results <- cases.traverse { case (chapter, order) => chapters.create(albumId, chapter, order = order).transact(xa).attempt }
          after   <- countRows.transact(xa)
        yield
          assert(results.forall(_.isLeft))
          assertEquals(after, before)
      }

  test("should report missing ownership and roll back metadata when the final ordering write fails"):
    buildTransactor.use: xa =>
      val albums                       = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val chapters                     = SqliteChapterRepository.make
      val missing                      = AlbumId(new UUID(2L, 2L))
      val revisedName: DestinationName = "Changed"
      val revisedFolder: PhotoSource   = "/photos/changed"
      val revisedCountries             = NonEmptyList.one(otherCountry.id)
      val trigger                      = sql"""CREATE TRIGGER fail_update BEFORE UPDATE OF position ON chapter WHEN NEW.position = 0
                           BEGIN SELECT RAISE(IGNORE); END"""

      buildChapter(xa, index = 1).flatMap: first =>
        val unknownChapter = ChapterId(new UUID(3L, 3L))
        val command        = ChapterRepository.Update(
          albumId,
          first.id,
          destination,
          first.name,
          countries = first.countries,
          period,
          first.sourceFolder,
          order = NonEmptyList.one(first.id)
        )
        val revised = command.copy(name = revisedName, countries = revisedCountries, sourceFolder = revisedFolder)

        for
          _           <- seedAlbum(xa)
          absentAlbum <- chapters.create(missing, first, order = NonEmptyList.one(first.id)).transact(xa)
          _           <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
          before      <- albums.get(albumId)
          rows        <- countRows.transact(xa)
          absentEdit  <- chapters.update(command.copy(album = missing)).transact(xa)
          absentChild <- chapters.update(command.copy(chapter = unknownChapter)).transact(xa)
          _           <- trigger.update.run.transact(xa)
          failed      <- chapters.update(revised).transact(xa).attempt
          after       <- albums.get(albumId)
          counted     <- countRows.transact(xa)
        yield
          assertEquals(absentAlbum, ChapterRepository.CreateResult.AlbumNotFound)
          assertEquals(absentEdit, ChapterRepository.UpdateResult.AlbumNotFound)
          assertEquals(absentChild, ChapterRepository.UpdateResult.ChapterNotFound)
          assert(failed.isLeft)
          assertEquals(after, before)
          assertEquals(counted, rows)

  test("should commit source clearing with metadata and evidence or preserve all state, then recover only retired files after reopening"):
    Vector("saved", "acknowledgement", "evidence", "removal", "order", "unknown-country", "unchanged").traverse_ { stage =>
      (buildDatabase, buildDirectory).tupled.use { case (path, root) =>
        val chapters                     = SqliteChapterRepository.make
        val photos                       = SqlitePhotoRepository.make
        val storage                      = FileThumbnailStorage.make[IO](root)
        val locations                    = Vector(ThumbnailLocation(new UUID(7L, 1L)), ThumbnailLocation(new UUID(7L, 6L)))
        val imageBytes                   = Vector(1.toByte).refineEither[ThumbnailBytesConstraint].fold(message => fail(message), identity)
        val image                        = EncodedThumbnail(imageBytes, ThumbnailDimensions(width = 1, height = 1), ThumbnailQuality.Low)
        val committed                    = stage === "saved" || stage === "acknowledgement"
        val requestedSource: PhotoSource = if stage === "unchanged" then "/photos/1" else "/photos/changed"
        val visit                        = ChapterMetadataManager.Period.Dates(period)
        val requestedCountries           =
          if stage === "unknown-country" then NonEmptyList.one(CountryId(new UUID(9L, 99L))) else NonEmptyList.one(otherCountry.id)
        val expectedPhotos = if committed then 0 else 2
        val expectedResult = if committed then ChapterMetadataManager.UpdateResult.Updated(Scheduling.Failed)
        else if stage === "unchanged" then ChapterMetadataManager.UpdateResult.Updated(Scheduling.NotRequested)
        else if stage === "unknown-country" then ChapterMetadataManager.UpdateResult.Rejected(ChapterConflict.UnknownCountries)
        else ChapterMetadataManager.UpdateResult.Failed
        val slots = sql"SELECT id, x, y, width, height FROM spread_photo_slot ORDER BY id".query[(String, Double, Double, Double, Double)].to[Vector]
        val coverSlots = sql"SELECT id, side FROM cover_slot ORDER BY id".query[(String, String)].to[Vector]
        val change     = buildConnection(path).use: xa =>
          val albums    = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, chapters)
          val templates = SqliteSpreadTemplateRepository.make[IO](xa)
          val calls     = Ref.unsafe[IO, Int](0)
          given Transaction[ConnectionIO, IO] with
            override def run[A](program: ConnectionIO[A]): IO[A] =
              calls.updateAndGet(_ + 1).flatMap: count =>
                val execute = program.transact(xa)
                if stage === "acknowledgement" && count === 2 then execute >> new IllegalStateException("Lost acknowledgement").raiseError[IO, A]
                else execute
            override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
          given IdGenerator[IO] with
            private val next                  = Ref.unsafe[IO, Long](200L)
            override def generateId: IO[UUID] = next.getAndUpdate(_ + 1).map(index => new UUID(8L, index))

          Mutex[IO].flatMap: mutex =>
            given Mutex[IO] = mutex
            val imports     = new ChapterPhotoImport[IO]:
              override def prepare(parent: AlbumId, chapter: ChapterId, source: PhotoSource): IO[Request] =
                Request(parent, chapter, source, generation = BigInt(1)).pure[IO]
              override def launch(request: Request): IO[Scheduling] =
                mutex.lock.surround(Scheduling.Failed.pure[IO])
              override def retire(parent: AlbumId, retirements: Vector[PhotoWriteIntent.Retirement]): IO[Unit] = IO.unit
              override def suspend(chapter: ChapterId): IO[Unit]                                               = IO.unit
              override def status(chapter: ChapterId): IO[ExecutionOutcome]                                    =
                ExecutionOutcome(Status.Idle, retirementIssues = Vector.empty[PhotoWriteRecovery.Issue]).pure[IO]
            val planning = ChapterPlanning.make(templates)
            val manager  = ChapterMetadataManager.make(albums, chapters, planning, imports, photos)
            val trigger  = stage match
              case "evidence" => "CREATE TRIGGER reject_write BEFORE INSERT ON photo_write_intent BEGIN SELECT RAISE(ABORT, 'evidence'); END"
              case "removal"  => "CREATE TRIGGER reject_write BEFORE DELETE ON photo BEGIN SELECT RAISE(ABORT, 'remove'); END"
              case "order"    =>
                "CREATE TRIGGER reject_write BEFORE UPDATE OF position ON chapter WHEN NEW.position = 0 BEGIN SELECT RAISE(ABORT, 'order'); END"
              case _ => ""

            for
              _           <- seedAlbum(xa)
              first       <- buildChapter(xa, index = 1)
              _           <- chapters.create(albumId, first, order = NonEmptyList.one(first.id)).transact(xa)
              _           <- (insertPhotoComposition(first.id, first.opening.id) >> insertCoverPhoto(first.id)).transact(xa)
              _           <- locations.traverse_(location => storage.storeThumbnail(location, image))
              before      <- albums.get(albumId)
              beforeSlots <- slots.transact(xa)
              beforeCover <- coverSlots.transact(xa)
              _           <- if trigger.isEmpty then IO.unit else Fragment.const(trigger).update.run.transact(xa).void
              result      <- manager.update(albumId, first.id, name = "Changed", countries = requestedCountries, visit, requestedSource)
              after       <- albums.get(albumId)
              afterSlots  <- slots.transact(xa)
              afterCover  <- coverSlots.transact(xa)
              photoCount  <- sql"SELECT count(*) FROM photo".query[Int].unique.transact(xa)
              placements  <- sql"SELECT count(*) FROM placement".query[Int].unique.transact(xa)
              pending     <- photos.listUnfinishedWrites.transact(xa)
            yield
              assertEquals(result, expectedResult)
              assertEquals(afterSlots, beforeSlots)
              assertEquals(afterCover, beforeCover)
              assertEquals(photoCount, expectedPhotos)
              assertEquals(placements, expectedPhotos)
              if !committed && stage =!= "unchanged" then assertEquals(after, before)
              after match
                case AlbumRepository.GetResult.Found(saved) =>
                  if committed || stage === "unchanged" then
                    assertEquals(saved.chapters.map(_.sourceFolder), Vector(requestedSource))
                    assertEquals(saved.chapters.map(_.destination.countries.map(_.id)), Vector(NonEmptyList.one(otherCountry.id)))
                case result => fail(s"Expected persisted album, obtained $result")
              pending match
                case listed: PhotoRepository.ListUnfinishedWritesResult.Listed =>
                  val expected = if !committed then Vector.empty[PhotoWriteIntent]
                  else
                    locations.zipWithIndex.map { case (location, index) =>
                      PhotoWriteIntent.Retirement(PhotoWriteIntentId(new UUID(8L, 200L + index)), first.id, PhotoId(location.value), location)
                    }
                  assertEquals(listed.intents, expected)
                case PhotoRepository.ListUnfinishedWritesResult.Failed => fail("Expected persisted evidence")

        change >> buildConnection(path).use: xa =>
          given Transaction[ConnectionIO, IO] with
            override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
            override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
          val recovery = PhotoWriteRecovery.make(photos, storage)

          for
            recovered <- recovery.recover
            pending   <- sql"SELECT count(*) FROM photo_write_intent".query[Int].unique.transact(xa)
            remaining <- locations.traverse(location => Files.exists(root.resolve(s"${location.value}.jpg")).blocking[IO])
          yield
            assertEquals(recovered, PhotoWriteRecovery.RecoveryResult.Recovered)
            assertEquals(pending, 0)
            assertEquals(remaining, Vector.fill(2)(!committed))
      }
    }

  test("should resolve saved chapter sources without photo rows and reject malformed stored sources"):
    buildTransactor.use: xa =>
      val repository = SqliteChapterRepository.make
      val missing    = ChapterId(new UUID(99L, 99L))

      for
        _       <- seedAlbum(xa)
        chapter <- buildChapter(xa, index = 1)
        _       <- repository.create(albumId, chapter, order = NonEmptyList.one(chapter.id)).transact(xa)
        found   <- repository.findSource(chapter.id).transact(xa)
        absent  <- repository.findSource(missing).transact(xa)
        _       <- sql"UPDATE chapter SET source_folder = '../invalid'".update.run.transact(xa)
        invalid <- repository.findSource(chapter.id).transact(xa).attempt
      yield
        assertEquals(found, ChapterRepository.SourceResult.Found(albumId, chapter.sourceFolder))
        assertEquals(absent, ChapterRepository.SourceResult.ChapterNotFound)
        assert(invalid.isLeft)

  private def insertCoverPhoto(chapter: ChapterId) =
    val photo       = new UUID(7L, 6L).toString
    val placement   = new UUID(7L, 7L).toString
    val insertPhoto = sql"""
      INSERT INTO photo (
        id, chapter_id, source, source_hash, width, height, thumbnail,
        thumbnail_version, thumbnail_width, thumbnail_height, thumbnail_quality, thumbnail_bytes
      )
      VALUES (
        $photo, ${chapter.value}, 'cover.jpg', 'hash', 100, 100, $photo,
        'version', 100, 100, 'low', 1
      )
    """
    val insertPlacement = sql"""
      INSERT INTO placement (
        id, photo_id, kind, cover_slot_id, shift_x, shift_y, mirrored, zoom
      )
      SELECT $placement, $photo, 'cover', id, 0, 0, 0, 1
      FROM cover_slot
      WHERE side = 'front'
    """
    insertPhoto.update.run >> insertPlacement.update.run.void

  private def buildDirectory =
    Resource.make(Files.createTempDirectory(Path.of("target"), "source-thumbnails-").toAbsolutePath.blocking[IO]): root =>
      Vector(new UUID(7L, 1L), new UUID(7L, 6L)).traverse_(id => Files.deleteIfExists(root.resolve(s"$id.jpg")).blocking[IO].void) >>
        Files.deleteIfExists(root).blocking[IO].void

  private def insertPhotoComposition(chapter: ChapterId, opening: SpreadId) =
    val photo           = new UUID(7L, 1L).toString
    val placement       = new UUID(7L, 2L).toString
    val template        = new UUID(7L, 3L).toString
    val spread          = new UUID(7L, 4L).toString
    val slot            = new UUID(7L, 5L).toString
    val insertPlacement = sql"""INSERT INTO placement
      (id, photo_id, kind, spread_photo_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner)
      SELECT $placement, $photo, 'spread', id, 0, 0, 0, 1, 'bottom_left'
      FROM spread_photo_slot WHERE spread_id = ${opening.value} ORDER BY id LIMIT 1"""
    val insertSpread = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
      VALUES ($spread, $template, 'photos_only', ${chapter.value}, 1)"""
    val insertPhoto = sql"""INSERT INTO photo (id, chapter_id, source, source_hash, width, height, thumbnail,
      thumbnail_version, thumbnail_width, thumbnail_height, thumbnail_quality, thumbnail_bytes)
      VALUES ($photo, ${chapter.value}, 'photo.jpg', 'hash', 100, 100, $photo, 'version', 100, 100, 'low', 1)"""
    for
      _ <- insertPhoto.update.run
      _ <- insertPlacement.update.run
      _ <- sql"INSERT INTO spread_template (id, name, kind) VALUES ($template, 'Photo layout', 'photos_only')".update.run
      _ <- sql"INSERT INTO spread_template_slot (template_id, kind, x, y, width, height) VALUES ($template, 'photo', 0, 0, 100, 100)".update.run
      _ <- insertSpread.update.run
      _ <- sql"INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height) VALUES ($slot, $spread, 0, 0, 100, 100)".update.run
    yield ()

  private def buildTransactor =
    buildDatabase.flatMap(buildConnection)

  private def buildDatabase =
    val acquireDatabase = Files.createTempFile(Path.of("target"), "chapter-", ".sqlite").blocking[IO]
    Resource.make(acquireDatabase)(path => Files.deleteIfExists(path).blocking[IO].void)
      .evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void)

  private def buildConnection(path: Path) =
    SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor

  private def seedAlbum(xa: Transactor[IO]) =
    val templates       = SqliteSpreadTemplateRepository.make[IO](xa)
    val insertCountries = sql"""INSERT INTO country VALUES
      (${country.id.value}, 'XX', 'Test country', '/flags/xx.svg'), (${otherCountry.id.value}, 'XY', 'Other country', '/flags/xx.svg')"""
    templates.listAlbumStartTemplates.flatMap:
      case SpreadTemplateRepository.ListAlbumStartTemplatesResult.Listed(values) =>
        val (album, cover) = buildAlbum(values.head)
        for
          saved <- SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make).create(album, cover)
          _     <- IO(assertEquals(saved, AlbumRepository.CreateResult.Created))
          _     <- insertCountries.update.run.transact(xa)
        yield ()
      case value => new IllegalStateException(s"Missing album templates: $value").raiseError[IO, Unit]

  private def buildAlbum(template: SpreadTemplate.AlbumStart) =
    val photos = template.photoSlots.zipWithIndex.map { case (box, index) =>
      SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(4L, index.toLong)), box, bleed = Set.empty[SpreadEdge])
    }
    val textSlot        = SpreadSlot.Text(TextSpreadSlotId(new UUID(4L, 11L)), template.textSlot)
    val emptyPlacements = Vector.empty[SpreadPlacement]
    val opening         = Spread.AlbumStart(SpreadId(new UUID(4L, 10L)), template.id, photoSlots = photos, textSlot, placements = emptyPlacements)
    val album           =
      Album(albumId, year = 2026, title = "Album", synopsis = "", authors = Author.all, Instant.EPOCH, opening, chapters = Vector.empty[Chapter])
    val cover = Cover(albumId, SpineOwner.Front, CoverSlot(CoverSlotId(new UUID(4L, 12L))), CoverSlot(CoverSlotId(new UUID(4L, 13L))))
    (album, cover)

  private def buildChapter(xa: Transactor[IO], index: Int) =
    SqliteSpreadTemplateRepository.make[IO](xa).listChapterStartTemplates.flatMap:
      case SpreadTemplateRepository.ListChapterStartTemplatesResult.Listed(values) =>
        val template = values.head
        val photos   = template.photoSlots.zipWithIndex.map { case (box, position) =>
          SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(index.toLong, position.toLong)), box, bleed = Set(SpreadEdge.Left))
        }
        val textSlot        = SpreadSlot.Text(TextSpreadSlotId(new UUID(index.toLong, 101L)), template.textSlot)
        val emptyPlacements = Vector.empty[SpreadPlacement]
        val opening         =
          Spread.ChapterStart(SpreadId(new UUID(index.toLong, 100L)), template.id, photoSlots = photos, textSlot, placements = emptyPlacements)
        val chapterId                 = ChapterId(new UUID(index.toLong, 102L))
        val destinationId             = DestinationId(new UUID(index.toLong, 103L))
        val countryIds                = NonEmptyList.of(country.id, otherCountry.id)
        val sourceFolder: PhotoSource = s"/photos/$index".refineUnsafe
        val description               = "Retained description"
        ChapterRepository.NewChapter(
          chapterId,
          destinationId,
          name = "Trip",
          countries = countryIds,
          period,
          description = description,
          sourceFolder,
          opening
        ).pure[IO]
      case value => new IllegalStateException(s"Missing chapter templates: $value").raiseError[IO, ChapterRepository.NewChapter]
