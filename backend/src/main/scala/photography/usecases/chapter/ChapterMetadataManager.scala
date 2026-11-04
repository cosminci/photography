package photography.usecases.chapter

import photography.usecases.photo.ChapterPhotoImport

import cats.data.{EitherT, NonEmptyList}
import cats.MonadThrow
import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import photography.capabilities.{IdGenerator, Transaction}
import org.typelevel.log4cats.Logger
import photography.domain.album.*
import photography.ports.album.AlbumRepository
import photography.ports.chapter.ChapterRepository
import photography.ports.photo.PhotoRepository
import photography.syntax.EitherSyntax.*
import photography.syntax.EitherSyntax.Pure.*
import photography.usecases.chapter.ChapterMetadataManager.*
import photography.usecases.photo.ChapterPhotoImport.{Request, Scheduling}

trait ChapterMetadataManager[F[_]]:
  def saveDescription(album: AlbumId, chapter: ChapterId, text: String): F[SaveDescriptionResult]
  def create(album: AlbumId, name: DestinationName, countries: NonEmptyList[CountryId], period: Period, sourceFolder: PhotoSource): F[CreateResult]
  def update(
      album: AlbumId,
      chapter: ChapterId,
      name: DestinationName,
      countries: NonEmptyList[CountryId],
      period: Period,
      sourceFolder: PhotoSource
  ): F[UpdateResult]
  def refresh(chapter: ChapterId): F[RefreshResult]

object ChapterMetadataManager:
  def make[F[_]: MonadCancelThrow: IdGenerator: Mutex: Logger, Db[_]: MonadThrow](
      albums: AlbumRepository[F],
      chapters: ChapterRepository.Metadata[Db],
      planning: ChapterPlanning[F],
      photos: ChapterPhotoImport[F],
      photoRepository: PhotoRepository[Db]
  )(using Transaction[Db, F]): ChapterMetadataManager[F] =
    new LiveChapterMetadataManager(albums, chapters, planning, photos, photoRepository)

  final private class LiveChapterMetadataManager[F[_]: MonadCancelThrow: IdGenerator: Mutex: Logger, Db[_]: MonadThrow](
      albums: AlbumRepository[F],
      chapters: ChapterRepository.Metadata[Db],
      planning: ChapterPlanning[F],
      photos: ChapterPhotoImport[F],
      photoRepository: PhotoRepository[Db]
  )(using Transaction[Db, F]) extends ChapterMetadataManager[F]:
    override def saveDescription(album: AlbumId, chapter: ChapterId, text: String): F[SaveDescriptionResult] =
      MonadCancelThrow[F].uncancelable: _ =>
        Transaction[Db, F].run(chapters.saveDescription(album, chapter, text = text)).flatMap:
          case ChapterRepository.SaveDescriptionResult.Saved           => SaveDescriptionResult.Saved.pure[F]
          case ChapterRepository.SaveDescriptionResult.AlbumNotFound   => SaveDescriptionResult.AlbumNotFound.pure[F]
          case ChapterRepository.SaveDescriptionResult.ChapterNotFound => SaveDescriptionResult.ChapterNotFound.pure[F]
          case ChapterRepository.SaveDescriptionResult.Failed(cause)   =>
            Logger[F].error(cause)("Chapter description write failed").as(SaveDescriptionResult.Failed)
          case ChapterRepository.SaveDescriptionResult.Uncertain => SaveDescriptionResult.Uncertain.pure[F]
        .handleErrorWith(error => Logger[F].error(error)("Chapter description commit acknowledgement failed").as(SaveDescriptionResult.Uncertain))

    override def create(
        album: AlbumId,
        name: DestinationName,
        countries: NonEmptyList[CountryId],
        period: Period,
        sourceFolder: PhotoSource
    ): F[CreateResult] =
      val creation = summon[Mutex[F]].lock.surround:
        val program =
          for
            saved    <- readAlbum(album).eitherT
            resolved <- checkCreate(saved.year, existing = saved.chapters, count = saved.collectSpreads.size, name, period, sourceFolder).eitherT[F]
            chapter  <- prepareChapter(name, countries = countries, resolved, sourceFolder).eitherT
            id       <- persistCreation(album, currentChapters = saved.chapters, chapter).eitherT
          yield id
        program.value.flatMap: outcome =>
          outcome.traverse(id => photos.prepare(album, id, sourceFolder).attempt.tupleLeft(id))
      MonadCancelThrow[F].uncancelable: _ =>
        creation.flatMap:
          case Left(failure)        => translateCreateFailure(failure).pure[F]
          case Right((id, request)) =>
            launchImport(album, request, retirements = Vector.empty[PhotoWriteIntent.Retirement]).map(CreateResult.Created(id, _))

    override def update(
        album: AlbumId,
        chapter: ChapterId,
        name: DestinationName,
        countries: NonEmptyList[CountryId],
        period: Period,
        sourceFolder: PhotoSource
    ): F[UpdateResult] =
      val update = summon[Mutex[F]].lock.surround:
        val program: EitherT[F, UpdateFailure, Option[ImportLaunch]] =
          for
            current     <- readAlbum(album).eitherT.leftWiden[UpdateFailure]
            plan        <- planUpdate(current.year, current = current.chapters, chapter, name, period, sourceFolder).eitherT[F]
            retirements <- prepareRetirements(chapter, sourceChanged = plan.sourceChanged).lift[UpdateFailure]
            command <- allocateUpdate(album, chapter, name, countries = countries, plan.period, sourceFolder, order = plan.order).lift[UpdateFailure]
            _       <- persistUpdate(plan.previousDestination, command, retirements = retirements).eitherT
            maybeRequest <- prepareImport(album, chapter, sourceFolder, sourceChanged = plan.sourceChanged).lift[UpdateFailure]
          yield maybeRequest.map(value => ImportLaunch(value, retirements = retirements))
        program.value
      MonadCancelThrow[F].uncancelable: _ =>
        update.flatMap:
          case Left(failure)       => translateUpdateFailure(failure).pure[F]
          case Right(None)         => UpdateResult.Updated(Scheduling.NotRequested).pure[F]
          case Right(Some(launch)) => launchImport(album, launch.request, retirements = launch.retirements).map(UpdateResult.Updated(_))
      .handleErrorWith(error => Logger[F].error(error)("Chapter update failed").as(UpdateResult.Failed))

    override def refresh(chapter: ChapterId): F[RefreshResult] =
      val refresh = summon[Mutex[F]].lock.surround:
        Transaction[Db, F].run(chapters.findSource(chapter)).flatMap:
          case ChapterRepository.SourceResult.Found(album, source) =>
            photos.prepare(album, chapter, source).attempt.tupleLeft(album).map(_.asRight[RefreshResult])
          case ChapterRepository.SourceResult.ChapterNotFound => RefreshResult.ChapterNotFound.asLeft[(AlbumId, Either[Throwable, Request])].pure[F]
          case ChapterRepository.SourceResult.Failed          => RefreshResult.Failed.asLeft[(AlbumId, Either[Throwable, Request])].pure[F]
      MonadCancelThrow[F].uncancelable: _ =>
        refresh.flatMap:
          case Right((album, request)) =>
            launchImport(album, request, retirements = Vector.empty[PhotoWriteIntent.Retirement]).map(RefreshResult.Scheduled(_))
          case Left(failure) => failure.pure[F]
      .handleErrorWith(error => Logger[F].error(error)("Chapter photo Refresh failed").as(RefreshResult.Failed))

    private def launchImport(album: AlbumId, request: Either[Throwable, Request], retirements: Vector[PhotoWriteIntent.Retirement]) =
      val launch = photos.retire(album, retirements = retirements) >> request.traverse(photos.launch).flatMap:
        case Right(scheduling) => scheduling.pure[F]
        case Left(error)       => Logger[F].error(error)("Saved chapter photo import could not be prepared").as(Scheduling.Failed)
      launch.handleErrorWith(error => Logger[F].error(error)("Saved chapter photo import could not be launched").as(Scheduling.Failed))

    private def readAlbum(id: AlbumId) =
      albums.get(id).map:
        case AlbumRepository.GetResult.Found(album) => album.asRight[Failure]
        case AlbumRepository.GetResult.NotFound     => Failure.AlbumNotFound.asLeft[Album]
        case _: AlbumRepository.GetResult.Failed    => Failure.Unavailable.asLeft[Album]

    private def checkCreate(year: AlbumYear, existing: Vector[Chapter], count: Int, name: DestinationName, period: Period, folder: PhotoSource) =
      planning.planCreation(year, chapters = existing, spreadCount = count, name, period, folder) match
        case ChapterPlanning.CreationResult.Planned(resolved) => resolved.asRight[Failure]
        case ChapterPlanning.CreationResult.Rejected(reason)  => Failure.Conflict(reason).asLeft[VisitPeriod]

    private def planUpdate(year: AlbumYear, current: Vector[Chapter], id: ChapterId, name: DestinationName, period: Period, folder: PhotoSource) =
      planning.planUpdate(year, chapters = current, id, name, period, folder) match
        case ChapterPlanning.UpdateResult.Planned(plan)    => plan.asRight[UpdateFailure]
        case ChapterPlanning.UpdateResult.Rejected(reason) => Failure.Conflict(reason).asLeft[ChapterPlanning.UpdatePlan]
        case ChapterPlanning.UpdateResult.ChapterNotFound  => MissingChapter.asLeft[ChapterPlanning.UpdatePlan]

    private def prepareChapter(name: DestinationName, countries: NonEmptyList[CountryId], period: VisitPeriod, source: PhotoSource) =
      planning.buildChapter(name, countries = countries, period, source).map:
        case ChapterPlanning.BuildResult.Built(chapter) => chapter.asRight[Failure]
        case ChapterPlanning.BuildResult.Failed         => Failure.Unavailable.asLeft[ChapterRepository.NewChapter]

    private def persistCreation(album: AlbumId, currentChapters: Vector[Chapter], chapter: ChapterRepository.NewChapter) =
      val order = planning.orderChapters(chapters = currentChapters, chapter.id, isHome = (chapter.name: String) === "Home", chapter.period)
      Transaction[Db, F].run(chapters.create(album, chapter, order = order)).map:
        case ChapterRepository.CreateResult.Saved            => chapter.id.asRight[Failure]
        case ChapterRepository.CreateResult.AlbumNotFound    => Failure.AlbumNotFound.asLeft[ChapterId]
        case ChapterRepository.CreateResult.UnknownCountries => Failure.Conflict(ChapterConflict.UnknownCountries).asLeft[ChapterId]
        case ChapterRepository.CreateResult.Failed           => Failure.Unavailable.asLeft[ChapterId]
      .handleErrorWith(error => Logger[F].error(error)("Chapter creation failed").as(Failure.Unavailable.asLeft[ChapterId]))

    private def persistUpdate(
        previousDestination: DestinationId,
        command: ChapterRepository.Update,
        retirements: Vector[PhotoWriteIntent.Retirement]
    ) =
      val update = chapters.update(command).flatTap:
        case ChapterRepository.UpdateResult.Saved => retirements.traverse_(removePhoto)
        case _                                    => ().pure[Db]
      val commit = Transaction[Db, F].run(update).handleErrorWith: error =>
        resolveUpdate(command.album, command.chapter, previousDestination, command.destination, error)
      commit.map:
        case ChapterRepository.UpdateResult.Saved            => ().asRight[UpdateFailure]
        case ChapterRepository.UpdateResult.ChapterNotFound  => MissingChapter.asLeft[Unit]
        case ChapterRepository.UpdateResult.AlbumNotFound    => Failure.AlbumNotFound.asLeft[Unit]
        case ChapterRepository.UpdateResult.UnknownCountries => Failure.Conflict(ChapterConflict.UnknownCountries).asLeft[Unit]
        case ChapterRepository.UpdateResult.Failed           => Failure.Unavailable.asLeft[Unit]

    private def prepareRetirements(chapter: ChapterId, sourceChanged: Boolean) =
      if !sourceChanged then Vector.empty[PhotoWriteIntent.Retirement].pure[F]
      else
        Transaction[Db, F].run(photoRepository.listChapter(chapter)).flatMap:
          case PhotoRepository.ListResult.Listed(current) =>
            current.traverse(photo => allocateRetirement(photo.chapter, photo.id, photo.thumbnail.location))
          case PhotoRepository.ListResult.Failed =>
            new IllegalStateException("Chapter photo listing failed").raiseError[F, Vector[PhotoWriteIntent.Retirement]]

    private def allocateRetirement(chapter: ChapterId, photo: PhotoId, location: ThumbnailLocation) =
      IdGenerator[F].generateId.map: id =>
        PhotoWriteIntent.Retirement(PhotoWriteIntentId(id), chapter, photo, location): PhotoWriteIntent.Retirement

    private def allocateUpdate(
        album: AlbumId,
        chapter: ChapterId,
        name: DestinationName,
        countries: NonEmptyList[CountryId],
        period: VisitPeriod,
        folder: PhotoSource,
        order: NonEmptyList[ChapterId]
    ) =
      IdGenerator[F].generateId.map: id =>
        ChapterRepository.Update(album, chapter, DestinationId(id), name, countries = countries, period, folder, order = order)

    private def removePhoto(intent: PhotoWriteIntent.Retirement) =
      val record = photoRepository.recordWrite(intent).flatMap:
        case PhotoRepository.RecordWriteResult.Recorded => ().pure[Db]
        case PhotoRepository.RecordWriteResult.Failed   => new IllegalStateException("Chapter retirement evidence rejected").raiseError[Db, Unit]
      record >> photoRepository.remove(intent.photo).flatMap:
        case PhotoRepository.WriteResult.Applied => ().pure[Db]
        case PhotoRepository.WriteResult.Failed  => new IllegalStateException("Chapter photo removal rejected").raiseError[Db, Unit]

    private def prepareImport(album: AlbumId, chapter: ChapterId, source: PhotoSource, sourceChanged: Boolean) =
      if !sourceChanged then none[Either[Throwable, Request]].pure[F]
      else
        photos.prepare(album, chapter, source).attempt.flatTap:
          case Left(_)                      => photos.suspend(chapter)
          case _: Right[Throwable, Request] => ().pure[F]
        .map(_.some)

    private def resolveUpdate(album: AlbumId, chapter: ChapterId, previousDestination: DestinationId, destination: DestinationId, error: Throwable) =
      Logger[F].error(error)("Chapter update commit acknowledgement failed") >> albums.get(album).flatMap:
        case AlbumRepository.GetResult.Found(current) =>
          current.chapters.find(_.id.value === chapter.value) match
            case Some(saved) if saved.destination.id.value === destination.value         => ChapterRepository.UpdateResult.Saved.pure[F]
            case Some(saved) if saved.destination.id.value === previousDestination.value => ChapterRepository.UpdateResult.Failed.pure[F]
            case _ => photos.suspend(chapter).as(ChapterRepository.UpdateResult.Failed)
        case _ => photos.suspend(chapter).as(ChapterRepository.UpdateResult.Failed)
      .handleErrorWith: failure =>
        Logger[F].error(failure)("Chapter update commit resolution failed") >> photos.suspend(chapter).as(ChapterRepository.UpdateResult.Failed)

    private def translateCreateFailure(failure: Failure) = failure match
      case Failure.AlbumNotFound    => CreateResult.AlbumNotFound
      case Failure.Conflict(reason) => CreateResult.Rejected(reason)
      case Failure.Unavailable      => CreateResult.Failed

    private def translateUpdateFailure(failure: UpdateFailure) = failure match
      case Failure.AlbumNotFound    => UpdateResult.AlbumNotFound
      case MissingChapter           => UpdateResult.ChapterNotFound
      case Failure.Conflict(reason) => UpdateResult.Rejected(reason)
      case Failure.Unavailable      => UpdateResult.Failed

  final private case class ImportLaunch(request: Either[Throwable, Request], retirements: Vector[PhotoWriteIntent.Retirement])

  private enum Failure:
    case AlbumNotFound, Unavailable
    case Conflict(reason: ChapterConflict)

  private case object MissingChapter
  private type UpdateFailure = Failure | MissingChapter.type

  enum CreateResult:
    case Created(id: ChapterId, scheduling: Scheduling)
    case Rejected(reason: ChapterConflict)
    case AlbumNotFound, Failed

  enum SaveDescriptionResult:
    case Saved, AlbumNotFound, ChapterNotFound, Failed, Uncertain

  enum UpdateResult:
    case Updated(scheduling: Scheduling)
    case Rejected(reason: ChapterConflict)
    case ChapterNotFound, AlbumNotFound, Failed

  enum RefreshResult:
    case Scheduled(scheduling: Scheduling)
    case ChapterNotFound, Failed

  enum Period:
    case AlbumYear
    case Dates(value: VisitPeriod)
