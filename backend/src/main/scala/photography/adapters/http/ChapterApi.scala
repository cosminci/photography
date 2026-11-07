package photography.adapters.http

import photography.adapters.http.Routes.Route
import org.http4s.{Request, Response}

import cats.data.{NonEmptyList, NonEmptyVector}
import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure, HCursor, Json}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.circe.jsonOf
import org.http4s.dsl.Http4sDsl
import photography.domain.album.*
import photography.usecases.chapter.ChapterMetadataManager
import photography.usecases.chapter.ChapterMetadataManager.{CreateResult, Period, UpdateResult}
import photography.usecases.photo.ChapterPhotoImport.Scheduling
import photography.ports.album.AlbumRepository
import photography.domain.gear.*
import photography.usecases.chapter.ChapterManager
import photography.usecases.chapter.ChapterManager.{GearReadResult, GearReplaceResult}
import photography.usecases.editing.EditingAccess.Protection

import java.time.{DateTimeException, LocalDate}
import java.time.temporal.ChronoUnit

trait ChapterApi[F[_]] extends Routes[F]

object ChapterApi:
  def make[F[_]: Async](
      chapters: ChapterMetadataManager[F],
      metadata: AlbumRepository.ChapterRead[F],
      gear: ChapterManager[F]
  ): ChapterApi[F] =
    new LiveChapterApi(chapters, metadata, gear)

  final private class LiveChapterApi[F[_]: Async](
      chapters: ChapterMetadataManager[F],
      metadata: AlbumRepository.ChapterRead[F],
      gear: ChapterManager[F]
  ) extends ChapterApi[F] with Http4sDsl[F]:
    override def routes: PartialFunction[Request[F], Route[F, ?]] = {
      case request @ PUT -> Root / "api" / "albums" / CanonicalId(album) / "chapters" / CanonicalId(chapter) / "description" =>
        saveDescription(AlbumId(album), ChapterId(chapter), request)
      case PUT -> Root / "api" / "albums" / CanonicalId(_) / "chapters" / _ / "description"                 => Route.public(chapterNotFound)
      case PUT -> Root / "api" / "albums" / _ / "chapters" / _ / "description"                              => Route.public(albumNotFound)
      case GET -> Root / "api" / "albums" / CanonicalId(album) / "chapters" / CanonicalId(chapter) / "gear" => Route.public {
          readGear(AlbumId(value = album), ChapterId(value = chapter))
        }
      case request @ PUT -> Root / "api" / "albums" / CanonicalId(album) / "chapters" / CanonicalId(chapter) / "gear" => Route.public {
          replaceGear(AlbumId(value = album), ChapterId(value = chapter), request)
        }
      case (GET | PUT) -> Root / "api" / "albums" / CanonicalId(_) / "chapters" / _ / "gear" => Route.public(chapterNotFound)
      case (GET | PUT) -> Root / "api" / "albums" / _ / "chapters" / _ / "gear"              => Route.public(albumNotFound)
      case GET -> Root / "api" / "albums" / CanonicalId(id) / "chapters"                     => Route.public(read(AlbumId(id)))
      case GET -> Root / "api" / "albums" / _ / "chapters"                                   => Route.public(albumNotFound)
      case request @ POST -> Root / "api" / "albums" / CanonicalId(id) / "chapters"          => Route.public {
          create(AlbumId(id), request)
        }
      case request @ PUT -> Root / "api" / "albums" / CanonicalId(id) / "chapters" / CanonicalId(chapter) => Route.public {
          update(AlbumId(id), ChapterId(chapter), request)
        }
      case PUT -> Root / "api" / "chapters" / CanonicalId(chapter) / "photo-import" => Route.public {
          refresh(ChapterId(chapter))
        }
      case PUT -> Root / "api" / "chapters" / _ / "photo-import"            => Route.public(chapterNotFound)
      case POST -> Root / "api" / "albums" / _ / "chapters"                 => Route.public(albumNotFound)
      case PUT -> Root / "api" / "albums" / CanonicalId(_) / "chapters" / _ => Route.public(chapterNotFound)
      case PUT -> Root / "api" / "albums" / _ / "chapters" / _              => Route.public(albumNotFound)
    }

    private def saveDescription(album: AlbumId, chapter: ChapterId, request: Request[F]) =
      val decode = request.attemptAs[String](using jsonOf[F, String](using Async[F], JsonBody.text)).value
        .flatMap(_.fold(_ => BadRequest(Map("message" -> "Supply a text string")).map(_.asLeft[String]), _.asRight[Response[F]].pure[F]))
      Route(
        Routes.Access.Editor(Protection.OwnChapterOpening(album, chapter)),
        decode,
        (text: String) => chapters.saveDescription(album, chapter, text = text).flatMap(respondDescriptionSave)
      )

    private def respondDescriptionSave(result: ChapterMetadataManager.SaveDescriptionResult) = result match
      case ChapterMetadataManager.SaveDescriptionResult.Saved           => NoContent()
      case ChapterMetadataManager.SaveDescriptionResult.AlbumNotFound   => albumNotFound
      case ChapterMetadataManager.SaveDescriptionResult.ChapterNotFound => chapterNotFound
      case ChapterMetadataManager.SaveDescriptionResult.Failed          => serviceFailure
      case ChapterMetadataManager.SaveDescriptionResult.Uncertain       => InternalServerError(body = Map("code" -> "mutation_uncertain"))

    private def readGear(album: AlbumId, chapter: ChapterId) =
      gear.readGear(album, chapter).flatMap:
        case GearReadResult.Found(selection) =>
          Ok(encodeSelection(selection = selection))
        case GearReadResult.NotFound => chapterNotFound
        case GearReadResult.Failed   => serviceFailure

    private def replaceGear(album: AlbumId, chapter: ChapterId, request: Request[F]) =
      decodeGear(request).flatMap:
        case Left(message)    => BadRequest(body = Map("message" -> message))
        case Right(selection) =>
          gear.replaceGear(album, chapter, selection = selection).flatMap:
            case GearReplaceResult.Saved            => NoContent()
            case GearReplaceResult.NotFound         => chapterNotFound
            case GearReplaceResult.Rejected(reason) => BadRequest(body = Map("message" -> explainGear(reason)))
            case GearReplaceResult.Failed           => serviceFailure
            case GearReplaceResult.Uncertain        => InternalServerError(body = Map("code" -> "mutation_uncertain"))

    private def decodeGear(request: Request[F]) =
      JsonBody.decode(request, selectionDecoder, malformed = "Supply a valid JSON gear selection")(_.getMessage)

    private val idDecoder = Decoder.decodeString.emap(value => CanonicalId.unapply(value).toRight(left = "Gear IDs must be canonical UUIDs"))
    private given Decoder[CameraId]        = idDecoder.map(value => CameraId(value = value))
    private given Decoder[LensId]          = idDecoder.map(value => LensId(value = value))
    private given Decoder[CameraSelection] = Decoder.instance: cursor =>
      for
        camera <- cursor.get[CameraId](k = "cameraId")
        lenses <- cursor.get[Vector[LensId]](k = "lensIds")
      yield CameraSelection(
        camera,
        NonEmptyVector.fromVector(vector = lenses).fold[CameraUse](CameraUse.FixedLens)(values => CameraUse.Interchangeable(lenses = values))
      )

    private val selectionDecoder = Decoder.decodeVector[CameraSelection].prepare(_.downField(k = "cameras"))

    private def encodeSelection(selection: GearSelection) =
      val cameras = selection.map: entry =>
        val lenses = entry.use match
          case CameraUse.FixedLens               => Vector.empty[LensId]
          case CameraUse.Interchangeable(values) => values.toVector
        Json.obj(
          "cameraId" -> Json.fromString(value = entry.camera.value.toString),
          "lensIds"  -> Json.arr(lenses.map(id => Json.fromString(value = id.value.toString))*)
        )
      Json.obj("cameras" -> Json.arr(cameras*))

    private def explainGear(reason: GearCatalog.Failure) = reason match
      case GearCatalog.Failure.DuplicateCamera    => "Select each camera only once"
      case GearCatalog.Failure.UnknownCamera      => "Select a camera from the current catalog"
      case GearCatalog.Failure.CameraKindMismatch => "Select lenses only for interchangeable cameras"
      case GearCatalog.Failure.DuplicateLens      => "Select each lens only once per camera"
      case GearCatalog.Failure.UnknownLens        => "Select a lens from the current catalog"
      case GearCatalog.Failure.IncompatibleLens   => "Select lenses with a compatible mount and coverage"

    private def read(id: AlbumId) =
      metadata.chapters(id).flatMap:
        case AlbumRepository.ChaptersResult.Found(values) => Ok(Json.obj("chapters" -> Json.arr(values.map(encodeChapter)*)))
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

    private def refresh(chapter: ChapterId) =
      chapters.refresh(chapter).flatMap:
        case ChapterMetadataManager.RefreshResult.Scheduled(scheduling @ (Scheduling.Queued | Scheduling.Coalesced)) =>
          Accepted(Map("scheduling" -> encodeScheduling(scheduling)))
        case ChapterMetadataManager.RefreshResult.ChapterNotFound => chapterNotFound
        case _                                                    => serviceFailure

    private def decode[A](request: Request[F], decoder: Decoder[A]) =
      JsonBody.decode(request, decoder, malformed = "Supply a valid JSON chapter object")(_.getMessage)

    private def albumNotFound   = NotFound(Map("code" -> "album_not_found"))
    private def chapterNotFound = NotFound(Map("code" -> "chapter_not_found"))
    private def serviceFailure  = InternalServerError(Map("code" -> "service_failure"))

    private def encodeScheduling(scheduling: Scheduling) = scheduling match
      case Scheduling.Queued       => "queued"
      case Scheduling.Coalesced    => "coalesced"
      case Scheduling.NotRequested => "not-requested"
      case Scheduling.Failed       => "failed"

    private def explain(conflict: ChapterConflict) = conflict match
      case ChapterConflict.AtCapacity         => "An album can contain at most 65 interior spreads"
      case ChapterConflict.HomeExists         => "This album already has a Home chapter"
      case ChapterConflict.HomePeriodMismatch => "Home must span the album year"
      case ChapterConflict.FolderAssigned     => "Another chapter already uses that source folder"
      case ChapterConflict.TypeChange         => "A chapter's type cannot change"
      case ChapterConflict.UnknownCountries   => "Select countries from the country catalog"

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
