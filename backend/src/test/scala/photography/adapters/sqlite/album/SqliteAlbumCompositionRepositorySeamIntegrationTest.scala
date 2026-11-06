package photography.adapters.sqlite.album

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.ConnectionIO
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.sqlite.{FlywayMigrator, SqliteConnection}
import photography.adapters.sqlite.SqliteCodecs.given
import photography.app.config.SqliteConfig
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.layout.SpreadId
import photography.ports.album.AlbumCompositionRepository.{CompositionResult, Failure, Member}
import java.nio.file.{Files, Path}
import java.util.UUID

final class SqliteAlbumCompositionRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO]           = Slf4jLogger.getLogger[IO]
  private given Logger[ConnectionIO] = Slf4jLogger.getLogger[ConnectionIO]
  private val album                  = AlbumId(new UUID(0L, 1L))
  private val chapter                = ChapterId(new UUID(0L, 2L))
  private val opening                = SpreadId(new UUID(0L, 3L))
  private val chapterOpening         = SpreadId(new UUID(0L, 4L))
  private val photo                  = SpreadId(new UUID(0L, 5L))
  private val destination            = new UUID(0L, 6L)

  test("should acquire ordered membership without reading Cover or spread geometry"):
    buildTransactor.use: xa =>
      val repository = SqliteAlbumCompositionRepository.makeMembershipRead[IO](xa)
      val expected   = CompositionResult.Found(NonEmptyList.of(
        Member(opening, maybeChapter = None),
        Member(chapterOpening, maybeChapter = chapter.some),
        Member(photo, maybeChapter = chapter.some)
      ))

      for
        _       <- seed.transact(xa)
        members <- repository.composition(album)
        exists  <- SqliteAlbumCompositionRepository.make.hasAlbum(album).transact(xa)
        count   <- SqliteAlbumCompositionRepository.make.countInteriorSpreads(album).transact(xa)
      yield
        assertEquals(members, expected)
        assertEquals(exists, true)
        assertEquals(count, 3)

  test("should reject incomplete chapter membership and leave the stored rows unchanged"):
    buildTransactor.use: xa =>
      val repository     = SqliteAlbumCompositionRepository.makeMembershipRead[IO](xa)
      val expectedResult = CompositionResult.Failed(Failure.Unavailable)

      for
        _              <- seed.transact(xa)
        _              <- sql"DELETE FROM spread WHERE id = ${chapterOpening.value}".update.run.transact(xa)
        result         <- repository.composition(album)
        retained       <- sql"SELECT id FROM spread WHERE chapter_id = ${chapter.value} ORDER BY position".query[UUID].to[List].transact(xa)
        _              <- sql"DELETE FROM spread WHERE id = ${opening.value}".update.run.transact(xa)
        missingOpening <- repository.composition(album)
      yield
        assertEquals(result, expectedResult)
        assertEquals(retained, List(photo.value))
        assertEquals(missingOpening, expectedResult)

  test("should reject saved geometry that no longer represents its template"):
    Vector(
      sql"UPDATE spread_photo_slot SET x = x + 1 WHERE spread_id = ${opening.value}",
      sql"DELETE FROM spread_photo_slot WHERE spread_id = ${opening.value}",
      sql"UPDATE spread_template SET kind = 'photos_only' WHERE id IN (SELECT template_id FROM spread WHERE id = ${opening.value})"
    ).traverse_ { mutation =>
      buildTransactor.use: xa =>
        val repository = SqliteAlbumCompositionRepository.make
        val photoSlots = sql"""INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height)
          SELECT printf('00000000-0000-0001-0000-%012x', t.id), s.id, t.x, t.y, t.width, t.height
          FROM spread s JOIN spread_template_slot t ON t.template_id = s.template_id WHERE t.kind = 'photo'""".update.run
        val textSlots = sql"""INSERT INTO spread_text_slot (id, spread_id, x, y, width, height)
          SELECT printf('00000000-0000-0002-0000-%012x', t.id), s.id, t.x, t.y, t.width, t.height
          FROM spread s JOIN spread_template_slot t ON t.template_id = s.template_id WHERE t.kind = 'text'""".update.run
        for
          _      <- (seed >> photoSlots >> textSlots >> mutation.update.run).transact(xa)
          result <- repository.findSpread(album, opening).transact(xa).attempt
        yield assert(result.isLeft)
    }

  test("should reject gaps in persisted chapter positions through the shared order decoder"):
    buildTransactor.use: xa =>
      val repository     = SqliteAlbumCompositionRepository.makeMembershipRead[IO](xa)
      val expectedResult = CompositionResult.Failed(Failure.Unavailable)

      for
        _         <- seed.transact(xa)
        _         <- sql"UPDATE spread SET position = 3 WHERE id = ${photo.value}".update.run.transact(xa)
        result    <- repository.composition(album)
        positions <- sql"SELECT position FROM spread WHERE chapter_id = ${chapter.value} ORDER BY position".query[Int].to[List].transact(xa)
      yield
        assertEquals(result, expectedResult)
        assertEquals(positions, List(0, 3))

  test("should distinguish absent ownership from unavailable persistence"):
    buildTransactor.use: xa =>
      val repository      = SqliteAlbumCompositionRepository.makeMembershipRead[IO](xa)
      val expectedFailure = CompositionResult.Failed(Failure.Unavailable)

      for
        absent      <- repository.composition(album)
        _           <- sql"DROP TABLE album".update.run.transact(xa)
        unavailable <- repository.composition(album)
      yield
        assertEquals(absent, CompositionResult.AlbumNotFound)
        assertEquals(unavailable, expectedFailure)

  private def seed =
    val insertAlbum   = sql"INSERT INTO album (id, year, title, synopsis, created_at_ms) VALUES (${album.value}, 2026, 'Year', '', 0)".update.run
    val insertChapter = sql"""INSERT INTO chapter (id, album_id, destination_id, description, start_ms, end_ms, position, source_folder)
                             VALUES (${chapter.value}, ${album.value}, $destination, '', 0, 86400000, 0, '/photos/coast')""".update.run
    val insertOpening = sql"""INSERT INTO spread (id, template_id, kind, album_id, position)
                 SELECT ${opening.value}, id, kind, ${album.value}, 0 FROM spread_template WHERE kind = 'album_start' ORDER BY id LIMIT 1""".update.run
    val insertChapterOpening = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
                 SELECT ${chapterOpening.value}, id, kind, ${chapter.value}, 0 FROM spread_template WHERE kind = 'chapter_start' ORDER BY id LIMIT 1""".update.run
    val insertPhotoSpread = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
                 SELECT ${photo.value}, id, kind, ${chapter.value}, 1 FROM spread_template WHERE kind = 'photos_only' ORDER BY id LIMIT 1""".update.run
    for
      _ <- insertAlbum
      _ <- sql"INSERT INTO destination (id, name) VALUES ($destination, 'Coast')".update.run
      _ <- insertChapter
      _ <- insertOpening
      _ <- insertChapterOpening
      _ <- insertPhotoSpread
    yield ()

  private def buildTransactor =
    val allocate = IO.blocking(Files.createTempFile(Path.of("target"), "composition-", ".sqlite"))
    for
      path <- Resource.make(allocate)(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa
