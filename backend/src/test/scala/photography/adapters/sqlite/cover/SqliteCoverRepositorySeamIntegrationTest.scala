package photography.adapters.sqlite.cover

import cats.effect.{IO, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.sqlite.{FlywayMigrator, SqliteConnection}
import photography.adapters.sqlite.SqliteCodecs.given
import photography.app.config.SqliteConfig
import photography.capabilities.{IdGenerator, Transaction}
import photography.adapters.sqlite.photo.SqlitePhotoPlacementRepository
import photography.adapters.sqlite.album.SqliteAlbumCompositionRepository
import photography.adapters.sqlite.spread.{SqliteSpreadRepository, SqliteSpreadTemplateRepository}
import photography.domain.album.{AlbumId, PhotoId}
import photography.domain.layout.*
import photography.ports.cover.CoverRepository.{Composition, ReadResult, ReplaceResult}
import photography.syntax.SyncSyntax.*
import photography.usecases.cover.CoverManager
import photography.usecases.spread.SpreadManager
import java.nio.file.{Files, Path}
import java.util.UUID

class SqliteCoverRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[ConnectionIO] = Slf4jLogger.getLogger[ConnectionIO]
  private val album                  = AlbumId(new UUID(0L, 1L))
  private val cover                  = Cover(
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
  private val opening      = SpreadId(new UUID(0L, 20L))
  private val openingSlot  = PhotoSpreadSlotId(new UUID(0L, 21L))
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given IdGenerator[IO] with
    override def generateId: IO[UUID] = new UUID(0L, 200L).pure[IO]

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
        val expectedForeign = ReadResult.Found(Composition(foreignCover, placements = Vector.empty[CoverPlacement]))
        val expectedMoved   = ReadResult.Found(Composition(cover, placements = swapped))

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
          release            <- repository.replace(album, placements = Vector.empty[CoverPlacement]).transact(xa)
          removed            <- repository.read(album).transact(xa)
          title              <- sql"SELECT title FROM album WHERE id = ${album.value}".query[String].unique.transact(xa)
        yield
          assertEquals(empty, ReadResult.Found(Composition(cover, placements = Vector.empty[CoverPlacement])))
          assertEquals(missing, ReadResult.NotFound)
          assertEquals(missingReplacement, ReplaceResult.NotFound)
          assertEquals(firstSave, ReplaceResult.Replaced)
          assertEquals(swapSave, ReplaceResult.Replaced)
          assertEquals(release, ReplaceResult.Replaced)
          assertEquals(saved, ReadResult.Found(Composition(cover, placements = Vector(first, second))))
          assertEquals(moved, expectedMoved)
          assert(failed.isLeft)
          assertEquals(retained, moved)
          assertEquals(rejectedSlots, Vector.fill(invalidSlots.size)((true, expectedMoved, expectedForeign)))
          assertEquals(removed, empty)
          assertEquals(title, "Album")

  test("should commit only one concurrent cover placement for the same photo"):
    val database = Resource.make(Files.createTempFile(Path.of("target"), "cover-concurrent-", ".sqlite").blocking[IO]): path =>
      Files.deleteIfExists(path).blocking[IO].void

    database.evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void).use: path =>
      SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor.use: xa =>
        Resource.eval(Mutex[IO]).use: mutex =>
          given Mutex[IO] = mutex
          val manager     = buildCoverManager(xa)
          val repository  = SqliteCoverRepository.make

          for
            _        <- seedComposition.transact(xa)
            outcomes <- Vector(cover.front.id, cover.back.id).parTraverse(slot => manager.edit(album, CoverManager.Edit.Place(slot, firstPhoto)))
            current  <- repository.read(album).transact(xa)
          yield
            val savedCount    = outcomes.count { case CoverManager.Result.Saved(_) => true; case _ => false }
            val rejectedCount = outcomes.count { case CoverManager.Result.Rejected(CoverManager.Failure.PhotoAlreadyUsed) => true; case _ => false }
            val currentPhotos = current match
              case ReadResult.Found(value) => value.placements.map(_.photo)
              case ReadResult.NotFound     => fail("Expected the saved cover")
            assertEquals(savedCount, 1)
            assertEquals(rejectedCount, 1)
            assertEquals(currentPhotos, Vector(firstPhoto))

  test("should commit only one concurrent placement across cover and interior contexts"):
    val database = Resource.make(Files.createTempFile(Path.of("target"), "cover-interior-concurrent-", ".sqlite").blocking[IO]): path =>
      Files.deleteIfExists(path).blocking[IO].void

    database.evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void).use: path =>
      SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor.use: xa =>
        Resource.eval(Mutex[IO]).use: mutex =>
          given Mutex[IO] = mutex
          val covers      = buildCoverManager(xa)
          val interiors   = buildSpreadManager(xa)
          val placeCover  = covers.edit(album, CoverManager.Edit.Place(cover.front.id, firstPhoto)).map:
            case CoverManager.Result.Saved(_)                                        => "saved"
            case CoverManager.Result.Rejected(CoverManager.Failure.PhotoAlreadyUsed) => "used"
            case _                                                                   => fail("Expected a saved cover or an already-used rejection")
          val placeOpening = interiors.editPlacements(album, opening, SpreadManager.Edit.Place(openingSlot, firstPhoto)).map:
            case SpreadManager.PlacementResult.Saved(_)                                         => "saved"
            case SpreadManager.PlacementResult.Rejected(SpreadManager.Failure.PhotoAlreadyUsed) => "used"
            case _ => fail("Expected a saved opening or an already-used rejection")

          for
            _              <- seedComposition.transact(xa)
            outcomes       <- (placeCover, placeOpening).parTupled
            placementCount <- sql"SELECT COUNT(*) FROM placement WHERE photo_id = ${firstPhoto.value}".query[Int].unique.transact(xa)
          yield
            val (coverOutcome, openingOutcome) = outcomes
            assertEquals(Vector(coverOutcome, openingOutcome).sorted, Vector("saved", "used"))
            assertEquals(placementCount, 1)

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
          assertEquals(retained, ReadResult.Found(Composition(cover, placements = Vector(first))))

  private def seedComposition =
    val template = new UUID(2L, 22L)
    val textSlot = new UUID(0L, 23L)
    sql"""INSERT INTO album (id, year, title, synopsis, created_at_ms)
          VALUES (${album.value}, 2026, 'Album', 'Synopsis', 0), (${foreignAlbum.value}, 2027, 'Other', '', 0)""".update.run >>
      sql"INSERT INTO destination VALUES ('d', 'Trip')".update.run >>
      sql"INSERT INTO chapter VALUES ('c', ${album.value}, 'd', '', 0, 1, 0, '/photos')".update.run >>
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

  private def buildCoverManager(xa: Transactor[IO])(using Mutex[IO]) =
    given Transaction[ConnectionIO, IO] with
      override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
      override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
    val repository = SqliteCoverRepository.make
    val photos     = SqlitePhotoPlacementRepository.make
    CoverManager.make[IO, ConnectionIO](repository, photos)

  private def buildSpreadManager(xa: Transactor[IO])(using Mutex[IO]) =
    given Transaction[ConnectionIO, IO] with
      override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
      override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
    val photos             = SqlitePhotoPlacementRepository.make
    val composition        = SqliteAlbumCompositionRepository.make
    val spreads            = SqliteSpreadRepository.make
    val templateRepository = SqliteSpreadTemplateRepository.make[IO](xa)
    val templates          = templateRepository
    val defaultLayout      = SpreadTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000113"))
    SpreadManager.make[IO, ConnectionIO](templates, composition, spreads, photos, defaultLayout)
