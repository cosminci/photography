package photography.usecases.chapter
import photography.ports.spread.SpreadTemplateRepository

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.album.AlbumRepository.{ChapterMetadata, PlanningSnapshot}
import photography.ports.chapter.ChapterRepository
import photography.usecases.chapter.ChapterMetadataManager.Period
import photography.usecases.chapter.ChapterPlanning.{CreationResult, UpdateResult}
import photography.ports.spread.SpreadTemplateRepository.{ListAlbumStartTemplatesResult, ListChapterStartTemplatesResult}
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

final class ChapterPlanningComponentTest extends CatsEffectSuite:
  private val country   = Country(CountryId(new UUID(0L, 2L)), code = "JP", name = "Japan", AbsoluteLocation(Path.of("/flags/jp.svg")))
  private val countries = NonEmptyList.of(country.id, CountryId(new UUID(0L, 6L)))
  private val box       = SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0)
  private val inset     = SlotBox(x = 10.0, y = 20.0, width = 30.0, height = 40.0)
  private val template  =
    SpreadTemplate.ChapterStart(SpreadTemplateId(new UUID(0L, 3L)), name = "Opening", photoSlots = Vector(box, inset), textSlot = box)
  private val opening = Spread.ChapterStart(
    SpreadId(new UUID(0L, 2L)),
    template.id,
    photoSlots = Vector(
      SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(0L, 3L)), box, bleed = Set.empty),
      SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(0L, 4L)), inset, bleed = Set.empty)
    ),
    textSlot = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 5L)), box),
    placements = Vector.empty[SpreadPlacement]
  )
  private val chapter =
    val destination = Destination(name = "Kyoto", countries = NonEmptyList.one(country))
    val period      = VisitPeriod(firstDay = LocalDate.of(2026, 5, 1), days = 2)
    ChapterMetadata(
      ChapterId(new UUID(0L, 7L)),
      destination,
      period,
      sourceFolder = "/photos/kyoto",
      description = ""
    )
  private val newChapter = ChapterRepository.NewChapter(
    ChapterId(new UUID(0L, 1L)),
    name = "Kyoto",
    countries,
    chapter.period,
    description = "",
    sourceFolder = "/photos/new",
    opening
  )

  test("should plan a complete empty opening from the first template and resolve Home to the album year"):
    Vector((2024: AlbumYear, 366: VisitDays), (2026: AlbumYear, 365: VisitDays)).traverse_ { case (year, days) =>
      val planning         = buildPlanning()
      val expected         = VisitPeriod(firstDay = LocalDate.of(year, 1, 1), days = days)
      val home             = chapter.copy(destination = chapter.destination.copy(name = "Home"), period = expected)
      val saved            = PlanningSnapshot(year, chapters = Vector(home), spreadCount = 2)
      val update           = planning.planUpdate(saved, home.id, name = "Home", Period.AlbumYear, home.sourceFolder)
      val expectedUpdate   = ChapterPlanning.UpdatePlan(home.sourceFolder, expected, order = NonEmptyList.one(home.id))
      val created          = newChapter.copy(name = "Home", period = expected, sourceFolder = "/photos/home")
      val expectedCreation = CreationResult.Planned(created, order = NonEmptyList.one(created.id))

      val result =
        planning.planCreation(
          PlanningSnapshot(year, chapters = Vector.empty[ChapterMetadata], spreadCount = 1),
          name = "Home",
          countries,
          Period.AlbumYear,
          folder = "/photos/home"
        )

      result.map: actual =>
        assertEquals(actual, expectedCreation)
        assertEquals(update, UpdateResult.Planned(expectedUpdate))
    }

  test("should plan edits from saved identity and source facts without conflicting with the edited chapter"):
    val planning      = buildPlanning()
    val chapters      = Vector(chapter)
    val period        = Period.Dates(chapter.period)
    val expectedOrder = NonEmptyList.one(chapter.id)
    val expected      = ChapterPlanning.UpdatePlan(chapter.sourceFolder, chapter.period, order = expectedOrder)
    val album         = PlanningSnapshot(year = 2026, chapters = chapters, spreadCount = 2)

    val unchanged = planning.planUpdate(album, chapter.id, name = "Kyoto", period, chapter.sourceFolder)
    val changed   = planning.planUpdate(album, chapter.id, name = "Kyoto", period, folder = "/photos/new")
    val missing = planning.planUpdate(album.copy(chapters = Vector.empty[ChapterMetadata]), chapter.id, name = "Kyoto", period, chapter.sourceFolder)
    val home    = chapter.copy(destination = chapter.destination.copy(name = "Home"))
    val typeChanges = Vector((chapter, "Home": DestinationName), (home, "Kyoto": DestinationName)).map { case (existing, name) =>
      planning.planUpdate(album.copy(chapters = Vector(existing)), existing.id, name, Period.AlbumYear, folder = "/photos/new")
    }

    assertEquals(unchanged, UpdateResult.Planned(expected))
    assertEquals(changed, UpdateResult.Planned(expected))
    assertEquals(missing, UpdateResult.ChapterNotFound)
    assertEquals(typeChanges, Vector.fill(2)(UpdateResult.Rejected(ChapterConflict.TypeChange)))

  test("should order ordinary chapters by start, duration and identity before Home"):
    val planning = buildPlanning()
    val shorter  = chapter.copy(id = ChapterId(new UUID(0L, 8L)), period = chapter.period.copy(days = 1))
    val tied     = shorter.copy(id = ChapterId(new UUID(0L, 9L)))
    val later    = chapter.copy(id = ChapterId(new UUID(0L, 10L)), period = chapter.period.copy(firstDay = LocalDate.of(2026, 5, 2)))
    val home     = chapter.copy(id = ChapterId(new UUID(0L, 11L)), destination = chapter.destination.copy(name = "Home"))
    val chapters = Vector(home, later, tied, chapter, shorter)
    val expected = NonEmptyList.of(shorter.id, tied.id, chapter.id, later.id, home.id)
    val album    = PlanningSnapshot(year = 2026, chapters = chapters, spreadCount = 6)

    val result =
      planning.planUpdate(album, chapter.id, name = "Kyoto", Period.Dates(chapter.period), folder = "/photos/new")

    planning.planCreation(album, name = "Kyoto", countries, Period.Dates(chapter.period), folder = "/photos/new").map:
      case CreationResult.Planned(created, order) =>
        assertEquals(order, NonEmptyList.of(shorter.id, tied.id, created.id, chapter.id, later.id, home.id))
        assertEquals(result, UpdateResult.Planned(ChapterPlanning.UpdatePlan(chapter.sourceFolder, chapter.period, order = expected)))
      case other => fail(s"Expected an ordered creation plan, got $other")

  test("should prioritize Home conflicts over occupied sources for creation and edits"):
    val planning  = buildPlanning()
    val edited    = chapter.copy(id = ChapterId(new UUID(0L, 20L)), sourceFolder = "/photos/edited")
    val fullYear  = VisitPeriod(LocalDate.of(2026, 1, 1), days = 365)
    val home      = edited.copy(destination = edited.destination.copy(name = "Home"), period = chapter.period.copy(days = 365))
    val leapHome  = home.copy(period = fullYear.copy(days = 366))
    val scenarios = Vector(
      (existing = chapter, proposed = home, conflict = ChapterConflict.HomePeriodMismatch),
      (existing = chapter, proposed = leapHome, conflict = ChapterConflict.HomePeriodMismatch),
      (existing = chapter, proposed = edited, conflict = ChapterConflict.FolderAssigned),
      (existing = chapter.copy(destination = home.destination, period = fullYear), proposed = home, conflict = ChapterConflict.HomeExists)
    )

    scenarios.traverse_ { case (existing, proposed, conflict) =>
      val album  = PlanningSnapshot(year = 2026, chapters = Vector(existing), spreadCount = 2)
      val period = Period.Dates(proposed.period)
      val saved  = album.chapters :+ proposed.copy(period = fullYear)
      val update = planning.planUpdate(album.copy(chapters = saved), proposed.id, proposed.destination.name, period, existing.sourceFolder)

      planning.planCreation(album, proposed.destination.name, countries, period, existing.sourceFolder).map: actual =>
        assertEquals(actual, CreationResult.Rejected(conflict))
        assertEquals(update, UpdateResult.Rejected(conflict))
    }

  test("should admit the last opening but keep edits available at capacity"):
    val planning  = buildPlanning()
    val album     = PlanningSnapshot(year = 2026, chapters = Vector(chapter), spreadCount = 64)
    val period    = Period.Dates(chapter.period)
    val fullAlbum = album.copy(spreadCount = 65)
    val expected  = UpdateResult.Planned(ChapterPlanning.UpdatePlan(chapter.sourceFolder, chapter.period, NonEmptyList.one(chapter.id)))

    for
      admitted <- planning.planCreation(album, name = "Kyoto", countries, period, folder = "/photos/new")
      full     <- planning.planCreation(fullAlbum, name = "Home", countries, period, folder = chapter.sourceFolder)
    yield
      assertEquals(admitted, CreationResult.Planned(newChapter, NonEmptyList.of(newChapter.id, chapter.id)))
      assertEquals(full, CreationResult.Rejected(ChapterConflict.AtCapacity))
      assertEquals(planning.planUpdate(fullAlbum, chapter.id, name = "Kyoto", period, folder = "/photos/new"), expected)

  test("should report unavailable opening templates instead of producing a partial plan"):
    val planning = buildPlanning(available = ListChapterStartTemplatesResult.Failed(SpreadTemplateRepository.Failure.DatabaseFailure))
    val album    = PlanningSnapshot(year = 2026, chapters = Vector.empty[ChapterMetadata], spreadCount = 1)

    planning.planCreation(album, name = "Kyoto", countries, Period.Dates(chapter.period), chapter.sourceFolder)
      .map(actual => assertEquals(actual, CreationResult.Failed))

  // scalafix:off DisableSyntax.defaultArgs
  private def buildPlanning(
      available: ListChapterStartTemplatesResult =
        ListChapterStartTemplatesResult.Listed(NonEmptyList.of(template, template.copy(id = SpreadTemplateId(new UUID(0L, 30L)), textSlot = inset)))
  ) =
    given IdGenerator[IO] with
      private val next                  = Ref.unsafe[IO, Long](1L)
      override def generateId: IO[UUID] = next.getAndUpdate(_ + 1L).map(value => new UUID(0L, value))
    val templates = new SpreadTemplateRepository[IO]:
      override def listPhotosOnlyTemplates: IO[SpreadTemplateRepository.ListPhotosOnlyTemplatesResult] =
        new AssertionError("Unexpected photo templates").raiseError[IO, SpreadTemplateRepository.ListPhotosOnlyTemplatesResult]
      override def findTemplate(id: SpreadTemplateId): IO[SpreadTemplateRepository.FindTemplateResult] =
        new AssertionError("Unexpected template lookup").raiseError[IO, SpreadTemplateRepository.FindTemplateResult]
      override def listAlbumStartTemplates: IO[ListAlbumStartTemplatesResult] =
        new AssertionError("Unexpected album templates").raiseError[IO, ListAlbumStartTemplatesResult]
      override def listChapterStartTemplates: IO[ListChapterStartTemplatesResult] = available.pure[IO]
    ChapterPlanning.make(templates)
  // scalafix:on DisableSyntax.defaultArgs
