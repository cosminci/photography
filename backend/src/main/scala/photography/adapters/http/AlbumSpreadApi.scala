package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import org.http4s.{Request, Response}
import photography.adapters.http.Routes.Route
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.editing.EditingTarget
import photography.usecases.editing.EditingAccess.Protection
import photography.domain.layout.{SpreadId, SpreadTemplateId}
import photography.usecases.spread.SpreadManager
import photography.usecases.spread.SpreadManager.{AddResult, ChangeTemplateResult, Conflict as SpreadConflict, DeleteResult}

trait AlbumSpreadApi[F[_]] extends Routes[F]

object AlbumSpreadApi:
  def make[F[_]: Async](spreads: SpreadManager[F]): AlbumSpreadApi[F] = new LiveAlbumSpreadApi(spreads)

  final private class LiveAlbumSpreadApi[F[_]: Async](spreads: SpreadManager[F]) extends AlbumSpreadApi[F] with Http4sDsl[F]:
    override def routes: PartialFunction[Request[F], Route[F, ?]] = {
      case request @ POST -> Root / "api" / "albums" / CanonicalId(album) / "chapters" / CanonicalId(chapter) / "spreads" =>
        add(AlbumId(album), ChapterId(chapter), request)
      case request @ PUT -> Root / "api" / "albums" / CanonicalId(album) / "spreads" / CanonicalId(spread) / "template" :?
          ConfirmPlacements(maybeConfirm) =>
        changeTemplate(AlbumId(album), SpreadId(spread), maybeConfirm = maybeConfirm, request)
      case DELETE -> Root / "api" / "albums" / CanonicalId(album) / "spreads" / CanonicalId(spread) :? ConfirmPlacements(maybeConfirm) =>
        delete(AlbumId(album), SpreadId(spread), maybeConfirm = maybeConfirm)
    }

    private def add(album: AlbumId, chapter: ChapterId, request: Request[F]) =
      Route(
        Routes.Access.Editor(Protection.ActiveTab),
        decode(request, afterDecoder),
        (after: SpreadId) =>
          spreads.add(album, chapter, after).flatMap:
            case AddResult.Added(id)          => Created(Map("id" -> id.value.toString))
            case AddResult.Rejected(conflict) => BadRequest(Map("message" -> explain(conflict)))
            case AddResult.AlbumNotFound      => albumNotFound
            case AddResult.ChapterNotFound    => notFound("chapter_not_found")
            case AddResult.SpreadNotFound     => notFound("spread_not_found")
            case AddResult.Failed             => serviceFailure
      )

    private def changeTemplate(album: AlbumId, spread: SpreadId, maybeConfirm: Option[String], request: Request[F]) =
      val parsed = decode(request, templateDecoder).flatMap:
        case Left(response)  => response.asLeft[(SpreadTemplateId, Boolean)].pure[F]
        case Right(template) =>
          parseConfirmation(maybeValue = maybeConfirm).fold(
            message => BadRequest(Map("message" -> message)).map(_.asLeft[(SpreadTemplateId, Boolean)]),
            confirm => (template, confirm).asRight[Response[F]].pure[F]
          )
      Route(
        Routes.Access.Editor(Protection.OwnSpread(EditingTarget.Spread(spread))),
        parsed,
        (template, confirm) => spreads.changeTemplate(album, spread, template, confirmPlacements = confirm).flatMap(respondTemplateChange)
      )

    private def respondTemplateChange(result: ChangeTemplateResult) = result match
      case ChangeTemplateResult.Changed(id, layout) =>
        Ok(Json.obj("id" -> Json.fromString(id.value.toString), "templateId" -> Json.fromString(layout.value.toString)))
      case ChangeTemplateResult.Occupied(placements) => Conflict(encodeOccupied(placements))
      case ChangeTemplateResult.Rejected(conflict)   => BadRequest(Map("message" -> explain(conflict)))
      case ChangeTemplateResult.AlbumNotFound        => albumNotFound
      case ChangeTemplateResult.SpreadNotFound       => notFound("spread_not_found")
      case ChangeTemplateResult.Failed               => serviceFailure

    private def delete(album: AlbumId, spread: SpreadId, maybeConfirm: Option[String]) =
      val parsed = parseConfirmation(maybeValue = maybeConfirm).fold(
        message => BadRequest(Map("message" -> message)).map(_.asLeft[Boolean]),
        _.asRight[Response[F]].pure[F]
      )
      Route(
        Routes.Access.Editor(Protection.ChangeSpreads(Set(EditingTarget.Spread(spread)))),
        parsed,
        (confirm: Boolean) =>
          spreads.delete(album, spread, confirmPlacements = confirm).flatMap:
            case DeleteResult.Deleted              => NoContent()
            case DeleteResult.Occupied(placements) => Conflict(encodeOccupied(placements))
            case DeleteResult.Rejected(conflict)   => BadRequest(Map("message" -> explain(conflict)))
            case DeleteResult.AlbumNotFound        => albumNotFound
            case DeleteResult.SpreadNotFound       => notFound("spread_not_found")
            case DeleteResult.Failed               => serviceFailure
      )

    private def parseConfirmation(maybeValue: Option[String]) =
      maybeValue.traverse {
        case "true"  => true.asRight[String]
        case "false" => false.asRight[String]
        case _       => "confirmPlacements must be true or false".asLeft[Boolean]
      }.map(_.contains(true))

    private def decode[A](request: Request[F], decoder: Decoder[A]) =
      JsonBody.decode(request, decoder, malformed = "Supply a valid JSON spread request")(_.message)
        .flatMap(_.fold(message => BadRequest(Map("message" -> message)).map(_.asLeft[A]), _.asRight[Response[F]].pure[F]))

    private val afterDecoder = Decoder.decodeString
      .emap(value => CanonicalId.unapply(value).map(SpreadId(_)).toRight("Spread IDs must be canonical UUIDs"))
      .prepare(_.downField("afterSpreadId"))

    private val templateDecoder = Decoder.decodeString
      .emap(value => CanonicalId.unapply(value).map(SpreadTemplateId(_)).toRight("Template IDs must be canonical UUIDs"))
      .prepare(_.downField("templateId"))

    private def encodeOccupied(placements: Int) =
      Json.obj("code" -> Json.fromString("spread_occupied"), "placements" -> Json.fromInt(placements))

    private def explain(conflict: SpreadConflict) = conflict match
      case SpreadConflict.AtCapacity      => "An album can contain at most 65 interior spreads"
      case SpreadConflict.AddTarget       => "Add a spread after a chapter opening or photo spread"
      case SpreadConflict.ContextMismatch => "Choose a layout for this spread's context"
      case SpreadConflict.OpeningDelete   => "Openings cannot be deleted"
      case SpreadConflict.UnknownTemplate => "Choose a layout from the catalog"

    private def albumNotFound          = NotFound(Map("code" -> "album_not_found"))
    private def notFound(code: String) = NotFound(Map("code" -> code))
    private def serviceFailure         = InternalServerError(Map("code" -> "service_failure"))

    private object ConfirmPlacements extends OptionalQueryParamDecoderMatcher[String]("confirmPlacements")
