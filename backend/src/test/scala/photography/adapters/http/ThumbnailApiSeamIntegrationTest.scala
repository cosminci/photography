package photography.adapters.http

import cats.effect.{IO, Ref}
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
    val calls      = Ref.unsafe[IO, Vector[ThumbnailRead]](Vector.empty)
    val app        = PublicRoutes(ThumbnailApi.make(buildStorage(calls, ThumbnailStorage.ReadResult.Read(bytes).pure[IO]))).orNotFound
    val conditions = Vector("*" -> Status.NotModified, s"""W/"$version"""" -> Status.NotModified, """"different"""" -> Status.Ok)

    for
      response  <- app.run(Request[IO](Method.GET, uri))
      actual    <- response.body.compile.toVector
      requested <- calls.get
      head      <- app.run(Request[IO](Method.HEAD, uri))
      headBody  <- head.body.compile.toVector
      cached    <- conditions.traverse { case (condition, expected) =>
        app.run(Request[IO](Method.GET, uri).putHeaders("If-None-Match" -> condition)).flatMap: value =>
          value.body.compile.toVector.map(body => (value.status, expected, body))
      }
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(actual, bytes: Vector[Byte])
      assertEquals(requested, Vector(ThumbnailRead(location, version)))
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
    val calls     = Ref.unsafe[IO, Vector[ThumbnailRead]](Vector.empty)
    val forbidden = buildStorage(calls, ThumbnailStorage.ReadResult.NotFound.pure[IO])
    val paths     = Vector(
      "/thumbnails/../original.jpg",
      s"/thumbnails/${location.value}/../original.jpg",
      "/thumbnails/%2e%2e/original.jpg",
      "/thumbnails/invalid",
      s"/thumbnails/invalid/${"0" * 64}.jpg",
      s"/thumbnails/${location.value}/bad.jpg",
      "/unrelated"
    )
    val failures = Vector(ThumbnailStorage.ReadResult.NotFound, ThumbnailStorage.ReadResult.Failed)

    val excluded = paths.traverse_(path =>
      PublicRoutes(ThumbnailApi.make(forbidden)).orNotFound.run(Request[IO](uri = Uri.unsafeFromString(path))).map(r =>
        assertEquals(r.status, Status.NotFound)
      )
    )
    for
      _        <- excluded
      statuses <- failures.traverse { result =>
        val service = buildStorage(calls, result.pure[IO])
        PublicRoutes(ThumbnailApi.make(service)).orNotFound.run(Request[IO](uri = uri)).map(_.status)
      }
      requested <- calls.get
    yield
      assertEquals(statuses, Vector(Status.NotFound, Status.InternalServerError))
      assertEquals(requested, Vector.fill(2)(ThumbnailRead(location, version)))

  final private case class ThumbnailRead(location: ThumbnailLocation, version: String :| Not[Blank])
  private def buildStorage(calls: Ref[IO, Vector[ThumbnailRead]], response: IO[ThumbnailStorage.ReadResult]) =
    new ThumbnailStorage[IO]:
      override def readThumbnail(id: ThumbnailLocation, requestedVersion: String :| Not[Blank]) =
        calls.update(_ :+ ThumbnailRead(id, requestedVersion)) >> response
      override def storeThumbnail(id: ThumbnailLocation, image: EncodedThumbnail): IO[ThumbnailStorage.StoreResult] =
        new AssertionError("Unexpected write").raiseError[IO, ThumbnailStorage.StoreResult]
      override def removeThumbnail(id: ThumbnailLocation): IO[ThumbnailStorage.RemoveResult] =
        new AssertionError("Unexpected removal").raiseError[IO, ThumbnailStorage.RemoveResult]
