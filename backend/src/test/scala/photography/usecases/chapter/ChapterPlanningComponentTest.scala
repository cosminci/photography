package photography.usecases.chapter
import photography.ports.spread.SpreadTemplateRepository

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.album.AlbumRepository.{ChapterMetadata, PlanningSnapshot}
import photography.usecases.chapter.ChapterMetadataManager.Period
import photography.usecases.chapter.ChapterPlanning.{CreationResult, UpdateResult}
import photography.ports.spread.SpreadTemplateRepository.{ListAlbumStartTemplatesResult, ListChapterStartTemplatesResult}
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

final class ChapterPlanningComponentTest extends CatsEffectSuite:
  private given IdGenerator[IO] with
    override def generateId: IO[UUID] = new UUID(0L, 1L).pure[IO]
  private val country   = Country(CountryId(new UUID(0L, 2L)), code = "JP", name = "Japan", AbsoluteLocation(Path.of("/flags/jp.svg")))
  private val countries = NonEmptyList.one(country.id)
  private val box       = SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0)
  private val template  =
    SpreadTemplate.ChapterStart(SpreadTemplateId(new UUID(0L, 3L)), name = "Opening", photoSlots = Vector.empty[SlotBox], textSlot = box)
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
  private val planning =
    val templates = new SpreadTemplateRepository[IO]:
      override def listPhotosOnlyTemplates: IO[SpreadTemplateRepository.ListPhotosOnlyTemplatesResult] =
        IO.raiseError[SpreadTemplateRepository.ListPhotosOnlyTemplatesResult](new AssertionError("Unexpected photo templates"))
      override def findTemplate(id: SpreadTemplateId): IO[SpreadTemplateRepository.FindTemplateResult] =
        IO.raiseError[SpreadTemplateRepository.FindTemplateResult](new AssertionError("Unexpected template lookup"))
      override def listAlbumStartTemplates: IO[ListAlbumStartTemplatesResult] =
        ListAlbumStartTemplatesResult.Failed(SpreadTemplateRepository.Failure.DatabaseFailure).pure[IO]
      override def listChapterStartTemplates: IO[ListChapterStartTemplatesResult] =
        ListChapterStartTemplatesResult.Listed(NonEmptyList.one(template)).pure[IO]
    ChapterPlanning.make(templates)

  test("should resolve full-year creation and updates from the requested album year"):
    Vector((2024: AlbumYear, 366: VisitDays), (2026: AlbumYear, 365: VisitDays)).traverse_ { case (year, days) =>
      val expected       = VisitPeriod(firstDay = LocalDate.of(year, 1, 1), days = days)
      val home           = chapter.copy(destination = chapter.destination.copy(name = "Home"), period = expected)
      val update         = planning.planUpdate(year, chapters = Vector(home), home.id, name = "Home", Period.AlbumYear, home.sourceFolder)
      val expectedUpdate = ChapterPlanning.UpdatePlan(home.sourceFolder, expected, order = NonEmptyList.one(home.id))

      val result =
        planning.planCreation(
          PlanningSnapshot(year, chapters = Vector.empty[ChapterMetadata], spreadCount = 1),
          name = "Home",
          countries,
          Period.AlbumYear,
          folder = "/photos/home"
        )

      result.map:
        case CreationResult.Planned(created, order) =>
          assertEquals(created.period, expected)
          assertEquals(order, NonEmptyList.one(created.id))
          assertEquals(update, UpdateResult.Planned(expectedUpdate))
        case other => fail(s"Expected creation plan, got $other")
    }

  test("should prioritize capacity and Home conflicts over folder conflicts"):
    val home         = chapter.copy(destination = chapter.destination.copy(name = "Home"))
    val period       = Period.Dates(chapter.period)
    val homeAlbum    = PlanningSnapshot(year = 2026, chapters = Vector(home), spreadCount = 2)
    val regularAlbum = homeAlbum.copy(chapters = Vector(chapter))
    val scenarios    = Vector(
      (homeAlbum.copy(spreadCount = 65), "Home": DestinationName, ChapterConflict.AtCapacity),
      (homeAlbum, "Home": DestinationName, ChapterConflict.HomeExists),
      (regularAlbum, "Home": DestinationName, ChapterConflict.HomePeriodMismatch),
      (regularAlbum, "Kyoto": DestinationName, ChapterConflict.FolderAssigned)
    )

    scenarios.traverse_ { case (album, name, expected) =>
      planning.planCreation(album, name, countries, period, chapter.sourceFolder).map: actual =>
        assertEquals(actual, CreationResult.Rejected(expected))
    }

  test("should plan edits from saved identity and source facts without conflicting with the edited chapter"):
    val chapters      = Vector(chapter)
    val period        = Period.Dates(chapter.period)
    val expectedOrder = NonEmptyList.one(chapter.id)
    val expected      = ChapterPlanning.UpdatePlan(chapter.sourceFolder, chapter.period, order = expectedOrder)

    val unchanged = planning.planUpdate(year = 2026, chapters = chapters, chapter.id, name = "Kyoto", period, chapter.sourceFolder)
    val changed   = planning.planUpdate(year = 2026, chapters = chapters, chapter.id, name = "Kyoto", period, folder = "/photos/new")
    val missing = planning.planUpdate(year = 2026, chapters = Vector.empty[ChapterMetadata], chapter.id, name = "Kyoto", period, chapter.sourceFolder)
    val home    = chapter.copy(destination = chapter.destination.copy(name = "Home"))
    val typeChanges = Vector((chapter, "Home": DestinationName), (home, "Kyoto": DestinationName)).map { case (existing, name) =>
      planning.planUpdate(year = 2026, chapters = Vector(existing), existing.id, name, Period.AlbumYear, folder = "/photos/new")
    }

    assertEquals(unchanged, UpdateResult.Planned(expected))
    assertEquals(changed, UpdateResult.Planned(expected))
    assertEquals(expected.changesSource(chapter.sourceFolder), false)
    assertEquals(expected.changesSource("/photos/new"), true)
    assertEquals(missing, UpdateResult.ChapterNotFound)
    assertEquals(typeChanges, Vector.fill(2)(UpdateResult.Rejected(ChapterConflict.TypeChange)))

  test("should order ordinary chapters by start, duration and identity before Home"):
    val shorter  = chapter.copy(id = ChapterId(new UUID(0L, 8L)), period = chapter.period.copy(days = 1))
    val tied     = shorter.copy(id = ChapterId(new UUID(0L, 9L)))
    val later    = chapter.copy(id = ChapterId(new UUID(0L, 10L)), period = chapter.period.copy(firstDay = LocalDate.of(2026, 5, 2)))
    val home     = chapter.copy(id = ChapterId(new UUID(0L, 11L)), destination = chapter.destination.copy(name = "Home"))
    val chapters = Vector(home, later, tied, chapter, shorter)
    val expected = NonEmptyList.of(shorter.id, tied.id, chapter.id, later.id, home.id)

    val result =
      planning.planUpdate(year = 2026, chapters = chapters, chapter.id, name = "Kyoto", Period.Dates(chapter.period), folder = "/photos/new")

    val album = PlanningSnapshot(year = 2026, chapters = chapters, spreadCount = 6)
    planning.planCreation(album, name = "Kyoto", countries, Period.Dates(chapter.period), folder = "/photos/new").map:
      case CreationResult.Planned(created, order) =>
        assertEquals(order, NonEmptyList.of(shorter.id, tied.id, created.id, chapter.id, later.id, home.id))
        assertEquals(result, UpdateResult.Planned(ChapterPlanning.UpdatePlan(chapter.sourceFolder, chapter.period, order = expected)))
      case other => fail(s"Expected an ordered creation plan, got $other")
