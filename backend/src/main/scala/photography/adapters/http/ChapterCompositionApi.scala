package photography.adapters.http

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all.*
import io.circe.Decoder
import org.http4s.{Request, Response}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.{AlbumId, ChapterId}
import photography.adapters.http.Routes.Route
import photography.usecases.editing.EditingAccess.Protection
import photography.domain.layout.SpreadId
import photography.usecases.spread.SpreadManager

object ChapterCompositionApi:
  def make[F[_]: Async](spreads: SpreadManager[F]): Routes[F] = new LiveChapterCompositionApi(spreads)

  final private class LiveChapterCompositionApi[F[_]: Async](spreads: SpreadManager[F]) extends Routes[F] with Http4sDsl[F]:
    override def routes: PartialFunction[Request[F], Route[F, ?]] = {
      case request @ PUT -> Root / "api" / "albums" / CanonicalId(album) / "chapters" / CanonicalId(chapter) / "composition" =>
        replace(AlbumId(album), ChapterId(chapter), request)
    }

    private def replace(album: AlbumId, chapter: ChapterId, request: Request[F]) =
      Route(
        Routes.Access.Editor(Protection.ActiveTab),
        decode(request),
        (order: Order) =>
          spreads.replaceChapterOrder(album, chapter, order.expected, order.proposed).flatMap:
            case SpreadManager.ReplacementResult.Saved     => NoContent()
            case SpreadManager.ReplacementResult.Invalid   => BadRequest(Map("message" -> "Retain the opening and every spread exactly once"))
            case SpreadManager.ReplacementResult.NotFound  => NotFound(Map("code" -> "chapter_not_found"))
            case SpreadManager.ReplacementResult.Stale     => Conflict(Map("code" -> "composition_changed"))
            case SpreadManager.ReplacementResult.Failed    => InternalServerError(Map("code" -> "service_failure"))
            case SpreadManager.ReplacementResult.Uncertain => InternalServerError(Map("code" -> "write_uncertain"))
      )

    private def decode(request: Request[F]) =
      JsonBody.decode(request, Decoder[Order], malformed = "Supply a valid composition")(_.message)
        .flatMap(_.fold(message => BadRequest(Map("message" -> message)).map(_.asLeft[Order]), _.asRight[Response[F]].pure[F]))

    private given Decoder[SpreadId] =
      Decoder.decodeString.emap(value => CanonicalId.unapply(value).map(SpreadId(_)).toRight("Use canonical spread IDs"))
    private given Decoder[NonEmptyList[SpreadId]] =
      Decoder.decodeList[SpreadId].emap(values => NonEmptyList.fromList(values).toRight("Supply an opening and spread order"))
    private given Decoder[Order] = Decoder.forProduct2("expectedSpreadIds", "spreadIds")(Order.apply)

  final private case class Order(expected: NonEmptyList[SpreadId], proposed: NonEmptyList[SpreadId])
