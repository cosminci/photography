package photography.adapters.sqlite.album
import photography.adapters.sqlite.chapter.SqliteChapterRepository
import photography.domain.gear.CameraSelection

import photography.adapters.sqlite.FlywayMigrator
import photography.adapters.sqlite.SqliteConnection
import photography.adapters.sqlite.SqliteCodecs.given

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.sqlite.{SQLiteErrorCode, SQLiteException}
import photography.app.config.SqliteConfig
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.album.AlbumRepository.*

import java.time.{Instant, LocalDate}
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

final class SqliteAlbumRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO]             = Slf4jLogger.getLogger[IO]
  private given Logger[ConnectionIO]   = Slf4jLogger.getLogger[ConnectionIO]
  private val createdAt                = Instant.parse("2026-10-07T12:00:00.123Z")
  private val changedTitle: AlbumTitle = "Asian capitals"
  private val countInitialRows         = sql"""SELECT (SELECT count(*) FROM album) + (SELECT count(*) FROM spread) +
                                      (SELECT count(*) FROM spread_photo_slot) + (SELECT count(*) FROM spread_text_slot) +
                                      (SELECT count(*) FROM spread_photo_slot_bleed) +
                                      (SELECT count(*) FROM cover) + (SELECT count(*) FROM cover_slot)""".query[Long].unique

  test("should read metadata and ordered chapters without acquiring unrelated Cover or spread geometry"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val album      = buildAlbum(index = 1)
      val expected   = Metadata(album.id, album.year, album.title, album.synopsis, album.createdAt)

      for
        _        <- repository.create(album, buildCover(album))
        _        <- sql"DELETE FROM cover_slot".update.run.transact(xa)
        metadata <- repository.metadata(album.id)
        chapters <- repository.chapters(album.id)
        _        <- sql"DELETE FROM spread".update.run.transact(xa)
        retained <- repository.metadata(album.id)
      yield
        assertEquals(metadata, MetadataResult.Found(expected))
        assertEquals(chapters, ChaptersResult.Found(Vector.empty[ChapterMetadata]))
        assertEquals(retained, metadata)

  test("should validate lightweight chapter periods and countries without acquiring their spread composition"):
    buildTransactor.use: xa =>
      val repository  = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val album       = buildAlbum(index = 1)
      val chapter     = buildUuid(index = 902)
      val destination = buildUuid(index = 901)
      val readCountry = sql"SELECT id, code, name, flag FROM country ORDER BY code LIMIT 1"
        .query[(UUID, CountryCode, CountryName, AbsoluteLocation)].unique
        .map { case (id, code, name, flag) => Country(CountryId(id), code, name, flag) }
      val insertChapter = sql"""INSERT INTO chapter (id, album_id, destination_id, description, start_ms, end_ms, position, source_folder)
                   VALUES ($chapter, ${album.id.value}, $destination, 'Coast text', 0, 86400000, 0, '/photos/coast')""".update.run
      val saveMetadata =
        for
          country <- readCountry
          _       <- sql"INSERT INTO destination (id, name) VALUES ($destination, 'Coast')".update.run
          _       <- insertChapter
          _ <- sql"INSERT INTO destination_country (destination_id, country_id, position) VALUES ($destination, ${country.id.value}, 0)".update.run
        yield country
      val invalidBounds      = Vector((start = 0L, end = 0L), (start = 1L, end = 86400000L), (start = 86400000L, end = 0L))
      val readInvalidPeriods = invalidBounds.traverse { case (start, end) =>
        sql"UPDATE chapter SET start_ms = $start, end_ms = $end WHERE id = $chapter".update.run.transact(xa) *> repository.chapters(album.id)
      }
      val removeCountries =
        (sql"UPDATE chapter SET start_ms = 0, end_ms = 86400000 WHERE id = $chapter".update.run >>
          sql"DELETE FROM destination_country WHERE destination_id = $destination".update.run).transact(xa)

      for
        _              <- repository.create(album, buildCover(album))
        country        <- saveMetadata.transact(xa)
        valid          <- repository.chapters(album.id)
        periods        <- readInvalidPeriods
        _              <- removeCountries
        emptyCountries <- repository.chapters(album.id)
      yield
        val expected = ChapterMetadata(
          ChapterId(chapter),
          Destination(DestinationId(destination), "Coast", NonEmptyList.one(country)),
          VisitPeriod(LocalDate.of(1970, 1, 1), days = 1L),
          "/photos/coast",
          "Coast text"
        )
        val failed = ChaptersResult.Failed(Failure.DatabaseFailure)
        assertEquals(valid, ChaptersResult.Found(Vector(expected)))
        assertEquals(periods, Vector.fill(3)(failed))
        assertEquals(emptyCountries, failed)

  test("should retain absent resource outcomes and explicit storage failures"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val album      = buildAlbum(index = 1).id

      for
        metadata <- repository.metadata(album)
        chapters <- repository.chapters(album)
        _        <- sql"DROP TABLE album".update.run.transact(xa)
        failed   <- repository.metadata(album)
      yield
        assertEquals(metadata, MetadataResult.NotFound)
        assertEquals(chapters, ChaptersResult.AlbumNotFound)
        assertEquals(failed, MetadataResult.Failed(Failure.DatabaseFailure))

  test("should persist the complete initial album and preserve year and composition when renamed"):
    Vector(SpineOwner.Front -> "front", SpineOwner.Back -> "back").traverse_ { case (spineOwner, expectedSpine) =>
      buildTransactor.use: xa =>
        val repository = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
        val album      = buildAlbum(index = 1)
        val cover      = buildCover(album).copy(spineOwner = spineOwner)

        for
          created  <- repository.create(album, cover)
          found    <- repository.get(album.id)
          spine    <- sql"SELECT spine_owner FROM cover".query[String].unique.transact(xa)
          sides    <- sql"SELECT side FROM cover_slot ORDER BY side".query[String].to[Vector].transact(xa)
          renamed  <- repository.rename(album.id, title = changedTitle)
          reloaded <- repository.get(album.id)
        yield
          assertEquals(created, CreateResult.Created)
          assertEquals(found, GetResult.Found(album))
          assertEquals(spine, expectedSpine)
          assertEquals(sides, Vector("back", "front"))
          assertEquals(renamed, RenameResult.Renamed(album.copy(title = changedTitle)))
          assertEquals(reloaded, GetResult.Found(album.copy(title = changedTitle)))
    }

  test("should list newest-first slices with stable ordering for equal creation instants and an empty collection"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val albums     = Vector(1, 2, 3).map(buildAlbum)

      for
        empty  <- repository.list(offset = 0, limit = 6)
        _      <- albums.traverse_(album => repository.create(album, buildCover(album)))
        first  <- repository.list(offset = 0, limit = 2)
        last   <- repository.list(offset = 2, limit = 2)
        beyond <- repository.list(offset = 3, limit = 2)
      yield
        val newestFirst = albums.take(1) ++ albums.drop(1).reverse
        assertEquals(empty, ListResult.Listed(AlbumPageSlice(totalAlbums = 0, albums = Vector.empty[Album])))
        assertEquals(first, ListResult.Listed(AlbumPageSlice(totalAlbums = 3, albums = newestFirst.take(2))))
        assertEquals(last, ListResult.Listed(AlbumPageSlice(totalAlbums = 3, albums = newestFirst.drop(2))))
        assertEquals(beyond, ListResult.Listed(AlbumPageSlice(totalAlbums = 3, albums = Vector.empty[Album])))

  test("should enforce unique required years atomically under concurrent creation"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val first      = buildAlbum(index = 1)
      val second     = buildAlbum(index = 2).copy(year = first.year)
      val later      = buildAlbum(index = 3)

      for
        results <- Vector(first, second).parTraverse(album => repository.create(album, buildCover(album)))
        before  <- countInitialRows.transact(xa)
        _       <- repository.create(later, buildCover(later))
        loaded  <- repository.get(later.id)
        after   <- countInitialRows.transact(xa)
      yield
        assertEquals(results.collect { case CreateResult.Created => () }.size, 1)
        assertEquals(results.collect { case CreateResult.Failed(Failure.YearAlreadyUsed) => () }.size, 1)
        assertEquals(loaded, GetResult.Found(later))
        assertEquals(after, before * 2L)

  test("should report unknown albums consistently and delete all initial owned rows without deleting shared templates"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val unknownId  = AlbumId(buildUuid(index = 999))
      val album      = buildAlbum(index = 1)

      for
        _             <- repository.create(album, buildCover(album))
        missing       <- repository.get(unknownId)
        missingRename <- repository.rename(unknownId, title = changedTitle)
        missingDelete <- repository.delete(unknownId)
        deleted       <- repository.delete(album.id)
        removed       <- repository.get(album.id)
        owned         <- countInitialRows.transact(xa)
        templates     <- sql"SELECT count(*) FROM spread_template".query[Long].unique.transact(xa)
      yield
        assertEquals(missing, GetResult.NotFound)
        assertEquals(missingRename, RenameResult.NotFound)
        assertEquals(missingDelete, DeleteResult.NotFound)
        assertEquals(deleted, DeleteResult.Deleted)
        assertEquals(removed, GetResult.NotFound)
        assertEquals(owned, 0L)
        assertEquals(templates, 99L)

  test("should roll back every album-owned row when a late cover write fails"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val failCover  = sql"""CREATE TRIGGER fail_cover BEFORE INSERT ON cover_slot WHEN NEW.side = 'back'
                           BEGIN SELECT RAISE(ABORT, 'injected cover failure'); END"""
      val album = buildAlbum(index = 1)

      for
        _      <- failCover.update.run.transact(xa)
        result <- repository.create(album, buildCover(album))
        count  <- countInitialRows.transact(xa)
      yield
        assertEquals(result, CreateResult.Failed(Failure.DatabaseFailure))
        assertEquals(count, 0L)

  test("should reject noninitial composition or a foreign cover without writing owned rows"):
    buildTransactor.use: xa =>
      val repository      = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val album           = buildAlbum(index = 1)
      val templateId      = SpreadTemplateId(buildUuid(index = 1))
      val textSlot        = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 11)), SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0))
      val emptyPhotoSlots = Vector.empty[SpreadSlot.Photo]
      val emptyPlacements = Vector.empty[SpreadPlacement]
      val chapterOpening  =
        Spread.ChapterStart(SpreadId(buildUuid(index = 10)), templateId, photoSlots = emptyPhotoSlots, textSlot, placements = emptyPlacements)
      val country      = Country(CountryId(buildUuid(index = 12)), code = "RO", name = "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
      val destination  = Destination(DestinationId(buildUuid(index = 14)), name = "Home", countries = NonEmptyList.one(country))
      val period       = VisitPeriod(firstDay = LocalDate.of(1901, 1, 1), days = 365)
      val emptySpreads = Vector.empty[Spread.PhotosOnly]
      val home         =
        Chapter(
          ChapterId(buildUuid(index = 13)),
          destination,
          period,
          description = "",
          gear = Vector.empty[CameraSelection],
          sourceFolder = "/photos/Home",
          chapterOpening,
          spreads = emptySpreads
        )

      Vector(
        album.copy(chapters = Vector(home)) -> buildCover(album),
        album                               -> buildCover(album).copy(album = AlbumId(buildUuid(index = 999)))
      ).traverse_ { case (invalidAlbum, invalidCover) =>
        for
          result <- repository.create(invalidAlbum, invalidCover)
          count  <- countInitialRows.transact(xa)
        yield
          assertEquals(result, CreateResult.Failed(Failure.DatabaseFailure))
          assertEquals(count, 0L)
      }

  test("should reject incomplete or invalid persisted albums rather than returning partial success"):
    Vector(
      "DELETE FROM cover",
      "DELETE FROM cover_slot WHERE side = 'front'",
      "DELETE FROM cover_slot WHERE side = 'back'",
      "DELETE FROM spread_text_slot",
      "UPDATE album SET year = 1899",
      "UPDATE album SET title = ''",
      "UPDATE album SET authors = 0",
      "UPDATE album SET authors = 8",
      "UPDATE spread_photo_slot SET x = x + 1",
      "UPDATE spread_template SET kind = 'photos_only' WHERE id = '00000000-0000-0000-0000-000000000001'",
      "DELETE FROM spread",
      "UPDATE spread_photo_slot SET width = -1",
      "UPDATE spread_text_slot SET id = '0-0-0-0-1'"
    ).traverse_ { corruption =>
      buildTransactor.use: xa =>
        val repository = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
        val album      = buildAlbum(index = 1)

        for
          _        <- repository.create(album, buildCover(album))
          _        <- (sql"PRAGMA ignore_check_constraints=ON".update.run >> Fragment.const(corruption).update.run).transact(xa)
          found    <- repository.get(album.id)
          listed   <- repository.list(offset = 0, limit = 6)
          before   <- sql"SELECT title FROM album".query[String].unique.transact(xa)
          changed  <- repository.rename(album.id, title = changedTitle)
          synopsis <- repository.saveSynopsis(album.id, text = "Replacement")
          authors  <- repository.saveAuthors(album.id, Author.all)
          after    <- sql"SELECT title FROM album".query[String].unique.transact(xa)
          deleted  <- repository.delete(album.id)
          removed  <- repository.get(album.id)
        yield
          assertEquals(found, GetResult.Failed(Failure.DatabaseFailure))
          assertEquals(listed, ListResult.Failed(Failure.DatabaseFailure))
          assertEquals(changed, RenameResult.Failed(Failure.DatabaseFailure))
          assertEquals(synopsis, SaveSynopsisResult.Failed(Failure.DatabaseFailure))
          assertEquals(authors, SaveAuthorsResult.Failed(Failure.DatabaseFailure))
          assertEquals(after, before)
          assertEquals(deleted, DeleteResult.Deleted)
          assertEquals(removed, GetResult.NotFound)
    }

  test("should report database failures explicitly for every operation"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val album      = buildAlbum(index = 1)

      for
        _       <- sql"DROP TABLE album".update.run.transact(xa)
        listed  <- repository.list(offset = 0, limit = 6)
        created <- repository.create(album, buildCover(album))
        found   <- repository.get(album.id)
        renamed <- repository.rename(album.id, title = changedTitle)
        deleted <- repository.delete(album.id)
      yield
        assertEquals(listed, ListResult.Failed(Failure.DatabaseFailure))
        assertEquals(created, CreateResult.Failed(Failure.DatabaseFailure))
        assertEquals(found, GetResult.Failed(Failure.DatabaseFailure))
        assertEquals(renamed, RenameResult.Failed(Failure.DatabaseFailure))
        assertEquals(deleted, DeleteResult.Failed(Failure.DatabaseFailure))

  test("should preserve persisted photo-slot bleed when reading an album"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val album      = buildAlbum(index = 1)

      for
        _      <- repository.create(album, buildCover(album))
        slotId <- sql"SELECT id FROM spread_photo_slot ORDER BY id LIMIT 1".query[String].unique.transact(xa)
        _      <- sql"INSERT INTO spread_photo_slot_bleed (slot_id, edge) VALUES ($slotId, 'left')".update.run.transact(xa)
        found  <- repository.get(album.id)
      yield
        val expectedSlots = album.opening.photoSlots.map:
          case slot if slot.id.value.toString === slotId => slot.copy(bleed = slot.bleed + SpreadEdge.Left)
          case slot                                      => slot
        val expectedAlbum = album.copy(opening = album.opening.copy(photoSlots = expectedSlots))
        assertEquals(found, GetResult.Found(expectedAlbum))

  test("should read chapter spreads and placements in domain order and cascade deletion through the owned graph"):
    buildTransactor.use: xa =>
      val repository         = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val countryId          = buildUuid(index = 400).toString
      val destinationId      = buildUuid(index = 401).toString
      val chapterId          = buildUuid(index = 402).toString
      val photoId            = buildUuid(index = 403).toString
      val templateId         = buildUuid(index = 404).toString
      val spreadId           = buildUuid(index = 405).toString
      val slotId             = buildUuid(index = 406).toString
      val placementId        = buildUuid(index = 407).toString
      val sessionId          = buildUuid(index = 408).toString
      val albumId            = buildUuid(index = 1).toString
      val photosTemplateId   = buildUuid(index = 409).toString
      val photosSpreadId     = buildUuid(index = 410).toString
      val photosSlotId       = buildUuid(index = 411).toString
      val secondChapterId    = buildUuid(index = 420).toString
      val secondOpeningId    = buildUuid(index = 421).toString
      val secondPhotoSlotId  = buildUuid(index = 422).toString
      val secondTextSlotId   = buildUuid(index = 423).toString
      val insertTemplateSlot = sql"""INSERT INTO spread_template_slot (template_id, kind, x, y, width, height)
                                     VALUES ($templateId, 'photo', 0, 0, 100, 100)"""
      val insertChapterStart = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
                                     VALUES ($spreadId, $templateId, 'chapter_start', $chapterId, 0)"""
      val insertPlacement = sql"""INSERT INTO placement
                                  (id, photo_id, kind, spread_photo_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner)
                                  VALUES ($placementId, $photoId, 'spread', $slotId, 0, 0, 0, 1, 'bottom_left')"""
      val insertPhotosTemplateSlot = sql"""INSERT INTO spread_template_slot (template_id, kind, x, y, width, height)
                                           VALUES ($photosTemplateId, 'photo', 0, 0, 100, 100)"""
      val insertPhotosSpread = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
                                     VALUES ($photosSpreadId, $photosTemplateId, 'photos_only', $chapterId, 1)"""
      val countOwned = sql"""SELECT (SELECT count(*) FROM album) + (SELECT count(*) FROM chapter) +
                             (SELECT count(*) FROM photo) + (SELECT count(*) FROM spread) +
                             (SELECT count(*) FROM placement) + (SELECT count(*) FROM spread_editing_lock) +
                             (SELECT count(*) FROM cover_editing_lock)""".query[Long].unique
      val countShared = sql"""SELECT (SELECT count(*) FROM country), (SELECT count(*) FROM destination),
                              (SELECT count(*) FROM spread_template), (SELECT count(*) FROM editor_session)"""
        .query[(Long, Long, Long, Long)].unique
      val insertSecondChapter =
        sql"""INSERT INTO chapter (id, album_id, destination_id, description, start_ms, end_ms, position, source_folder) VALUES
                                      ($secondChapterId, $albumId, $destinationId, '', 86400000, 172800000, 1, '/photos/second')"""
      val insertSecondOpening = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
                                      VALUES ($secondOpeningId, $templateId, 'chapter_start', $secondChapterId, 0)"""
      val insertSecondPhoto  = sql"""INSERT INTO spread_photo_slot VALUES ($secondPhotoSlotId, $secondOpeningId, 0, 0, 100, 100)"""
      val insertSecondText   = sql"""INSERT INTO spread_text_slot VALUES ($secondTextSlotId, $secondOpeningId, 0, 0, 100, 100)"""
      val insertFirstChapter = sql"""INSERT INTO chapter (id, album_id, destination_id, description, start_ms, end_ms, position, source_folder)
        VALUES ($chapterId, $albumId, $destinationId, '', 0, 86400000, 0, '/photos/coast')"""
      val insertPhoto = sql"""INSERT INTO photo (id, chapter_id, source, source_hash, width, height, thumbnail,
        thumbnail_version, thumbnail_width, thumbnail_height, thumbnail_quality, thumbnail_bytes)
        VALUES ($photoId, $chapterId, 'photo.jpg', 'hash', 100, 100, $photoId, 'version', 100, 100, 'low', 1)"""
      val initialiseChapter =
        for
          _ <- sql"DELETE FROM country WHERE code IN ('IS', 'JP')".update.run
          _ <- sql"INSERT INTO country VALUES ($countryId, 'IS', 'Iceland', '/flags/is.svg')".update.run
          _ <- sql"INSERT INTO destination VALUES ($destinationId, 'Coast')".update.run
          _ <- sql"INSERT INTO destination_country VALUES ($destinationId, $countryId, 0)".update.run
          _ <- sql"INSERT INTO country VALUES (${buildUuid(index = 430).toString}, 'JP', 'Japan', '/flags/jp.svg')".update.run
          _ <- sql"INSERT INTO destination_country VALUES ($destinationId, ${buildUuid(index = 430).toString}, 1)".update.run
          _ <- insertFirstChapter.update.run
          _ <- insertPhoto.update.run
          _ <- sql"INSERT INTO spread_template VALUES ($templateId, 'Chapter', 'chapter_start')".update.run
          _ <- insertTemplateSlot.update.run
          _ <- sql"INSERT INTO spread_template_slot (template_id, kind, x, y, width, height) VALUES ($templateId, 'text', 0, 0, 100, 100)".update.run
          _ <- insertChapterStart.update.run
          _ <- sql"INSERT INTO spread_photo_slot VALUES ($slotId, $spreadId, 0, 0, 100, 100)".update.run
          _ <- sql"INSERT INTO spread_text_slot VALUES (${buildUuid(index = 412).toString}, $spreadId, 0, 0, 100, 100)".update.run
          _ <- insertPlacement.update.run
          _ <- sql"INSERT INTO spread_template VALUES ($photosTemplateId, 'Photos', 'photos_only')".update.run
          _ <- insertPhotosTemplateSlot.update.run
          _ <- insertPhotosSpread.update.run
          _ <- sql"INSERT INTO spread_photo_slot VALUES ($photosSlotId, $photosSpreadId, 0, 0, 100, 100)".update.run
          _ <- sql"INSERT INTO editor_session VALUES ($sessionId, 'owner', 0)".update.run
          _ <- sql"INSERT INTO spread_editing_lock VALUES ($spreadId, $sessionId)".update.run
          _ <- sql"INSERT INTO cover_editing_lock VALUES ($albumId, $sessionId)".update.run
          _ <- insertSecondChapter.update.run
          _ <- insertSecondOpening.update.run
          _ <- insertSecondPhoto.update.run
          _ <- insertSecondText.update.run
        yield ()
      val corruptions = Vector(
        (s"UPDATE chapter SET destination_id = '${buildUuid(index = 499)}'", s"UPDATE chapter SET destination_id = '$destinationId'"),
        (
          s"UPDATE destination_country SET country_id = '${buildUuid(index = 499)}' WHERE position = 0",
          s"UPDATE destination_country SET country_id = '$countryId' WHERE position = 0"
        ),
        (
          "DELETE FROM destination_country",
          s"INSERT INTO destination_country VALUES ('$destinationId', '$countryId', 0), ('$destinationId', '${buildUuid(index = 430)}', 1)"
        ),
        (
          s"UPDATE chapter SET source_folder = 'relative' WHERE id = '$chapterId'",
          s"UPDATE chapter SET source_folder = '/photos/coast' WHERE id = '$chapterId'"
        ),
        (
          s"UPDATE chapter SET source_folder = '/photos/../coast' WHERE id = '$chapterId'",
          s"UPDATE chapter SET source_folder = '/photos/coast' WHERE id = '$chapterId'"
        ),
        ("UPDATE chapter SET position = -1 WHERE position = 0", "UPDATE chapter SET position = 0 WHERE position = -1"),
        (
          "UPDATE spread_template SET kind = 'photos_only' WHERE name = 'Chapter'",
          "UPDATE spread_template SET kind = 'chapter_start' WHERE name = 'Chapter'"
        ),
        (
          "UPDATE spread SET kind = 'photos_only', position = 2 WHERE position = 0 AND chapter_id IS NOT NULL",
          "UPDATE spread SET kind = 'chapter_start', position = 0 WHERE position = 2 AND chapter_id IS NOT NULL"
        )
      )
      def rejectCorruptions(album: Album) =
        corruptions.traverse_ { case (corruption, repair) =>
          val corrupt = sql"PRAGMA foreign_keys = OFF".update.run >> Fragment.const(corruption).update.run >>
            sql"PRAGMA foreign_keys = ON".update.run

          for
            _       <- xa.rawTrans.apply(corrupt)
            invalid <- repository.get(album.id)
            _       <- Fragment.const(repair).update.run.transact(xa)
          yield assertEquals(invalid, GetResult.Failed(Failure.DatabaseFailure))
        }
      val album           = buildAlbum(index = 1)
      val selectedAuthors = Set(Author.Mara).refineUnsafe[MinLength[1]]

      for
        _             <- repository.create(album, buildCover(album))
        _             <- initialiseChapter.transact(xa)
        found         <- repository.get(album.id)
        renamed       <- repository.rename(album.id, title = changedTitle)
        reloaded      <- repository.get(album.id)
        _             <- rejectCorruptions(album)
        savedSynopsis <- repository.saveSynopsis(album.id, text = "Retained references\nȘ")
        savedAuthors  <- repository.saveAuthors(album.id, selectedAuthors)
        deleted       <- repository.delete(album.id)
        owned         <- countOwned.transact(xa)
        shared        <- countShared.transact(xa)
      yield
        val box                 = SlotBox(x = 0.0.refineUnsafe, y = 0.0.refineUnsafe, width = 100.0.refineUnsafe, height = 100.0.refineUnsafe)
        val expectedSlot        = SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index = 406)), box, bleed = Set.empty[SpreadEdge])
        val transform           = PhotoTransform(Shift(x = 0.0.refineUnsafe, y = 0.0.refineUnsafe), mirrored = false, zoom = 1.0.refineUnsafe)
        val expectedPlacementId = SpreadPlacementId(buildUuid(index = 407))
        val expectedPhotoId     = PhotoId(buildUuid(index = 403))
        val expectedPlacement   = SpreadPlacement(expectedPlacementId, expectedSlot.id, expectedPhotoId, transform, CaptionCorner.BottomLeft)
        val expectedTemplateId  = SpreadTemplateId(buildUuid(index = 404))
        val expectedText        = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 412)), box)
        val expectedPlacements  = Vector(expectedPlacement)
        val expectedChapter     =
          Spread.ChapterStart(
            SpreadId(buildUuid(index = 405)),
            expectedTemplateId,
            photoSlots = Vector(expectedSlot),
            expectedText,
            placements = expectedPlacements
          )
        val expectedPhotosSlot       = SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index = 411)), box, bleed = Set.empty[SpreadEdge])
        val expectedPhotosTemplateId = SpreadTemplateId(buildUuid(index = 409))
        val emptyPlacements          = Vector.empty[SpreadPlacement]
        val expectedPhotoSlots       = NonEmptyList.one(expectedPhotosSlot)
        val expectedPhotos           =
          Spread.PhotosOnly(SpreadId(buildUuid(index = 410)), expectedPhotosTemplateId, photoSlots = expectedPhotoSlots, placements = emptyPlacements)
        val expectedCountry = Country(CountryId(buildUuid(index = 400)), code = "IS", name = "Iceland", AbsoluteLocation(Path.of("/flags/is.svg")))
        val japan           = Country(CountryId(buildUuid(index = 430)), code = "JP", name = "Japan", AbsoluteLocation(Path.of("/flags/jp.svg")))
        val destination     =
          Destination(DestinationId(buildUuid(index = 401)), name = "Coast", countries = NonEmptyList.of(expectedCountry, japan))
        val period                    = VisitPeriod(firstDay = LocalDate.of(1970, 1, 1), days = 1)
        val expectedSpreads           = Vector(expectedPhotos)
        val expectedChapterId         = ChapterId(buildUuid(index = 402))
        val sourceFolder: PhotoSource = "/photos/coast"
        val chapter                   =
          Chapter(
            expectedChapterId,
            destination,
            period,
            description = "",
            gear = Vector.empty[CameraSelection],
            sourceFolder,
            expectedChapter,
            spreads = expectedSpreads
          )
        val secondPhotoSlot = expectedSlot.copy(id = PhotoSpreadSlotId(buildUuid(index = 422)))
        val secondTextSlot  = expectedChapter.textSlot.copy(id = TextSpreadSlotId(buildUuid(index = 423)))
        val secondSpreadId  = SpreadId(buildUuid(index = 421))
        val secondOpening   =
          expectedChapter.copy(id = secondSpreadId, photoSlots = Vector(secondPhotoSlot), textSlot = secondTextSlot, placements = emptyPlacements)
        val expectedSecondChapterId   = ChapterId(buildUuid(index = 420))
        val secondPeriod              = VisitPeriod(firstDay = LocalDate.of(1970, 1, 2), days = 1)
        val emptySpreads              = Vector.empty[Spread.PhotosOnly]
        val secondFolder: PhotoSource = "/photos/second"
        val secondChapter             =
          chapter.copy(
            id = expectedSecondChapterId,
            period = secondPeriod,
            sourceFolder = secondFolder,
            opening = secondOpening,
            spreads = emptySpreads
          )
        val expectedAlbum    = album.copy(chapters = Vector(chapter, secondChapter))
        val expectedSynopsis = expectedAlbum.copy(title = changedTitle, synopsis = "Retained references\nȘ")
        val expectedAuthors  = expectedSynopsis.copy(authors = selectedAuthors)
        assertEquals(found, GetResult.Found(expectedAlbum))
        assertEquals(renamed, RenameResult.Renamed(expectedAlbum.copy(title = changedTitle)))
        assertEquals(reloaded, GetResult.Found(expectedAlbum.copy(title = changedTitle)))
        assertEquals(savedSynopsis, SaveSynopsisResult.Saved(expectedSynopsis))
        assertEquals(savedAuthors, SaveAuthorsResult.Saved(expectedAuthors))
        assertEquals(expectedAlbum.collectCountries, Vector(expectedCountry, japan))
        assertEquals(deleted, DeleteResult.Deleted)
        assertEquals(owned, 0L)
        assertEquals(shared, (249L, 1L, 101L, 1L))

  test("should preserve the complete aggregate through all nonempty author combinations and Unicode synopsis clearing"):
    buildTransactor.use: xa =>
      val repository             = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val album                  = buildAlbum(index = 1)
      val text                   = "Ș 日本語\n🙂"
      val combinations           = Author.all.subsets().filter(_.nonEmpty).toVector.map(_.refineUnsafe[MinLength[1]])
      val saveAuthorCombinations = combinations.traverse_ { authors =>
        val expected = album.copy(synopsis = text, authors = authors)

        for
          result <- repository.saveAuthors(album.id, authors)
          found  <- repository.get(album.id)
        yield
          assertEquals(result, SaveAuthorsResult.Saved(expected))
          assertEquals(found, GetResult.Found(expected))
      }

      for
        _               <- repository.create(album, buildCover(album))
        saved           <- repository.saveSynopsis(album.id, text = text)
        _               <- saveAuthorCombinations
        _               <- repository.saveAuthors(album.id, Author.all)
        cleared         <- repository.saveSynopsis(album.id, text = "")
        unknownSynopsis <- repository.saveSynopsis(AlbumId(buildUuid(index = 999)), text = text)
        unknownAuthors  <- repository.saveAuthors(AlbumId(buildUuid(index = 999)), Author.all)
      yield
        assertEquals(saved, SaveSynopsisResult.Saved(album.copy(synopsis = text)))
        assertEquals(cleared, SaveSynopsisResult.Saved(album))
        assertEquals(unknownSynopsis, SaveSynopsisResult.NotFound)
        assertEquals(unknownAuthors, SaveAuthorsResult.NotFound)

  test("should reject field writes atomically without treating known rejection as uncertain"):
    Vector("RAISE(ABORT, 'rejected')", "RAISE(IGNORE)").traverse_ { rejection =>
      buildTransactor.use: xa =>
        val repository = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
        val album      = buildAlbum(index = 1)
        val reject     = Fragment.const(s"CREATE TRIGGER reject_album BEFORE UPDATE ON album BEGIN SELECT $rejection; END")

        for
          _        <- repository.create(album, buildCover(album))
          _        <- reject.update.run.transact(xa)
          synopsis <- repository.saveSynopsis(album.id, text = "changed")
          authors  <- repository.saveAuthors(album.id, Set(Author.Mara).refineUnsafe[MinLength[1]])
          renamed  <- repository.rename(album.id, title = changedTitle)
          found    <- repository.get(album.id)
        yield
          assertEquals(synopsis, SaveSynopsisResult.Failed(Failure.DatabaseFailure))
          assertEquals(authors, SaveAuthorsResult.Failed(Failure.DatabaseFailure))
          assertEquals(renamed, RenameResult.Failed(Failure.DatabaseFailure))
          assertEquals(found, GetResult.Found(album))
    }

  test("should reject empty domain authors and invalid stored representations without changing retained data"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val album      = buildAlbum(index = 1)
      val invalid    = Vector("0", "8", "'Cosmin'", "1.5")

      for
        _        <- repository.create(album, buildCover(album))
        failures <- invalid.traverse(value => (fr"UPDATE album SET authors = " ++ Fragment.const(value)).update.run.transact(xa).attempt)
        retained <- repository.get(album.id)
      yield
        assert(Set.empty[Author].refineEither[MinLength[1]].isLeft)
        assert(failures.forall(_.isLeft))
        assertEquals(retained, GetResult.Found(album))

  test("should retain authored album content and composition across connection reopening"):
    val acquireDatabase = IO.blocking(Files.createTempFile(Path.of("target"), "album-history-", ".sqlite"))
    val database        = Resource.make(acquireDatabase)(path => IO.blocking(Files.deleteIfExists(path)).void)
      .evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void)

    database.use: path =>
      val original    = buildAlbum(index = 1).copy(year = 2025, synopsis = "Retained Ș\n日本")
      val later       = buildAlbum(index = 2).copy(year = 2026, synopsis = original.synopsis)
      val albums      = Vector(original, later)
      val selected    = Set(Author.Iacob).refineUnsafe[MinLength[1]]
      val saveAuthors = buildConnection(path).use: xa =>
        val repository = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)

        for
          _       <- albums.traverse_(album => repository.create(album, buildCover(album)))
          first   <- repository.get(original.id)
          current <- repository.get(later.id)
          saved   <- repository.saveAuthors(original.id, selected)
        yield
          assertEquals(first, GetResult.Found(original))
          assertEquals(current, GetResult.Found(later))
          assertEquals(saved, SaveAuthorsResult.Saved(original.copy(authors = selected)))
      val verifyRestartRetention = buildConnection(path).use: xa =>
        val repository = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)

        for
          retained <- repository.get(original.id)
          renamed  <- repository.rename(original.id, title = changedTitle)
          cleared  <- repository.saveSynopsis(original.id, text = "")
          deleted  <- repository.delete(original.id)
        yield
          assertEquals(retained, GetResult.Found(original.copy(authors = selected)))
          assertEquals(renamed, RenameResult.Renamed(original.copy(title = changedTitle, authors = selected)))
          assertEquals(cleared, SaveSynopsisResult.Saved(original.copy(title = changedTitle, synopsis = "", authors = selected)))
          assertEquals(deleted, DeleteResult.Deleted)

      saveAuthors >> verifyRestartRetention

  test("should report lost commit acknowledgement as uncertain without replaying the field mutation"):
    val acknowledgements = Vector[Throwable](
      new IllegalStateException("Lost acknowledgement"),
      new SQLiteException("Lost acknowledgement", SQLiteErrorCode.SQLITE_CONSTRAINT)
    )

    acknowledgements.traverse_ { error =>
      buildTransactor.use: xa =>
        val repository  = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
        val album       = buildAlbum(index = 1)
        val interrupted = Transactor.after.set(xa, xa.strategy.after >> error.raiseError[ConnectionIO, Unit])
        val uncertain   = SqliteAlbumRepository.make[IO](interrupted, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
        val selected    = Set(Author.Mara).refineUnsafe[MinLength[1]]

        for
          _        <- repository.create(album, buildCover(album))
          synopsis <- uncertain.saveSynopsis(album.id, text = "Saved once")
          authors  <- uncertain.saveAuthors(album.id, selected)
          renamed  <- uncertain.rename(album.id, title = changedTitle)
          found    <- repository.get(album.id)
        yield
          assertEquals(synopsis, SaveSynopsisResult.Uncertain)
          assertEquals(authors, SaveAuthorsResult.Uncertain)
          assertEquals(renamed, RenameResult.Failed(Failure.DatabaseFailure))
          assertEquals(found, GetResult.Found(album.copy(title = changedTitle, synopsis = "Saved once", authors = selected)))
    }

  private def buildConnection(path: Path) =
    SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor

  private def buildTransactor =
    val acquireDatabase = IO.blocking(Files.createTempFile(Path.of("target"), "album-", ".sqlite"))

    for
      path <- Resource.make(acquireDatabase)(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa

  private def buildAlbum(index: Int) =
    val albumId  = AlbumId(buildUuid(index = index))
    val spreadId = SpreadId(buildUuid(index = index + 100))
    val boxes    = Vector(
      SlotBox(x = 0.293982, y = 0.914609, width = 24.695519, height = 48.758744),
      SlotBox(x = 25.199487, y = 0.914609, width = 24.695519, height = 48.758744),
      SlotBox(x = 0.293982, y = 50.326646, width = 24.695519, height = 48.758744),
      SlotBox(x = 25.199487, y = 50.326646, width = 24.695519, height = 48.758744)
    )
    val photos = boxes.zipWithIndex.map { case (box, position) =>
      SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index = index * 1000 + position)), box, bleed = Set.empty[SpreadEdge])
    }
    val textBox         = SlotBox(x = 50.104993, y = 0.914609, width = 49.601025, height = 98.170781)
    val text            = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = index * 1000 + 4)), textBox)
    val emptyPlacements = Vector.empty[SpreadPlacement]
    val start           =
      Spread.AlbumStart(spreadId, SpreadTemplateId(buildUuid(index = 1)), photoSlots = photos, textSlot = text, placements = emptyPlacements)
    val albumCreatedAt = if index === 1 then createdAt.plusSeconds(1) else createdAt
    val year           = (1900 + index).refineUnsafe[AlbumYearConstraint]
    Album(albumId, year, title = "My photographs", synopsis = "", Author.all, albumCreatedAt, start, chapters = Vector.empty[Chapter])

  private def buildCover(album: Album) =
    val index = album.id.value.getLeastSignificantBits.toInt
    val front = CoverSlot(CoverSlotId(buildUuid(index = index + 200)))
    val back  = CoverSlot(CoverSlotId(buildUuid(index = index + 300)))
    Cover(album.id, SpineOwner.Front, front, back)

  private def buildUuid(index: Int) = new UUID(0L, index.toLong)
