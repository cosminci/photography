package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{HttpApp, MediaType, Method, Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import photography.domain.album.{ChapterId, PhotoId, PhotoSource}
import photography.usecases.photo.PhotoManager
import photography.usecases.photo.PhotoManager.SaveCaptionResult
import java.util.UUID

final class PhotoApiSeamIntegrationTest extends CatsEffectSuite:
  private val photo = PhotoId(value = new UUID(0L, 1L))
  private val path  = s"/api/photos/${photo.value}/caption"

  Vector("Cluj — 山\nSecond line", "", " \t\n ").foreach { text =>
    val result = SaveCaptionResult.Saved
    test(s"should deliver caption $result with exact text ${Json.fromString(text).noSpaces}"):
      val refs        = Refs()
      val server      = buildApi(refs, result.pure[IO])
      val requestBody = Json.obj("text" -> Json.fromString(text)).noSpaces

      for
        response <- send(server, path = path, body = requestBody)
        calls    <- refs.calls.get
      yield
        assertEquals(response, WireResponse(Status.NoContent, body = "", maybeMediaType = None, maybeCacheControl = None))
        assertEquals(calls, Vector(CaptionCall(photo, text = text)))
  }

  Vector(
    CaptionScenario(SaveCaptionResult.PhotoNotFound, Status.NotFound, code = "photo_not_found"),
    CaptionScenario(SaveCaptionResult.Failed, Status.InternalServerError, code = "service_failure"),
    CaptionScenario(SaveCaptionResult.Uncertain, Status.InternalServerError, code = "mutation_uncertain")
  ).foreach { scenario =>
    test(s"should deliver caption ${scenario.result} without read import or retry"):
      val refs   = Refs()
      val server = buildApi(refs, scenario.result.pure[IO])

      for
        response <- send(server, path = path, body = """{"text":"once"}""")
        calls    <- refs.calls.get
      yield
        val expectedBody = Json.obj("code" -> Json.fromString(scenario.code)).noSpaces
        assertEquals(response, WireResponse(scenario.status, expectedBody, MediaType.application.json.some, maybeCacheControl = None))
        assertEquals(calls, Vector(CaptionCall(photo, text = "once")))
  }

  test("should reject malformed captions and canonical identity violations before application work"):
    val refs          = Refs()
    val server        = buildApi(refs, IO.raiseError[SaveCaptionResult](new AssertionError("Unexpected caption write")))
    val malformed     = Vector("{", "[]", "null", "{}", """{"text":null}""", """{"text":1}""", """{"text":[]}""")
    val checkPayloads = malformed.traverse_ { body =>
      val expected = WireResponse(
        Status.BadRequest,
        body = """{"message":"Supply a text string"}""",
        MediaType.application.json.some,
        maybeCacheControl = None
      )
      send(server, path = path, body = body).map(response => assertEquals(response, expected))
    }
    val checkTargets = Vector("bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A").traverse_ { id =>
      val expected = WireResponse(
        Status.NotFound,
        body = """{"code":"photo_not_found"}""",
        MediaType.application.json.some,
        maybeCacheControl = None
      )
      send(server, path = s"/api/photos/$id/caption", body = "{}").map(response => assertEquals(response, expected))
    }

    for
      _     <- checkPayloads
      _     <- checkTargets
      calls <- refs.calls.get
    yield assertEquals(calls, Vector.empty[CaptionCall])

  final private case class CaptionCall(photo: PhotoId, text: String)
  final private case class CaptionScenario(result: SaveCaptionResult, status: Status, code: String)
  final private case class WireResponse(status: Status, body: String, maybeMediaType: Option[MediaType], maybeCacheControl: Option[String])
  final private case class Refs():
    val calls: Ref[IO, Vector[CaptionCall]] = Ref.unsafe(a = Vector.empty[CaptionCall])

  private def buildApi(refs: Refs, result: IO[SaveCaptionResult]) =
    val photos = new PhotoManager[IO]:
      override def saveCaption(photo: PhotoId, text: String): IO[SaveCaptionResult] =
        refs.calls.update(_ :+ CaptionCall(photo, text = text)) >> result
      override def refresh(chapter: ChapterId, source: PhotoSource): IO[PhotoManager.RefreshResult] =
        IO.raiseError[PhotoManager.RefreshResult](new AssertionError("Unexpected photo import"))
    PhotoApi.make(photos).routes.orNotFound

  private def send(server: HttpApp[IO], path: String, body: String) =
    val request = Request[IO](Method.PUT, Uri.unsafeFromString(s = path)).withEntity(b = body)
      .putHeaders(`Content-Type`(MediaType.application.json))
    for
      response <- server.run(request)
      content  <- response.as[String]
    yield WireResponse(
      response.status,
      content,
      response.contentType.map(_.mediaType),
      response.headers.get(CIString(value = "Cache-Control")).map(_.head.value)
    )
