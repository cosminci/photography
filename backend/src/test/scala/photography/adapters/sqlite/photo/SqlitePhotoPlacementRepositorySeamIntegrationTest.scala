package photography.adapters.sqlite.photo

import photography.adapters.sqlite.FlywayMigrator
import photography.adapters.sqlite.SqliteConnection

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.doobie.implicits.*
import photography.adapters.sqlite.SqliteCodecs.given
import photography.app.config.SqliteConfig
import photography.domain.album.{AlbumId, ChapterId, PhotoId}
import photography.domain.editing.EditingTarget
import photography.domain.layout.SpreadId
import photography.ports.photo.PhotoPlacementRepository.{FindResult, InspectResult, TargetResult}
import photography.syntax.SyncSyntax.*
import java.nio.file.{Files, Path}
import java.util.UUID

final class SqlitePhotoPlacementRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO] = NoOpLogger.impl[IO]

  test("should read authoritative chapter-owned spread and cover placements, including empty inventories and failures"):
    val album        = AlbumId(new UUID(0L, 100L))
    val chapter      = ChapterId(new UUID(0L, 1L))
    val other        = ChapterId(new UUID(0L, 2L))
    val spread       = PhotoId(new UUID(0L, 3L))
    val cover        = PhotoId(new UUID(0L, 4L))
    val foreign      = PhotoId(new UUID(0L, 5L))
    val unplaced     = ChapterId(new UUID(0L, 6L))
    val unused       = PhotoId(new UUID(0L, 7L))
    val missingPhoto = PhotoId(new UUID(0L, 8L))
    val unknownAlbum = AlbumId(new UUID(0L, 999L))
    val spreadId     = SpreadId(new UUID(0L, 9L))
    val targets      = Vector(
      (chapter, spread, TargetResult.Found(Some(EditingTarget.Spread(spreadId)))),
      (chapter, cover, TargetResult.Found(Some(EditingTarget.Cover(album)))),
      (other, foreign, TargetResult.Found(Some(EditingTarget.Cover(album)))),
      (chapter, unused, TargetResult.Found(maybeTarget = None)),
      (chapter, foreign, TargetResult.PhotoNotFound),
      (chapter, missingPhoto, TargetResult.PhotoNotFound),
      (ChapterId(new UUID(0L, 999L)), unused, TargetResult.PhotoNotFound)
    )
    val inspections = Vector(
      (album, none[ChapterId], spread, InspectResult.Found(inCollection = true, placed = true)),
      (album, chapter.some, cover, InspectResult.Found(inCollection = true, placed = true)),
      (album, chapter.some, foreign, InspectResult.Found(inCollection = false, placed = true)),
      (unknownAlbum, none[ChapterId], spread, InspectResult.Found(inCollection = false, placed = true)),
      (album, chapter.some, unused, InspectResult.Found(inCollection = true, placed = false)),
      (unknownAlbum, none[ChapterId], unused, InspectResult.Found(inCollection = false, placed = false)),
      (album, none[ChapterId], missingPhoto, InspectResult.NotFound)
    )
    val database = Resource.make(Files.createTempFile(Path.of("target"), "placements-", ".sqlite").blocking[IO]): path =>
      Files.deleteIfExists(path).blocking[IO].void

    database.evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void).use: path =>
      SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor.use: xa =>
        val repository = SqlitePhotoPlacementRepository.make
        val targetRead = SqlitePhotoPlacementRepository.makeTargetRead[IO](xa)
        val seed       =
          sql"INSERT INTO album (id, year, title, synopsis, created_at_ms) VALUES (${album.value}, 2026, 'Album', '', 0)".update.run >>
            sql"INSERT INTO destination VALUES ('d', 'Trip')".update.run >>
            sql"INSERT INTO chapter VALUES (${chapter.value}, ${album.value}, 'd', '', 0, 1, 0, '/photos')".update.run >>
            sql"INSERT INTO chapter VALUES (${other.value}, ${album.value}, 'd', '', 0, 1, 1, '/other')".update.run >>
            sql"INSERT INTO chapter VALUES (${unplaced.value}, ${album.value}, 'd', '', 0, 1, 2, '/empty')".update.run >>
            sql"INSERT INTO cover VALUES (${album.value}, 'front')".update.run >>
            sql"INSERT INTO cover_slot VALUES ('front', ${album.value}, 'front'), ('back', ${album.value}, 'back')".update.run >>
            sql"INSERT INTO spread_template VALUES ('template', 'Test', 'photos_only')".update.run >>
            sql"INSERT INTO spread VALUES (${spreadId.value}, 'template', 'photos_only', NULL, ${chapter.value}, 1)".update.run >>
            sql"INSERT INTO spread_photo_slot VALUES ('slot', ${spreadId.value}, 0, 0, 10, 10)".update.run >>
            Vector((spread, chapter), (cover, chapter), (foreign, other), (unused, chapter)).traverse_ { case (photo, owner) =>
              sql"""INSERT INTO photo
                (id, chapter_id, source, source_hash, width, height, thumbnail, thumbnail_version,
                 thumbnail_width, thumbnail_height, thumbnail_quality, thumbnail_bytes)
                VALUES (${photo.value}, ${owner.value}, ${photo.value.toString}, 'hash', 1, 1, 'key', 'version', 1, 1, 'low', 1)""".update.run.void
            } >>
            sql"""INSERT INTO placement VALUES
              ('spread-placement', ${spread.value}, 'spread', 'slot', NULL, 0, 0, 0, 1, 'top_left'),
              ('cover-placement', ${cover.value}, 'cover', NULL, 'front', 0, 0, 0, 1, NULL),
              ('other-placement', ${foreign.value}, 'cover', NULL, 'back', 0, 0, 0, 1, NULL)""".update.run
        val unavailableInspection =
          sql"ALTER TABLE placement RENAME TO inaccessible".update.run >> repository.inspect(album, maybeChapter = none[ChapterId], spread)

        for
          _         <- seed.transact(xa)
          placed    <- repository.findPlacedPhotos(chapter).transact(xa)
          scoped    <- repository.findPlacedPhotos(other).transact(xa)
          empty     <- repository.findPlacedPhotos(unplaced).transact(xa)
          inspected <- inspections.traverse { case (owner, scope, photo, _) => repository.inspect(owner, maybeChapter = scope, photo).transact(xa) }
          located   <- targets.traverse { case (chapter, photo, _) => targetRead.findTarget(chapter, photo) }
          _         <-
          (sql"PRAGMA ignore_check_constraints = ON".update.run >>
            sql"UPDATE placement SET kind = 'unknown' WHERE photo_id = ${spread.value}".update.run).transact(xa)
          malformed <- targetRead.findTarget(chapter, spread)
          _         <-
          (sql"UPDATE placement SET kind = 'spread' WHERE photo_id = ${spread.value}".update.run >>
            sql"PRAGMA ignore_check_constraints = OFF".update.run).transact(xa)
          inspectionFailure <- unavailableInspection.transact(xa).attempt
          failed            <- (sql"DROP TABLE placement".update.run >> repository.findPlacedPhotos(chapter)).transact(xa).attempt
          _                 <- sql"ALTER TABLE placement RENAME TO inaccessible".update.run.transact(xa)
          unavailable       <- targetRead.findTarget(chapter, spread)
        yield
          assertEquals(placed, FindResult.Found(photos = Set(spread, cover)))
          assertEquals(scoped, FindResult.Found(photos = Set(foreign)))
          assertEquals(empty, FindResult.Found(photos = Set.empty[PhotoId]))
          assertEquals(inspected, inspections.map { case (_, _, _, expected) => expected })
          assertEquals(located, targets.map { case (_, _, expected) => expected })
          assertEquals(malformed, TargetResult.Failed)
          assertEquals(unavailable, TargetResult.Failed)
          assert(inspectionFailure.isLeft)
          assert(failed.isLeft)
