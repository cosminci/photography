package photography.adapters.http

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.http4s.{CacheDirective, Method, Request, Status, Uri}
import org.http4s.headers.`Cache-Control`
import org.http4s.circe.CirceEntityCodec.*

final class PhotoSourceApiSeamIntegrationTest extends CatsEffectSuite:
  test("should supply the configured photo-folder suggestion root as an independent uncached resource"):
    for
      response <- PhotoSourceApi.make[IO]("/photos").orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString("/api/photo-source")))
      body     <- response.as[Json]
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(body, Json.obj("photoRoot" -> Json.fromString("/photos")))
      assertEquals(response.headers.get[`Cache-Control`], `Cache-Control`(CacheDirective.`no-store`).some)
