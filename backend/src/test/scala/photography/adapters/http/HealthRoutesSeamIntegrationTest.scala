package photography.adapters.http

import cats.syntax.all.*
import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.{MediaType, Method, Request, Status, Uri}

final class HealthRoutesSeamIntegrationTest extends CatsEffectSuite:

  test("should serve JSON readiness at /health and not found for unknown paths"):
    val healthRoutes = PublicRoutes(HealthRoutes.routes[IO]).orNotFound
    val health       = Request[IO](Method.GET, Uri.unsafeFromString("/health"))
    val unknown      = Request[IO](Method.GET, Uri.unsafeFromString("/unknown"))

    for
      response <- healthRoutes.run(health)
      body     <- response.as[String]
      missing  <- healthRoutes.run(unknown)
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(response.contentType.map(_.mediaType), MediaType.application.json.some)
      assertEquals(body, """{"status":"ok"}""")
      assertEquals(missing.status, Status.NotFound)
