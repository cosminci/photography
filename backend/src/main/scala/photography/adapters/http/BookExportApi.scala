package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import org.http4s.{Request, Response}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.Logger
import photography.adapters.http.Routes.Route
import photography.domain.album.*
import photography.domain.gear.*
import photography.domain.layout.*
import photography.usecases.album.BookExport
import photography.usecases.album.BookExport.{Code, Issue, SavedBook, Severity, Target}
import java.util.UUID

object BookExportApi:
  def make[F[_]: Async: Logger](exports: BookExport[F]): Routes[F] = new LiveBookExportApi(exports)

  final private class LiveBookExportApi[F[_]: Async: Logger](exports: BookExport[F]) extends Routes[F] with Http4sDsl[F]:
    override def routes: PartialFunction[Request[F], Route[F, ?]] = {
      case GET -> Root / "api" / "albums" / CanonicalId(id) / "indesign-export" => Route.public(exportBook(AlbumId(id)))
      case GET -> Root / "api" / "albums" / _ / "indesign-export"               =>
        Route.public(NotFound(Json.obj("code" -> text("album_not_found"))).map(noStore))
    }

    private def exportBook(album: AlbumId): F[Response[F]] =
      exports.exportBook(album).flatMap {
        case BookExport.Result.Ready(book, warnings) =>
          encodeBook(book, warnings) match
            case Left(error) =>
              Logger[F].error(s"Book export projection failed for ${album.value}: $error") *>
                InternalServerError(Json.obj("code" -> text("service_failure")))
            case Right(encoded) =>
              Ok(encoded).map(_.putHeaders("Content-Disposition" -> s"""attachment; filename="album-${book.album.year}-indesign.json""""))
        case BookExport.Result.Blocked(issues) =>
          UnprocessableContent(Json.obj("code" -> text("export_blocked"), "issues" -> Json.fromValues(issues.map(encodeIssue))))
        case BookExport.Result.NotFound => NotFound(Json.obj("code" -> text("album_not_found")))
        case BookExport.Result.Failed   => InternalServerError(Json.obj("code" -> text("service_failure")))
      }.map(noStore)

    private def noStore(response: Response[F]) = response.putHeaders("Cache-Control" -> "no-store")

    private def encodeBook(book: SavedBook, warnings: Vector[Issue]) =
      val references = book.album.collectSpreads.toList.flatMap(_.placements.map(_.photo)).toVector ++ book.cover.placements.map(_.photo)
      book.album.collectSpreads.toList.traverse(encodeSpread).map: spreads =>
        Json.obj(
          "formatVersion" -> Json.fromInt(1),
          "album"         -> Json.obj(
            "id"       -> text(book.album.id.value.toString),
            "title"    -> text(book.album.title),
            "year"     -> Json.fromInt(book.album.year),
            "synopsis" -> text(book.album.synopsis),
            "authors"  -> Json.fromValues(Author.values.toVector.filter(book.album.authors.contains).map(encodeAuthor))
          ),
          "sources"  -> Json.fromValues(book.photos.filter(photo => references.contains(photo.id)).map(encodePhoto)),
          "interior" -> Json.obj(
            "widthPt"   -> number(InteriorDimensions.width),
            "heightPt"  -> number(InteriorDimensions.height),
            "pageCount" -> Json.fromLong(book.cover.dimensions.pageCount),
            "spreads"   -> Json.fromValues(spreads)
          ),
          "chapters" -> Json.fromValues(book.album.chapters.map(encodeChapter)),
          "cover"    -> encodeCover(book),
          "gear"     -> Json.obj(
            "cameras" ->
              Json.fromValues(book.gear.cameras.map(camera => Json.obj("id" -> text(camera.id.value.toString), "name" -> text(camera.friendlyName)))),
            "lenses" ->
              Json.fromValues(book.gear.lenses.map(lens => Json.obj("id" -> text(lens.id.value.toString), "name" -> text(lens.friendlyName))))
          ),
          "warnings" -> Json.fromValues(warnings.map(encodeIssue))
        )

    private def encodePhoto(photo: Photo) = Json.obj(
      "id"       -> text(photo.id.value.toString),
      "source"   -> text(photo.original.source),
      "filename" -> text(photo.original.filename),
      "hash"     -> text(photo.metadata.hash),
      "width"    -> Json.fromInt(photo.metadata.dimensions.width),
      "height"   -> Json.fromInt(photo.metadata.dimensions.height),
      "caption"  -> photo.maybeCaption.fold(Json.Null)(text)
    )

    private def encodeSpread(spread: Spread) =
      val (kind, maybeTextBox) = spread match
        case value: Spread.AlbumStart   => ("synopsis", value.textSlot.some)
        case value: Spread.ChapterStart => ("chapter_opening", value.textSlot.some)
        case _: Spread.PhotosOnly       => ("photos", none[SpreadSlot.Text])
      spread.photos.traverse(slot =>
        (slot.visibleBox, slot.contentBox).mapN { (visible, content) =>
          Json.obj(
            "id"           -> text(slot.id.value.toString),
            "frame"        -> encodeFrame(InteriorDimensions.photoFrame(visible, slot.bleed)),
            "captionFrame" -> encodeFrame(InteriorDimensions.contentFrame(content))
          )
        }
      ).map: slots =>
        Json.obj(
          "id"         -> text(spread.id.value.toString),
          "kind"       -> text(kind),
          "textFrame"  -> maybeTextBox.fold(Json.Null)(slot => encodeFrame(InteriorDimensions.contentFrame(slot.box))),
          "slots"      -> Json.fromValues(slots),
          "placements" -> Json.fromValues(spread.placements.map(placement =>
            Json.obj(
              "slot"          -> text(placement.slot.value.toString),
              "photo"         -> text(placement.photo.value.toString),
              "transform"     -> encodeTransform(placement.transform),
              "captionCorner" -> encodeCorner(placement.captionCorner)
            )
          ))
        )

    private def encodeFrame(frame: PrintFrame) = Json.obj(
      "xPt"      -> number(frame.x),
      "yPt"      -> number(frame.y),
      "widthPt"  -> number(frame.width),
      "heightPt" -> number(frame.height)
    )

    private def encodeTransform(transform: PhotoTransform) = Json.obj(
      "xPt"      -> number(transform.shift.x),
      "yPt"      -> number(transform.shift.y),
      "zoom"     -> number(transform.zoom),
      "mirrored" -> Json.fromBoolean(transform.mirrored)
    )

    private def encodeChapter(chapter: Chapter) = Json.obj(
      "id"          -> text(chapter.id.value.toString),
      "name"        -> text(chapter.destination.name),
      "description" -> text(chapter.description),
      "opening"     -> text(chapter.opening.id.value.toString),
      "firstDay"    -> text(chapter.period.firstDay.toString),
      "days"        -> Json.fromLong(chapter.period.days),
      "countries"   -> Json.fromValues(chapter.destination.countries.toList.map(country =>
        Json.obj(
          "code" -> text(country.code),
          "name" -> text(country.name),
          "flag" -> text(country.flag.value.toString)
        )
      )),
      "gear" -> Json.fromValues(chapter.gear.map(selection =>
        Json.obj(
          "cameraId" -> text(selection.camera.value.toString),
          "lensIds"  -> Json.fromValues((selection.use match
            case CameraUse.FixedLens               => Vector.empty[LensId]
            case CameraUse.Interchangeable(lenses) => lenses.toVector
          ).map(id => text(id.value.toString)))
        )
      ))
    )

    private def encodeCover(book: SavedBook) =
      val composition = book.cover
      val dimensions  = composition.dimensions
      val front       = dimensions.frontFrame(composition.cover.spineOwner)
      val back        = dimensions.backFrame(composition.cover.spineOwner)
      Json.obj(
        "widthPt"      -> number(dimensions.width),
        "heightPt"     -> number(dimensions.height),
        "spineStartPt" -> number(dimensions.spineStart),
        "spineWidthPt" -> number(dimensions.spineWidth),
        "spineOwner"   -> text(composition.cover.spineOwner match
          case SpineOwner.Front => "front"
          case SpineOwner.Back  => "back"),
        "frontSlot"  -> text(composition.cover.front.id.value.toString),
        "backSlot"   -> text(composition.cover.back.id.value.toString),
        "frontFrame" -> encodeFrame(PrintFrame(front.x, 0.0, front.width, front.height)),
        "backFrame"  -> encodeFrame(PrintFrame(back.x, 0.0, back.width, back.height)),
        "placements" -> Json.fromValues(composition.placements.map(placement =>
          Json.obj(
            "slot"      -> text(placement.slot.value.toString),
            "photo"     -> text(placement.photo.value.toString),
            "transform" -> encodeTransform(placement.transform)
          )
        ))
      )

    private def encodeIssue(issue: Issue) =
      val scope = issue.target match
        case Target.Album              => Json.obj("kind" -> text("album"))
        case Target.Chapter(chapter)   => Json.obj("kind" -> text("chapter"), "chapter" -> text(chapter.value.toString))
        case Target.Spread(spread)     => Json.obj("kind" -> text("spread"), "spread" -> text(spread.value.toString))
        case Target.Slot(spread, slot) =>
          Json.obj("kind" -> text("slot"), "spread" -> text(spread.value.toString), "slot" -> text(slot.value.toString))
        case Target.CoverSlot(slot) => Json.obj("kind" -> text("cover"), "slot" -> text(slot.value.toString))
        case Target.Photo(photo)    => Json.obj("kind" -> text("photo"), "photo" -> text(photo.value.toString))
      val severity = issue.severity match
        case Severity.Blocking => "blocking"
        case Severity.Warning  => "warning"
      val code = issue.code match
        case Code.EmptySynopsis       => "empty_synopsis"
        case Code.EmptyDescription    => "empty_description"
        case Code.EmptyPhotoSlot      => "empty_photo_slot"
        case Code.MissingPhoto        => "missing_photo"
        case Code.InvalidPageCount    => "invalid_page_count"
        case Code.InvalidGeometry     => "invalid_geometry"
        case Code.InvalidFraming      => "invalid_framing"
        case Code.LowResolution       => "low_resolution"
        case Code.OriginalChanged     => "original_changed"
        case Code.OriginalUndecodable => "original_undecodable"
        case Code.OriginalUnstable    => "original_unstable"
        case Code.OriginalUnavailable => "original_unavailable"
      Json.obj("code" -> text(code), "severity" -> text(severity), "target" -> scope)

    private def encodeAuthor(author: Author) = text(author match
      case Author.Cosmin => "Cosmin"
      case Author.Mara   => "Mara"
      case Author.Iacob  => "Iacob")

    private def encodeCorner(corner: CaptionCorner) = text(corner match
      case CaptionCorner.TopLeft     => "top_left"
      case CaptionCorner.TopRight    => "top_right"
      case CaptionCorner.BottomLeft  => "bottom_left"
      case CaptionCorner.BottomRight => "bottom_right")

    private def text(value: String)   = Json.fromString(value)
    private def number(value: Double) = Json.fromBigDecimal(BigDecimal(value))

    private object CanonicalId:
      def unapply(value: String): Option[UUID] =
        Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value)
