package photography.app.runtime

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.free.connection
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.filesystem.{FilePhotoStorage, FileThumbnailStorage}
import photography.adapters.sqlite.{FlywayMigrator, SqliteConnection}
import photography.adapters.sqlite.album.{SqliteAlbumCompositionRepository, SqliteAlbumRepository}
import photography.adapters.sqlite.chapter.SqliteChapterRepository
import photography.adapters.sqlite.cover.SqliteCoverRepository
import photography.adapters.sqlite.gear.SqliteGearRepository
import photography.adapters.sqlite.photo.SqlitePhotoRepository
import photography.adapters.sqlite.spread.SqliteSpreadTemplateRepository
import photography.app.config.SqliteConfig
import photography.capabilities.Transaction
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.album.AlbumRepository
import photography.ports.gear.GearRepository
import photography.ports.spread.SpreadTemplateRepository
import photography.usecases.album.BookExport
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}

final class BookExportSystemIntegrationTest extends CatsEffectSuite:
  private given Logger[IO]           = Slf4jLogger.getLogger[IO]
  private given Logger[ConnectionIO] = Slf4jLogger.getLogger[ConnectionIO]

  test("should export one coherent snapshot without writes while another connection invalidates saved content cover and catalog"):
    buildTransactor.use: xa =>
      val albums    = SqliteAlbumRepository.make[IO](xa, SqliteAlbumCompositionRepository.make, SqliteChapterRepository.make)
      val catalog   = SqliteGearRepository.make[IO](xa)
      val started   = new CountDownLatch(1)
      val committed = new CountDownLatch(1)
      val snapshot  = new AlbumRepository.AggregateRead[ConnectionIO]:
        override def readAlbum(id: AlbumId): ConnectionIO[AlbumRepository.GetResult] =
          albums.readAlbum(id).flatTap: _ =>
            connection.delay(started.countDown()) >>
              connection.delay(committed.await(10L, TimeUnit.SECONDS))
                .ensure(new IllegalStateException("Writer did not finish"))(identity).void
      val exporter   = buildExporter(xa, albums, catalog)
      val concurrent = buildExporter(xa, snapshot, catalog)
      val templates  = SqliteSpreadTemplateRepository.make[IO](xa)

      templates.listAlbumStartTemplates.map {
        case SpreadTemplateRepository.ListAlbumStartTemplatesResult.Listed(values) => buildAlbum(values.head)
        case other                                                                 => fail(s"Expected seeded opening templates, obtained $other")
      }.flatMap: album =>
        val cover   = Cover(album.id, SpineOwner.Front, CoverSlot(CoverSlotId(new UUID(0L, 3L))), CoverSlot(CoverSlotId(new UUID(0L, 4L))))
        val changed = sql"PRAGMA ignore_check_constraints=ON".update.run >>
          sql"UPDATE album SET synopsis = '' WHERE id = ${album.id.value.toString}".update.run >>
          sql"DELETE FROM cover_slot WHERE id = ${cover.front.id.value.toString}".update.run >>
          sql"UPDATE camera SET friendly_name = ''".update.run
        val writer =
          (IO.blocking(started.await(10L, TimeUnit.SECONDS))
            .ensure(new IllegalStateException("Reader did not start"))(identity) >> changed.transact(xa))
            .guarantee(IO.blocking(committed.countDown()))

        for
          _              <- xa.rawTrans.apply(sql"PRAGMA journal_mode = WAL".query[String].unique)
          _              <- albums.create(album, cover)
          initial        <- exporter.exportBook(album.id)
          retained       <- albums.get(album.id)
          absent         <- exporter.exportBook(AlbumId(new UUID(0L, 999L)))
          snapshotResult <- concurrent.exportBook(album.id).both(writer)
          synopsis       <- sql"SELECT synopsis FROM album WHERE id = ${album.id.value.toString}".query[String].unique.transact(xa)
          slots          <- sql"SELECT count(*) FROM cover_slot WHERE album_id = ${album.id.value.toString}".query[Long].unique.transact(xa)
          currentCatalog <- catalog.readCatalog
          failed         <- exporter.exportBook(album.id)
        yield
          val issues = album.opening.photoSlots.map(slot =>
            BookExport.Issue(BookExport.Code.EmptyPhotoSlot, BookExport.Severity.Blocking, BookExport.Target.Slot(album.opening.id, slot.id))
          ) ++ Vector(cover.front.id, cover.back.id).map(slot =>
            BookExport.Issue(BookExport.Code.EmptyPhotoSlot, BookExport.Severity.Blocking, BookExport.Target.CoverSlot(slot))
          )
          val (found, _) = snapshotResult
          assertEquals(initial, BookExport.Result.Blocked(issues))
          assertEquals(retained, AlbumRepository.GetResult.Found(album))
          assertEquals(absent, BookExport.Result.NotFound)
          assertEquals(found, initial)
          assertEquals(synopsis, "")
          assertEquals(slots, 1L)
          assertEquals(currentCatalog, GearRepository.CatalogReadResult.Failed)
          assertEquals(failed, BookExport.Result.Failed)

  private def buildAlbum(template: SpreadTemplate.AlbumStart) =
    val album = AlbumId(new UUID(0L, 1L))
    val slots = template.photoSlots.zipWithIndex.map { case (box, index) =>
      SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(1L, index.toLong)), box, bleed = Set.empty[SpreadEdge])
    }
    val text    = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 5L)), template.textSlot)
    val opening = Spread.AlbumStart(SpreadId(new UUID(0L, 2L)), template.id, slots, text, placements = Vector.empty[SpreadPlacement])
    Album(album, 2025, "Retained Hội An", "Original synopsis", Author.all, Instant.EPOCH, opening, chapters = Vector.empty[Chapter])

  private def buildExporter(
      xa: Transactor[IO],
      albums: AlbumRepository.AggregateRead[ConnectionIO],
      gear: GearRepository.CatalogRead[ConnectionIO]
  ) =
    given Transaction[ConnectionIO, IO] with
      override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
      override def resolve[A](program: ConnectionIO[A]): IO[A] = fail("Export must not resolve writes")
    val originals = FilePhotoStorage.make[IO](FileThumbnailStorage.make[IO](Path.of("target")))
    BookExport.make(albums, SqliteCoverRepository.make, SqlitePhotoRepository.make, gear, originals)

  private def buildTransactor =
    val acquireDatabase = IO.blocking(Files.createTempFile(Path.of("target"), "book-export-", ".sqlite"))

    for
      path <- Resource.make(acquireDatabase)(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa
