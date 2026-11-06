package photography.adapters.sqlite.chapter

import cats.data.{NonEmptyList, NonEmptyVector}
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.typelevel.doobie.{ConnectionIO, Fragment, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.sqlite.{FlywayMigrator, SqliteConnection}
import photography.adapters.sqlite.SqliteCodecs.given
import photography.adapters.sqlite.album.{SqliteAlbumCompositionRepository, SqliteAlbumRepository}
import photography.adapters.sqlite.gear.SqliteGearRepository
import photography.adapters.sqlite.spread.SqliteSpreadTemplateRepository
import photography.app.config.SqliteConfig
import photography.domain.album.*
import photography.domain.gear.*
import photography.domain.layout.*
import photography.ports.album.AlbumCompositionRepository.{Located, SpreadResult}
import photography.ports.album.AlbumRepository.{ChapterMetadata, ChaptersResult, CreateResult as AlbumCreateResult}
import photography.ports.chapter.ChapterRepository.*
import photography.ports.spread.SpreadTemplateRepository
import photography.syntax.SyncSyntax.*

import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate}
import java.util.UUID

final class SqliteChapterRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO]           = Slf4jLogger.getLogger[IO]
  private given Logger[ConnectionIO] = Slf4jLogger.getLogger[ConnectionIO]
  private val album                  = AlbumId(new UUID(1L, 1L))
  private val period                 = VisitPeriod(firstDay = LocalDate.of(2026, 6, 1), days = 3L)
  private val country                = Country(CountryId(new UUID(9L, 1L)), "XX", "Test country", AbsoluteLocation(Path.of("/flags/xx.svg")))
  private val otherCountry           = country.copy(id = CountryId(new UUID(9L, 2L)), code = "XY", name = "Other country")
  private val body                   =
    Camera(CameraId(new UUID(12L, 1L)), "body", "Body", CameraKind.InterchangeableLens(LensMount.SonyE, SensorFormat.ApsC))
  private val fixedBody = body.copy(id = CameraId(new UUID(12L, 2L)), code = "fixed", kind = CameraKind.FixedLens)
  private val lens      = Lens(LensId(new UUID(12L, 3L)), "lens", "Lens", LensMount.SonyE, SensorFormat.FullFrame)
  private val otherLens = lens.copy(id = LensId(new UUID(12L, 4L)), code = "other-lens")
  private val selection = Vector(
    CameraSelection(fixedBody.id, CameraUse.FixedLens),
    CameraSelection(body.id, CameraUse.Interchangeable(NonEmptyVector.of(otherLens.id, lens.id)))
  )
  private val chapters    = SqliteChapterRepository.make
  private val composition = SqliteAlbumCompositionRepository.make
  private val countRows   = sql"""SELECT (SELECT count(*) FROM chapter) + (SELECT count(*) FROM destination) +
    (SELECT count(*) FROM destination_country) + (SELECT count(*) FROM spread) +
    (SELECT count(*) FROM spread_photo_slot) + (SELECT count(*) FROM spread_text_slot)""".query[Long].unique

  test("should replace ordered chapter gear completely, including clearing it, without changing chapter content"):
    buildTransactor.use: xa =>
      val albums  = SqliteAlbumRepository.make[IO](xa)
      val catalog = SqliteGearRepository.make[IO](xa)
      for
        first   <- buildChapter(xa, index = 1)
        second  <- buildChapter(xa, index = 2)
        _       <- chapters.create(album, first, NonEmptyList.one(first.id)).transact(xa)
        _       <- chapters.create(album, second, NonEmptyList.of(first.id, second.id)).transact(xa)
        _       <- Vector(body, fixedBody).traverse_(catalog.createCamera)
        _       <- Vector(lens, otherLens).traverse_(catalog.createLens)
        before  <- albums.chapters(album)
        saved   <- chapters.replaceGear(album, first.id, selection).transact(xa)
        read    <- chapters.readGear(album, first.id).transact(xa)
        all     <- chapters.readSelections.transact(xa)
        cleared <- chapters.replaceGear(album, first.id, Vector.empty[CameraSelection]).transact(xa)
        empty   <- chapters.readGear(album, first.id).transact(xa)
        after   <- albums.chapters(album)
      yield
        val expected = Vector(ChapterGearSelection(first.id, selection), ChapterGearSelection(second.id, Vector.empty[CameraSelection]))
        assertEquals(saved, GearWriteResult.Saved)
        assertEquals(read, GearReadResult.Found(selection))
        assertEquals(all, SelectionsReadResult.Found(expected))
        assertEquals(cleared, GearWriteResult.Saved)
        assertEquals(empty, GearReadResult.Found(Vector.empty[CameraSelection]))
        assertEquals(after, before)

  test("should reject missing ownership and invalid gear references or cardinality without changing saved selections"):
    buildTransactor.use: xa =>
      val catalog        = SqliteGearRepository.make[IO](xa)
      val missingAlbum   = AlbumId(new UUID(99L, 99L))
      val missingChapter = ChapterId(new UUID(99L, 99L))
      val invalid        = Vector(
        selection ++ selection,
        Vector(CameraSelection(CameraId(new UUID(99L, 99L)), CameraUse.FixedLens)),
        Vector(CameraSelection(body.id, CameraUse.Interchangeable(NonEmptyVector.one(LensId(new UUID(99L, 99L)))))),
        Vector(CameraSelection(body.id, CameraUse.Interchangeable(NonEmptyVector.of(lens.id, lens.id))))
      )
      for
        first      <- buildChapter(xa, index = 1)
        _          <- chapters.create(album, first, NonEmptyList.one(first.id)).transact(xa)
        _          <- Vector(body, fixedBody).traverse_(catalog.createCamera)
        _          <- Vector(lens, otherLens).traverse_(catalog.createLens)
        _          <- chapters.replaceGear(album, first.id, selection).transact(xa)
        wrongOwner <- chapters.replaceGear(missingAlbum, first.id, selection).transact(xa)
        wrongRead  <- chapters.readGear(missingAlbum, first.id).transact(xa)
        missing    <- chapters.readGear(album, missingChapter).transact(xa)
        rejected   <- invalid.traverse(values => chapters.replaceGear(album, first.id, values).transact(xa))
        saved      <- chapters.readGear(album, first.id).transact(xa)
      yield
        assertEquals(wrongOwner, GearWriteResult.NotFound)
        assertEquals(wrongRead, GearReadResult.NotFound)
        assertEquals(missing, GearReadResult.NotFound)
        assertEquals(rejected, Vector.fill(invalid.size)(GearWriteResult.Failed))
        assertEquals(saved, GearReadResult.Found(selection))

  test("should roll back a partial gear replacement and preserve the original cause if savepoint cleanup also fails"):
    buildTransactor.use: xa =>
      val catalog = SqliteGearRepository.make[IO](xa)
      val trigger = sql"""CREATE TRIGGER reject_gear BEFORE INSERT ON chapter_camera_lens BEGIN
        UPDATE chapter SET description = 'Partial'; SELECT RAISE(FAIL, 'Rejected gear'); END""".update.run
      val abort = sql"""CREATE TRIGGER abort_gear BEFORE INSERT ON chapter_camera_lens BEGIN
        SELECT RAISE(ROLLBACK, 'Gear transaction aborted'); END""".update.run
      val abortCaller = new IllegalStateException("Abort caller").raiseError[ConnectionIO, Unit]
      for
        first       <- buildChapter(xa, index = 1)
        _           <- chapters.create(album, first, NonEmptyList.one(first.id)).transact(xa)
        _           <- Vector(body, fixedBody).traverse_(catalog.createCamera)
        _           <- Vector(lens, otherLens).traverse_(catalog.createLens)
        _           <- chapters.replaceGear(album, first.id, selection.take(1)).transact(xa)
        _           <- trigger.transact(xa)
        failed      <- chapters.replaceGear(album, first.id, selection).transact(xa)
        retained    <- chapters.readGear(album, first.id).transact(xa)
        description <- sql"SELECT description FROM chapter".query[String].unique.transact(xa)
        _           <- sql"DROP TRIGGER reject_gear".update.run.transact(xa)
        callerAbort <- (chapters.replaceGear(album, first.id, selection) >> abortCaller).transact(xa).attempt
        afterAbort  <- chapters.readGear(album, first.id).transact(xa)
        _           <- abort.transact(xa)
        aborted     <- chapters.replaceGear(album, first.id, selection).transact(xa).attempt
      yield
        assertEquals(failed, GearWriteResult.Failed)
        assertEquals(retained, GearReadResult.Found(selection.take(1)))
        assertEquals(description, first.description)
        assert(callerAbort.isLeft)
        assertEquals(afterAbort, retained)
        aborted match
          case Left(cause) =>
            assert(cause.getMessage.contains("Gear transaction aborted"))
            assert(cause.getSuppressed.toVector.exists(_.getMessage.contains("savepoint")))
          case other => fail(s"Expected the original failure and cleanup evidence, got $other")

  test("should report unavailable persistence before attempting chapter content writes"):
    buildTransactor.use: xa =>
      val id = ChapterId(new UUID(0L, 1L))
      for
        _           <- sql"ALTER TABLE chapter RENAME TO inaccessible_chapter".update.run.transact(xa)
        gear        <- chapters.replaceGear(album, id, Vector.empty[CameraSelection]).transact(xa)
        _           <- sql"ALTER TABLE album RENAME TO inaccessible_album".update.run.transact(xa)
        description <- chapters.saveDescription(album, id, text = "Saved text").transact(xa)
      yield
        assertEquals(gear, GearWriteResult.Failed)
        description match
          case SaveDescriptionResult.Failed(_) => ()
          case other                           => fail(s"Expected failed acquisition, got $other")

  test("should reject malformed saved gear instead of returning empty or partial selections"):
    Vector(
      "DELETE FROM chapter_camera_lens",
      "UPDATE chapter_camera SET use_kind = 'fixed_lens'"
    ).traverse_ { corruption =>
      buildTransactor.use: xa =>
        val catalog  = SqliteGearRepository.make[IO](xa)
        val selected = Vector(CameraSelection(body.id, CameraUse.Interchangeable(NonEmptyVector.one(lens.id))))
        for
          first <- buildChapter(xa, index = 1)
          _     <- chapters.create(album, first, NonEmptyList.one(first.id)).transact(xa)
          _     <- catalog.createCamera(body)
          _     <- catalog.createLens(lens)
          _     <- chapters.replaceGear(album, first.id, selected).transact(xa)
          _     <- Fragment.const(corruption).update.run.transact(xa)
          read  <- chapters.readGear(album, first.id).transact(xa)
          all   <- chapters.readSelections.transact(xa)
        yield
          assertEquals(read, GearReadResult.Failed)
          assertEquals(all, SelectionsReadResult.Failed)
    }

  test("should save and clear exact scoped descriptions while preserving metadata, gear and opening geometry"):
    buildTransactor.use: xa =>
      val albums  = SqliteAlbumRepository.make[IO](xa)
      val catalog = SqliteGearRepository.make[IO](xa)
      val missing = AlbumId(new UUID(0L, 99L))
      for
        first          <- buildChapter(xa, index = 1)
        second         <- buildChapter(xa, index = 2)
        _              <- chapters.create(album, first, NonEmptyList.one(first.id)).transact(xa)
        _              <- chapters.create(album, second, NonEmptyList.of(second.id, first.id)).transact(xa)
        _              <- catalog.createCamera(fixedBody)
        _              <- chapters.replaceGear(album, first.id, selection.take(1)).transact(xa)
        saved          <- chapters.saveDescription(album, first.id, text = "日本\nVacanță").transact(xa)
        after          <- albums.chapters(album)
        missingAlbum   <- chapters.saveDescription(missing, first.id, text = "wrong album").transact(xa)
        missingChapter <- chapters.saveDescription(album, ChapterId(missing.value), text = "missing").transact(xa)
        cleared        <- chapters.saveDescription(album, first.id, text = "").transact(xa)
        empty          <- albums.chapters(album)
        gear           <- chapters.readGear(album, first.id).transact(xa)
        opening        <- composition.findSpread(album, first.opening.id).transact(xa)
      yield
        val expected        = Vector(buildMetadata(second), buildMetadata(first).copy(description = "日本\nVacanță"))
        val clearedMetadata = expected.map(value => if value.id.value === first.id.value then value.copy(description = "") else value)
        assertEquals(saved, SaveDescriptionResult.Saved)
        assertEquals(after, ChaptersResult.Found(expected))
        assertEquals(missingAlbum, SaveDescriptionResult.AlbumNotFound)
        assertEquals(missingChapter, SaveDescriptionResult.ChapterNotFound)
        assertEquals(cleared, SaveDescriptionResult.Saved)
        assertEquals(empty, ChaptersResult.Found(clearedMetadata))
        assertEquals(gear, GearReadResult.Found(selection.take(1)))
        assertEquals(opening, SpreadResult.Found(Located.ChapterOpening(first.id, first.opening)))

  test("should reject cross-album description edits and roll back partial or ignored statements"):
    Vector("RAISE(FAIL, 'Rejected description')", "RAISE(IGNORE)").traverse_ { rejection =>
      buildTransactor.use: xa =>
        val albums     = SqliteAlbumRepository.make[IO](xa)
        val otherAlbum = AlbumId(new UUID(0L, 99L))
        val trigger    = Fragment.const(s"""CREATE TRIGGER reject_description BEFORE UPDATE OF description ON chapter BEGIN
          UPDATE chapter SET source_folder = '/partial' WHERE id = OLD.id; SELECT $rejection; END""").update.run
        for
          first <- buildChapter(xa, index = 1)
          _     <- chapters.create(album, first, NonEmptyList.one(first.id)).transact(xa)
          _     <-
            sql"INSERT INTO album (id, year, title, synopsis, created_at_ms) VALUES (${otherAlbum.value}, 2099, 'Other', '', 0)".update.run.transact(
              xa
            )
          scoped   <- chapters.saveDescription(otherAlbum, first.id, text = "Wrong owner").transact(xa)
          _        <- trigger.transact(xa)
          failed   <- chapters.saveDescription(album, first.id, text = "Rejected").transact(xa)
          retained <- albums.chapters(album)
        yield
          assertEquals(scoped, SaveDescriptionResult.ChapterNotFound)
          failed match
            case SaveDescriptionResult.Failed(cause) => assert(cause.getMessage.nonEmpty)
            case other                               => fail(s"Expected rejected description, got $other")
          assertEquals(retained, ChaptersResult.Found(Vector(buildMetadata(first))))
    }

  test("should persist supplied chapter order and isolate shared destinations without changing description, gear or openings"):
    buildTransactor.use: xa =>
      val albums  = SqliteAlbumRepository.make[IO](xa)
      val catalog = SqliteGearRepository.make[IO](xa)
      for
        first   <- buildChapter(xa, index = 1)
        second  <- buildChapter(xa, index = 2)
        created <- chapters.create(album, first, NonEmptyList.one(first.id)).transact(xa)
        added   <- chapters.create(album, second, NonEmptyList.of(second.id, first.id)).transact(xa)
        initial <- albums.chapters(album)
        _       <- sql"UPDATE chapter SET destination_id = ${first.destinationId.value} WHERE id = ${second.id.value}".update.run.transact(xa)
        _       <- catalog.createCamera(fixedBody)
        _       <- chapters.replaceGear(album, first.id, selection.take(1)).transact(xa)
        updated <- chapters.update(Update(
          album,
          first.id,
          DestinationId(new UUID(99L, 1L)),
          "Changed",
          NonEmptyList.of(otherCountry.id, country.id),
          period,
          "/photos/changed",
          NonEmptyList.of(first.id, second.id)
        )).transact(xa)
        loaded        <- albums.chapters(album)
        gear          <- chapters.readGear(album, first.id).transact(xa)
        firstOpening  <- composition.findSpread(album, first.opening.id).transact(xa)
        secondOpening <- composition.findSpread(album, second.opening.id).transact(xa)
      yield
        val originalDestination = buildMetadata(first).destination
        val changedDestination  =
          originalDestination.copy(id = DestinationId(new UUID(99L, 1L)), name = "Changed", countries = NonEmptyList.of(otherCountry, country))
        val expected = Vector(
          buildMetadata(first).copy(destination = changedDestination, sourceFolder = "/photos/changed"),
          buildMetadata(second).copy(destination = originalDestination)
        )
        assertEquals(created, CreateResult.Saved)
        assertEquals(added, CreateResult.Saved)
        assertEquals(initial, ChaptersResult.Found(Vector(buildMetadata(second), buildMetadata(first))))
        assertEquals(updated, UpdateResult.Saved)
        assertEquals(loaded, ChaptersResult.Found(expected))
        assertEquals(gear, GearReadResult.Found(selection.take(1)))
        assertEquals(firstOpening, SpreadResult.Found(Located.ChapterOpening(first.id, first.opening)))
        assertEquals(secondOpening, SpreadResult.Found(Located.ChapterOpening(second.id, second.opening)))

  test("should reject unknown countries and missing ownership without creating partial rows"):
    buildTransactor.use: xa =>
      val missingAlbum   = AlbumId(new UUID(99L, 99L))
      val missingChapter = ChapterId(new UUID(99L, 99L))
      val unknown        = NonEmptyList.one(CountryId(new UUID(9L, 99L)))
      for
        first       <- buildChapter(xa, index = 1)
        second      <- buildChapter(xa, index = 2)
        absent      <- chapters.create(missingAlbum, first, NonEmptyList.one(first.id)).transact(xa)
        _           <- chapters.create(album, first, NonEmptyList.one(first.id)).transact(xa)
        before      <- countRows.transact(xa)
        created     <- chapters.create(album, second.copy(countries = unknown), NonEmptyList.of(first.id, second.id)).transact(xa)
        updated     <- chapters.update(buildUpdate(first).copy(countries = unknown)).transact(xa)
        absentEdit  <- chapters.update(buildUpdate(first).copy(album = missingAlbum)).transact(xa)
        absentChild <- chapters.update(buildUpdate(first).copy(chapter = missingChapter)).transact(xa)
        after       <- countRows.transact(xa)
      yield
        assertEquals(absent, CreateResult.AlbumNotFound)
        assertEquals(created, CreateResult.UnknownCountries)
        assertEquals(updated, UpdateResult.UnknownCountries)
        assertEquals(absentEdit, UpdateResult.AlbumNotFound)
        assertEquals(absentChild, UpdateResult.ChapterNotFound)
        assertEquals(after, before)

  test("should reject incomplete openings, invalid orders and duplicate identities without partial writes"):
    buildTransactor.use: xa =>
      (buildChapter(xa, index = 1), buildChapter(xa, index = 2)).tupled.flatMap { case (first, second) =>
        val invalid = Vector(
          (
            chapter = second.copy(opening = second.opening.copy(photoSlots = Vector.empty[SpreadSlot.Photo])),
            order = NonEmptyList.of(first.id, second.id)
          ),
          (
            chapter = second.copy(opening = second.opening.copy(textSlot = second.opening.textSlot.copy(box = SlotBox(0.0, 0.0, 100.0, 100.0)))),
            order = NonEmptyList.of(first.id, second.id)
          ),
          (chapter = second, order = NonEmptyList.one(second.id)),
          (chapter = second, order = NonEmptyList.of(first.id, first.id, second.id)),
          (chapter = first, order = NonEmptyList.one(first.id))
        )
        for
          _       <- chapters.create(album, first, NonEmptyList.one(first.id)).transact(xa)
          before  <- countRows.transact(xa)
          results <- invalid.traverse { case (chapter, order) => chapters.create(album, chapter, order).transact(xa).attempt }
          after   <- countRows.transact(xa)
        yield
          assert(results.forall(_.isLeft))
          assertEquals(after, before)
      }

  test("should roll back chapter metadata and openings when a late write fails or the caller aborts"):
    buildTransactor.use: xa =>
      val albums  = SqliteAlbumRepository.make[IO](xa)
      val trigger = sql"""CREATE TRIGGER reject_order BEFORE UPDATE OF position ON chapter WHEN NEW.position = 0 BEGIN
        SELECT RAISE(IGNORE); END""".update.run
      val abort = new IllegalStateException("Abort composed transaction")
      for
        first   <- buildChapter(xa, index = 1)
        second  <- buildChapter(xa, index = 2)
        _       <- chapters.create(album, first, NonEmptyList.one(first.id)).transact(xa)
        rows    <- countRows.transact(xa)
        aborted <- (chapters.create(album, second, NonEmptyList.of(first.id, second.id)) >> abort.raiseError[ConnectionIO, Unit]).transact(xa).attempt
        _       <- trigger.transact(xa)
        failed  <- chapters.update(buildUpdate(first).copy(name = "Changed", sourceFolder = "/photos/changed")).transact(xa).attempt
        created <- chapters.create(album, second, NonEmptyList.of(first.id, second.id)).transact(xa).attempt
        retained <- albums.chapters(album)
        after    <- countRows.transact(xa)
      yield
        assert(aborted.isLeft)
        assert(failed.isLeft)
        assert(created.isLeft)
        assertEquals(retained, ChaptersResult.Found(Vector(buildMetadata(first))))
        assertEquals(after, rows)

  test("should resolve saved sources without photo rows and reject malformed stored sources"):
    buildTransactor.use: xa =>
      for
        chapter <- buildChapter(xa, index = 1)
        _       <- chapters.create(album, chapter, NonEmptyList.one(chapter.id)).transact(xa)
        found   <- chapters.findSource(chapter.id).transact(xa)
        absent  <- chapters.findSource(ChapterId(new UUID(99L, 99L))).transact(xa)
        _       <- sql"UPDATE chapter SET source_folder = '../invalid'".update.run.transact(xa)
        invalid <- chapters.findSource(chapter.id).transact(xa).attempt
      yield
        assertEquals(found, SourceResult.Found(album, chapter.sourceFolder))
        assertEquals(absent, SourceResult.ChapterNotFound)
        assert(invalid.isLeft)

  private def buildMetadata(chapter: NewChapter) =
    ChapterMetadata(
      chapter.id,
      Destination(chapter.destinationId, chapter.name, NonEmptyList.of(country, otherCountry)),
      chapter.period,
      chapter.sourceFolder,
      chapter.description
    )

  private def buildUpdate(chapter: NewChapter) =
    Update(
      album,
      chapter.id,
      DestinationId(new UUID(99L, 1L)),
      chapter.name,
      chapter.countries,
      chapter.period,
      chapter.sourceFolder,
      NonEmptyList.one(chapter.id)
    )

  private def buildTransactor =
    val acquire = Files.createTempFile(Path.of("target"), "chapter-", ".sqlite").blocking[IO]
    for
      path <- Resource.make(acquire)(path => Files.deleteIfExists(path).blocking[IO].void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
      _    <- Resource.eval(seedAlbum(xa))
    yield xa

  private def seedAlbum(xa: Transactor[IO]) =
    SqliteSpreadTemplateRepository.make[IO](xa).listAlbumStartTemplates.flatMap:
      case SpreadTemplateRepository.ListAlbumStartTemplatesResult.Listed(values) =>
        val template = values.head
        val slots    = template.photoSlots.zipWithIndex.map { case (box, index) =>
          SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(4L, index.toLong)), box, Set.empty[SpreadEdge])
        }
        val text    = SpreadSlot.Text(TextSpreadSlotId(new UUID(4L, 11L)), template.textSlot)
        val opening = Spread.AlbumStart(SpreadId(new UUID(4L, 10L)), template.id, slots, text, Vector.empty[SpreadPlacement])
        val initial = Album(album, 2026, "Album", "", Author.all, Instant.EPOCH, opening, Vector.empty[Chapter])
        val cover   = Cover(album, SpineOwner.Front, CoverSlot(CoverSlotId(new UUID(4L, 12L))), CoverSlot(CoverSlotId(new UUID(4L, 13L))))
        for
          saved <- SqliteAlbumRepository.make[IO](xa).create(initial, cover)
          _     <- IO(assertEquals(saved, AlbumCreateResult.Created))
          _     <- sql"""INSERT INTO country VALUES
            (${country.id.value}, 'XX', 'Test country', '/flags/xx.svg'),
            (${otherCountry.id.value}, 'XY', 'Other country', '/flags/xx.svg')""".update.run.transact(xa)
        yield ()
      case other => new IllegalStateException(s"Missing album templates: $other").raiseError[IO, Unit]

  private def buildChapter(xa: Transactor[IO], index: Int) =
    SqliteSpreadTemplateRepository.make[IO](xa).listChapterStartTemplates.flatMap:
      case SpreadTemplateRepository.ListChapterStartTemplatesResult.Listed(values) =>
        val template = values.head
        val slots    = template.photoSlots.zipWithIndex.map { case (box, position) =>
          SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(index.toLong, position.toLong)), box, Set(SpreadEdge.Left))
        }
        val text    = SpreadSlot.Text(TextSpreadSlotId(new UUID(index.toLong, 101L)), template.textSlot)
        val opening = Spread.ChapterStart(SpreadId(new UUID(index.toLong, 100L)), template.id, slots, text, Vector.empty[SpreadPlacement])
        val folder  = s"/photos/$index".refineUnsafe[PhotoSourceConstraint]
        NewChapter(
          ChapterId(new UUID(index.toLong, 102L)),
          DestinationId(new UUID(index.toLong, 103L)),
          "Trip",
          NonEmptyList.of(country.id, otherCountry.id),
          period,
          "Retained description",
          folder,
          opening
        ).pure[IO]
      case other => new IllegalStateException(s"Missing chapter templates: $other").raiseError[IO, NewChapter]
