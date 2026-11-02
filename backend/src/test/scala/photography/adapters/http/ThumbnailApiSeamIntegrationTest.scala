package photography.adapters.http

import cats.effect.IO
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import org.http4s.{MediaType, Method, Request, Status, Uri}
import org.typelevel.ci.CIString
import photography.domain.album.{EncodedThumbnail, ThumbnailBytesConstraint, ThumbnailLocation}
import photography.ports.photo.ThumbnailStorage
import java.util.UUID

final class ThumbnailApiSeamIntegrationTest extends CatsEffectSuite:
  private val location                      = ThumbnailLocation(new UUID(0L, 1L))
  private val version: String :| Not[Blank] = "a12871fee210fb8619291eaea194581cbd2531e4b23759d225f6806923f63222"
  private val bytes                         = Vector(1.toByte, 2.toByte).refineUnsafe[ThumbnailBytesConstraint]
  private val uri                           = Uri.unsafeFromString(s"/thumbnails/${location.value}/$version.jpg")

  test("should translate versioned delivery into immutable JPEG responses and honor conditional GET and HEAD"):
    val app        = PublicRoutes(ThumbnailApi.make(buildStorage(ThumbnailStorage.ReadResult.Read(bytes).pure[IO]))).orNotFound
    val conditions = Vector("*" -> Status.NotModified, s"""W/"$version"""" -> Status.NotModified, """"different"""" -> Status.Ok)

    for
      response <- app.run(Request[IO](Method.GET, uri))
      actual   <- response.body.compile.toVector
      head     <- app.run(Request[IO](Method.HEAD, uri))
      headBody <- head.body.compile.toVector
      cached   <- conditions.traverse { case (condition, expected) =>
        app.run(Request[IO](Method.GET, uri).putHeaders("If-None-Match" -> condition)).flatMap: value =>
          value.body.compile.toVector.map(body => (value.status, expected, body))
      }
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(actual, bytes: Vector[Byte])
      assertEquals(response.contentType.map(_.mediaType), MediaType.image.jpeg.some)
      assertEquals(response.headers.get(CIString("Cache-Control")).map(_.head.value), "private, max-age=31536000, immutable".some)
      assertEquals(response.headers.get(CIString("ETag")).map(_.head.value), s""""$version"""".some)
      assertEquals(head.status, Status.Ok)
      assertEquals(headBody, Vector.empty[Byte])
      cached.foreach { case (status, expected, body) =>
        assertEquals(status, expected)
        assertEquals(body, if expected === Status.NotModified then Vector.empty[Byte] else bytes: Vector[Byte])
      }

  test("should reject traversal and malformed paths without invoking delivery and translate missing and failed reads"):
    val forbidden = buildStorage(IO.raiseError[ThumbnailStorage.ReadResult](new AssertionError("Unexpected read")))
    val paths     = Vector(
      "/thumbnails/../original.jpg",
      s"/thumbnails/${location.value}/../original.jpg",
      "/thumbnails/%2e%2e/original.jpg",
      "/thumbnails/invalid",
      s"/thumbnails/invalid/${"0" * 64}.jpg",
      s"/thumbnails/${location.value}/bad.jpg",
      "/unrelated"
    )
    val failures = Vector(ThumbnailStorage.ReadResult.NotFound -> Status.NotFound, ThumbnailStorage.ReadResult.Failed -> Status.InternalServerError)

    paths.traverse_(path =>
      PublicRoutes(ThumbnailApi.make(forbidden)).orNotFound.run(Request[IO](uri = Uri.unsafeFromString(path))).map(r =>
        assertEquals(r.status, Status.NotFound)
      )
    ) >>
      failures.traverse_ { case (result, expected) =>
        val service = buildStorage(result.pure[IO])
        PublicRoutes(ThumbnailApi.make(service)).orNotFound.run(Request[IO](uri = uri)).map(r => assertEquals(r.status, expected))
      }

  private def buildStorage(response: IO[ThumbnailStorage.ReadResult]) =
    new ThumbnailStorage[IO]:
      override def readThumbnail(id: ThumbnailLocation, requestedVersion: String :| Not[Blank]) =
        IO {
          assertEquals(id, location)
          assertEquals(requestedVersion, version)
        } >> response
      override def storeThumbnail(id: ThumbnailLocation, image: EncodedThumbnail): IO[ThumbnailStorage.StoreResult] =
        IO.raiseError[ThumbnailStorage.StoreResult](new AssertionError("Unexpected write"))
      override def removeThumbnail(id: ThumbnailLocation): IO[ThumbnailStorage.RemoveResult] =
        IO.raiseError[ThumbnailStorage.RemoveResult](new AssertionError("Unexpected removal"))
