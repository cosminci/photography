package photography.adapters.http

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure, HCursor, Json}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.http4s.{CacheDirective, HttpRoutes, MessageBodyFailure, Request}
import org.http4s.headers.`Cache-Control`
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.*
import photography.usecases.chapter.ChapterMetadataManager
import photography.usecases.chapter.ChapterMetadataManager.{CreateResult, Period, UpdateResult}
import photography.usecases.photo.{ChapterPhotoImport, PhotoManager, PhotoWriteRecovery}
import photography.usecases.photo.ChapterPhotoInventory
import photography.usecases.photo.ChapterPhotoInventory.{Snapshot as ChapterPhotos, Result as ChapterResult}
import photography.usecases.photo.ChapterPhotoImport.Scheduling
import photography.ports.album.AlbumRepository

import java.time.{DateTimeException, LocalDate}
import java.time.temporal.ChronoUnit
import java.util.UUID

trait ChapterApi[F[_]]:
  def routes: HttpRoutes[F]

object ChapterApi:
  def make[F[_]: Async](
      inventories: ChapterPhotoInventory[F],
      chapters: ChapterMetadataManager[F],
      metadata: AlbumRepository.ChapterRead[F]
  ): ChapterApi[F] =
    new LiveChapterApi(inventories, chapters, metadata)

  final private class LiveChapterApi[F[_]: Async](
      inventories: ChapterPhotoInventory[F],
      chapters: ChapterMetadataManager[F],
      metadata: AlbumRepository.ChapterRead[F]
  ) extends ChapterApi[F] with Http4sDsl[F]:
    override def routes: HttpRoutes[F] =
      HttpRoutes.of[F] {
        case GET -> Root / "api" / "albums" / CanonicalId(id) / "chapters"            => read(AlbumId(id))
        case GET -> Root / "api" / "albums" / _ / "chapters"                          => albumNotFound
        case request @ POST -> Root / "api" / "albums" / CanonicalId(id) / "chapters" =>
          create(AlbumId(id), request)
        case request @ PUT -> Root / "api" / "albums" / CanonicalId(id) / "chapters" / CanonicalId(chapter) =>
          update(AlbumId(id), ChapterId(chapter), request)
        case GET -> Root / "api" / "chapters" / CanonicalId(chapter) / "photos" =>
          listChapter(ChapterId(chapter))
        case PUT -> Root / "api" / "chapters" / CanonicalId(chapter) / "photo-import" =>
          refresh(ChapterId(chapter))
        case GET -> Root / "api" / "chapters" / _ / "photos"                  => chapterNotFound
        case PUT -> Root / "api" / "chapters" / _ / "photo-import"            => chapterNotFound
        case POST -> Root / "api" / "albums" / _ / "chapters"                 => albumNotFound
        case PUT -> Root / "api" / "albums" / CanonicalId(_) / "chapters" / _ => chapterNotFound
        case PUT -> Root / "api" / "albums" / _ / "chapters" / _              => albumNotFound
      }

    private def read(id: AlbumId) =
      metadata.chapters(id).flatMap:
        case AlbumRepository.ChaptersResult.Found(values) => Ok(Json.obj("chapters" -> Json.arr(values.map(encodeChapter)*)))
            .map(_.putHeaders(`Cache-Control`(CacheDirective.`no-store`)))
        case AlbumRepository.ChaptersResult.AlbumNotFound => albumNotFound
        case AlbumRepository.ChaptersResult.Failed(_)     => serviceFailure

    private def encodeChapter(value: AlbumRepository.ChapterMetadata) =
      val destination = value.destination
      val period      = value.period
      Json.obj(
        "id"           -> Json.fromString(value.id.value.toString),
        "kind"         -> Json.fromString(if (destination.name: String) === "Home" then "home" else "vacation"),
        "name"         -> Json.fromString(destination.name),
        "countries"    -> Json.arr(destination.countries.toList.map(country => Json.fromString(country.id.value.toString))*),
        "start"        -> Json.fromString(period.firstDay.toString),
        "end"          -> Json.fromString(period.firstDay.plusDays((period.days: Long) - 1).toString),
        "sourceFolder" -> Json.fromString(value.sourceFolder),
        "description"  -> Json.fromString(value.description)
      )

    private def create(id: AlbumId, request: Request[F]) =
      decode(request, creationDecoder).flatMap {
        case Left(message)                            => BadRequest(Map("message" -> message))
        case Right((name, countries, period, folder)) =>
          chapters.create(id, name, countries, period, folder).flatMap:
            case created: CreateResult.Created =>
              Created(Map("id" -> created.id.value.toString, "scheduling" -> encodeScheduling(created.scheduling)))
            case CreateResult.Rejected(reason) => BadRequest(Map("message" -> explain(reason)))
            case CreateResult.AlbumNotFound    => albumNotFound
            case CreateResult.Failed           => serviceFailure
      }

    private def update(id: AlbumId, target: ChapterId, request: Request[F]) =
      decode(request, creationDecoder).flatMap {
        case Left(message)                            => BadRequest(Map("message" -> message))
        case Right((name, countries, period, folder)) =>
          chapters.update(id, target, name, countries, period, folder).flatMap:
            case updated: UpdateResult.Updated => Ok(Map("id" -> target.value.toString, "scheduling" -> encodeScheduling(updated.scheduling)))
            case UpdateResult.Rejected(reason) => BadRequest(Map("message" -> explain(reason)))
            case UpdateResult.ChapterNotFound  => chapterNotFound
            case UpdateResult.AlbumNotFound    => albumNotFound
            case UpdateResult.Failed           => serviceFailure
      }

    private def listChapter(chapter: ChapterId) =
      inventories.read(chapter).flatMap:
        case ChapterResult.Found(snapshot) => Ok(encodePhotos(snapshot)).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-store`)))
        case ChapterResult.ChapterNotFound => chapterNotFound
        case ChapterResult.Failed          => serviceFailure

    private def refresh(chapter: ChapterId) =
      chapters.refresh(chapter).flatMap:
        case ChapterMetadataManager.RefreshResult.Scheduled(scheduling @ (Scheduling.Queued | Scheduling.Coalesced)) =>
          Accepted(Map("scheduling" -> encodeScheduling(scheduling)))
        case ChapterMetadataManager.RefreshResult.ChapterNotFound => chapterNotFound
        case _                                                    => serviceFailure

    private def decode[A](request: Request[F], decoder: Decoder[A]) =
      request.as[Json].map(value => decoder.decodeJson(value).leftMap(_.getMessage))
        .recover { case _: MessageBodyFailure => "Supply a valid JSON chapter object".asLeft[A] }

    private def albumNotFound   = NotFound(Map("code" -> "album_not_found"))
    private def chapterNotFound = NotFound(Map("code" -> "chapter_not_found"))
    private def serviceFailure  = InternalServerError(Map("code" -> "service_failure"))

    private def encodeScheduling(scheduling: Scheduling) = scheduling match
      case Scheduling.Queued       => "queued"
      case Scheduling.Coalesced    => "coalesced"
      case Scheduling.NotRequested => "not-requested"
      case Scheduling.Failed       => "failed"

    private def encodePhotos(snapshot: ChapterPhotos) =
      val inventory = snapshot.inventory
      Json.obj(
        "photos"         -> Json.arr(inventory.photos.map(encodePhoto)*),
        "placedPhotoIds" -> Json.arr(inventory.placed.toVector.sortBy(_.value.toString).map(photo => Json.fromString(photo.value.toString))*),
        "counts"         -> Json.obj("unplaced" -> Json.fromInt(inventory.countUnplaced), "total" -> Json.fromInt(inventory.total)),
        "processing"     -> encodeProcessing(snapshot.execution)
      )

    private def encodePhoto(photo: Photo) =
      val thumbnail = photo.thumbnail
      val url       = s"/thumbnails/${thumbnail.location.value}/${thumbnail.version}.jpg"
      Json.obj(
        "id"            -> Json.fromString(photo.id.value.toString),
        "filename"      -> Json.fromString(photo.original.filename),
        "capturedLocal" -> photo.metadata.maybeCapturedLocal.fold(Json.Null)(value => Json.fromString(value.toString)),
        "width"         -> Json.fromInt(photo.metadata.dimensions.width),
        "height"        -> Json.fromInt(photo.metadata.dimensions.height),
        "caption"       -> photo.maybeCaption.fold(Json.Null)(Json.fromString),
        "thumbnail"     -> Json.obj(
          "url"    -> Json.fromString(url),
          "width"  -> Json.fromInt(thumbnail.dimensions.width),
          "height" -> Json.fromInt(thumbnail.dimensions.height)
        )
      )

    private def encodeProcessing(execution: ChapterPhotoImport.ExecutionOutcome) =
      val (state, unsupported, issues) = execution.status match
        case ChapterPhotoImport.Status.Idle             => ("idle", 0, Vector.empty[PhotoManager.Issue])
        case ChapterPhotoImport.Status.Queued           => ("queued", 0, Vector.empty[PhotoManager.Issue])
        case ChapterPhotoImport.Status.Running          => ("running", 0, Vector.empty[PhotoManager.Issue])
        case ChapterPhotoImport.Status.LaunchFailed     => ("failed", 0, Vector.empty[PhotoManager.Issue])
        case ChapterPhotoImport.Status.Uncertain        => ("failed", 0, Vector.empty[PhotoManager.Issue])
        case ChapterPhotoImport.Status.Finished(result) =>
          val failed = result.issues.exists(issue => !isSkipped(issue))
          (if failed then "failed" else "completed", result.unsupportedCount: Int, result.issues)
      val scheduling = execution.status match
        case ChapterPhotoImport.Status.LaunchFailed => Vector(encodeIssue(stage = "scheduling", code = "scheduling_failed", maybeFilename = None))
        case ChapterPhotoImport.Status.Uncertain    => Vector(encodeIssue(stage = "persistence", code = "persistence_failed", maybeFilename = None))
        case _                                      => Vector.empty[Json]
      val failures = issues.map(encodeImportIssue) ++ execution.retirementIssues.map(encodeRetirementIssue) ++ scheduling
      Json.obj(
        "state"            -> Json.fromString(if execution.retirementIssues.nonEmpty then "failed" else state),
        "unsupportedCount" -> Json.fromInt(unsupported),
        "skippedCount"     -> Json.fromInt(issues.count(isSkipped)),
        "issues"           -> Json.arr(failures*)
      )

    private def isSkipped(issue: PhotoManager.Issue) = issue match
      case _: PhotoManager.Issue.Undecodable | _: PhotoManager.Issue.Unstable => true
      case _                                                                  => false

    private def encodeImportIssue(issue: PhotoManager.Issue) = issue match
      case PhotoManager.Issue.Discovery             => encodeIssue(stage = "discovery", code = "unreadable_source", maybeFilename = None)
      case PhotoManager.Issue.Read(filename)        => encodeIssue(stage = "read", code = "unreadable_source", maybeFilename = filename.some)
      case PhotoManager.Issue.Generation(filename)  => encodeIssue(stage = "generation", code = "encoding_failed", maybeFilename = filename.some)
      case PhotoManager.Issue.Undecodable(filename) => encodeIssue(stage = "read", code = "undecodable", maybeFilename = filename.some)
      case PhotoManager.Issue.Unstable(filename)    => encodeIssue(stage = "read", code = "unstable", maybeFilename = filename.some)
      case PhotoManager.Issue.Storage               => encodeIssue(stage = "storage", code = "storage_failed", maybeFilename = None)
      case _: PhotoManager.Issue.Cleanup            => encodeIssue(stage = "cleanup", code = "cleanup_failed", maybeFilename = None)
      case _                                        => encodeIssue(stage = "persistence", code = "persistence_failed", maybeFilename = None)

    private def encodeRetirementIssue(issue: PhotoWriteRecovery.Issue) = issue match
      case _: PhotoWriteRecovery.Issue.Cleanup => encodeIssue(stage = "cleanup", code = "cleanup_failed", maybeFilename = None)
      case _                                   => encodeIssue(stage = "persistence", code = "persistence_failed", maybeFilename = None)

    private def encodeIssue(stage: String, code: String, maybeFilename: Option[PhotoFilename]) =
      Json.obj("stage" -> Json.fromString(stage), "code" -> Json.fromString(code), "filename" -> maybeFilename.fold(Json.Null)(Json.fromString))

    private def explain(conflict: ChapterConflict) = conflict match
      case ChapterConflict.AtCapacity         => "An album can contain at most 65 interior spreads"
      case ChapterConflict.HomeExists         => "This album already has a Home chapter"
      case ChapterConflict.HomePeriodMismatch => "Home must span the album year"
      case ChapterConflict.FolderAssigned     => "Another chapter already uses that source folder"
      case ChapterConflict.TypeChange         => "A chapter's type cannot change"
      case ChapterConflict.UnknownCountries   => "Select countries from the country catalog"

    private object CanonicalId:
      def unapply(value: String): Option[UUID] =
        Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value)

    private given Decoder[CountryId] = Decoder.decodeString.emap: value =>
      CanonicalId.unapply(value).map(CountryId(_)).toRight("Country IDs must be canonical UUIDs")

    private val countryDecoder = Decoder.decodeList[CountryId].emap: values =>
      val distinct = values.map(_.value).distinct.size === values.size
      val nonEmpty = NonEmptyList.fromList(values).toRight("Select at least one country")
      Either.cond(distinct, (), "Select each country only once").flatMap(_ => nonEmpty)

    private val dateDecoder = Decoder.decodeString.emap: value =>
      val isCalendarDay = value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
      Either.cond(isCalendarDay, value, "Use calendar dates in YYYY-MM-DD format").flatMap: date =>
        Either.catchOnly[DateTimeException](LocalDate.parse(date)).leftMap(_ => "Supply a valid calendar date")

    private val periodDecoder = Decoder.instance: cursor =>
      val invalidPeriod = DecodingFailure("End must not precede start", cursor.history)
      for
        start <- cursor.get[LocalDate]("start")(using dateDecoder)
        end   <- cursor.get[LocalDate]("end")(using dateDecoder)
        days  <- (ChronoUnit.DAYS.between(start, end) + 1L).refineEither[Positive].leftMap(_ => invalidPeriod)
      yield Period.Dates(VisitPeriod(start, days))

    private val folderDecoder = Decoder.decodeString.emap: value =>
      value.refineEither[PhotoSourceConstraint].leftMap(_ => "Supply an absolute source folder without traversal")

    private val creationDecoder = Decoder.instance: cursor =>
      cursor.get[String]("kind").flatMap:
        case "home"     => detailsDecoder(isHome = true)(cursor)
        case "vacation" => detailsDecoder(isHome = false)(cursor)
        case _ => DecodingFailure("Choose vacation or home", cursor.history).asLeft[(DestinationName, NonEmptyList[CountryId], Period, PhotoSource)]

    private def detailsDecoder(isHome: Boolean) =
      Decoder.instance[(DestinationName, NonEmptyList[CountryId], Period, PhotoSource)]: cursor =>
        for
          name      <- decodeName(cursor, isHome)
          countries <- cursor.get[NonEmptyList[CountryId]]("countries")(using countryDecoder)
          period    <- if isHome then Period.AlbumYear.asRight else periodDecoder(cursor)
          folder    <- cursor.get[PhotoSource]("sourceFolder")(using folderDecoder)
        yield (name, countries, period, folder)

    private def decodeName(cursor: HCursor, isHome: Boolean) =
      val raw = if isHome then "Home".asRight[DecodingFailure] else cursor.get[String]("name").map(_.trim)
      raw.flatMap: name =>
        val reservedName = !isHome && name === "Home"
        val valid        =
          if reservedName then "Choose a vacation name other than Home".asLeft[DestinationName] else name.refineEither[Not[Empty] & Not[Blank]]
        valid.leftMap(message => DecodingFailure(message, cursor.history))
