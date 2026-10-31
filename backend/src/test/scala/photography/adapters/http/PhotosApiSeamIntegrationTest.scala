package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.parse
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.http4s.{HttpApp, MediaType, Method, Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import photography.domain.album.*
import photography.usecases.photo.{ChapterPhotoInventory, PhotoInventory, PhotoManager, PhotoWriteRecovery}
import photography.usecases.photo.ChapterPhotoImport.{ExecutionOutcome, Status as ImportStatus}
import photography.usecases.photo.PhotoManager.SaveCaptionResult
import java.util.UUID
import java.time.LocalDateTime

final class PhotosApiSeamIntegrationTest extends CatsEffectSuite:
  private val chapter             = ChapterId(value = new UUID(0L, 2L))
  private val photo               = PhotoId(value = new UUID(0L, 1L))
  private val path                = s"/api/chapters/${chapter.value}/photos/${photo.value}/caption"
  private val unexpectedCaption   = IO.raiseError[SaveCaptionResult](new AssertionError("Unexpected caption write"))
  private val unexpectedInventory = IO.raiseError[ChapterPhotoInventory.Result](new AssertionError("Unexpected inventory read"))

  Vector("Cluj — 山\nSecond line", "", " \t\n ").foreach { text =>
    val result = SaveCaptionResult.Saved
    test(s"should deliver caption $result with exact text ${Json.fromString(text).noSpaces}"):
      val refs        = Refs()
      val server      = buildApi(refs, result.pure[IO], unexpectedInventory)
      val requestBody = Json.obj("text" -> Json.fromString(text)).noSpaces

      for
        response <- send(server, path = path, body = requestBody)
        calls    <- refs.calls.get
      yield
        assertEquals(response, WireResponse(Status.NoContent, body = "", maybeMediaType = None, maybeCacheControl = None))
        assertEquals(calls, Vector(CaptionCall(chapter, photo, text = text)))
  }

  Vector(
    CaptionScenario(SaveCaptionResult.PhotoNotFound, Status.NotFound, code = "photo_not_found"),
    CaptionScenario(SaveCaptionResult.Failed, Status.InternalServerError, code = "service_failure"),
    CaptionScenario(SaveCaptionResult.Uncertain, Status.InternalServerError, code = "mutation_uncertain")
  ).foreach { scenario =>
    test(s"should deliver caption ${scenario.result} without read import or retry"):
      val refs   = Refs()
      val server = buildApi(refs, scenario.result.pure[IO], unexpectedInventory)

      for
        response <- send(server, path = path, body = """{"text":"once"}""")
        calls    <- refs.calls.get
      yield
        val expectedBody = Json.obj("code" -> Json.fromString(scenario.code)).noSpaces
        assertEquals(response, WireResponse(scenario.status, expectedBody, MediaType.application.json.some, maybeCacheControl = None))
        assertEquals(calls, Vector(CaptionCall(chapter, photo, text = "once")))
  }

  test("should reject malformed captions and canonical identity violations before application work"):
    val refs          = Refs()
    val server        = buildApi(refs, unexpectedCaption, unexpectedInventory)
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
      send(server, path = s"/api/chapters/${chapter.value}/photos/$id/caption", body = "{}").map(response => assertEquals(response, expected))
    }

    for
      _     <- checkPayloads
      _     <- checkTargets
      calls <- refs.calls.get
    yield assertEquals(calls, Vector.empty[CaptionCall])

  test("should adapt chapter-addressed inventory with explicit missing and failure outcomes"):
    val empty     = PhotoInventory.Inventory(photos = Vector.empty[Photo], placed = Set.empty[PhotoId])
    val execution = ExecutionOutcome(ImportStatus.Idle, retirementIssues = Vector.empty[PhotoWriteRecovery.Issue])
    val found     = ChapterPhotoInventory.Result.Found(ChapterPhotoInventory.Snapshot(empty, execution))
    val scenarios = Vector(
      InventoryScenario(found, Status.Ok),
      InventoryScenario(ChapterPhotoInventory.Result.ChapterNotFound, Status.NotFound),
      InventoryScenario(ChapterPhotoInventory.Result.Failed, Status.InternalServerError)
    )
    scenarios.traverse_ { scenario =>
      val server = buildApi(Refs(), unexpectedCaption, scenario.result.pure[IO])

      for
        read    <- sendJson(server, Method.GET, s"/api/chapters/${chapter.value}/photos")
        missing <- sendJson(server, Method.GET, "/api/chapters/bad/photos")
      yield
        assertEquals(read.status, scenario.status)
        assertEquals(missing, ApiResponse(Status.NotFound, json("""{"code":"chapter_not_found"}""")))
    }

  test("should expose safe processing feedback"):
    val empty          = PhotoInventory.Inventory(photos = Vector.empty[Photo], placed = Set.empty[PhotoId])
    val completed      = PhotoManager.RefreshResult(unsupportedCount = 2, issues = Vector(PhotoManager.Issue.Undecodable(filename = "bad.jpg")))
    val idleProcessing = json("""{"state":"idle","unsupportedCount":0,"skippedCount":0,"issues":[]}""")
    val launchFailed   = json("""{"state":"failed","unsupportedCount":0,"skippedCount":0,
      "issues":[{"stage":"scheduling","code":"scheduling_failed","filename":null}]}""")
    val uncertain = json("""{"state":"failed","unsupportedCount":0,"skippedCount":0,
      "issues":[{"stage":"persistence","code":"persistence_failed","filename":null}]}""")
    val skipped = json("""{"state":"completed","unsupportedCount":2,"skippedCount":1,
      "issues":[{"stage":"read","code":"undecodable","filename":"bad.jpg"}]}""")
    val statuses = Vector(
      (ImportStatus.Idle, idleProcessing),
      (ImportStatus.Queued, idleProcessing.deepMerge(json("""{"state":"queued"}"""))),
      (ImportStatus.Running, idleProcessing.deepMerge(json("""{"state":"running"}"""))),
      (ImportStatus.LaunchFailed, launchFailed),
      (ImportStatus.Uncertain, uncertain),
      (ImportStatus.Finished(completed), skipped)
    )

    statuses.traverse_ { case (status, expected) =>
      val execution         = ExecutionOutcome(status, retirementIssues = Vector.empty[PhotoWriteRecovery.Issue])
      val snapshot          = ChapterPhotoInventory.Snapshot(empty, execution)
      val found             = ChapterPhotoInventory.Result.Found(snapshot)
      val server            = buildApi(Refs(), unexpectedCaption, found.pure[IO])
      val expectedInventory = Json.obj(
        "photos"         -> Json.arr(),
        "placedPhotoIds" -> Json.arr(),
        "counts"         -> Json.obj("unplaced" -> Json.fromInt(0), "total" -> Json.fromInt(0)),
        "processing"     -> expected
      )

      for
        response <- sendJson(server, Method.GET, s"/api/chapters/${chapter.value}/photos")
      yield assertEquals(response, ApiResponse(Status.Ok, expectedInventory))
    }

  test("should encode completed photos and every failure without exposing source paths or write identities"):
    val location = ThumbnailLocation(new UUID(0L, 80L))
    val intent   = PhotoWriteIntentId(new UUID(0L, 81L))
    val issues   = Vector(
      PhotoManager.Issue.Discovery,
      PhotoManager.Issue.Read(filename = "one.jpg"),
      PhotoManager.Issue.Generation(filename = "one.jpg"),
      PhotoManager.Issue.Undecodable(filename = "one.jpg"),
      PhotoManager.Issue.Unstable(filename = "one.jpg"),
      PhotoManager.Issue.Storage,
      PhotoManager.Issue.Cleanup(location),
      PhotoManager.Issue.Persistence,
      PhotoManager.Issue.Unexpected,
      PhotoManager.Issue.WriteReferences(intent),
      PhotoManager.Issue.WriteCompletion(intent)
    )
    val cleanup = Vector(
      PhotoWriteRecovery.Issue.Listing,
      PhotoWriteRecovery.Issue.References(intent),
      PhotoWriteRecovery.Issue.Cleanup(intent, location),
      PhotoWriteRecovery.Issue.Completion(intent)
    )
    val expectedIssues = Vector(
      ("discovery", "unreadable_source", Json.Null),
      ("read", "unreadable_source", Json.fromString("one.jpg")),
      ("generation", "encoding_failed", Json.fromString("one.jpg")),
      ("read", "undecodable", Json.fromString("one.jpg")),
      ("read", "unstable", Json.fromString("one.jpg")),
      ("storage", "storage_failed", Json.Null),
      ("cleanup", "cleanup_failed", Json.Null)
    ) ++ Vector.fill(6)(("persistence", "persistence_failed", Json.Null)) ++
      Vector(("cleanup", "cleanup_failed", Json.Null), ("persistence", "persistence_failed", Json.Null))
    val expectedFeedback = expectedIssues.map { case (stage, code, filename) =>
      Json.obj("stage" -> Json.fromString(stage), "code" -> Json.fromString(code), "filename" -> filename)
    }
    val expectedProcessing = Json.obj(
      "state"            -> Json.fromString("failed"),
      "unsupportedCount" -> Json.fromInt(3),
      "skippedCount"     -> Json.fromInt(2),
      "issues"           -> Json.arr(expectedFeedback*)
    )

    Vector(false, true).traverse_ { annotated =>
      val maybeDate    = Option.when(annotated)(LocalDateTime.of(2026, 1, 2, 3, 4))
      val maybeCaption = Option.when(annotated)("Ș 日本語\nSecond line")
      val metadata     = PhotoMetadata(hash = "source-hash", maybeCapturedLocal = maybeDate, PhotoDimensions(width = 20, height = 30))
      val thumbnail    = Thumbnail(location, version = "version", ThumbnailDimensions(width = 10, height = 15), ThumbnailQuality.Standard, bytes = 1)
      val photo        = Photo(
        PhotoId(new UUID(0L, 82L)),
        chapter,
        OriginalPhotoLocation(source = "/private/originals", filename = "one.jpg"),
        metadata,
        thumbnail,
        maybeCaption = maybeCaption
      )
      val secondPhoto   = photo.copy(id = PhotoId(new UUID(0L, 84L)))
      val unplacedPhoto = photo.copy(id = PhotoId(new UUID(0L, 83L)))
      val photos        = Vector(photo, secondPhoto, unplacedPhoto)
      val placements    = Vector(
        (Set.empty[PhotoId], Json.arr(), 3),
        (Set(secondPhoto.id, photo.id), Json.arr(Json.fromString(photo.id.value.toString), Json.fromString(secondPhoto.id.value.toString)), 1)
      )
      val execution =
        ExecutionOutcome(ImportStatus.Finished(PhotoManager.RefreshResult(unsupportedCount = 3, issues = issues)), retirementIssues = cleanup)
      val expectedPhoto = json(s"""{"id":"${photo.id.value}","filename":"one.jpg","capturedLocal":${maybeDate.fold("null")(date => s"\"$date\"")},
        "width":20,"height":30,"caption":${maybeCaption.fold("null")(caption => Json.fromString(value = caption).noSpaces)},
        "thumbnail":{"url":"/thumbnails/${location.value}/version.jpg","width":10,"height":15}}""")
      val expectedSecond   = expectedPhoto.deepMerge(Json.obj("id" -> Json.fromString(secondPhoto.id.value.toString)))
      val expectedUnplaced = expectedPhoto.deepMerge(Json.obj("id" -> Json.fromString(unplacedPhoto.id.value.toString)))

      placements.traverse_ { case (placed, encodedPlaced, unplaced) =>
        val inventory         = PhotoInventory.Inventory(photos = photos, placed = placed)
        val snapshot          = ChapterPhotoInventory.Snapshot(inventory, execution)
        val server            = buildApi(Refs(), unexpectedCaption, ChapterPhotoInventory.Result.Found(snapshot).pure[IO])
        val expectedInventory = Json.obj(
          "photos"         -> Json.arr(expectedPhoto, expectedSecond, expectedUnplaced),
          "placedPhotoIds" -> encodedPlaced,
          "counts"         -> Json.obj("unplaced" -> Json.fromInt(unplaced), "total" -> Json.fromInt(photos.size)),
          "processing"     -> expectedProcessing
        )

        for
          read <- sendJson(server, Method.GET, s"/api/chapters/${chapter.value}/photos")
        yield
          assertEquals(read, ApiResponse(Status.Ok, expectedInventory))
          assert(!read.body.noSpaces.contains("/private") && !read.body.noSpaces.contains(intent.value.toString))
      }
    }

  final private case class CaptionCall(chapter: ChapterId, photo: PhotoId, text: String)
  final private case class InventoryScenario(result: ChapterPhotoInventory.Result, status: Status)
  final private case class ApiResponse(status: Status, body: Json)
  final private case class CaptionScenario(result: SaveCaptionResult, status: Status, code: String)
  final private case class WireResponse(status: Status, body: String, maybeMediaType: Option[MediaType], maybeCacheControl: Option[String])
  final private case class Refs():
    val calls: Ref[IO, Vector[CaptionCall]] = Ref.unsafe(a = Vector.empty[CaptionCall])

  private def buildApi(refs: Refs, result: IO[SaveCaptionResult], inventoryResult: IO[ChapterPhotoInventory.Result]) =
    val photos = new PhotoManager[IO]:
      override def saveCaption(chapter: ChapterId, photo: PhotoId, text: String): IO[SaveCaptionResult] =
        refs.calls.update(_ :+ CaptionCall(chapter, photo, text = text)) >> result
      override def refresh(chapter: ChapterId, source: PhotoSource): IO[PhotoManager.RefreshResult] =
        IO.raiseError[PhotoManager.RefreshResult](new AssertionError("Unexpected photo import"))
    val inventories = new ChapterPhotoInventory[IO]:
      override def read(chapter: ChapterId): IO[ChapterPhotoInventory.Result] = inventoryResult
    PhotosApi.mount(PhotosApi.make(inventories, photos)).orNotFound

  private def sendJson(server: HttpApp[IO], method: Method, path: String) =
    sendRaw(server, method, path = path, body = "").map { response =>
      assertEquals(response.maybeMediaType, MediaType.application.json.some)
      if method === Method.GET && response.status === Status.Ok then assertEquals(response.maybeCacheControl, "no-store".some)
      ApiResponse(response.status, json(response.body))
    }

  private def send(server: HttpApp[IO], path: String, body: String) =
    sendRaw(server, Method.PUT, path = path, body = body)

  private def sendRaw(server: HttpApp[IO], method: Method, path: String, body: String) =
    val request = Request[IO](method, Uri.unsafeFromString(s = path)).withEntity(b = body)
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

  private def json(value: String) = parse(value).fold(error => fail(error.message), identity)
