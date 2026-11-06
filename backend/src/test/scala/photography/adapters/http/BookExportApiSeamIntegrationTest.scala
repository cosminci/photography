package photography.adapters.http

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityCodec.*
import org.typelevel.ci.CIString
import photography.domain.album.AlbumId
import photography.usecases.album.BookExport
import photography.usecases.album.BookExport.*
import java.util.UUID

final class BookExportApiSeamIntegrationTest extends CatsEffectSuite:
  private val album = AlbumId(new UUID(0L, 1L))
  private val path  = s"/api/albums/${album.value}/indesign-export"

  test("should download the validated artifact returned by export without regenerating it"):
    val json     = Json.obj("retained" -> Json.fromString("Exact Ș Hội An"))
    val filename = "album-2025-indesign.json"

    for
      response <- read(Result.Ready(json, filename), path)
      body     <- response.as[Json]
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(body, json)
      assertEquals(response.headers.get(CIString("Content-Disposition")).map(_.head.value), s"""attachment; filename="$filename"""".some)
      assertEquals(response.headers.get(CIString("Cache-Control")).map(_.head.value), "no-store".some)

  test("should report scoped blocking issues without an attachment"):
    val issue    = Issue(Code.EmptySynopsis, Severity.Blocking, Target.Album, "Album synopsis is empty.")
    val expected = Json.obj(
      "code"   -> Json.fromString("export_blocked"),
      "issues" -> Json.arr(Json.obj(
        "code"     -> Json.fromString("empty_synopsis"),
        "severity" -> Json.fromString("blocking"),
        "target"   -> Json.obj("kind" -> Json.fromString("album")),
        "message"  -> Json.fromString("Album synopsis is empty.")
      ))
    )

    for
      response <- read(Result.Blocked(Vector(issue)), path)
      body     <- response.as[Json]
    yield
      assertEquals(response.status, Status.UnprocessableContent)
      assertEquals(body, expected)
      assertEquals(response.headers.get(CIString("Content-Disposition")), None)
      assertEquals(response.headers.get(CIString("Cache-Control")).map(_.head.value), "no-store".some)

  test("should distinguish an absent album from export failure without offering a download"):
    val results = Vector(
      (result = Result.NotFound, status = Status.NotFound, code = "album_not_found"),
      (result = Result.Failed, status = Status.InternalServerError, code = "service_failure")
    )

    results.traverse_ { case (result, status, code) =>
      for
        response <- read(result, path)
        body     <- response.as[Json]
      yield
        assertEquals(response.status, status)
        assertEquals(body, Json.obj("code" -> Json.fromString(code)))
        assertEquals(response.headers.get(CIString("Content-Disposition")), None)
        assertEquals(response.headers.get(CIString("Cache-Control")).map(_.head.value), "no-store".some)
    }

  test("should reject a noncanonical album identity without invoking export"):
    val forbidden = new BookExport[IO]:
      override def exportBook(id: AlbumId): IO[Result] = fail("Invalid IDs must not trigger acquisition")

    for
      response <- PublicRoutes(BookExportApi.make(forbidden)).orNotFound.run(Request[IO](
        Method.GET,
        Uri.unsafeFromString("/api/albums/1-1-1-1-1/indesign-export")
      ))
      body <- response.as[Json]
    yield
      assertEquals(response.status, Status.NotFound)
      assertEquals(body, Json.obj("code" -> Json.fromString("album_not_found")))

  private def read(result: Result, path: String) =
    val exports = new BookExport[IO]:
      override def exportBook(id: AlbumId): IO[Result] = IO(assertEquals(id, album)).as(result)
    PublicRoutes(BookExportApi.make(exports)).orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString(path)))
