package photography.usecases.chapter
import photography.domain.gear.CameraSelection

import photography.ports.spread.SpreadTemplateRepository

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.usecases.chapter.ChapterMetadataManager.Period
import photography.usecases.chapter.ChapterPlanning.{CreationResult, UpdateResult}
import photography.ports.spread.SpreadTemplateRepository.{ListAlbumStartTemplatesResult, ListChapterStartTemplatesResult}
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

final class ChapterPlanningComponentTest extends FunSuite:
  private given IdGenerator[IO] with
    override def generateId: IO[UUID] = new UUID(0L, 1L).pure[IO]
  private val country = Country(CountryId(new UUID(0L, 2L)), code = "JP", name = "Japan", AbsoluteLocation(Path.of("/flags/jp.svg")))
  private val box     = SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0)
  private val opening =
    val slot = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 3L)), box)
    Spread.ChapterStart(
      SpreadId(new UUID(0L, 4L)),
      SpreadTemplateId(new UUID(0L, 5L)),
      photoSlots = Vector.empty[SpreadSlot.Photo],
      slot,
      placements = Vector.empty[SpreadPlacement]
    )
  private val chapter =
    val destination = Destination(DestinationId(new UUID(0L, 6L)), name = "Kyoto", countries = NonEmptyList.one(country))
    val period      = VisitPeriod(firstDay = LocalDate.of(2026, 5, 1), days = 2)
    Chapter(
      ChapterId(new UUID(0L, 7L)),
      destination,
      period,
      description = "",
      gear = Vector.empty[CameraSelection],
      sourceFolder = "/photos/kyoto",
      opening,
      spreads = Vector.empty[Spread.PhotosOnly]
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
        ListChapterStartTemplatesResult.Failed(SpreadTemplateRepository.Failure.DatabaseFailure).pure[IO]
    ChapterPlanning.make(templates)

  test("should resolve full-year creation from the requested album year"):
    Vector((2024: AlbumYear, 366: VisitDays), (2026: AlbumYear, 365: VisitDays)).foreach { case (year, days) =>
      val expected = VisitPeriod(firstDay = LocalDate.of(year, 1, 1), days = days)

      val result =
        planning.planCreation(year, chapters = Vector.empty[Chapter], spreadCount = 1, name = "Home", Period.AlbumYear, folder = "/photos/home")

      assertEquals(result, CreationResult.Planned(expected))
    }

  test("should prioritize capacity and Home conflicts over folder conflicts"):
    val home   = chapter.copy(destination = chapter.destination.copy(name = "Home"))
    val period = Period.Dates(chapter.period)

    val full        = planning.planCreation(year = 2026, chapters = Vector(home), spreadCount = 65, name = "Home", period, chapter.sourceFolder)
    val duplicate   = planning.planCreation(year = 2026, chapters = Vector(home), spreadCount = 2, name = "Home", period, chapter.sourceFolder)
    val wrongPeriod = planning.planCreation(year = 2026, chapters = Vector(chapter), spreadCount = 2, name = "Home", period, chapter.sourceFolder)
    val reused      = planning.planCreation(year = 2026, chapters = Vector(chapter), spreadCount = 2, name = "Kyoto", period, chapter.sourceFolder)

    assertEquals(full, CreationResult.Rejected(ChapterConflict.AtCapacity))
    assertEquals(duplicate, CreationResult.Rejected(ChapterConflict.HomeExists))
    assertEquals(wrongPeriod, CreationResult.Rejected(ChapterConflict.HomePeriodMismatch))
    assertEquals(reused, CreationResult.Rejected(ChapterConflict.FolderAssigned))

  test("should plan edits from saved identity and source facts without conflicting with the edited chapter"):
    val chapters      = Vector(chapter)
    val period        = Period.Dates(chapter.period)
    val expectedOrder = NonEmptyList.one(chapter.id)
    val expected      = ChapterPlanning.UpdatePlan(chapter.destination.id, sourceChanged = false, chapter.period, order = expectedOrder)

    val unchanged  = planning.planUpdate(year = 2026, chapters = chapters, chapter.id, name = "Kyoto", period, chapter.sourceFolder)
    val changed    = planning.planUpdate(year = 2026, chapters = chapters, chapter.id, name = "Kyoto", period, folder = "/photos/new")
    val missing    = planning.planUpdate(year = 2026, chapters = Vector.empty[Chapter], chapter.id, name = "Kyoto", period, chapter.sourceFolder)
    val typeChange = planning.planUpdate(year = 2026, chapters = chapters, chapter.id, name = "Home", Period.AlbumYear, chapter.sourceFolder)

    assertEquals(unchanged, UpdateResult.Planned(expected))
    assertEquals(changed, UpdateResult.Planned(expected.copy(sourceChanged = true)))
    assertEquals(missing, UpdateResult.ChapterNotFound)
    assertEquals(typeChange, UpdateResult.Rejected(ChapterConflict.TypeChange))

  test("should order ordinary chapters by start, duration and identity before Home"):
    val shorter  = chapter.copy(id = ChapterId(new UUID(0L, 8L)), period = chapter.period.copy(days = 1))
    val tied     = shorter.copy(id = ChapterId(new UUID(0L, 9L)))
    val later    = chapter.copy(id = ChapterId(new UUID(0L, 10L)), period = chapter.period.copy(firstDay = LocalDate.of(2026, 5, 2)))
    val home     = chapter.copy(id = ChapterId(new UUID(0L, 11L)), destination = chapter.destination.copy(name = "Home"))
    val chapters = Vector(home, later, tied, chapter, shorter)
    val expected = NonEmptyList.of(shorter.id, tied.id, chapter.id, later.id, home.id)

    val result = planning.orderChapters(chapters = chapters, chapter.id, isHome = false, chapter.period)

    assertEquals(result, expected)
