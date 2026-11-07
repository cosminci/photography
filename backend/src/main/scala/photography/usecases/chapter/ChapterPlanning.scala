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
import photography.ports.album.AlbumRepository.{ChapterMetadata, PlanningSnapshot}
import photography.usecases.chapter.ChapterMetadataManager.Period
import photography.usecases.chapter.ChapterPlanning.*
import photography.ports.spread.SpreadTemplateRepository.ListChapterStartTemplatesResult
import java.time.{LocalDate, Year}

trait ChapterPlanning[F[_]]:
  def planCreation(
      album: PlanningSnapshot,
      name: DestinationName,
      countries: NonEmptyList[CountryId],
      period: Period,
      folder: PhotoSource
  ): F[CreationResult]
  def planUpdate(
      album: PlanningSnapshot,
      id: ChapterId,
      name: DestinationName,
      period: Period,
      folder: PhotoSource
  ): UpdateResult

object ChapterPlanning:
  def make[F[_]: Monad: IdGenerator](templates: SpreadTemplateRepository[F]): ChapterPlanning[F] = new LiveChapterPlanning(templates)

  final private class LiveChapterPlanning[F[_]: Monad: IdGenerator](templates: SpreadTemplateRepository[F]) extends ChapterPlanning[F]:
    override def planCreation(
        album: PlanningSnapshot,
        name: DestinationName,
        countries: NonEmptyList[CountryId],
        period: Period,
        folder: PhotoSource
    ): F[CreationResult] =
      val resolved      = resolvePeriod(album.year, period)
      val maybeConflict =
        if InteriorSpreadCapacity.isReached(album.spreadCount) then ChapterConflict.AtCapacity.some
        else findMetadataConflict(album.year, chapters = album.chapters, name, resolved, folder)
      maybeConflict.fold(buildChapter(name, countries, resolved, folder, chapters = album.chapters))(reason =>
        CreationResult.Rejected(reason).pure[F]
      )

    override def planUpdate(
        album: PlanningSnapshot,
        id: ChapterId,
        name: DestinationName,
        period: Period,
        folder: PhotoSource
    ): UpdateResult =
      album.chapters.find(_.id.value === id.value).fold[UpdateResult](UpdateResult.ChapterNotFound): existing =>
        val resolved      = resolvePeriod(album.year, period)
        val others        = album.chapters.filterNot(_.id.value === id.value)
        val maybeConflict =
          if existing.destination.isHome =!= ((name: String) === "Home") then ChapterConflict.TypeChange.some
          else findMetadataConflict(album.year, chapters = others, name, resolved, folder)
        val order = orderChapters(chapters = album.chapters, id, isHome = (name: String) === "Home", resolved)
        val plan  = UpdatePlan(existing.sourceFolder, resolved, order = order)
        maybeConflict.fold[UpdateResult](UpdateResult.Planned(plan))(UpdateResult.Rejected(_))

    private def orderChapters(chapters: Vector[ChapterMetadata], id: ChapterId, isHome: Boolean, period: VisitPeriod) =
      val others = chapters.filterNot(_.id.value === id.value).map(value => ChapterOrder(value.id, isHome = value.destination.isHome, value.period))
      NonEmptyList(ChapterOrder(id, isHome = isHome, period), tail = others.toList)
        .sortBy(value => (value.isHome, value.period.firstDay.toEpochDay, value.period.days: Long, value.id.value.toString)).map(_.id)

    private def buildChapter(
        name: DestinationName,
        countries: NonEmptyList[CountryId],
        period: VisitPeriod,
        source: PhotoSource,
        chapters: Vector[ChapterMetadata]
    ) =
      templates.listChapterStartTemplates.flatMap:
        case ListChapterStartTemplatesResult.Failed(_)         => (CreationResult.Failed: CreationResult).pure[F]
        case ListChapterStartTemplatesResult.Listed(available) =>
          val template = available.head
          for
            id      <- IdGenerator[F].generateId.map(ChapterId(_))
            opening <- buildOpening(template)
          yield CreationResult.Planned(
            ChapterRepository.NewChapter(id, name, countries = countries, period, description = "", source, opening),
            orderChapters(chapters, id, isHome = (name: String) === "Home", period)
          )

    private def findMetadataConflict(
        year: AlbumYear,
        chapters: Vector[ChapterMetadata],
        name: DestinationName,
        period: VisitPeriod,
        folder: PhotoSource
    ) =
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

    private def buildOpening(template: SpreadTemplate.ChapterStart) =
      val newPhotos =
        template.photoSlots.traverse(box => IdGenerator[F].generateId.map(id => SpreadSlot.Photo(PhotoSpreadSlotId(id), box, bleed = Set.empty)))
      for
        id     <- IdGenerator[F].generateId.map(SpreadId(_))
        photos <- newPhotos
        text   <- IdGenerator[F].generateId.map(slot => SpreadSlot.Text(TextSpreadSlotId(slot), template.textSlot))
      yield Spread.ChapterStart(id, template.id, photoSlots = photos, textSlot = text, placements = Vector.empty[SpreadPlacement])

  enum CreationResult:
    case Planned(chapter: ChapterRepository.NewChapter, order: NonEmptyList[ChapterId])
    case Rejected(reason: ChapterConflict)
    case Failed

  enum UpdateResult:
    case Planned(plan: UpdatePlan)
    case Rejected(reason: ChapterConflict)
    case ChapterNotFound

  final case class UpdatePlan(previousSource: PhotoSource, period: VisitPeriod, order: NonEmptyList[ChapterId])
  final private case class ChapterOrder(id: ChapterId, isHome: Boolean, period: VisitPeriod)
