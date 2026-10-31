package photography.usecases.chapter

import photography.ports.spread.SpreadTemplateRepository

import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.chapter.ChapterRepository
import photography.usecases.chapter.ChapterMetadataManager.Period
import photography.usecases.chapter.ChapterPlanning.*
import photography.ports.spread.SpreadTemplateRepository.ListChapterStartTemplatesResult
import java.time.{LocalDate, Year}

trait ChapterPlanning[F[_]]:
  def planCreation(
      year: AlbumYear,
      chapters: Vector[Chapter],
      spreadCount: Int,
      name: DestinationName,
      period: Period,
      folder: PhotoSource
  ): CreationResult
  def planUpdate(year: AlbumYear, chapters: Vector[Chapter], id: ChapterId, name: DestinationName, period: Period, folder: PhotoSource): UpdateResult
  def orderChapters(chapters: Vector[Chapter], id: ChapterId, isHome: Boolean, period: VisitPeriod): NonEmptyList[ChapterId]
  def buildChapter(name: DestinationName, countries: NonEmptyList[CountryId], period: VisitPeriod, source: PhotoSource): F[BuildResult]

object ChapterPlanning:
  def make[F[_]: Monad: IdGenerator](templates: SpreadTemplateRepository[F]): ChapterPlanning[F] = new LiveChapterPlanning(templates)

  final private class LiveChapterPlanning[F[_]: Monad: IdGenerator](templates: SpreadTemplateRepository[F]) extends ChapterPlanning[F]:
    override def planCreation(
        year: AlbumYear,
        chapters: Vector[Chapter],
        spreadCount: Int,
        name: DestinationName,
        period: Period,
        folder: PhotoSource
    ): CreationResult =
      val resolved      = resolvePeriod(year, period)
      val maybeConflict =
        if InteriorSpreadCapacity.isReached(spreadCount) then ChapterConflict.AtCapacity.some
        else findMetadataConflict(year, chapters = chapters, name, resolved, folder)
      maybeConflict.fold[CreationResult](CreationResult.Planned(resolved))(CreationResult.Rejected(_))

    override def planUpdate(
        year: AlbumYear,
        chapters: Vector[Chapter],
        id: ChapterId,
        name: DestinationName,
        period: Period,
        folder: PhotoSource
    ): UpdateResult =
      chapters.find(_.id.value === id.value).fold[UpdateResult](UpdateResult.ChapterNotFound): existing =>
        val resolved      = resolvePeriod(year, period)
        val others        = chapters.filterNot(_.id.value === id.value)
        val maybeConflict =
          if existing.destination.isHome =!= ((name: String) === "Home") then ChapterConflict.TypeChange.some
          else findMetadataConflict(year, chapters = others, name, resolved, folder)
        val order = orderChapters(chapters = chapters, id, isHome = (name: String) === "Home", resolved)
        val plan  = UpdatePlan(existing.destination.id, sourceChanged = (existing.sourceFolder: String) =!= folder, resolved, order = order)
        maybeConflict.fold[UpdateResult](UpdateResult.Planned(plan))(UpdateResult.Rejected(_))

    override def orderChapters(chapters: Vector[Chapter], id: ChapterId, isHome: Boolean, period: VisitPeriod): NonEmptyList[ChapterId] =
      val others = chapters.filterNot(_.id.value === id.value).map(value => ChapterOrder(value.id, isHome = value.destination.isHome, value.period))
      NonEmptyList(ChapterOrder(id, isHome = isHome, period), tail = others.toList)
        .sortBy(value => (value.isHome, value.period.firstDay.toEpochDay, value.period.days: Long, value.id.value.toString)).map(_.id)

    override def buildChapter(
        name: DestinationName,
        countries: NonEmptyList[CountryId],
        period: VisitPeriod,
        source: PhotoSource
    ): F[BuildResult] =
      templates.listChapterStartTemplates.flatMap:
        case ListChapterStartTemplatesResult.Failed(_)         => (BuildResult.Failed: BuildResult).pure[F]
        case ListChapterStartTemplatesResult.Listed(available) =>
          val template = available.head
          for
            id          <- IdGenerator[F].generateId.map(ChapterId(_))
            opening     <- buildOpening(template.id, photoSlots = template.photoSlots, template.textSlot)
            destination <- IdGenerator[F].generateId.map(DestinationId(_))
          yield
            val chapter = ChapterRepository.NewChapter(id, destination, name, countries = countries, period, description = "", source, opening)
            BuildResult.Built(chapter)

    private def findMetadataConflict(year: AlbumYear, chapters: Vector[Chapter], name: DestinationName, period: VisitPeriod, folder: PhotoSource) =
      val maybeHomeConflict =
        if (name: String) === "Home" then findHomeConflict(year, period, homeExists = chapters.exists(_.destination.isHome)) else None
      maybeHomeConflict.orElse(Option.when(chapters.exists(value => (value.sourceFolder: String) === folder))(ChapterConflict.FolderAssigned))

    private def resolvePeriod(year: AlbumYear, period: Period) = period match
      case Period.AlbumYear    => buildYearPeriod(year)
      case Period.Dates(value) => value

    private def findHomeConflict(year: AlbumYear, period: VisitPeriod, homeExists: Boolean) =
      val expected    = buildYearPeriod(year)
      val matchesYear = period.firstDay.compareTo(expected.firstDay) === 0 && period.days.compare(expected.days) === 0
      if homeExists then ChapterConflict.HomeExists.some
      else if !matchesYear then ChapterConflict.HomePeriodMismatch.some
      else None

    private def buildYearPeriod(year: AlbumYear) =
      val days: VisitDays = if Year.isLeap(year.toLong) then 366L else 365L
      VisitPeriod(firstDay = LocalDate.of(year, 1, 1), days = days)

    private def buildOpening(template: SpreadTemplateId, photoSlots: Vector[SlotBox], textSlot: SlotBox) =
      val newPhotos = photoSlots.traverse(box => IdGenerator[F].generateId.map(id => SpreadSlot.Photo(PhotoSpreadSlotId(id), box, bleed = Set.empty)))
      for
        id     <- IdGenerator[F].generateId.map(SpreadId(_))
        photos <- newPhotos
        text   <- IdGenerator[F].generateId.map(slot => SpreadSlot.Text(TextSpreadSlotId(slot), textSlot))
      yield Spread.ChapterStart(id, template, photoSlots = photos, textSlot = text, placements = Vector.empty[SpreadPlacement])

  enum CreationResult:
    case Planned(period: VisitPeriod)
    case Rejected(reason: ChapterConflict)

  enum UpdateResult:
    case Planned(plan: UpdatePlan)
    case Rejected(reason: ChapterConflict)
    case ChapterNotFound

  enum BuildResult:
    case Built(chapter: ChapterRepository.NewChapter)
    case Failed

  final case class UpdatePlan(previousDestination: DestinationId, sourceChanged: Boolean, period: VisitPeriod, order: NonEmptyList[ChapterId])
  final private case class ChapterOrder(id: ChapterId, isHome: Boolean, period: VisitPeriod)
