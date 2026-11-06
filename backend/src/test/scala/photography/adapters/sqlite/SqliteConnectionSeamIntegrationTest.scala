package photography.adapters.sqlite

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.sqlite.{SQLiteConnection, SQLiteErrorCode, SQLiteException}
import org.typelevel.doobie.{Fragment, Transactor}
import org.typelevel.doobie.implicits.*
import photography.app.config.SqliteConfig
import photography.domain.album.AlbumId
import java.util.UUID

import java.nio.file.{Files, Path}

final class SqliteConnectionSeamIntegrationTest extends CatsEffectSuite:
  private val indexedReads = Vector(
    (
      sql"""SELECT id, year, title, synopsis, CASE WHEN typeof(authors) = 'integer' THEN authors ELSE 0 END, created_at_ms
           FROM album ORDER BY created_at_ms DESC, id DESC LIMIT 6""",
      "album_created_at_id"
    ),
    (sql"SELECT id FROM chapter WHERE destination_id = 'destination'", "chapter_destination"),
    (sql"SELECT id FROM photo WHERE chapter_id = 'chapter' ORDER BY id", "photo_chapter_id"),
    (sql"SELECT chapter_id FROM chapter_camera WHERE camera_id = 'camera'", "chapter_camera_camera"),
    (sql"SELECT chapter_id FROM chapter_camera_lens WHERE lens_id = 'lens'", "chapter_camera_lens_lens"),
    (sql"SELECT id FROM spread_photo_slot WHERE spread_id = 'spread'", "spread_photo_slot_spread"),
    (sql"SELECT id FROM spread_text_slot WHERE spread_id = 'spread'", "spread_text_slot_spread")
  )

  test("should index ordered inventory and saved ownership lookups without temporary sorting"):
    buildTransactor.use: xa =>
      indexedReads.traverse_ { case (query, expectedIndex) =>
        (Fragment.const("EXPLAIN QUERY PLAN") ++ query).query[(Int, Int, Int, String)].to[Vector].transact(xa).map: plan =>
          val details = plan.map { case (_, _, _, detail) => detail }
          assert(details.exists(_.contains(expectedIndex)), s"Expected $expectedIndex for $query: $details")
          assert(!details.exists(_.contains("USE TEMP B-TREE")), s"Unexpected temporary sorting for $query: $details")
      }

  test("should enforce foreign keys on every pooled connection"):
    buildTransactor.use: xa =>
      (xa.connect(xa.kernel), xa.connect(xa.kernel)).tupled.use { case (first, second) =>
        val distinctConnections = IO.blocking:
          val firstIdentity  = System.identityHashCode(first.unwrap(classOf[SQLiteConnection]))
          val secondIdentity = System.identityHashCode(second.unwrap(classOf[SQLiteConnection]))
          firstIdentity =!= secondIdentity
        val connections = Vector(first, second)

        distinctConnections.flatMap: distinct =>
          assert(distinct)
          connections.traverse_ { connection =>
            val held         = Transactor.fromConnection[IO](connection, logHandler = None)
            val invalidWrite = sql"INSERT INTO cover (album_id, spine_owner) VALUES ('missing', 'front')".update.run

            for
              enabled  <- sql"PRAGMA foreign_keys".query[Int].unique.transact(held)
              rejected <- invalidWrite.transact(held).attempt
            yield
              assertEquals(enabled, 1)
              rejected match
                case Left(error: SQLiteException) => assertEquals(error.getResultCode, SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY)
                case result                       => fail(s"Expected a foreign-key constraint rejection, obtained $result")
          }
      }

  test("should count both spread ownership kinds using owner indexes rather than scanning other albums"):
    val query = SqliteAlbumQueries.interiorSpreadCount(AlbumId(new UUID(0L, 1L)))

    buildTransactor.use: xa =>
      (Fragment.const("EXPLAIN QUERY PLAN") ++ query).query[(Int, Int, Int, String)].to[Vector].transact(xa).map: plan =>
        val details = plan.map { case (_, _, _, detail) => detail }
        assert(details.exists(_.contains("(album_id=?)")), s"Missing indexed album lookup: $details")
        assert(details.exists(_.contains("(chapter_id=?)")), s"Missing indexed chapter spread lookup: $details")
        assertEquals(details.filter(_.startsWith("SCAN ")), Vector("SCAN CONSTANT ROW"))

  private def buildTransactor =
    val acquireDatabase = IO.blocking(Files.createTempFile(Path.of("target"), "connection-", ".sqlite"))

    for
      path <- Resource.make(acquireDatabase)(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa
