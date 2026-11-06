package photography.adapters.sqlite.spread
import photography.adapters.sqlite.album.SqliteAlbumCompositionRepository

import photography.adapters.sqlite.FlywayMigrator
import photography.adapters.sqlite.SqliteConnection
import photography.adapters.sqlite.album.SqliteAlbumRepository
import photography.adapters.sqlite.chapter.SqliteChapterRepository

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.sqlite.SqliteCodecs.given
import photography.app.config.SqliteConfig
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.album.AlbumRepository
import photography.ports.album.AlbumCompositionRepository.{SpreadResult, Located}
import photography.ports.chapter.ChapterRepository
import photography.ports.spread.SpreadTemplateRepository

import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate}
import java.util.UUID

final class SqliteSpreadRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given logger: Logger[IO]   = Slf4jLogger.getLogger[IO]
  private given Logger[ConnectionIO] = Slf4jLogger.getLogger[ConnectionIO]

  private val albumId       = AlbumId(new UUID(1L, 1L))
  private val chapterId     = ChapterId(new UUID(1L, 2L))
  private val photoId       = PhotoId(new UUID(77L, 1L))
  private val placementId   = SpreadPlacementId(new UUID(78L, 1L))
  private val defaultLayout = SpreadTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000113"))
  private val singleLayout  = SpreadTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000144"))
  private val identity      = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)
  private val country       = Country(CountryId(new UUID(9L, 1L)), code = "XX", name = "Test country", AbsoluteLocation(Path.of("/flags/xx.svg")))
  private val spreads       = SqliteSpreadRepository.make
  private val composition   = SqliteAlbumCompositionRepository.make
  private val spreadRows    = sql"SELECT count(*) FROM spread".query[Long].unique
  private val photoRows     = sql"SELECT count(*) FROM photo".query[Long].unique
  private val placementRows = sql"SELECT count(*) FROM placement".query[Long].unique
  private val chapterOrder  = sql"SELECT position FROM spread WHERE chapter_id = ${chapterId.value} ORDER BY position".query[Int].to[List]

  test("should persist insert, reorder, template replacement and removal with placement cascades"):
    buildTransactor.use: xa =>
      val cause       = new IllegalStateException("Lost commit acknowledgement")
      val interrupted = Transactor.after.set(xa, xa.strategy.after >> cause.raiseError[ConnectionIO, Unit])
      for
        opening       <- seedChapter(xa)
        albumTemplate <- loadAlbumTemplate(xa)
        albumOpening = buildAlbum(albumTemplate).opening
        _        <- spreads.replace(albumOpening).transact(xa)
        _        <- spreads.replace(opening).transact(xa)
        photo    <- buildPhotoSpread(xa, index = 5L, layout = defaultLayout)
        _        <- (spreads.insert(chapterId, photo) >> spreads.reorder(chapterId, order = NonEmptyList.of(opening.id, photo.id))).transact(xa)
        _        <- placePhoto(xa, photo.photoSlots.head.id, index = 1L)
        before   <- readSpreads(xa)
        replaced <- retemplate(xa, photo)
        _        <- spreads.replace(replaced).transact(xa)
        after    <- readSpreads(xa)
        _        <- sql"""CREATE TRIGGER protect_geometry BEFORE DELETE ON spread_photo_slot BEGIN
          SELECT RAISE(ABORT, 'Placement edits cannot rebuild geometry'); END""".update.run.transact(xa)
        _ <- CaptionCorner.values.toVector.traverse_ { corner =>
          val changed = replaced.copy(placements = replaced.placements.map(_.copy(captionCorner = corner)))
          (spreads.replacePlacements(changed.id, changed.placements) >> composition.findSpread(albumId, changed.id)).transact(xa).map(actual =>
            assertEquals(actual, SpreadResult.Found(Located.Photo(chapterId, changed)))
          )
        }
        _ <- spreads.replacePlacements(replaced.id, placements = replaced.placements).transact(xa)
        _ <- sql"""CREATE TRIGGER protect_placements BEFORE DELETE ON placement BEGIN
          SELECT RAISE(ABORT, 'Bleed edits cannot rewrite placements'); END""".update.run.transact(xa)
        _ <- Vector(true, false).traverse_ { enabled =>
          val changed = replaced.copy(photoSlots = replaced.photoSlots.map(_.copy(bleed = if enabled then SpreadEdge.values.toSet else Set.empty)))
          val edges   = changed.photos.map(slot => slot.id -> slot.bleed).toMap
          (spreads.replaceBleed(changed.id, edges) >> composition.findSpread(albumId, changed.id)).transact(xa).map(actual =>
            assertEquals(actual, SpreadResult.Found(Located.Photo(chapterId, changed)))
          )
        }
        _               <- (sql"DROP TRIGGER protect_placements".update.run >> sql"DROP TRIGGER protect_geometry".update.run).transact(xa)
        _               <- spreads.remove(photo.id).transact(xa)
        removed         <- readSpreads(xa)
        photos          <- photoRows.transact(xa)
        placements      <- placementRows.transact(xa)
        uncertainInsert <- (spreads.insert(chapterId, photo) >> spreads.reorder(chapterId, NonEmptyList.of(opening.id, photo.id)))
          .transact(interrupted).attempt
        inserted         <- readSpreads(xa)
        uncertainReplace <- spreads.replace(replaced).transact(interrupted).attempt
        committed        <- readSpreads(xa)
        uncertainRemove  <- spreads.remove(photo.id).transact(interrupted).attempt
        finalSpreads     <- readSpreads(xa)
      yield
        val beforeSpreads = before
        val afterSpreads  = after
        assertEquals(beforeSpreads.map(_.placements.map(placed => (placed.id, placed.photo))), Vector(Vector((placementId, photoId))))
        assertEquals(afterSpreads.map(_.template), Vector(singleLayout))
        assertEquals(afterSpreads.flatMap(_.placements).map(_.transform), Vector(identity))
        assertEquals(afterSpreads.flatMap(_.placements).map(_.slot), afterSpreads.flatMap(_.photoSlots.toList).map(_.id))
        val commitFailures = Vector(uncertainInsert.swap.toOption, uncertainReplace.swap.toOption, uncertainRemove.swap.toOption)

        assertEquals(removed, Vector.empty[Spread.PhotosOnly])
        assertEquals(photos, 1L)
        assertEquals(placements, 0L)
        assertEquals(commitFailures, Vector.fill(3)(cause.some))
        assertEquals(inserted, Vector(photo))
        assertEquals(committed, Vector(replaced))
        assertEquals(finalSpreads, Vector.empty[Spread.PhotosOnly])

  test("should roll back every spread write of a failed action and reject incomplete chapter orders"):
    buildTransactor.use: xa =>
      val failure = new IllegalStateException("Abort composed action")
      for
        opening    <- seedChapter(xa)
        committed  <- buildPhotoSpread(xa, index = 6L, layout = defaultLayout)
        second     <- buildPhotoSpread(xa, index = 7L, layout = defaultLayout)
        _          <- spreads.insert(chapterId, committed).transact(xa)
        failed     <- (spreads.insert(chapterId, second) >> failure.raiseError[ConnectionIO, Unit]).transact(xa).attempt
        rows       <- spreadRows.transact(xa)
        incomplete <- spreads.reorder(chapterId, order = NonEmptyList.one(opening.id)).transact(xa).attempt
        positions  <- chapterOrder.transact(xa)
      yield
        assert(failed.isLeft)
        assertEquals(rows, 3L)
        assert(incomplete.isLeft)
        assertEquals(positions, List(0, 1))

  test("should roll back a destructive replacement when its final placement insert fails"):
    buildTransactor.use: xa =>
      for
        _           <- seedChapter(xa)
        photo       <- buildPhotoSpread(xa, index = 21L, layout = defaultLayout)
        _           <- spreads.insert(chapterId, photo).transact(xa)
        _           <- placePhoto(xa, photo.photoSlots.head.id, index = 1L)
        maybeBefore <- composition.findSpread(albumId, photo.id).transact(xa)
        replacement <- retemplate(xa, photo)
        broken = replacement.copy(placements = replacement.placements.map(_.copy(photo = PhotoId(new UUID(99L, 99L)))))
        failed       <- spreads.replace(broken).transact(xa).attempt
        partial      <- spreads.replacePlacements(photo.id, placements = broken.placements).transact(xa).attempt
        invalidBleed <- spreads.replaceBleed(photo.id, edges = Map.empty[PhotoSpreadSlotId, Set[SpreadEdge]]).transact(xa).attempt
        absent       <- spreads.replacePlacements(SpreadId(new UUID(99L, 99L)), placements = Vector.empty[SpreadPlacement]).transact(xa).attempt
        maybeAfter   <- composition.findSpread(albumId, photo.id).transact(xa)
      yield
        assert(failed.isLeft)
        assert(partial.isLeft)
        assert(invalidBleed.isLeft)
        assert(absent.isLeft)
        assertEquals(maybeAfter, maybeBefore)

  test("should distinguish absent chapter ownership from malformed persisted chapter ordering"):
    buildTransactor.use: xa =>
      val foreignAlbum = AlbumId(new UUID(12L, 1L))

      for
        opening      <- seedChapter(xa)
        maybeForeign <- composition.findChapterOrder(foreignAlbum, chapterId).transact(xa)
        _            <- sql"DELETE FROM spread WHERE id = ${opening.id.value}".update.run.transact(xa)
        malformed    <- composition.findChapterOrder(albumId, chapterId).transact(xa).attempt
      yield
        assertEquals(maybeForeign, none[NonEmptyList[SpreadId]])
        assert(malformed.isLeft)

  private def readSpreads(xa: Transactor[IO]) =
    composition.findChapterOrder(albumId, chapterId).flatMap:
      case Some(order) => order.tail.traverse { id =>
          composition.findSpread(albumId, id).map:
            case SpreadResult.Found(Located.Photo(_, value)) => value
            case other                                       => fail(s"Expected saved photo spread, got $other")
        }.map(_.toVector)
      case None => fail("Missing chapter order")
    .transact(xa)

  private def seedChapter(xa: Transactor[IO]) =
    val insertCountry = sql"INSERT INTO country VALUES (${country.id.value}, 'XX', 'Test country', '/flags/xx.svg')".update.run.transact(xa)
    val repository    = SqliteAlbumRepository.make[IO](xa)
    val cover         = Cover(albumId, SpineOwner.Front, CoverSlot(CoverSlotId(new UUID(1L, 12L))), CoverSlot(CoverSlotId(new UUID(1L, 13L))))
    for
      _            <- insertCountry
      albumStart   <- loadAlbumTemplate(xa)
      chapterStart <- loadChapterTemplate(xa)
      created      <- repository.create(buildAlbum(albumStart), cover)
      _            <- IO(assertEquals(created, AlbumRepository.CreateResult.Created))
      opening    = buildOpening(chapterStart)
      newChapter = ChapterRepository.NewChapter(
        chapterId,
        name = "Trip",
        countries = NonEmptyList.one(country.id),
        VisitPeriod(firstDay = LocalDate.of(2026, 6, 1), days = 3L),
        description = "Retained description",
        sourceFolder = "/photos/trip",
        opening
      )
      _ <- SqliteChapterRepository.make.create(albumId, newChapter, order = NonEmptyList.one(chapterId)).transact(xa)
    yield opening

  private def loadAlbumTemplate(xa: Transactor[IO]) =
    SqliteSpreadTemplateRepository.make[IO](xa).listAlbumStartTemplates.flatMap:
      case SpreadTemplateRepository.ListAlbumStartTemplatesResult.Listed(values) => values.head.pure[IO]
      case other => IO.raiseError[SpreadTemplate.AlbumStart](new IllegalStateException(s"Missing album templates: $other"))

  private def loadChapterTemplate(xa: Transactor[IO]) =
    SqliteSpreadTemplateRepository.make[IO](xa).listChapterStartTemplates.flatMap:
      case SpreadTemplateRepository.ListChapterStartTemplatesResult.Listed(values) => values.head.pure[IO]
      case other => IO.raiseError[SpreadTemplate.ChapterStart](new IllegalStateException(s"Missing chapter templates: $other"))

  private def buildAlbum(template: SpreadTemplate.AlbumStart) =
    val photos = template.photoSlots.zipWithIndex.map { case (box, index) =>
      SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(1L, 100L + index.toLong)), box, bleed = Set.empty[SpreadEdge])
    }
    val opening = Spread.AlbumStart(
      SpreadId(new UUID(1L, 10L)),
      template.id,
      photoSlots = photos,
      textSlot = SpreadSlot.Text(TextSpreadSlotId(new UUID(1L, 11L)), template.textSlot),
      placements = Vector.empty[SpreadPlacement]
    )
    Album(albumId, year = 2026, title = "Album", synopsis = "", authors = Author.all, Instant.EPOCH, opening, chapters = Vector.empty[Chapter])

  private def buildOpening(template: SpreadTemplate.ChapterStart) =
    val photos = template.photoSlots.zipWithIndex.map { case (box, index) =>
      SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(2L, 100L + index.toLong)), box, bleed = Set.empty[SpreadEdge])
    }
    Spread.ChapterStart(
      SpreadId(new UUID(2L, 10L)),
      template.id,
      photoSlots = photos,
      textSlot = SpreadSlot.Text(TextSpreadSlotId(new UUID(2L, 11L)), template.textSlot),
      placements = Vector.empty[SpreadPlacement]
    )

  private def buildPhotoSpread(xa: Transactor[IO], index: Long, layout: SpreadTemplateId) =
    SqliteSpreadTemplateRepository.make[IO](xa).listPhotosOnlyTemplates.flatMap:
      case SpreadTemplateRepository.ListPhotosOnlyTemplatesResult.Listed(values) =>
        values.find(_.id.value === layout.value) match
          case Some(template) =>
            val slots = template.photoSlots.zipWithIndex.map { case (box, position) =>
              SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(index, position.toLong)), box, bleed = Set.empty[SpreadEdge])
            }
            Spread.PhotosOnly(SpreadId(new UUID(index, 100L)), template.id, photoSlots = slots, placements = Vector.empty[SpreadPlacement]).pure[IO]
          case None => IO.raiseError[Spread.PhotosOnly](new IllegalStateException(s"Missing photo layout $layout"))
      case other => IO.raiseError[Spread.PhotosOnly](new IllegalStateException(s"Missing photo layouts: $other"))

  private def retemplate(xa: Transactor[IO], photo: Spread.PhotosOnly) =
    SqliteSpreadTemplateRepository.make[IO](xa).listPhotosOnlyTemplates.flatMap:
      case SpreadTemplateRepository.ListPhotosOnlyTemplatesResult.Listed(values) =>
        values.find(_.id.value === singleLayout.value) match
          case Some(template) =>
            val slot = SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(5L, 900L)), template.photoSlots.head, bleed = SpreadEdge.values.toSet)
            val kept = SpreadPlacement(placementId, slot.id, photoId, identity, CaptionCorner.TopRight)
            Spread.PhotosOnly(photo.id, singleLayout, photoSlots = NonEmptyList.one(slot), placements = Vector(kept)).pure[IO]
          case None => IO.raiseError[Spread.PhotosOnly](new IllegalStateException("Missing single layout"))
      case other => IO.raiseError[Spread.PhotosOnly](new IllegalStateException(s"Missing photo layouts: $other"))

  private def placePhoto(xa: Transactor[IO], slot: PhotoSpreadSlotId, index: Long) =
    val photo       = buildPhotoId(index).value
    val placement   = buildPlacementId(index).value
    val source      = s"$index.jpg"
    val thumbnail   = new UUID(88L, index).toString
    val insertPhoto = sql"""INSERT INTO photo (id, chapter_id, source, source_hash, thumbnail, thumbnail_version, thumbnail_width, thumbnail_height,
                           thumbnail_quality, thumbnail_bytes, width, height)
                           VALUES ($photo, ${chapterId.value}, $source, 'hash', $thumbnail, 'v1', 10, 10, 'standard', 10, 10, 10)"""
    val insertPlace =
      sql"""INSERT INTO placement (id, photo_id, kind, spread_photo_slot_id, cover_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner)
                           VALUES ($placement, $photo, 'spread', ${slot.value}, NULL, 3.0, 4.0, 1, 2.0, 'top_right')"""
    (insertPhoto.update.run >> insertPlace.update.run).transact(xa).void

  private def buildPhotoId(index: Long)     = PhotoId(new UUID(77L, index))
  private def buildPlacementId(index: Long) = SpreadPlacementId(new UUID(78L, index))

  private def buildTransactor =
    val acquireDatabase = IO.blocking(Files.createTempFile(Path.of("target"), "spread-", ".sqlite"))
    for
      path <- Resource.make(acquireDatabase)(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa
