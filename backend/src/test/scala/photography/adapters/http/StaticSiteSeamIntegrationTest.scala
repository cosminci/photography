package photography.adapters.http

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.http4s.{HttpRoutes, MediaType, Method, Request, Response, Status, Uri}
import org.http4s.headers.`Content-Type`

import java.nio.charset.StandardCharsets

final class StaticSiteSeamIntegrationTest extends CatsEffectSuite:

  test("should serve the entry point for extensionless navigation and preserve built resource bytes and media types"):
    val app       = StaticSite.make(PublicRoutes(HealthRoutes.routes[IO]), assetRoot = "static-site")
    val scenarios = List(
      ("/", "index.html", MediaType.text.html.some),
      ("/albums/00000000-0000-0000-0000-000000000001?source=dashboard", "index.html", MediaType.text.html.some),
      ("/nested/extensionless/", "index.html", MediaType.text.html.some),
      ("/assets/app.js", "assets/app.js", MediaType.application.javascript.some),
      ("/assets/app.css", "assets/app.css", MediaType.text.css.some),
      ("/assets/LICENSE", "assets/LICENSE", none[MediaType])
    )

    scenarios.traverse_ { case (path, resource, mediaType) =>
      val fixture = Resource.fromAutoCloseable(IO.blocking(getClass.getResourceAsStream(s"/static-site/$resource")))
      fixture.use { stream =>
        for
          expected <- IO.blocking(stream.readAllBytes().toVector)
          response <- app.use(_.app.run(Request[IO](Method.GET, Uri.unsafeFromString(path))))
          actual   <- response.body.compile.toVector
        yield
          assertEquals(response.status, Status.Ok)
          assertEquals(response.contentType.map(_.mediaType), mediaType)
          assertEquals(actual, expected)
      }
    }

  test("should preserve API and health responses and return JSON for unmatched API requests"):
    val api = HttpRoutes.of[IO] {
      case request if request.uri.path.renderString === "/api/albums" =>
        Response[IO](Status.Ok).withEntity("""{"albums":[]}""").putHeaders(`Content-Type`(MediaType.application.json)).pure[IO]
    }
    val app       = StaticSite.make(api <+> PublicRoutes(HealthRoutes.routes[IO]), assetRoot = "static-site")
    val scenarios = List(
      (Method.GET, "/api/albums", Status.Ok, """{"albums":[]}"""),
      (Method.GET, "/health", Status.Ok, """{"status":"ok"}"""),
      (Method.GET, "/api", Status.NotFound, """{"code":"not_found"}"""),
      (Method.GET, "/api/unknown", Status.NotFound, """{"code":"not_found"}"""),
      (Method.POST, "/api/unknown", Status.NotFound, """{"code":"not_found"}""")
    )

    scenarios.traverse_ { case (method, path, status, expectedBody) =>
      for
        response <- app.use(_.app.run(Request[IO](method, Uri.unsafeFromString(path))))
        body     <- response.as[String]
      yield
        assertEquals(response.status, status)
        assertEquals(response.contentType.map(_.mediaType), MediaType.application.json.some)
        assertEquals(body, expectedBody)
    }

  test("should not turn missing resources or unsupported frontend methods into successful navigation"):
    val app       = StaticSite.make(PublicRoutes(HealthRoutes.routes[IO]), assetRoot = "static-site")
    val scenarios = List(
      (Method.GET, "/assets/missing.js"),
      (Method.GET, "/assets/missing"),
      (Method.GET, "/assets"),
      (Method.GET, "/health/unknown"),
      (Method.GET, "/missing.css"),
      (Method.GET, "/dotted.directory/route"),
      (Method.POST, "/albums/unknown"),
      (Method.PATCH, "/"),
      (Method.DELETE, "/assets/app.js")
    )

    scenarios.traverse_ { case (method, path) =>
      app.use(_.app.run(Request[IO](method, Uri.unsafeFromString(path)))).flatMap { response =>
        response.as[String].map { body =>
          assertEquals(response.status, Status.NotFound)
          assert(!body.contains("<!doctype html>"))
        }
      }
    }

  test("should serve bodyless HEAD responses with the same resource headers as GET"):
    val app   = StaticSite.make(PublicRoutes(HealthRoutes.routes[IO]), assetRoot = "static-site")
    val paths = List("/", "/albums/unknown", "/assets/app.js", "/assets/app.css")

    paths.traverse_ { path =>
      for
        getResponse  <- app.use(_.app.run(Request[IO](Method.GET, Uri.unsafeFromString(path))))
        _            <- getResponse.body.compile.drain
        headResponse <- app.use(_.app.run(Request[IO](Method.HEAD, Uri.unsafeFromString(path))))
        headBody     <- headResponse.body.compile.toVector
      yield
        assertEquals(headResponse.status, Status.Ok)
        assertEquals(headResponse.headers, getResponse.headers)
        assertEquals(headBody, Vector.empty[Byte])
    }

  test("should deliver the real built frontend entry point and its emitted assets"):
    val app        = StaticSite.make(PublicRoutes(HealthRoutes.routes[IO]), assetRoot = "frontend")
    val entryPoint = Resource.fromAutoCloseable(IO.blocking(getClass.getResourceAsStream("/frontend/index.html")))

    entryPoint.use { stream =>
      IO.blocking(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).flatMap { entryHtml =>
        val assetPaths = """/assets/[^"]+""".r.findAllIn(entryHtml).toList
        val scenarios  = List(("/", "/index.html"), ("/albums/reloaded", "/index.html")) ++ assetPaths.map(path => (path, path))
        assert(assetPaths.nonEmpty)

        scenarios.traverse_ { case (path, resource) =>
          val asset = Resource.fromAutoCloseable(IO.blocking(getClass.getResourceAsStream(s"/frontend$resource")))
          asset.use { assetStream =>
            for
              expectedBytes <- IO.blocking(assetStream.readAllBytes().toVector)
              assetResponse <- app.use(_.app.run(Request[IO](Method.GET, Uri.unsafeFromString(path))))
              actualBytes   <- assetResponse.body.compile.toVector
            yield
              assertEquals(assetResponse.status, Status.Ok)
              assertEquals(actualBytes, expectedBytes)
          }
        }
      }
    }
