package photography.adapters.sqlite.album

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MinLength
import munit.CatsEffectSuite
import org.typelevel.doobie.{ConnectionIO, Fragment, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.sqlite.{SQLiteErrorCode, SQLiteException}
import photography.adapters.sqlite.{FlywayMigrator, SqliteConnection}
import photography.adapters.sqlite.SqliteCodecs.given
import io.github.iltotore.iron.doobie.given
import photography.app.config.SqliteConfig
import photography.domain.album.*
import photography.domain.gear.CameraSelection
import photography.domain.layout.*
import photography.ports.album.AlbumCompositionRepository.{Located, SpreadResult}
import photography.ports.album.AlbumRepository.*
import photography.syntax.SyncSyntax.*

import java.time.{Instant, LocalDate}
import java.nio.file.{Files, Path}
import java.util.UUID

final class SqliteAlbumRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO]             = Slf4jLogger.getLogger[IO]
  private given Logger[ConnectionIO]   = Slf4jLogger.getLogger[ConnectionIO]
  private val createdAt                = Instant.parse("2026-10-07T12:00:00.123Z")
  private val changedTitle: AlbumTitle = "Asian capitals"
  private val countInitialRows         = sql"""SELECT (SELECT count(*) FROM album) + (SELECT count(*) FROM spread) +
    (SELECT count(*) FROM spread_photo_slot) + (SELECT count(*) FROM spread_text_slot) +
    (SELECT count(*) FROM spread_photo_slot_bleed) + (SELECT count(*) FROM cover) + (SELECT count(*) FROM cover_slot)""".query[Long].unique

  test("should read and save metadata without acquiring unrelated Cover or spread geometry"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val album      = buildAlbum(index = 1)
      val renamed    = buildMetadata(album).copy(title = changedTitle)
      val revised    = renamed.copy(synopsis = "Replacement")
      for
        _        <- repository.create(album, buildCover(album))
        _        <- sql"DELETE FROM cover".update.run.transact(xa)
        metadata <- repository.metadata(album.id)
        chapters <- repository.chapters(album.id)
        planning <- repository.readPlanning(album.id)
        _        <- sql"DELETE FROM spread".update.run.transact(xa)
        changed  <- repository.rename(album.id, changedTitle)
        synopsis <- repository.saveSynopsis(album.id, text = "Replacement")
        authors  <- repository.saveAuthors(album.id, Author.all)
        retained <- repository.metadata(album.id)
      yield
        assertEquals(metadata, MetadataResult.Found(buildMetadata(album)))
        assertEquals(chapters, ChaptersResult.Found(Vector.empty[ChapterMetadata]))
        assertEquals(planning, PlanningResult.Found(PlanningSnapshot(album.year, Vector.empty[ChapterMetadata], 1)))
        assertEquals(changed, RenameResult.Renamed(renamed))
        assertEquals(synopsis, SaveSynopsisResult.Saved(revised))
        assertEquals(authors, SaveAuthorsResult.Saved(revised))
        assertEquals(retained, MetadataResult.Found(revised))

  test("should read ordered chapter facts and spread counts without requiring chapter geometry"):
    buildTransactor.use: xa =>
      val repository    = SqliteAlbumRepository.make[IO](xa)
      val album         = buildAlbum(index = 1)
      val chapter       = buildUuid(index = 902)
      val nextChapter   = buildUuid(index = 903)
      val insertChapter = sql"""INSERT INTO chapter (id, album_id, name, description, start_ms, end_ms, position, source_folder)
        VALUES ($nextChapter, ${album.id.value}, 'Coast', 'Mountain text', 86400000, 172800000, 1, '/photos/mountain'),
               ($chapter, ${album.id.value}, 'Coast', 'Coast text', 0, 86400000, 0, '/photos/coast')""".update.run
      val saveMetadata =
        for
          country <-
            sql"SELECT id, code, name, flag FROM country ORDER BY code LIMIT 1".query[(UUID, CountryCode, CountryName, AbsoluteLocation)].unique
              .map { case (id, code, name, flag) => Country(CountryId(id), code, name, flag) }
          _ <- insertChapter
          _ <- sql"INSERT INTO chapter_country VALUES ($chapter, ${country.id.value}, 0), ($nextChapter, ${country.id.value}, 0)".update.run
        yield country
      for
        _            <- repository.create(album, buildCover(album))
        country      <- saveMetadata.transact(xa)
        chapters     <- repository.chapters(album.id)
        planning     <- repository.readPlanning(album.id)
        _            <- sql"UPDATE chapter SET position = -1 WHERE id = $chapter".update.run.transact(xa)
        invalidOrder <- repository.chapters(album.id)
        _ <- (sql"UPDATE chapter SET position = 0 WHERE id = $chapter".update.run >> sql"DELETE FROM chapter_country".update.run).transact(xa)
        missingCountries <- repository.chapters(album.id)
      yield
        val expected = ChapterMetadata(
          ChapterId(chapter),
          Destination("Coast", NonEmptyList.one(country)),
          VisitPeriod(LocalDate.of(1970, 1, 1), days = 1L),
          "/photos/coast",
          "Coast text"
        )
        val next = expected.copy(
          id = ChapterId(nextChapter),
          period = VisitPeriod(LocalDate.of(1970, 1, 2), days = 1L),
          sourceFolder = "/photos/mountain",
          description = "Mountain text"
        )
        val ordered = Vector(expected, next)
        assertEquals(chapters, ChaptersResult.Found(ordered))
        assertEquals(planning, PlanningResult.Found(PlanningSnapshot(album.year, ordered, 1)))
        assertEquals(invalidOrder, ChaptersResult.Failed(Failure.DatabaseFailure))
        assertEquals(missingCountries, ChaptersResult.Failed(Failure.DatabaseFailure))

  test("should persist the initial album and its Cover while field edits preserve year and opening geometry"):
    Vector(SpineOwner.Front -> "front", SpineOwner.Back -> "back").traverse_ { case (owner, expectedSpine) =>
      buildTransactor.use: xa =>
        val repository  = SqliteAlbumRepository.make[IO](xa)
        val composition = SqliteAlbumCompositionRepository.make
        val initial     = buildAlbum(index = 1)
        val album = initial.copy(opening = initial.opening.copy(photoSlots = initial.opening.photoSlots.map(_.copy(bleed = SpreadEdge.values.toSet))))
        val expected = SpreadResult.Found(Located.AlbumOpening(album.opening))
        for
          created  <- repository.create(album, buildCover(album).copy(spineOwner = owner))
          found    <- repository.metadata(album.id)
          opening  <- composition.findSpread(album.id, album.opening.id).transact(xa)
          spine    <- sql"SELECT spine_owner FROM cover".query[String].unique.transact(xa)
          sides    <- sql"SELECT side FROM cover_slot ORDER BY side".query[String].to[Vector].transact(xa)
          renamed  <- repository.rename(album.id, changedTitle)
          retained <- composition.findSpread(album.id, album.opening.id).transact(xa)
        yield
          assertEquals(created, CreateResult.Created)
          assertEquals(found, MetadataResult.Found(buildMetadata(album)))
          assertEquals(opening, expected)
          assertEquals(spine, expectedSpine)
          assertEquals(sides, Vector("back", "front"))
          assertEquals(renamed, RenameResult.Renamed(buildMetadata(album.copy(title = changedTitle))))
          assertEquals(retained, expected)
    }

  test("should list newest-first slices with stable ordering for equal creation instants and an empty collection"):
    buildTransactor.use: xa =>
      val repository  = SqliteAlbumRepository.make[IO](xa)
      val albums      = Vector(1, 2, 3).map(buildAlbum)
      val newestFirst = (albums.take(1) ++ albums.drop(1).reverse).map(buildMetadata)
      for
        empty  <- repository.list(offset = 0, limit = 6)
        _      <- albums.traverse_(album => repository.create(album, buildCover(album)))
        first  <- repository.list(offset = 0, limit = 2)
        last   <- repository.list(offset = 2, limit = 2)
        beyond <- repository.list(offset = 3, limit = 2)
      yield
        assertEquals(empty, ListResult.Listed(AlbumPageSlice(0, Vector.empty[Metadata])))
        assertEquals(first, ListResult.Listed(AlbumPageSlice(3, newestFirst.take(2))))
        assertEquals(last, ListResult.Listed(AlbumPageSlice(3, newestFirst.drop(2))))
        assertEquals(beyond, ListResult.Listed(AlbumPageSlice(3, Vector.empty[Metadata])))

  test("should enforce unique years atomically under concurrent creation"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val first      = buildAlbum(index = 1)
      val second     = buildAlbum(index = 2).copy(year = first.year)
      val later      = buildAlbum(index = 3)
      for
        results <- Vector(first, second).parTraverse(album => repository.create(album, buildCover(album)))
        before  <- countInitialRows.transact(xa)
        _       <- repository.create(later, buildCover(later))
        loaded  <- repository.metadata(later.id)
        after   <- countInitialRows.transact(xa)
      yield
        val created   = results.collect { case CreateResult.Created => () }.size
        val conflicts = results.collect { case CreateResult.Failed(Failure.YearAlreadyUsed) => () }.size
        assertEquals(created, 1)
        assertEquals(conflicts, 1)
        assertEquals(loaded, MetadataResult.Found(buildMetadata(later)))
        assertEquals(after, before * 2L)

  test("should distinguish missing albums without changing saved albums or shared templates"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val missing    = AlbumId(buildUuid(index = 999))
      val album      = buildAlbum(index = 1)
      for
        _         <- repository.create(album, buildCover(album))
        before    <- countInitialRows.transact(xa)
        metadata  <- repository.metadata(missing)
        chapters  <- repository.chapters(missing)
        planning  <- repository.readPlanning(missing)
        renamed   <- repository.rename(missing, changedTitle)
        synopsis  <- repository.saveSynopsis(missing, text = "")
        authors   <- repository.saveAuthors(missing, Author.all)
        retained  <- repository.metadata(album.id)
        owned     <- countInitialRows.transact(xa)
        templates <- sql"SELECT count(*) FROM spread_template".query[Long].unique.transact(xa)
      yield
        assertEquals(metadata, MetadataResult.NotFound)
        assertEquals(chapters, ChaptersResult.AlbumNotFound)
        assertEquals(planning, PlanningResult.NotFound)
        assertEquals(renamed, RenameResult.NotFound)
        assertEquals(synopsis, SaveSynopsisResult.NotFound)
        assertEquals(authors, SaveAuthorsResult.NotFound)
        assertEquals(retained, MetadataResult.Found(buildMetadata(album)))
        assertEquals(owned, before)
        assertEquals(templates, 99L)

  test("should reject noninitial albums or foreign Covers and roll back all initial rows after a late failure"):
    buildTransactor.use: xa =>
      val repository     = SqliteAlbumRepository.make[IO](xa)
      val album          = buildAlbum(index = 1)
      val chapterOpening = Spread.ChapterStart(
        SpreadId(buildUuid(index = 10)),
        album.opening.template,
        album.opening.photoSlots,
        album.opening.textSlot,
        Vector.empty[SpreadPlacement]
      )
      val country = Country(CountryId(buildUuid(index = 12)), "RO", "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
      val chapter = Chapter(
        ChapterId(buildUuid(index = 13)),
        Destination("Home", NonEmptyList.one(country)),
        VisitPeriod(LocalDate.of(1901, 1, 1), days = 365),
        "",
        Vector.empty[CameraSelection],
        "/photos/Home",
        chapterOpening,
        Vector.empty[Spread.PhotosOnly]
      )
      val failure = CreateResult.Failed(Failure.DatabaseFailure)
      for
        noninitial <- repository.create(album.copy(chapters = Vector(chapter)), buildCover(album))
        foreign    <- repository.create(album, buildCover(album).copy(album = AlbumId(buildUuid(index = 999))))
        _          <- sql"""CREATE TRIGGER fail_cover BEFORE INSERT ON cover_slot WHEN NEW.side = 'back'
          BEGIN SELECT RAISE(ABORT, 'injected cover failure'); END""".update.run.transact(xa)
        rejected <- repository.create(album, buildCover(album))
        count    <- countInitialRows.transact(xa)
      yield
        assertEquals(noninitial, failure)
        assertEquals(foreign, failure)
        assertEquals(rejected, failure)
        assertEquals(count, 0L)

  test("should reject invalid stored metadata without saving fields or disguising failed reads as absence"):
    Vector("year = 1899", "title = ''", "authors = 0", "authors = 8").traverse_ { invalid =>
      buildTransactor.use: xa =>
        val repository = SqliteAlbumRepository.make[IO](xa)
        val album      = buildAlbum(index = 1)
        for
          _        <- repository.create(album, buildCover(album))
          _        <- (sql"PRAGMA ignore_check_constraints=ON".update.run >> Fragment.const(s"UPDATE album SET $invalid").update.run).transact(xa)
          metadata <- repository.metadata(album.id)
          listed   <- repository.list(offset = 0, limit = 6)
          renamed  <- repository.rename(album.id, changedTitle)
          synopsis <- repository.saveSynopsis(album.id, text = "Replacement")
          authors  <- repository.saveAuthors(album.id, Author.all)
        yield
          assertEquals(metadata, MetadataResult.Failed(Failure.DatabaseFailure))
          assertEquals(listed, ListResult.Failed(Failure.DatabaseFailure))
          assert(fieldFailure(renamed).getMessage.nonEmpty)
          assert(fieldFailure(synopsis).getMessage.nonEmpty)
          assert(fieldFailure(authors).getMessage.nonEmpty)
    }

  test("should report unavailable persistence explicitly"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val album      = buildAlbum(index = 1)
      for
        _        <- sql"DROP TABLE album".update.run.transact(xa)
        listed   <- repository.list(offset = 0, limit = 6)
        created  <- repository.create(album, buildCover(album))
        found    <- repository.metadata(album.id)
        chapters <- repository.chapters(album.id)
        planning <- repository.readPlanning(album.id)
        renamed  <- repository.rename(album.id, changedTitle)
      yield
        assertEquals(listed, ListResult.Failed(Failure.DatabaseFailure))
        assertEquals(created, CreateResult.Failed(Failure.DatabaseFailure))
        assertEquals(found, MetadataResult.Failed(Failure.DatabaseFailure))
        assertEquals(chapters, ChaptersResult.Failed(Failure.DatabaseFailure))
        assertEquals(planning, PlanningResult.Failed(Failure.DatabaseFailure))
        assert(fieldFailure(renamed).getMessage.nonEmpty)

  test("should preserve Unicode synopsis text and all nonempty author combinations, including synopsis clearing"):
    buildTransactor.use: xa =>
      val repository   = SqliteAlbumRepository.make[IO](xa)
      val album        = buildAlbum(index = 1)
      val text         = "Ș 日本語\n🙂"
      val combinations = Author.all.subsets().filter(_.nonEmpty).toVector.map(_.refineUnsafe[MinLength[1]])
      for
        _     <- repository.create(album, buildCover(album))
        saved <- repository.saveSynopsis(album.id, text)
        _     <- combinations.traverse_ { authors =>
          val expected = buildMetadata(album).copy(synopsis = text, authors = authors)
          for
            result   <- repository.saveAuthors(album.id, authors)
            metadata <- repository.metadata(album.id)
          yield
            assertEquals(result, SaveAuthorsResult.Saved(expected))
            assertEquals(metadata, MetadataResult.Found(expected))
        }
        _       <- repository.saveAuthors(album.id, Author.all)
        cleared <- repository.saveSynopsis(album.id, text = "")
      yield
        assertEquals(saved, SaveSynopsisResult.Saved(buildMetadata(album).copy(synopsis = text)))
        assertEquals(cleared, SaveSynopsisResult.Saved(buildMetadata(album)))

  test("should reject field writes atomically without treating known rejection as uncertain"):
    Vector("RAISE(ABORT, 'rejected')", "RAISE(IGNORE)").traverse_ { rejection =>
      buildTransactor.use: xa =>
        val repository = SqliteAlbumRepository.make[IO](xa)
        val album      = buildAlbum(index = 1)
        for
          _        <- repository.create(album, buildCover(album))
          _        <- Fragment.const(s"CREATE TRIGGER reject_album BEFORE UPDATE ON album BEGIN SELECT $rejection; END").update.run.transact(xa)
          synopsis <- repository.saveSynopsis(album.id, text = "changed")
          authors  <- repository.saveAuthors(album.id, Set(Author.Mara).refineUnsafe[MinLength[1]])
          renamed  <- repository.rename(album.id, changedTitle)
          found    <- repository.metadata(album.id)
        yield
          assert(fieldFailure(synopsis).getMessage.nonEmpty)
          assert(fieldFailure(authors).getMessage.nonEmpty)
          assert(fieldFailure(renamed).getMessage.nonEmpty)
          assertEquals(found, MetadataResult.Found(buildMetadata(album)))
    }

  test("should reject invalid author representations at the storage boundary"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa)
      val album      = buildAlbum(index = 1)
      val invalid    = Vector("0", "8", "'Cosmin'", "1.5")
      for
        _        <- repository.create(album, buildCover(album))
        failures <- invalid.traverse(value => Fragment.const(s"UPDATE album SET authors = $value").update.run.transact(xa).attempt)
        retained <- repository.metadata(album.id)
      yield
        assert(failures.forall(_.isLeft))
        assertEquals(retained, MetadataResult.Found(buildMetadata(album)))

  test("should retain authored metadata across connection reopening"):
    val acquire = Files.createTempFile(Path.of("target"), "album-history-", ".sqlite").blocking[IO]
    Resource.make(acquire)(path => Files.deleteIfExists(path).blocking[IO].void).use: path =>
      val album    = buildAlbum(index = 1).copy(synopsis = "Retained Ș\n日本")
      val authors  = Set(Author.Iacob).refineUnsafe[MinLength[1]]
      val expected = buildMetadata(album).copy(authors = authors)
      val save     = buildConnection(path).use: xa =>
        val repository = SqliteAlbumRepository.make[IO](xa)
        repository.create(album, buildCover(album)) >>
          repository.saveAuthors(album.id, authors).map(assertEquals(_, SaveAuthorsResult.Saved(expected)))
      val read = buildConnection(path).use: xa =>
        SqliteAlbumRepository.make[IO](xa).metadata(album.id).map(assertEquals(_, MetadataResult.Found(expected)))
      FlywayMigrator.make[IO](path).use(_.migrate) >> save >> read

  test("should report lost commit acknowledgements without replaying field mutations"):
    Vector[Throwable](
      new IllegalStateException("Lost acknowledgement"),
      new SQLiteException("Lost acknowledgement", SQLiteErrorCode.SQLITE_CONSTRAINT)
    )
      .traverse_ { error =>
        buildTransactor.use: xa =>
          val repository  = SqliteAlbumRepository.make[IO](xa)
          val album       = buildAlbum(index = 1)
          val interrupted = Transactor.after.set(xa, xa.strategy.after >> error.raiseError[ConnectionIO, Unit])
          val uncertain   = SqliteAlbumRepository.make[IO](interrupted)
          val selected    = Set(Author.Mara).refineUnsafe[MinLength[1]]
          for
            _ <- repository.create(album, buildCover(album))
            _ <- sql"CREATE TABLE field_audit (field TEXT NOT NULL)".update.run.transact(xa)
            _ <- Vector("authors", "synopsis", "title").traverse_ { field =>
              Fragment.const(s"CREATE TRIGGER count_$field AFTER UPDATE OF $field ON album BEGIN INSERT INTO field_audit VALUES ('$field'); END")
                .update.run.transact(xa)
            }
            synopsis <- uncertain.saveSynopsis(album.id, text = "Saved once")
            authors  <- uncertain.saveAuthors(album.id, selected)
            renamed  <- uncertain.rename(album.id, changedTitle)
            found    <- repository.metadata(album.id)
            writes   <- sql"SELECT field, count(*) FROM field_audit GROUP BY field ORDER BY field".query[(String, Long)].to[Vector].transact(xa)
          yield
            val expected = buildMetadata(album).copy(title = changedTitle, synopsis = "Saved once", authors = selected)
            assertEquals(synopsis, SaveSynopsisResult.Uncertain(error))
            assertEquals(authors, SaveAuthorsResult.Uncertain(error))
            assertEquals(renamed, RenameResult.Failed(error))
            assertEquals(found, MetadataResult.Found(expected))
            assertEquals(writes, Vector(("authors", 1L), ("synopsis", 1L), ("title", 1L)))
      }

  test("should retain failure evidence when field mutation cannot acquire its transaction"):
    buildTransactor.use: xa =>
      val album       = buildAlbum(index = 1)
      val repository  = SqliteAlbumRepository.make[IO](xa)
      val failure     = new IllegalStateException("Transaction unavailable")
      val unavailable = SqliteAlbumRepository.make[IO](Transactor.before.set(xa, failure.raiseError[ConnectionIO, Unit]))

      for
        _        <- repository.create(album, buildCover(album))
        synopsis <- unavailable.saveSynopsis(album.id, text = "unsaved")
        authors  <- unavailable.saveAuthors(album.id, Author.all)
        renamed  <- unavailable.rename(album.id, changedTitle)
        retained <- repository.metadata(album.id)
      yield
        assertEquals(synopsis, SaveSynopsisResult.Uncertain(failure))
        assertEquals(authors, SaveAuthorsResult.Uncertain(failure))
        assertEquals(renamed, RenameResult.Failed(failure))
        assertEquals(retained, MetadataResult.Found(buildMetadata(album)))

  private def fieldFailure(result: SaveSynopsisResult | SaveAuthorsResult | RenameResult) = result match
    case RenameResult.Failed(cause)       => cause
    case SaveSynopsisResult.Failed(cause) => cause
    case SaveAuthorsResult.Failed(cause)  => cause
    case other                            => fail(s"Expected a known field rejection, got $other")

  private def buildConnection(path: Path) =
    SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor

  private def buildTransactor =
    val acquire = Files.createTempFile(Path.of("target"), "album-", ".sqlite").blocking[IO]
    for
      path <- Resource.make(acquire)(path => Files.deleteIfExists(path).blocking[IO].void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- buildConnection(path)
    yield xa

  private def buildMetadata(album: Album) = Metadata(album.id, album.year, album.title, album.synopsis, album.authors, album.createdAt)

  private def buildAlbum(index: Int) =
    val album = AlbumId(buildUuid(index))
    val boxes = Vector(
      SlotBox(0.293982, 0.914609, 24.695519, 48.758744),
      SlotBox(25.199487, 0.914609, 24.695519, 48.758744),
      SlotBox(0.293982, 50.326646, 24.695519, 48.758744),
      SlotBox(25.199487, 50.326646, 24.695519, 48.758744)
    )
    val photos = boxes.zipWithIndex.map { case (box, position) =>
      SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index * 1000 + position)), box, Set.empty[SpreadEdge])
    }
    val text    = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index * 1000 + 4)), SlotBox(50.104993, 0.914609, 49.601025, 98.170781))
    val opening =
      Spread.AlbumStart(SpreadId(buildUuid(index + 100)), SpreadTemplateId(buildUuid(index = 1)), photos, text, Vector.empty[SpreadPlacement])
    val timestamp = if index === 1 then createdAt.plusSeconds(1) else createdAt
    Album(album, (1900 + index).refineUnsafe[AlbumYearConstraint], "My photographs", "", Author.all, timestamp, opening, Vector.empty[Chapter])

  private def buildCover(album: Album) =
    val index = album.id.value.getLeastSignificantBits.toInt
    Cover(album.id, SpineOwner.Front, CoverSlot(CoverSlotId(buildUuid(index + 200))), CoverSlot(CoverSlotId(buildUuid(index + 300))))

  private def buildUuid(index: Int) = new UUID(0L, index.toLong)
