package photography.adapters.sqlite.gear

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.{Not, Empty}
import munit.CatsEffectSuite
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.sqlite.{FlywayMigrator, SqliteConnection}
import photography.adapters.sqlite.SqliteCodecs.given
import photography.app.config.SqliteConfig
import photography.domain.gear.*
import photography.ports.gear.GearRepository.*
import photography.syntax.SyncSyntax.*

import java.nio.file.{Files, Path}
import java.util.UUID

final class SqliteGearRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val camera       = Camera(CameraId(new UUID(1L, 1L)), code = "body", friendlyName = "Body", CameraKind.FixedLens)
  private val lens         = Lens(LensId(new UUID(2L, 1L)), code = "lens", friendlyName = "Lens", LensMount.SonyE, SensorFormat.FullFrame)

  test("should seed the confirmed historical equipment in V2 and preserve it after migration validation and reopening"):
    buildSeededDatabase.use: path =>
      val expectedCameras: Vector[(String, String, Option[String], Option[String])] = Vector(
        ("Nikon P520", "fixed_lens", None, None),
        ("Nikon Z6 III", "interchangeable_lens", Some("nikon_z"), Some("full_frame")),
        ("Samsung Galaxy S20 FE", "fixed_lens", None, None),
        ("Samsung Galaxy S20 Ultra", "fixed_lens", None, None),
        ("Samsung Galaxy S22", "fixed_lens", None, None),
        ("Samsung Galaxy S25", "fixed_lens", None, None),
        ("Sony α6000", "interchangeable_lens", Some("sony_e"), Some("aps_c"))
      )
      val expectedLenses = Vector(
        ("Nikkor 28-400mm f/4-8", "nikon_z", "full_frame"),
        ("Sigma 16-300mm F3.5-6.7 DC OS Contemporary", "sony_e", "aps_c"),
        ("Sigma 30mm f/2.8 DN Art", "sony_e", "aps_c"),
        ("Sigma 60mm f/2.8 DN Art", "sony_e", "aps_c"),
        ("Viltrox 16mm f/1.8", "nikon_z", "full_frame")
      )
      val read = buildConnection(path).use: xa =>
        val repository = SqliteGearRepository.make[IO](xa)

        for
          cameras <- sql"SELECT friendly_name, kind, mount, sensor_format FROM camera ORDER BY friendly_name"
            .query[(String, String, Option[String], Option[String])].to[Vector].transact(xa)
          lenses <-
            sql"SELECT friendly_name, mount, coverage FROM lens ORDER BY friendly_name".query[(String, String, String)].to[Vector].transact(xa)
          differentCodes <- sql"""SELECT (SELECT count(*) FROM camera WHERE code != friendly_name) +
            (SELECT count(*) FROM lens WHERE code != friendly_name)""".query[Int].unique.transact(xa)
          catalog <- repository.readCatalog
        yield
          assertEquals(cameras, expectedCameras)
          assertEquals(lenses, expectedLenses)
          assertEquals(differentCodes, 0)
          catalog match
            case CatalogReadResult.Found(_) => catalog
            case other                      => fail(s"Expected valid seeded gear, obtained $other")

      for
        before <- read
        _      <- FlywayMigrator.make[IO](path).use(_.migrate)
        after  <- read
      yield assertEquals(after, before)

  test("should persist complete independent camera and lens catalogs and retain them after reopening"):
    buildDatabase.use: path =>
      val kinds = Vector(CameraKind.FixedLens) ++ LensMount.values.toVector.flatMap: mount =>
        SensorFormat.values.toVector.map(sensor => CameraKind.InterchangeableLens(mount, sensor))
      val cameras = kinds.zipWithIndex.map { case (kind, index) =>
        val code = s"body-$index".refineEither[Not[Empty]].fold(message => fail(message), identity)
        camera.copy(id = CameraId(new UUID(1L, index.toLong)), code = code, kind = kind)
      }
      val lenses = LensMount.values.toVector.flatMap: mount =>
        SensorFormat.values.toVector.map: coverage =>
          val index = mount.ordinal * 2 + coverage.ordinal
          val code  = s"lens-$index".refineEither[Not[Empty]].fold(message => fail(message), identity)
          lens.copy(id = LensId(new UUID(2L, index.toLong)), code = code, mount = mount, coverage = coverage)
      val expected = CatalogReadResult.Found(GearCatalog(cameras = cameras, lenses = lenses))
      val write    = buildConnection(path).use: xa =>
        val repository = SqliteGearRepository.make[IO](xa)

        for
          empty          <- repository.readCatalog
          createdCameras <- cameras.traverse(repository.createCamera)
          createdLenses  <- lenses.traverse(repository.createLens)
          saved          <- repository.readCatalog
        yield
          assertEquals(empty, CatalogReadResult.Found(GearCatalog(cameras = Vector.empty[Camera], lenses = Vector.empty[Lens])))
          assertEquals(createdCameras, Vector.fill(cameras.size)(CameraCreateResult.Created))
          assertEquals(createdLenses, Vector.fill(lenses.size)(LensCreateResult.Created))
          assertEquals(saved, expected)

      write >> buildConnection(path).use: xa =>
        SqliteGearRepository.make[IO](xa).readCatalog.map(actual => assertEquals(actual, expected))

  test("should update complete optics and names, reject duplicate identities and codes, and report missing targets"):
    buildDatabase.flatMap(buildConnection).use: xa =>
      val repository    = SqliteGearRepository.make[IO](xa)
      val revisedCamera = camera.copy(friendlyName = "Revised body", kind = CameraKind.InterchangeableLens(LensMount.NikonZ, SensorFormat.FullFrame))
      val revisedLens   = lens.copy(friendlyName = "Revised lens", mount = LensMount.NikonZ, coverage = SensorFormat.ApsC)
      val otherCamera   = camera.copy(id = CameraId(new UUID(1L, 99L)), code = "other-body")
      val otherLens     = lens.copy(id = LensId(new UUID(2L, 99L)), code = "other-lens")

      for
        _               <- repository.createCamera(camera)
        _               <- repository.createLens(lens)
        cameraConflicts <- Vector(camera, otherCamera.copy(code = camera.code)).traverse(repository.createCamera)
        lensConflicts   <- Vector(lens, otherLens.copy(code = lens.code)).traverse(repository.createLens)
        missingCamera   <- repository.updateCamera(otherCamera)
        missingLens     <- repository.updateLens(otherLens)
        _               <- repository.createCamera(otherCamera)
        _               <- repository.createLens(otherLens)
        cameraConflict  <- repository.updateCamera(otherCamera.copy(code = camera.code))
        lensConflict    <- repository.updateLens(otherLens.copy(code = lens.code))
        updatedCamera   <- repository.updateCamera(revisedCamera)
        updatedLens     <- repository.updateLens(revisedLens)
        saved           <- repository.readCatalog
        deletedCamera   <- repository.deleteCamera(otherCamera.id)
        deletedLens     <- repository.deleteLens(otherLens.id)
        absentCamera    <- repository.deleteCamera(otherCamera.id)
        absentLens      <- repository.deleteLens(otherLens.id)
        remaining       <- repository.readCatalog
      yield
        val expected = CatalogReadResult.Found(GearCatalog(cameras = Vector(revisedCamera, otherCamera), lenses = Vector(revisedLens, otherLens)))
        val expectedRemaining = CatalogReadResult.Found(GearCatalog(cameras = Vector(revisedCamera), lenses = Vector(revisedLens)))

        assertEquals(cameraConflicts, Vector.fill(2)(CameraCreateResult.Conflict))
        assertEquals(lensConflicts, Vector.fill(2)(LensCreateResult.Conflict))
        assertEquals(missingCamera, CameraUpdateResult.NotFound)
        assertEquals(missingLens, LensUpdateResult.NotFound)
        assertEquals(cameraConflict, CameraUpdateResult.Conflict)
        assertEquals(lensConflict, LensUpdateResult.Conflict)
        assertEquals(updatedCamera, CameraUpdateResult.Updated)
        assertEquals(updatedLens, LensUpdateResult.Updated)
        assertEquals(saved, expected)
        assertEquals(deletedCamera, CameraDeleteResult.Deleted)
        assertEquals(deletedLens, LensDeleteResult.Deleted)
        assertEquals(absentCamera, CameraDeleteResult.NotFound)
        assertEquals(absentLens, LensDeleteResult.NotFound)
        assertEquals(remaining, expectedRemaining)

  test("should protect actual saved references while allowing shared lenses and independent unused camera deletion"):
    buildDatabase.flatMap(buildConnection).use: xa =>
      val repository  = SqliteGearRepository.make[IO](xa)
      val first       = camera.copy(kind = CameraKind.InterchangeableLens(LensMount.SonyE, SensorFormat.ApsC))
      val second      = first.copy(id = CameraId(new UUID(1L, 2L)), code = "second")
      val unused      = first.copy(id = CameraId(new UUID(1L, 3L)), code = "unused")
      val album       = new UUID(3L, 1L)
      val chapter     = new UUID(3L, 2L)
      val destination = new UUID(3L, 3L)
      val seed        = sql"INSERT INTO album (id, year, title, synopsis, created_at_ms) VALUES ($album, 2026, 'Album', '', 0)".update.run >>
        sql"INSERT INTO destination (id, name) VALUES ($destination, 'Trip')".update.run >>
        sql"""INSERT INTO chapter (id, album_id, destination_id, description, start_ms, end_ms, position, source_folder)
          VALUES ($chapter, $album, $destination, '', 0, 86400000, 0, '/photos')""".update.run
      val select = Vector(first, second).traverse_ { body =>
        sql"INSERT INTO chapter_camera (chapter_id, camera_id, use_kind) VALUES ($chapter, ${body.id.value}, 'interchangeable_lenses')".update.run >>
          sql"INSERT INTO chapter_camera_lens (chapter_id, camera_id, lens_id) VALUES ($chapter, ${body.id.value}, ${lens.id.value})".update.run.void
      }

      for
        _          <- Vector(first, second, unused).traverse_(repository.createCamera)
        _          <- repository.createLens(lens)
        _          <- (seed >> select).transact(xa)
        before     <- repository.readCatalog
        usedCamera <- repository.deleteCamera(first.id)
        usedLens   <- repository.deleteLens(lens.id)
        unchanged  <- repository.readCatalog
        deleted    <- repository.deleteCamera(unused.id)
        saved      <- repository.readCatalog
      yield
        val expected = CatalogReadResult.Found(GearCatalog(cameras = Vector(first, second), lenses = Vector(lens)))

        assertEquals(usedCamera, CameraDeleteResult.Used)
        assertEquals(usedLens, LensDeleteResult.Used)
        assertEquals(unchanged, before)
        assertEquals(deleted, CameraDeleteResult.Deleted)
        assertEquals(saved, expected)

  test("should roll back failed and ignored catalog statements without success-shaped outcomes"):
    Vector("ABORT", "FAIL", "IGNORE").traverse_ { rejection =>
      buildDatabase.flatMap(buildConnection).use: xa =>
        val repository   = SqliteGearRepository.make[IO](xa)
        val rejectCamera = s"""CREATE TRIGGER reject_camera BEFORE UPDATE ON camera BEGIN
          UPDATE lens SET friendly_name = 'Partial'; SELECT RAISE($rejection${if rejection === "IGNORE" then "" else ", 'Rejected'"}); END"""
        val rejectLens = s"""CREATE TRIGGER reject_lens BEFORE UPDATE ON lens BEGIN
          UPDATE camera SET friendly_name = 'Partial'; SELECT RAISE($rejection${if rejection === "IGNORE" then "" else ", 'Rejected'"}); END"""

        for
          _            <- repository.createCamera(camera)
          _            <- repository.createLens(lens)
          before       <- repository.readCatalog
          _            <- Fragment.const(rejectCamera).update.run.transact(xa)
          cameraFailed <- repository.updateCamera(camera.copy(friendlyName = "Changed"))
          afterCamera  <- repository.readCatalog
          _            <- sql"DROP TRIGGER reject_camera".update.run.transact(xa)
          _            <- Fragment.const(rejectLens).update.run.transact(xa)
          lensFailed   <- repository.updateLens(lens.copy(friendlyName = "Changed"))
          afterLens    <- repository.readCatalog
        yield
          assertEquals(cameraFailed, CameraUpdateResult.Failed)
          assertEquals(lensFailed, LensUpdateResult.Failed)
          assertEquals(afterCamera, before)
          assertEquals(afterLens, before)
    }

  test("should report lost commit acknowledgements without replaying writes"):
    Vector(false, true).traverse_ { committed =>
      buildDatabase.flatMap(buildConnection).use: xa =>
        val lost          = new IllegalStateException("Lost acknowledgement").raiseError[ConnectionIO, Unit]
        val after         = if committed then xa.strategy.after >> lost else lost
        val repository    = SqliteGearRepository.make[IO](Transactor.after.set(xa, after))
        val authoritative = SqliteGearRepository.make[IO](xa)
        val expected      = if committed then Vector(camera) else Vector.empty[Camera]

        for
          result <- repository.createCamera(camera)
          actual <- authoritative.readCatalog
        yield
          assertEquals(result, CameraCreateResult.Uncertain)
          assertEquals(actual, CatalogReadResult.Found(GearCatalog(cameras = expected, lenses = Vector.empty[Lens])))
    }

  test("should preserve saved state after rejected creates and deletes and expose uncertain outcomes for every mutation"):
    buildDatabase.flatMap(buildConnection).use: xa =>
      val repository         = SqliteGearRepository.make[IO](xa)
      val rejectCamera       = sql"CREATE TRIGGER reject_camera BEFORE INSERT ON camera BEGIN SELECT RAISE(IGNORE); END".update.run
      val rejectLens         = sql"CREATE TRIGGER reject_lens BEFORE INSERT ON lens BEGIN SELECT RAISE(IGNORE); END".update.run
      val rejectCameraDelete = sql"CREATE TRIGGER reject_camera BEFORE DELETE ON camera BEGIN SELECT RAISE(IGNORE); END".update.run
      val rejectLensDelete   = sql"CREATE TRIGGER reject_lens BEFORE DELETE ON lens BEGIN SELECT RAISE(IGNORE); END".update.run
      val lost               = new IllegalStateException("Lost acknowledgement").raiseError[ConnectionIO, Unit]
      val uncertain          = SqliteGearRepository.make[IO](Transactor.after.set(xa, lost))

      for
        _                     <- rejectCamera.transact(xa)
        cameraRejected        <- repository.createCamera(camera)
        _                     <- sql"DROP TRIGGER reject_camera".update.run.transact(xa)
        _                     <- rejectLens.transact(xa)
        lensRejected          <- repository.createLens(lens)
        _                     <- sql"DROP TRIGGER reject_lens".update.run.transact(xa)
        _                     <- repository.createCamera(camera)
        _                     <- repository.createLens(lens)
        before                <- repository.readCatalog
        _                     <- rejectCameraDelete.transact(xa)
        cameraDeleteRejected  <- repository.deleteCamera(camera.id)
        _                     <- sql"DROP TRIGGER reject_camera".update.run.transact(xa)
        _                     <- rejectLensDelete.transact(xa)
        lensDeleteRejected    <- repository.deleteLens(lens.id)
        _                     <- sql"DROP TRIGGER reject_lens".update.run.transact(xa)
        cameraUpdateUncertain <- uncertain.updateCamera(camera.copy(friendlyName = "Changed"))
        lensUpdateUncertain   <- uncertain.updateLens(lens.copy(friendlyName = "Changed"))
        cameraDeleteUncertain <- uncertain.deleteCamera(camera.id)
        lensDeleteUncertain   <- uncertain.deleteLens(lens.id)
        lensCreateUncertain   <- uncertain.createLens(lens.copy(id = LensId(new UUID(2L, 99L)), code = "other"))
        after                 <- repository.readCatalog
      yield
        assertEquals(cameraRejected, CameraCreateResult.Failed)
        assertEquals(lensRejected, LensCreateResult.Failed)
        assertEquals(cameraDeleteRejected, CameraDeleteResult.Failed)
        assertEquals(lensDeleteRejected, LensDeleteResult.Failed)
        assertEquals(cameraUpdateUncertain, CameraUpdateResult.Uncertain)
        assertEquals(lensUpdateUncertain, LensUpdateResult.Uncertain)
        assertEquals(cameraDeleteUncertain, CameraDeleteResult.Uncertain)
        assertEquals(lensDeleteUncertain, LensDeleteResult.Uncertain)
        assertEquals(lensCreateUncertain, LensCreateResult.Uncertain)
        assertEquals(after, before)

  test("should fail complete catalog reads for malformed identities, names and optics rather than return partial or empty success"):
    Vector(
      "UPDATE camera SET id = 'invalid'",
      "UPDATE camera SET code = ''",
      "UPDATE camera SET friendly_name = ''",
      "UPDATE camera SET kind = 'unknown'",
      "UPDATE camera SET mount = 'unknown', sensor_format = 'aps_c', kind = 'interchangeable_lens'",
      "UPDATE camera SET mount = 'sony_e', sensor_format = 'unknown', kind = 'interchangeable_lens'",
      "UPDATE lens SET id = 'invalid'",
      "UPDATE lens SET code = ''",
      "UPDATE lens SET friendly_name = ''",
      "UPDATE lens SET mount = 'unknown'",
      "UPDATE lens SET coverage = 'unknown'"
    ).traverse_ { corrupt =>
      buildDatabase.flatMap(buildConnection).use: xa =>
        val repository = SqliteGearRepository.make[IO](xa)
        val corruption = sql"PRAGMA ignore_check_constraints = ON".update.run >> Fragment.const(corrupt).update.run

        for
          _      <- repository.createCamera(camera)
          _      <- repository.createLens(lens)
          _      <- corruption.transact(xa)
          result <- repository.readCatalog
        yield assertEquals(result, CatalogReadResult.Failed)
    }

  test("should read both catalog inventories from one snapshot during concurrent atomic changes"):
    buildDatabase.flatMap(buildConnection).use: xa =>
      val repository    = SqliteGearRepository.make[IO](xa)
      val changedCamera = camera.copy(friendlyName = "Changed body")
      val changedLens   = lens.copy(friendlyName = "Changed lens")
      val before        = CatalogReadResult.Found(GearCatalog(cameras = Vector(camera), lenses = Vector(lens)))
      val after         = CatalogReadResult.Found(GearCatalog(cameras = Vector(changedCamera), lenses = Vector(changedLens)))
      val change        = sql"UPDATE camera SET friendly_name = 'Changed body'".update.run >>
        sql"UPDATE lens SET friendly_name = 'Changed lens'".update.run

      for
        _            <- repository.createCamera(camera)
        _            <- repository.createLens(lens)
        catalogs     <- (change.transact(xa), Vector.fill(20)(()).traverse(_ => repository.readCatalog)).parMapN((_, values) => values)
        finalCatalog <- repository.readCatalog
      yield
        catalogs.foreach(actual => assert(Vector(before, after).contains(actual)))
        assertEquals(finalCatalog, after)

  test("should preserve catalog uniqueness under concurrent writes"):
    buildDatabase.flatMap(buildConnection).use: xa =>
      val repository = SqliteGearRepository.make[IO](xa)
      val other      = camera.copy(id = CameraId(new UUID(1L, 99L)))

      for
        results <- Vector(camera, other).parTraverse(repository.createCamera)
        valid   <- repository.readCatalog
      yield
        val outcomes = results.map:
          case CameraCreateResult.Created  => "created"
          case CameraCreateResult.Conflict => "conflict"
          case other                       => fail(s"Unexpected concurrent outcome: $other")

        assertEquals(outcomes.sorted, Vector("conflict", "created"))
        val expected = Vector(camera, other).map(saved => CatalogReadResult.Found(GearCatalog(cameras = Vector(saved), lenses = Vector.empty[Lens])))

        assert(expected.contains(valid))

  private def buildDatabase =
    buildSeededDatabase.evalTap: path =>
      buildConnection(path).use: xa =>
        (sql"DELETE FROM lens".update.run >> sql"DELETE FROM camera".update.run).transact(xa).void

  private def buildSeededDatabase =
    Resource.make(Files.createTempFile(Path.of("target"), "gear-", ".sqlite").blocking[IO])(path => Files.deleteIfExists(path).blocking[IO].void)
      .evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void)

  private def buildConnection(path: Path) =
    SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
