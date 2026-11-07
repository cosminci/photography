package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityCodec.*
import org.typelevel.ci.CIString
import photography.domain.album.AlbumId
import photography.domain.album.{ChapterId, PhotoId}
import photography.domain.layout.{SpreadId, PhotoSpreadSlotId, CoverSlotId}
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
      calls     <- Ref.of[IO, Vector[AlbumId]](Vector.empty)
      response  <- read(calls, Result.Ready(json, filename), path)
      body      <- response.as[Json]
      requested <- calls.get
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(body, json)
      assertEquals(response.headers.get(CIString("Content-Disposition")).map(_.head.value), s"""attachment; filename="$filename"""".some)
      assertEquals(response.headers.get(CIString("Cache-Control")).map(_.head.value), "no-store".some)
      assertEquals(requested, Vector(album))

  test("should report scoped blocking issues without an attachment"):
    val chapter = ChapterId(new UUID(0L, 2L))
    val spread  = SpreadId(new UUID(0L, 3L))
    val slot    = PhotoSpreadSlotId(new UUID(0L, 4L))
    val cover   = CoverSlotId(new UUID(0L, 5L))
    val photo   = PhotoId(new UUID(0L, 6L))
    val issues  = Vector(
      (
        issue = Issue(Code.EmptySynopsis, Severity.Blocking, Target.Album, "Album synopsis is empty."),
        code = "empty_synopsis",
        severity = "blocking",
        target = Json.obj("kind" -> Json.fromString("album"))
      ),
      (
        issue = Issue(Code.EmptyDescription, Severity.Blocking, Target.Chapter(chapter), "Chapter description is empty."),
        code = "empty_description",
        severity = "blocking",
        target = Json.obj("kind" -> Json.fromString("chapter"), "chapter" -> Json.fromString(chapter.value.toString))
      ),
      (
        issue = Issue(Code.EmptyPhotoSlot, Severity.Blocking, Target.Slot(spread, slot), "Spread photograph is missing."),
        code = "empty_photo_slot",
        severity = "blocking",
        target = Json.obj(
          "kind"   -> Json.fromString("slot"),
          "spread" -> Json.fromString(spread.value.toString),
          "slot"   -> Json.fromString(slot.value.toString)
        )
      ),
      (
        issue = Issue(Code.EmptyPhotoSlot, Severity.Blocking, Target.CoverSlot(cover), "Cover photograph is missing."),
        code = "empty_photo_slot",
        severity = "blocking",
        target = Json.obj("kind" -> Json.fromString("cover"), "slot" -> Json.fromString(cover.value.toString))
      ),
      (
        issue = Issue(Code.LowResolution, Severity.Warning, Target.Photo(photo), "Photograph has low resolution."),
        code = "low_resolution",
        severity = "warning",
        target = Json.obj("kind" -> Json.fromString("photo"), "photo" -> Json.fromString(photo.value.toString))
      )
    )
    val expected = Json.obj(
      "code"   -> Json.fromString("export_blocked"),
      "issues" -> Json.fromValues(issues.map { case (issue, code, severity, target) =>
        Json.obj(
          "code"     -> Json.fromString(code),
          "severity" -> Json.fromString(severity),
          "target"   -> target,
          "message"  -> Json.fromString(issue.message)
        )
      })
    )

    for
      calls    <- Ref.of[IO, Vector[AlbumId]](Vector.empty)
      response <- read(calls, Result.Blocked(issues.map(_.issue)), path)
      body     <- response.as[Json]
    yield
      assertEquals(response.status, Status.UnprocessableContent)
      assertEquals(body, expected)
      assertEquals(response.headers.get(CIString("Content-Disposition")), none)
      assertEquals(response.headers.get(CIString("Cache-Control")).map(_.head.value), "no-store".some)

  test("should distinguish an absent album from export failure without offering a download"):
    val results = Vector(
      (result = Result.NotFound, status = Status.NotFound, code = "album_not_found"),
      (result = Result.Failed, status = Status.InternalServerError, code = "service_failure")
    )

    results.traverse_ { case (result, status, code) =>
      for
        calls    <- Ref.of[IO, Vector[AlbumId]](Vector.empty)
        response <- read(calls, result, path)
        body     <- response.as[Json]
      yield
        assertEquals(response.status, status)
        assertEquals(body, Json.obj("code" -> Json.fromString(code)))
        assertEquals(response.headers.get(CIString("Content-Disposition")), none)
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

  private def read(calls: Ref[IO, Vector[AlbumId]], result: Result, path: String) =
    val exports = new BookExport[IO]:
      override def exportBook(id: AlbumId): IO[Result] = calls.update(_ :+ id).as(result)
    PublicRoutes(BookExportApi.make(exports)).orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString(path)))
