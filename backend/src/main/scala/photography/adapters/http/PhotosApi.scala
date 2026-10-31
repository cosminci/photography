package photography.adapters.http

import cats.data.{Kleisli, OptionT}
import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import org.http4s.{CacheDirective, HttpRoutes, MessageBodyFailure, Request, Response, Status}
import org.http4s.headers.`Cache-Control`
import org.http4s.server.Router
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.*
import photography.usecases.photo.{ChapterPhotoImport, ChapterPhotoInventory, PhotoManager, PhotoWriteRecovery}
import photography.usecases.photo.ChapterPhotoInventory.{Snapshot as ChapterPhotos, Result as ChapterResult}
import photography.usecases.photo.PhotoManager.SaveCaptionResult
import java.util.UUID

trait PhotosApi[F[_]]:
  def routes(chapter: ChapterId): HttpRoutes[F]

object PhotosApi:
  def make[F[_]: Async](inventories: ChapterPhotoInventory[F], photos: PhotoManager[F]): PhotosApi[F] =
    new LivePhotosApi(inventories, photos)

  def mount[F[_]: Async](photos: PhotosApi[F]): HttpRoutes[F] =
    val scoped: HttpRoutes[F] = Kleisli { request =>
      request.pathInfo.segments.toList match
        case segment :: remaining if remaining.headOption.exists(_.decoded() === "photos") =>
          CanonicalId.unapply(segment.decoded()) match
            case Some(id) => photos.routes(ChapterId(id))(request.withPathInfo(request.pathInfo.dropSegments(1)))
            case None     => OptionT.pure[F](Response[F](Status.NotFound).withEntity(Map("code" -> "chapter_not_found")))
        case _ => OptionT.none[F, Response[F]]
    }
    Router("/api/chapters" -> scoped)

  final private class LivePhotosApi[F[_]: Async](inventories: ChapterPhotoInventory[F], photos: PhotoManager[F])
      extends PhotosApi[F] with Http4sDsl[F]:
    override def routes(chapter: ChapterId): HttpRoutes[F] = HttpRoutes.of[F] {
      case GET -> Root / "photos"                                            => listChapter(chapter)
      case request @ PUT -> Root / "photos" / CanonicalId(photo) / "caption" => saveCaption(chapter, PhotoId(photo), request)
      case PUT -> Root / "photos" / _ / "caption"                            => NotFound(body = Map("code" -> "photo_not_found"))
    }

    private def listChapter(chapter: ChapterId) =
      inventories.read(chapter).flatMap:
        case ChapterResult.Found(snapshot) => Ok(encodePhotos(snapshot)).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-store`)))
        case ChapterResult.ChapterNotFound => NotFound(body = Map("code" -> "chapter_not_found"))
        case ChapterResult.Failed          => InternalServerError(body = Map("code" -> "service_failure"))

    private def saveCaption(chapter: ChapterId, photo: PhotoId, request: Request[F]) =
      request.as[Json].flatMap { value =>
        value.hcursor.get[String](k = "text").fold(
          _ => BadRequest(body = Map("message" -> "Supply a text string")),
          text =>
            photos.saveCaption(chapter, photo, text = text).flatMap {
              case SaveCaptionResult.Saved         => NoContent()
              case SaveCaptionResult.PhotoNotFound => NotFound(body = Map("code" -> "photo_not_found"))
              case SaveCaptionResult.Failed        => InternalServerError(body = Map("code" -> "service_failure"))
              case SaveCaptionResult.Uncertain     => InternalServerError(body = Map("code" -> "mutation_uncertain"))
            }
        )
      }.recoverWith { case _: MessageBodyFailure => BadRequest(body = Map("message" -> "Supply a text string")) }

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

  private object CanonicalId:
    def unapply(value: String): Option[UUID] =
      Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value)
