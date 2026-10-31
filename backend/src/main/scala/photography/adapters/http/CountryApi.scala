package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import org.http4s.{CacheDirective, HttpRoutes}
import org.http4s.headers.`Cache-Control`
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.Country
import photography.ports.album.CountryRepository

object CountryApi:
  def make[F[_]: Async](countries: CountryRepository[F]): HttpRoutes[F] =
    new LiveCountryApi(countries).routes

  final private class LiveCountryApi[F[_]: Async](countries: CountryRepository[F]) extends Http4sDsl[F]:
    val routes: HttpRoutes[F] = HttpRoutes.of[F] {
      case GET -> Root / "api" / "countries" => list
    }

    private def list =
      countries.list.flatMap:
        case CountryRepository.ListResult.Listed(values) =>
          Ok(Json.obj("countries" -> Json.arr(values.toList.map(encode)*))).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-store`)))
        case CountryRepository.ListResult.Failed => InternalServerError(Map("code" -> "service_failure"))

    private def encode(value: Country) =
      Json.obj("id" -> Json.fromString(value.id.value.toString), "name" -> Json.fromString(value.name), "code" -> Json.fromString(value.code))
