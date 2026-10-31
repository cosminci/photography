package photography.adapters.http

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import org.http4s.{HttpRoutes, MessageBodyFailure, Request}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.layout.SpreadId
import photography.usecases.spread.SpreadManager
import java.util.UUID

object ChapterCompositionApi:
  def make[F[_]: Async](spreads: SpreadManager[F]): HttpRoutes[F] = new LiveChapterCompositionApi(spreads).routes

  final private class LiveChapterCompositionApi[F[_]: Async](spreads: SpreadManager[F]) extends Http4sDsl[F]:
    val routes: HttpRoutes[F] = HttpRoutes.of[F] {
      case request @ PUT -> Root / "api" / "albums" / CanonicalId(album) / "chapters" / CanonicalId(chapter) / "composition" =>
        replace(AlbumId(album), ChapterId(chapter), request)
    }

    private def replace(album: AlbumId, chapter: ChapterId, request: Request[F]) =
      decode(request).flatMap:
        case Left(message) => BadRequest(Map("message" -> message))
        case Right(order)  =>
          spreads.replaceChapterOrder(album, chapter, order.expected, order.proposed).flatMap:
            case SpreadManager.ReplacementResult.Saved     => NoContent()
            case SpreadManager.ReplacementResult.Invalid   => BadRequest(Map("message" -> "Retain the opening and every spread exactly once"))
            case SpreadManager.ReplacementResult.NotFound  => NotFound(Map("code" -> "chapter_not_found"))
            case SpreadManager.ReplacementResult.Stale     => Conflict(Map("code" -> "composition_changed"))
            case SpreadManager.ReplacementResult.Failed    => InternalServerError(Map("code" -> "service_failure"))
            case SpreadManager.ReplacementResult.Uncertain => InternalServerError(Map("code" -> "write_uncertain"))

    private def decode(request: Request[F]) =
      request.as[Json].map(value => Decoder[Order].decodeJson(value).leftMap(_.message))
        .recover { case _: MessageBodyFailure => "Supply a valid composition".asLeft[Order] }

    private object CanonicalId:
      def unapply(value: String): Option[UUID] =
        Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value)

    private given Decoder[SpreadId] =
      Decoder.decodeString.emap(value => CanonicalId.unapply(value).map(SpreadId(_)).toRight("Use canonical spread IDs"))
    private given Decoder[NonEmptyList[SpreadId]] =
      Decoder.decodeList[SpreadId].emap(values => NonEmptyList.fromList(values).toRight("Supply an opening and spread order"))
    private given Decoder[Order] = Decoder.forProduct2("expectedSpreadIds", "spreadIds")(Order.apply)

  final private case class Order(expected: NonEmptyList[SpreadId], proposed: NonEmptyList[SpreadId])
