package photography.adapters.http

import photography.adapters.http.Routes.Route
import org.http4s.Request

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import org.http4s.CacheDirective
import org.http4s.headers.`Cache-Control`
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.AlbumId
import photography.ports.album.AlbumCompositionRepository
import java.util.UUID

object AlbumCompositionApi:
  def make[F[_]: Async](composition: AlbumCompositionRepository.MembershipRead[F]): Routes[F] =
    new LiveAlbumCompositionApi(composition)

  final private class LiveAlbumCompositionApi[F[_]: Async](composition: AlbumCompositionRepository.MembershipRead[F]) extends Routes[F]
      with Http4sDsl[F]:
    override def routes: PartialFunction[Request[F], Route[F, ?]] = {
      case GET -> Root / "api" / "albums" / CanonicalId(id) / "composition" => Route.public(read(AlbumId(id)))
      case GET -> Root / "api" / "albums" / _ / "composition"               => Route.public(missing)
    }

    private def read(id: AlbumId) =
      composition.composition(id).flatMap:
        case AlbumCompositionRepository.CompositionResult.Found(values) => Ok(Json.obj("spreads" -> Json.arr(values.toList.map(encode)*)))
            .map(_.putHeaders(`Cache-Control`(CacheDirective.`no-store`)))
        case AlbumCompositionRepository.CompositionResult.AlbumNotFound => missing
        case AlbumCompositionRepository.CompositionResult.Failed(_)     => InternalServerError(Map("code" -> "service_failure"))

    private def missing = NotFound(Map("code" -> "album_not_found"))

    private def encode(value: AlbumCompositionRepository.Member) =
      Json.obj(
        "id"      -> Json.fromString(value.id.value.toString),
        "chapter" -> value.maybeChapter.fold(Json.Null)(id => Json.fromString(id.value.toString))
      )

    private object CanonicalId:
      def unapply(value: String): Option[UUID] =
        Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value)
