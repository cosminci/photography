package photography.adapters.sqlite.album

import photography.adapters.sqlite.FlywayMigrator
import photography.adapters.sqlite.SqliteConnection

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.typelevel.doobie.Fragment
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.app.config.SqliteConfig
import photography.ports.album.CountryRepository.ListResult

import java.nio.file.{Files, Path}

final class SqliteCountryRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  test("should list all 249 distinct valid countries in name and identity order without changing their records"):
    buildTransactor.use: xa =>
      val repository = SqliteCountryRepository.make[IO](xa)
      val inventory  = sql"SELECT id, code, name, flag FROM country ORDER BY name, id".query[(String, String, String, String)].to[List]

      for
        expected <- inventory.transact(xa)
        result   <- repository.list
      yield result match
        case ListResult.Listed(countries) =>
          val actual =
            countries.toList.map(country => (country.id.value.toString, country.code: String, country.name: String, country.flag.value.toString))
          val expectedRomania = ("10000000-0000-0000-0000-000000000001", "RO", "Romania", "/flags/ro.png")
          val romania         = actual.find { case (_, code, _, _) => code === "RO" }

          assertEquals(actual, expected)
          assertEquals(countries.size, 249)
          assertEquals(countries.toList.map(_.id).distinct.size, 249)
          assertEquals(countries.toList.map(_.code).distinct.size, 249)
          assertEquals(romania, expectedRomania.some)
          countries.toList.foreach { country =>
            assertEquals(country.flag.value.toString, s"/flags/${country.code.toLowerCase(java.util.Locale.ROOT)}.png")
            assert(Option(getClass.getResource(s"/frontend${country.flag.value}")).nonEmpty, s"Missing packaged flag for ${country.code}")
          }
          assert(countries.toList.forall(country => country.code.matches("[A-Z]{2}") && country.name.nonEmpty && country.flag.value.isAbsolute))
        case result => fail(s"Expected the complete country catalog, obtained $result")

  test("should order equal country names by identity"):
    buildTransactor.use: xa =>
      val repository = SqliteCountryRepository.make[IO](xa)
      val rename     = sql"UPDATE country SET name = 'Same name'".update.run
      val identities = sql"SELECT id FROM country ORDER BY id".query[String].to[List]

      for
        _        <- rename.transact(xa)
        expected <- identities.transact(xa)
        result   <- repository.list
      yield result match
        case ListResult.Listed(countries) => assertEquals(countries.toList.map(_.id.value.toString), expected)
        case result                       => fail(s"Expected ordered country catalog, obtained $result")

  test("should fail explicitly for an empty, malformed or unavailable country catalog"):
    Vector(
      "DELETE FROM country",
      "UPDATE country SET id = 'not-a-uuid' WHERE code = 'RO'",
      "UPDATE country SET id = '1-1-1-1-1' WHERE code = 'RO'",
      "UPDATE country SET code = 'ro' WHERE code = 'RO'",
      "UPDATE country SET code = 'ROM' WHERE code = 'RO'",
      "UPDATE country SET name = '' WHERE code = 'RO'",
      "UPDATE country SET flag = 'flags/ro.svg' WHERE code = 'RO'",
      "UPDATE country SET flag = char(0) WHERE code = 'RO'",
      "DROP TABLE country"
    ).traverse_ { corruption =>
      buildTransactor.use: xa =>
        val repository = SqliteCountryRepository.make[IO](xa)
        val corrupt    = sql"PRAGMA ignore_check_constraints = ON".update.run >>
          Fragment.const(corruption).update.run >> sql"PRAGMA ignore_check_constraints = OFF".update.run

        for
          _      <- corrupt.transact(xa)
          result <- repository.list
        yield assertEquals(result, ListResult.Failed)
    }

  private def buildTransactor =
    val acquireDatabase = IO.blocking(Files.createTempFile(Path.of("target"), "country-", ".sqlite"))

    for
      path <- Resource.make(acquireDatabase)(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa
