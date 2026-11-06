package photography.adapters.sqlite.spread

import photography.adapters.sqlite.FlywayMigrator
import photography.adapters.sqlite.SqliteConnection

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.typelevel.doobie.Fragment
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.app.config.SqliteConfig
import photography.domain.layout.*
import photography.ports.spread.SpreadTemplateRepository.*

import java.nio.file.{Files, Path}
import java.util.UUID

final class SqliteSpreadTemplateRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO]               = Slf4jLogger.getLogger[IO]
  private val defaultTemplateName        = "2 x (L/L) + Text"
  private val defaultChapterTemplateName = "Text + 2 x P + (L/L)"
  private val defaultPhotoLayout         = "00000000-0000-0000-0000-000000000113"

  test(s"should preserve ordered synopsis-right templates with $defaultTemplateName first"):
    buildTransactor.use: xa =>
      val repository     = SqliteSpreadTemplateRepository.make[IO](xa)
      val expectedPhotos = Vector(
        buildBox(x = 0.293982, y = 0.914609, width = 24.695519, height = 48.758744),
        buildBox(x = 25.199487, y = 0.914609, width = 24.695519, height = 48.758744),
        buildBox(x = 0.293982, y = 50.326646, width = 24.695519, height = 48.758744),
        buildBox(x = 25.199487, y = 50.326646, width = 24.695519, height = 48.758744)
      )
      val expectedText = buildBox(x = 50.104993, y = 0.914609, width = 49.601025, height = 98.170781)

      repository.listAlbumStartTemplates.map:
        case ListAlbumStartTemplatesResult.Listed(templates) =>
          val identities = templates.toList.map(_.id.value.toString)

          assertEquals(templates.size, 4)
          assertEquals(identities, identities.sorted)
          assertEquals(identities.distinct.size, 4)
          assertEquals(templates.head.id.value.toString, "00000000-0000-0000-0000-000000000001")
          assertEquals(templates.head.name: String, defaultTemplateName)
          assertEquals(templates.head.photoSlots, expectedPhotos)
          assertEquals(templates.head.textSlot, expectedText)
          assert(templates.toList.forall(template => template.photoSlots.nonEmpty && template.photoSlots.forall(_.x < 50.0)))
          assert(templates.toList.forall(_.textSlot.x >= 50.0))
        case result => fail(s"Expected migrated templates, obtained $result")

  test(s"should load the 27 chapter openings with $defaultChapterTemplateName first"):
    buildTransactor.use: xa =>
      val repository     = SqliteSpreadTemplateRepository.make[IO](xa)
      val expectedPhotos = Vector(
        buildBox(x = 25.199487, y = 0.914609, width = 23.666164, height = 98.170781),
        buildBox(x = 49.075638, y = 0.914609, width = 23.666164, height = 98.170781),
        buildBox(x = 72.951789, y = 0.914609, width = 26.754230, height = 48.758744),
        buildBox(x = 72.951789, y = 50.326646, width = 26.754230, height = 48.758744)
      )
      val expectedText = buildBox(x = 0.293982, y = 0.914609, width = 24.695519, height = 98.170781)

      repository.listChapterStartTemplates.map:
        case ListChapterStartTemplatesResult.Listed(templates) =>
          assertEquals(templates.size, 27)
          val identities = templates.toList.map(_.id.value.toString)

          assertEquals(identities, identities.sorted)
          assertEquals(identities.distinct.size, 27)
          assertEquals(templates.head.id.value.toString, "00000000-0000-0000-0000-000000000005")
          assertEquals(templates.head.name: String, defaultChapterTemplateName)
          assertEquals(templates.head.photoSlots, expectedPhotos)
          assertEquals(templates.head.textSlot, expectedText)
        case result => fail(s"Expected migrated chapter templates, obtained $result")

  test("should reject malformed or unavailable chapter opening catalogs"):
    Vector(
      "DELETE FROM spread_template_slot WHERE template_id IN (SELECT id FROM spread_template WHERE kind = 'chapter_start')",
      "DELETE FROM spread_template_slot WHERE kind = 'text' AND template_id IN (SELECT id FROM spread_template WHERE kind = 'chapter_start')",
      "DELETE FROM spread_template_slot WHERE kind = 'photo' AND template_id IN (SELECT id FROM spread_template WHERE kind = 'chapter_start')",
      "UPDATE spread_template_slot SET width = -1 WHERE template_id IN (SELECT id FROM spread_template WHERE kind = 'chapter_start')",
      "UPDATE spread_template SET name = '' WHERE kind = 'chapter_start'",
      "UPDATE spread_template_slot SET kind = 'unknown' WHERE template_id IN (SELECT id FROM spread_template WHERE kind = 'chapter_start')",
      """DELETE FROM spread_template_slot WHERE template_id IN (SELECT id FROM spread_template WHERE kind = 'chapter_start');
         DELETE FROM spread_template WHERE kind = 'chapter_start'"""
    ).traverse_ { corruption =>
      buildTransactor.use: xa =>
        val repository = SqliteSpreadTemplateRepository.make[IO](xa)
        val statements = corruption.split(";").toList.traverse_(statement => Fragment.const(statement).update.run)
        val corrupt    = sql"PRAGMA ignore_check_constraints = ON".update.run >> statements >> sql"PRAGMA ignore_check_constraints = OFF".update.run
        val expectedFailure = ListChapterStartTemplatesResult.Failed(Failure.DatabaseFailure)

        for
          _      <- corrupt.transact(xa)
          result <- repository.listChapterStartTemplates
        yield assertEquals(result, expectedFailure)
    }

  test("should reject malformed template catalogs and report database failures explicitly"):
    Vector(
      "DELETE FROM spread_template_slot",
      "UPDATE spread_template_slot SET width = -1",
      "UPDATE spread_template SET name = ''",
      "UPDATE spread_template_slot SET kind = 'unknown'",
      "UPDATE spread_template_slot SET kind = 'photo' WHERE kind = 'text'",
      "DROP TABLE spread_template_slot"
    ).traverse_ { corruption =>
      buildTransactor.use: xa =>
        val repository = SqliteSpreadTemplateRepository.make[IO](xa)
        val corrupt    = sql"PRAGMA ignore_check_constraints = ON".update.run >>
          Fragment.const(corruption).update.run >> sql"PRAGMA ignore_check_constraints = OFF".update.run
        val expectedFailure = ListAlbumStartTemplatesResult.Failed(Failure.DatabaseFailure)

        for
          _      <- corrupt.transact(xa)
          result <- repository.listAlbumStartTemplates
          found  <- repository.findTemplate(SpreadTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000001")))
        yield
          assertEquals(result, expectedFailure)
          assertEquals(found, FindTemplateResult.Failed(Failure.DatabaseFailure))
    }

  test(s"should load the 68 photo layouts with seeded default $defaultPhotoLayout and only photo slots"):
    buildTransactor.use: xa =>
      val repository = SqliteSpreadTemplateRepository.make[IO](xa)
      repository.listPhotosOnlyTemplates.map:
        case ListPhotosOnlyTemplatesResult.Listed(templates) =>
          val identities = templates.toList.map(_.id.value.toString)
          val defaults   = templates.toList.filter(_.id.value.toString === defaultPhotoLayout)

          assertEquals(templates.size, 68)
          assertEquals(identities, identities.sorted)
          assertEquals(identities.distinct.size, 68)
          assertEquals(templates.head.id.value.toString, "00000000-0000-0000-0000-000000000101")
          assertEquals(defaults.map(_.name: String), List("4 x (L/L)"))
          assertEquals(defaults.map(_.photoSlots.size), List(8))
          assert(templates.toList.forall(template => template.photoSlots.size >= 1 && template.photoSlots.size <= 24))
        case result => fail(s"Expected seeded photo layouts, obtained $result")

  test("should resolve each catalog kind by template identity and report unknown identities"):
    buildTransactor.use: xa =>
      val repository = SqliteSpreadTemplateRepository.make[IO](xa)
      val unknown    = "00000000-0000-0000-0000-00000000ffff"

      for
        album   <- repository.findTemplate(SpreadTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000001")))
        chapter <- repository.findTemplate(SpreadTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000005")))
        photo   <- repository.findTemplate(SpreadTemplateId(UUID.fromString(defaultPhotoLayout)))
        missing <- repository.findTemplate(SpreadTemplateId(UUID.fromString(unknown)))
      yield
        assertEquals(getTemplateKind(album), "album_start")
        assertEquals(getTemplateKind(chapter), "chapter_start")
        assertEquals(getTemplateKind(photo), "photos_only")
        assertEquals(missing, FindTemplateResult.NotFound)

  test("should reject malformed photo layout catalogs explicitly"):
    Vector(
      "UPDATE spread_template_slot SET kind = 'text' WHERE template_id IN (SELECT id FROM spread_template WHERE kind = 'photos_only')",
      "DELETE FROM spread_template_slot WHERE template_id IN (SELECT id FROM spread_template WHERE kind = 'photos_only')"
    ).traverse_ { corruption =>
      buildTransactor.use: xa =>
        val repository = SqliteSpreadTemplateRepository.make[IO](xa)
        val corrupt    = sql"PRAGMA ignore_check_constraints = ON".update.run >>
          Fragment.const(corruption).update.run >> sql"PRAGMA ignore_check_constraints = OFF".update.run

        for
          _      <- corrupt.transact(xa)
          result <- repository.listPhotosOnlyTemplates
        yield assertEquals(result, ListPhotosOnlyTemplatesResult.Failed(Failure.DatabaseFailure))
    }

  private def getTemplateKind(result: FindTemplateResult) = result match
    case FindTemplateResult.Found(_: SpreadTemplate.AlbumStart)   => "album_start"
    case FindTemplateResult.Found(_: SpreadTemplate.ChapterStart) => "chapter_start"
    case FindTemplateResult.Found(_: SpreadTemplate.PhotosOnly)   => "photos_only"
    case other                                                    => s"unexpected $other"

  private def buildTransactor =
    val acquireDatabase = IO.blocking(Files.createTempFile(Path.of("target"), "template-", ".sqlite"))

    for
      path <- Resource.make(acquireDatabase)(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa

  private def buildBox(x: Double, y: Double, width: Double, height: Double) =
    SlotBox(x = x.refineUnsafe, y = y.refineUnsafe, width = width.refineUnsafe, height = height.refineUnsafe)
