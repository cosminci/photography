package photography.adapters.http

import photography.adapters.http.Routes.Route
import org.http4s.Request

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.Country
import photography.ports.album.CountryRepository

object CountryApi:
  def make[F[_]: Async](countries: CountryRepository[F]): Routes[F] =
    new LiveCountryApi(countries)

  final private class LiveCountryApi[F[_]: Async](countries: CountryRepository[F]) extends Routes[F] with Http4sDsl[F]:
    override def routes: PartialFunction[Request[F], Route[F, ?]] = {
      case GET -> Root / "api" / "countries" => Route.public(list)
    }

    private def list =
      countries.list.flatMap:
        case CountryRepository.ListResult.Listed(values) =>
          Ok(Json.obj("countries" -> Json.arr(values.toList.map(encode)*)))
        case CountryRepository.ListResult.Failed => InternalServerError(Map("code" -> "service_failure"))

    private def encode(value: Country) =
      Json.obj("id" -> Json.fromString(value.id.value.toString), "name" -> Json.fromString(value.name), "code" -> Json.fromString(value.code))
