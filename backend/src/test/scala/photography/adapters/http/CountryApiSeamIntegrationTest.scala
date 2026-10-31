package photography.adapters.http

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.http4s.{CacheDirective, Method, Request, Status, Uri}
import org.http4s.headers.`Cache-Control`
import org.http4s.circe.CirceEntityCodec.*
import photography.domain.album.*
import photography.ports.album.CountryRepository
import java.nio.file.Path
import java.util.UUID

final class CountryApiSeamIntegrationTest extends CatsEffectSuite:
  private val country = Country(CountryId(new UUID(0L, 1L)), "RO", "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))

  test("should expose the authoritative catalog without leaking filesystem flag paths"):
    val expected = Json.obj("countries" -> Json.arr(
      Json.obj("id" -> Json.fromString(country.id.value.toString), "name" -> Json.fromString("Romania"), "code" -> Json.fromString("RO"))
    ))

    for
      response <- read(CountryRepository.ListResult.Listed(NonEmptyList.one(country)))
      body     <- response.as[Json]
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(body, expected)
      assertEquals(response.headers.get[`Cache-Control`], `Cache-Control`(CacheDirective.`no-store`).some)

  test("should expose a failed catalog read rather than an empty catalog"):
    for
      response <- read(CountryRepository.ListResult.Failed)
      body     <- response.as[Json]
    yield
      assertEquals(response.status, Status.InternalServerError)
      assertEquals(body, Json.obj("code" -> Json.fromString("service_failure")))

  private def read(result: CountryRepository.ListResult) =
    val repository = new CountryRepository[IO]:
      override def list: IO[CountryRepository.ListResult] = result.pure[IO]
    CountryApi.make(repository).orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString("/api/countries")))
