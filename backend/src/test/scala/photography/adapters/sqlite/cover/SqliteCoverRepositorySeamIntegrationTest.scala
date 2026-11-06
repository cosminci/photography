package photography.adapters.sqlite.cover

import cats.effect.{IO, Resource}
import cats.data.NonEmptyVector
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.{ConnectionIO, Transactor}
import photography.adapters.sqlite.{FlywayMigrator, SqliteConnection}
import photography.adapters.sqlite.SqliteCodecs.given
import photography.app.config.SqliteConfig
import photography.domain.album.{AlbumId, PhotoId}
import photography.domain.layout.*
import photography.ports.cover.CoverRepository.{Stored, ReadResult, ReplaceResult, SetSpineOwnerResult}
import photography.syntax.SyncSyntax.*
import java.nio.file.{Files, Path}
import java.util.UUID

class SqliteCoverRepositorySeamIntegrationTest extends CatsEffectSuite:
  private val album = AlbumId(new UUID(0L, 1L))
  private val cover = Cover(
    album,
    SpineOwner.Back,
    CoverSlot(CoverSlotId(new UUID(0L, 2L))),
    CoverSlot(CoverSlotId(new UUID(0L, 3L)))
  )
  private val firstPhoto   = PhotoId(new UUID(0L, 4L))
  private val secondPhoto  = PhotoId(new UUID(0L, 5L))
  private val initial      = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)
  private val adjusted     = PhotoTransform(Shift(x = 12.5, y = -7.25), mirrored = true, zoom = 3.0)
  private val first        = CoverPlacement(CoverPlacementId(new UUID(0L, 6L)), cover.front.id, firstPhoto, adjusted)
  private val second       = CoverPlacement(CoverPlacementId(new UUID(0L, 7L)), cover.back.id, secondPhoto, initial)
  private val foreignAlbum = AlbumId(new UUID(0L, 8L))
  private val foreignCover = Cover(
    foreignAlbum,
    SpineOwner.Front,
    CoverSlot(CoverSlotId(new UUID(0L, 9L))),
    CoverSlot(CoverSlotId(new UUID(0L, 10L)))
  )
  private val opening           = SpreadId(new UUID(0L, 20L))
  private val openingSlot       = PhotoSpreadSlotId(new UUID(0L, 21L))
  private val specification     = CoverSpecification.from(pageLimit = 30, width = 10382, height = 3402, spine = 142).fold(fail(_), identity)
  private val nextSpecification = CoverSpecification.from(pageLimit = 36, width = 10397, height = 3402, spine = 154).fold(fail(_), identity)
  private val specifications    = NonEmptyVector.of(specification, nextSpecification)

  test("should atomically save, swap and release cover placements while retaining cover structure"):
    val absent   = AlbumId(new UUID(0L, 99L))
    val database = Resource.make(Files.createTempFile(Path.of("target"), "cover-placement-", ".sqlite").blocking[IO]): path =>
      Files.deleteIfExists(path).blocking[IO].void
    val swapped      = Vector(first.copy(slot = cover.back.id), second.copy(slot = cover.front.id))
    val invalidSlots = Vector(CoverSlotId(new UUID(0L, 98L)), foreignCover.front.id)

    database.evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void).use: path =>
      SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor.use: xa =>
        val repository          = SqliteCoverRepository.make
        val invalidReplacements = invalidSlots.traverse: slot =>
          val replacement = Vector(first.copy(slot = slot))
          for
            result  <- repository.replace(album, placements = replacement).transact(xa).attempt
            current <- repository.read(album).transact(xa)
            foreign <- repository.read(foreignAlbum).transact(xa)
          yield (result.isLeft, current, foreign)
        val expectedForeign =
          ReadResult.Found(Stored(foreignCover, placements = Vector.empty[CoverPlacement], interiorSpreads = 0, specifications = specifications))
        val expectedMoved = ReadResult.Found(Stored(cover, placements = swapped, interiorSpreads = 1, specifications = specifications))
        val expectedEmpty =
          ReadResult.Found(Stored(cover, placements = Vector.empty[CoverPlacement], interiorSpreads = 1, specifications = specifications))
        val expectedSaved = ReadResult.Found(Stored(cover, placements = Vector(first, second), interiorSpreads = 1, specifications = specifications))
        val cause         = new IllegalStateException("Lost commit acknowledgement")
        val interrupted   = Transactor.after.set(xa, xa.strategy.after >> cause.raiseError[ConnectionIO, Unit])

        for
          _                  <- seedComposition.transact(xa)
          empty              <- repository.read(album).transact(xa)
          missing            <- repository.read(absent).transact(xa)
          missingReplacement <- repository.replace(absent, placements = Vector.empty[CoverPlacement]).transact(xa)
          firstSave          <- repository.replace(album, placements = Vector(first, second)).transact(xa)
          saved              <- repository.read(album).transact(xa)
          swapSave           <- repository.replace(album, placements = swapped).transact(xa)
          moved              <- repository.read(album).transact(xa)
          failed             <- repository.replace(album, placements = Vector(first, second.copy(photo = firstPhoto))).transact(xa).attempt
          retained           <- repository.read(album).transact(xa)
          rejectedSlots      <- invalidReplacements
          uncertain          <- repository.replace(album, placements = Vector(first)).transact(interrupted).attempt
          committed          <- repository.read(album).transact(xa)
          release            <- repository.replace(album, placements = Vector.empty[CoverPlacement]).transact(xa)
          removed            <- repository.read(album).transact(xa)
          title              <- sql"SELECT title FROM album WHERE id = ${album.value}".query[String].unique.transact(xa)
        yield
          assertEquals(empty, expectedEmpty)
          assertEquals(missing, ReadResult.NotFound)
          assertEquals(missingReplacement, ReplaceResult.NotFound)
          assertEquals(firstSave, ReplaceResult.Replaced)
          assertEquals(swapSave, ReplaceResult.Replaced)
          assertEquals(release, ReplaceResult.Replaced)
          assertEquals(saved, expectedSaved)
          assertEquals(moved, expectedMoved)
          assert(failed.isLeft)
          assertEquals(retained, moved)
          assertEquals(rejectedSlots, Vector.fill(invalidSlots.size)((true, expectedMoved, expectedForeign)))
          assertEquals(uncertain, cause.asLeft[ReplaceResult])
          assertEquals(committed, ReadResult.Found(Stored(cover, placements = Vector(first), interiorSpreads = 1, specifications = specifications)))
          assertEquals(removed, empty)
          assertEquals(title, "Album")

  test("should persist either spine owner independently of placements and preserve it on later placement saves"):
    val database = Resource.make(Files.createTempFile(Path.of("target"), "cover-owner-", ".sqlite").blocking[IO]): path =>
      Files.deleteIfExists(path).blocking[IO].void

    database.evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void).use: path =>
      SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor.use: xa =>
        val repository    = SqliteCoverRepository.make
        val absent        = AlbumId(new UUID(0L, 99L))
        val changed       = cover.copy(spineOwner = SpineOwner.Front)
        val expectedSaved =
          ReadResult.Found(Stored(changed, placements = Vector(first, second), interiorSpreads = 1, specifications = specifications))
        val cause       = new IllegalStateException("Lost owner acknowledgement")
        val interrupted = Transactor.after.set(xa, xa.strategy.after >> cause.raiseError[ConnectionIO, Unit])

        for
          _         <- seedComposition.transact(xa)
          _         <- repository.replace(album, placements = Vector(first, second)).transact(xa)
          front     <- repository.setSpineOwner(album, SpineOwner.Front).transact(xa)
          saved     <- repository.read(album).transact(xa)
          _         <- repository.replace(album, placements = Vector(first)).transact(xa)
          later     <- repository.read(album).transact(xa)
          missing   <- repository.setSpineOwner(absent, SpineOwner.Front).transact(xa)
          lost      <- repository.setSpineOwner(album, SpineOwner.Back).transact(interrupted).attempt
          finalRead <- repository.read(album).transact(xa)
        yield
          assertEquals(front, SetSpineOwnerResult.Updated)
          assertEquals(saved, expectedSaved)
          assertEquals(later, ReadResult.Found(Stored(changed, placements = Vector(first), interiorSpreads = 1, specifications = specifications)))
          assertEquals(missing, SetSpineOwnerResult.NotFound)
          assertEquals(lost, cause.asLeft[SetSpineOwnerResult])
          assertEquals(finalRead, ReadResult.Found(Stored(cover, placements = Vector(first), interiorSpreads = 1, specifications = specifications)))

  test("should reject invalid persisted cover shape, identities and transforms and propagate unavailable reads"):
    val database = Resource.make(Files.createTempFile(Path.of("target"), "cover-validity-", ".sqlite").blocking[IO]): path =>
      Files.deleteIfExists(path).blocking[IO].void

    database.evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void).use: path =>
      SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor.use: xa =>
        val repository      = SqliteCoverRepository.make
        val missingSide     = sql"DELETE FROM cover_slot WHERE album_id = ${album.value} AND side = 'front'".update.run >> repository.read(album)
        val invalidIdentity = sql"UPDATE cover_slot SET id = 'invalid' WHERE album_id = ${album.value} AND side = 'front'".update.run >>
          repository.read(album)
        val invalidTransform = sql"UPDATE placement SET zoom = 0".update.run >> repository.read(album)
        val unavailable      = sql"ALTER TABLE placement RENAME TO inaccessible".update.run >> repository.read(album)

        for
          _         <- seedComposition.transact(xa)
          missing   <- missingSide.transact(xa).attempt
          identity  <- invalidIdentity.transact(xa).attempt
          _         <- repository.replace(album, placements = Vector(first)).transact(xa)
          transform <- invalidTransform.transact(xa).attempt
          failed    <- unavailable.transact(xa).attempt
          retained  <- repository.read(album).transact(xa)
        yield
          assert(missing.isLeft)
          assert(identity.isLeft)
          assert(transform.isLeft)
          assert(failed.isLeft)
          assertEquals(retained, ReadResult.Found(Stored(cover, placements = Vector(first), interiorSpreads = 1, specifications = specifications)))

  test("should return owned opening and photo spread counts and the sizing catalog and fail explicitly without specifications"):
    val database = Resource.make(Files.createTempFile(Path.of("target"), "cover-dimensions-", ".sqlite").blocking[IO]): path =>
      Files.deleteIfExists(path).blocking[IO].void
    val empty =
      Stored(cover, placements = Vector.empty[CoverPlacement], interiorSpreads = 15, specifications)
    val chapterOpening = new UUID(7L, 0L)
    val sixteenth      = new UUID(7L, 14L)

    database.evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void).use: path =>
      SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor.use: xa =>
        val repository = SqliteCoverRepository.make
        val addSpreads = (1 to 13).toVector.traverse_ { position =>
          val id = new UUID(7L, position.toLong)
          sql"""INSERT INTO spread
                SELECT $id, id, 'photos_only', NULL, 'c', $position FROM spread_template WHERE kind = 'photos_only' LIMIT 1""".update.run.void
        }
        val clearSpecifications = sql"DELETE FROM cover_specification".update.run >> repository.read(album)

        for
          _ <- seedComposition.transact(xa)
          _ <- sql"""INSERT INTO spread
                     SELECT $chapterOpening, id, 'chapter_start', NULL, 'c', 0 FROM spread_template WHERE kind = 'chapter_start' LIMIT 1""".update.run.transact(
            xa
          )
          _       <- addSpreads.transact(xa)
          fifteen <- repository.read(album).transact(xa)
          _       <- sql"""INSERT INTO spread
                     SELECT $sixteenth, id, 'photos_only', NULL, 'c', 14 FROM spread_template WHERE kind = 'photos_only' LIMIT 1""".update.run.transact(
            xa
          )
          sixteen  <- repository.read(album).transact(xa)
          foreign  <- repository.read(foreignAlbum).transact(xa)
          _        <- sql"DELETE FROM spread WHERE id = $sixteenth".update.run.transact(xa)
          restored <- repository.read(album).transact(xa)
          failed   <- clearSpecifications.transact(xa).attempt
          retained <- repository.read(album).transact(xa)
        yield
          assertEquals(fifteen, ReadResult.Found(empty))
          assertEquals(sixteen, ReadResult.Found(empty.copy(interiorSpreads = 16)))
          assertEquals(foreign, ReadResult.Found(empty.copy(cover = foreignCover, interiorSpreads = 0)))
          assertEquals(restored, fifteen)
          assertEquals(failed.isLeft, true)
          assertEquals(retained, restored)

  private def seedComposition =
    val template = new UUID(2L, 22L)
    val textSlot = new UUID(0L, 23L)
    sql"DELETE FROM cover_specification".update.run >>
      sql"""INSERT INTO cover_specification (page_limit, width_pixels, height_pixels, spine_pixels)
            VALUES (30, 10382, 3402, 142), (36, 10397, 3402, 154)""".update.run >>
      sql"""INSERT INTO album (id, year, title, synopsis, created_at_ms)
          VALUES (${album.value}, 2026, 'Album', 'Synopsis', 0), (${foreignAlbum.value}, 2027, 'Other', '', 0)""".update.run >>
      sql"INSERT INTO chapter VALUES ('c', ${album.value}, 'Trip', '', 0, 1, 0, '/photos')".update.run >>
      sql"INSERT INTO cover VALUES (${album.value}, 'back'), (${foreignAlbum.value}, 'front')".update.run >>
      sql"""INSERT INTO cover_slot VALUES
            (${cover.front.id.value}, ${album.value}, 'front'), (${cover.back.id.value}, ${album.value}, 'back'),
            (${foreignCover.front.id.value}, ${foreignAlbum.value}, 'front'), (${foreignCover.back.id.value}, ${foreignAlbum.value}, 'back')""".update.run >>
      Vector(firstPhoto, secondPhoto).traverse_ { photo =>
        sql"""INSERT INTO photo
          (id, chapter_id, source, source_hash, width, height, thumbnail, thumbnail_version,
           thumbnail_width, thumbnail_height, thumbnail_quality, thumbnail_bytes)
          VALUES (${photo.value}, 'c', ${photo.value.toString}, 'hash', 1, 1, 'key', 'version', 1, 1, 'low', 1)""".update.run.void
      } >>
      sql"INSERT INTO spread_template VALUES ($template, 'Opening', 'album_start')".update.run >>
      sql"""INSERT INTO spread_template_slot (template_id, kind, x, y, width, height)
            VALUES ($template, 'photo', 0, 0, 50, 100), ($template, 'text', 50, 0, 50, 100)""".update.run >>
      sql"INSERT INTO spread VALUES (${opening.value}, $template, 'album_start', ${album.value}, NULL, 0)".update.run >>
      sql"INSERT INTO spread_photo_slot VALUES (${openingSlot.value}, ${opening.value}, 0, 0, 50, 100)".update.run >>
      sql"INSERT INTO spread_text_slot VALUES ($textSlot, ${opening.value}, 50, 0, 50, 100)".update.run
