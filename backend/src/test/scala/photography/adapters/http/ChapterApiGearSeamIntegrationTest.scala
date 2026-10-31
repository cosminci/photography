package photography.adapters.http

import cats.data.NonEmptyList
import photography.domain.album.{CountryId, DestinationName, PhotoSource}
import photography.ports.album.AlbumRepository
import photography.usecases.photo.ChapterPhotoInventory
import photography.usecases.chapter.ChapterMetadataManager

import cats.data.NonEmptyVector
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{HttpApp, MediaType, Method, Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.gear.*
import photography.usecases.chapter.ChapterManager
import photography.usecases.chapter.ChapterManager.*
import photography.usecases.gear.GearManager
import java.util.UUID

final class ChapterApiGearSeamIntegrationTest extends CatsEffectSuite:
  private val album     = AlbumId(value = new UUID(0L, 1L))
  private val chapter   = ChapterId(value = new UUID(0L, 2L))
  private val fixed     = CameraId(value = new UUID(0L, 3L))
  private val body      = CameraId(value = new UUID(0L, 4L))
  private val second    = CameraId(value = new UUID(0L, 5L))
  private val lens      = LensId(value = new UUID(0L, 6L))
  private val other     = LensId(value = new UUID(0L, 7L))
  private val path      = s"/api/albums/${album.value}/chapters/${chapter.value}/gear"
  private val selection = Vector(
    CameraSelection(fixed, CameraUse.FixedLens),
    CameraSelection(body, CameraUse.Interchangeable(lenses = NonEmptyVector.of(other, lens))),
    CameraSelection(second, CameraUse.Interchangeable(lenses = NonEmptyVector.one(lens)))
  )
  private val selectedJson =
    s"""{"cameras":[{"cameraId":"${fixed.value}","lensIds":[]},""" +
      s"""{"cameraId":"${body.value}","lensIds":["${other.value}","${lens.value}"]},""" +
      s"""{"cameraId":"${second.value}","lensIds":["${lens.value}"]}]}"""

  test("should read and replace ordered shared selections with typed fixed and interchangeable cardinality and explicit empty success"):
    val savedSelections = Vector(
      SavedSelection(selection = selection, body = selectedJson),
      SavedSelection(selection = Vector.empty[CameraSelection], body = """{"cameras":[]}""")
    )

    savedSelections.traverse_ { case SavedSelection(values, json) =>
      val refs   = Refs()
      val server = buildChapterGearApi(refs, readResult = GearReadResult.Found(selection = values))

      for
        read  <- send(server, Method.GET, path = path, body = "")
        saved <- send(server, Method.PUT, path = path, body = json)
        calls <- refs.calls.get
      yield
        assertEquals(read, (Status.Ok, json, MediaType.application.json.some, "no-store".some))
        assertEquals(saved, (Status.NoContent, "", None, None))
        assertEquals(calls, expected = Vector(Call.Read(album, chapter), Call.Replace(album, chapter, selection = values)))
    }

  Vector(
    ReadScenario(result = GearReadResult.NotFound, status = Status.NotFound, code = "chapter_not_found"),
    ReadScenario(result = GearReadResult.Failed, status = Status.InternalServerError, code = "service_failure")
  ).foreach { case ReadScenario(result, status, code) =>
    test(s"should translate chapter gear read $result"):
      val refs   = Refs()
      val server = buildChapterGearApi(refs, readResult = result)

      for
        response <- send(server, Method.GET, path = path, body = "")
        calls    <- refs.calls.get
      yield
        assertEquals(response, encodeErrorResponse(status, code = code))
        assertEquals(calls, expected = Vector(Call.Read(album, chapter)))
  }
  Vector(
    ReplacementScenario(result = GearReplaceResult.Saved, status = Status.NoContent, code = ""),
    ReplacementScenario(result = GearReplaceResult.NotFound, status = Status.NotFound, code = "chapter_not_found"),
    ReplacementScenario(result = GearReplaceResult.Failed, status = Status.InternalServerError, code = "service_failure"),
    ReplacementScenario(result = GearReplaceResult.Uncertain, status = Status.InternalServerError, code = "mutation_uncertain")
  ).foreach { case ReplacementScenario(result, status, code) =>
    test(s"should translate chapter gear replacement $result"):
      val refs     = Refs()
      val server   = buildChapterGearApi(refs, replaceResult = result)
      val expected = if status === Status.NoContent then (status, "", None, None) else encodeErrorResponse(status, code = code)

      for
        response <- send(server, Method.PUT, path = path, body = selectedJson)
        calls    <- refs.calls.get
      yield
        assertEquals(response, expected)
        assertEquals(calls, expected = Vector(Call.Replace(album, chapter, selection = selection)))
  }
  GearManager.Failure.values.foreach { reason =>
    test(s"should translate chapter gear rejection $reason"):
      val refs         = Refs()
      val server       = buildChapterGearApi(refs, replaceResult = GearReplaceResult.Rejected(reason))
      val expectedBody = Json.obj("message" -> Json.fromString(value = explain(reason))).noSpaces
      val expected     = (Status.BadRequest, expectedBody, MediaType.application.json.some, None)

      for
        response <- send(server, Method.PUT, path = path, body = selectedJson)
        calls    <- refs.calls.get
      yield
        assertEquals(response, expected)
        assertEquals(calls, expected = Vector(Call.Replace(album, chapter, selection = selection)))
  }

  test("should reject malformed selection fields or identities before application calls while leaving duplicates to the application"):
    val malformedIds  = Vector("bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A")
    val invalidLenses = malformedIds.map: id =>
      InvalidSelection(
        body = s"""{"cameras":[{"cameraId":"${body.value}","lensIds":["$id"]}]}""",
        message = "DecodingFailure at .cameras[0].lensIds[0]: Gear IDs must be canonical UUIDs"
      )
    val malformed = invalidLenses ++ Vector(
      InvalidSelection(body = "{", message = "Supply a valid JSON gear selection"),
      InvalidSelection(body = "[]", message = "DecodingFailure at .cameras: Missing required field"),
      InvalidSelection(body = "null", message = "DecodingFailure at .cameras: Missing required field"),
      InvalidSelection(body = "{}", message = "DecodingFailure at .cameras: Missing required field"),
      InvalidSelection(body = """{"cameras":null}""", message = "DecodingFailure at .cameras: Got value 'null' with wrong type, expecting array"),
      InvalidSelection(body = """{"cameras":{}}""", message = "DecodingFailure at .cameras: Got value '{}' with wrong type, expecting array"),
      InvalidSelection(body = """{"cameras":[null]}""", message = "DecodingFailure at .cameras[0].cameraId: Missing required field"),
      InvalidSelection(
        body = s"""{"cameras":[{"cameraId":"${fixed.value}"}]}""",
        message = "DecodingFailure at .cameras[0].lensIds: Missing required field"
      ),
      InvalidSelection(
        body = s"""{"cameras":[{"cameraId":"${fixed.value}","lensIds":null}]}""",
        message = "DecodingFailure at .cameras[0].lensIds: Got value 'null' with wrong type, expecting array"
      ),
      InvalidSelection(
        body = s"""{"cameras":[{"cameraId":"${fixed.value}","lensIds":"bad"}]}""",
        message = """DecodingFailure at .cameras[0].lensIds: Got value '"bad"' with wrong type, expecting array"""
      ),
      InvalidSelection(
        body = """{"cameras":[{"cameraId":1,"lensIds":[]}]}""",
        message = "DecodingFailure at .cameras[0].cameraId: Got value '1' with wrong type, expecting string"
      ),
      InvalidSelection(
        body = """{"cameras":[{"cameraId":"1-1-1-1-1","lensIds":[]}]}""",
        message = "DecodingFailure at .cameras[0].cameraId: Gear IDs must be canonical UUIDs"
      ),
      InvalidSelection(
        body = """{"cameras":[{"cameraId":"00000000-0000-0000-0000-00000000000A","lensIds":[]}]}""",
        message = "DecodingFailure at .cameras[0].cameraId: Gear IDs must be canonical UUIDs"
      ),
      InvalidSelection(
        body = s"""{"cameras":[{"cameraId":"${body.value}","lensIds":[null]}]}""",
        message = "DecodingFailure at .cameras[0].lensIds[0]: Got value 'null' with wrong type, expecting string"
      ),
      InvalidSelection(
        body = s"""{"cameras":[{"cameraId":"${body.value}","lensIds":["bad"]}]}""",
        message = "DecodingFailure at .cameras[0].lensIds[0]: Gear IDs must be canonical UUIDs"
      )
    )
    val refs          = Refs()
    val server        = buildChapterGearApi(refs)
    val duplicate     = Vector.fill(n = 2)(CameraSelection(body, CameraUse.Interchangeable(lenses = NonEmptyVector.of(lens, lens))))
    val duplicateJson =
      s"""{"cameras":[{"cameraId":"${body.value}","lensIds":["${lens.value}","${lens.value}"]},""" +
        s"""{"cameraId":"${body.value}","lensIds":["${lens.value}","${lens.value}"]}]}"""

    val checkMalformed = malformed.traverse_ { case InvalidSelection(body, message) =>
      val expected = (Status.BadRequest, Json.obj("message" -> Json.fromString(value = message)).noSpaces, MediaType.application.json.some, None)

      send(server, Method.PUT, path = path, body = body).map(response => assertEquals(response, expected, clue = body))
    }

    for
      _         <- checkMalformed
      before    <- refs.calls.get
      forwarded <- send(server, Method.PUT, path = path, body = duplicateJson)
      after     <- refs.calls.get
    yield
      assertEquals(before, expected = Vector.empty[Call])
      assertEquals(forwarded, (Status.NoContent, "", None, None))
      assertEquals(after, expected = Vector(Call.Replace(album, chapter, selection = duplicate)))

  test("should forward independent album and chapter scopes and reject malformed route identities"):
    val refs         = Refs()
    val server       = buildChapterGearApi(refs, readResult = GearReadResult.NotFound, replaceResult = GearReplaceResult.NotFound)
    val otherAlbum   = AlbumId(value = new UUID(0L, 9L))
    val otherPath    = s"/api/albums/${otherAlbum.value}/chapters/${chapter.value}/gear"
    val malformedIds = Vector("bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A")

    val checkOtherScope = Vector(Method.GET, Method.PUT).traverse_ { method =>
      send(server, method, path = otherPath, body = selectedJson).map(response =>
        assertEquals(response, encodeErrorResponse(Status.NotFound, code = "chapter_not_found"))
      )
    }
    val checkMalformedScopes = malformedIds.traverse_ { id =>
      Vector(Method.GET, Method.PUT).traverse_ { method =>
        val chapterPath = s"/api/albums/${album.value}/chapters/$id/gear"
        val albumPath   = s"/api/albums/$id/chapters/${chapter.value}/gear"

        for
          badChapter <- send(server, method, path = chapterPath, body = "{}")
          badAlbum   <- send(server, method, path = albumPath, body = "{}")
        yield
          assertEquals(badChapter, encodeErrorResponse(Status.NotFound, code = "chapter_not_found"))
          assertEquals(badAlbum, encodeErrorResponse(Status.NotFound, code = "album_not_found"))
      }
    }

    for
      _     <- checkOtherScope
      _     <- checkMalformedScopes
      calls <- refs.calls.get
    yield assertEquals(calls, expected = Vector(Call.Read(otherAlbum, chapter), Call.Replace(otherAlbum, chapter, selection = selection)))

  private enum Call:
    case Read(album: AlbumId, chapter: ChapterId)
    case Replace(album: AlbumId, chapter: ChapterId, selection: GearSelection)

  final private case class InvalidSelection(body: String, message: String)
  final private case class SavedSelection(selection: GearSelection, body: String)
  final private case class ReadScenario(result: GearReadResult, status: Status, code: String)
  final private case class ReplacementScenario(result: GearReplaceResult, status: Status, code: String)

  final private case class Refs():
    val calls: Ref[IO, Vector[Call]] = Ref.unsafe(a = Vector.empty[Call])

  // scalafix:off DisableSyntax.defaultArgs
  private def buildChapterGearApi(
      refs: Refs,
      readResult: GearReadResult = GearReadResult.Found(selection = Vector.empty[CameraSelection]),
      replaceResult: GearReplaceResult = GearReplaceResult.Saved
  ) =
    val chapters = new ChapterManager[IO]:
      override def readGear(album: AlbumId, chapter: ChapterId): IO[GearReadResult] = refs.calls.update(_ :+ Call.Read(album, chapter)).as(readResult)
      override def replaceGear(album: AlbumId, chapter: ChapterId, selection: GearSelection): IO[GearReplaceResult] =
        refs.calls.update(_ :+ Call.Replace(album, chapter, selection = selection)).as(replaceResult)
    ChapterApi.make(buildUnusedInventory, buildUnusedMetadata, buildUnusedChapterRead, chapters).routes.orNotFound
  // scalafix:on DisableSyntax.defaultArgs

  private def send(server: HttpApp[IO], method: Method, path: String, body: String) =
    val request = Request[IO](method, Uri.unsafeFromString(s = path)).withEntity(b = body).putHeaders(`Content-Type`(MediaType.application.json))

    for
      response <- server.run(request)
      text     <- response.as[String]
    yield (
      response.status,
      text,
      response.contentType.map(_.mediaType),
      response.headers.get(CIString(value = "Cache-Control")).map(_.head.value)
    )

  private def encodeErrorResponse(status: Status, code: String) =
    (status, Json.obj("code" -> Json.fromString(value = code)).noSpaces, MediaType.application.json.some, None)

  private def explain(reason: GearManager.Failure) = reason match
    case GearManager.Failure.DuplicateCamera    => "Select each camera only once"
    case GearManager.Failure.UnknownCamera      => "Select a camera from the current catalog"
    case GearManager.Failure.CameraKindMismatch => "Select lenses only for interchangeable cameras"
    case GearManager.Failure.DuplicateLens      => "Select each lens only once per camera"
    case GearManager.Failure.UnknownLens        => "Select a lens from the current catalog"
    case GearManager.Failure.IncompatibleLens   => "Select lenses with a compatible mount and coverage"

  private def buildUnusedChapterRead =
    new AlbumRepository.ChapterRead[IO]:
      override def chapters(id: AlbumId): IO[AlbumRepository.ChaptersResult] =
        IO.raiseError[AlbumRepository.ChaptersResult](new AssertionError("Unexpected chapter metadata read"))

  private def buildUnusedInventory =
    new ChapterPhotoInventory[IO]:
      override def read(id: ChapterId): IO[ChapterPhotoInventory.Result] =
        IO.raiseError[ChapterPhotoInventory.Result](new AssertionError("Unexpected photo inventory read"))

  private def buildUnusedMetadata =
    new ChapterMetadataManager[IO]:
      override def saveDescription(album: AlbumId, chapter: ChapterId, text: String): IO[ChapterMetadataManager.SaveDescriptionResult] =
        IO.raiseError[ChapterMetadataManager.SaveDescriptionResult](new AssertionError("Unexpected description mutation"))
      override def refresh(chapter: ChapterId): IO[ChapterMetadataManager.RefreshResult] =
        IO.raiseError[ChapterMetadataManager.RefreshResult](new AssertionError("Unexpected photo refresh"))
      override def create(
          album: AlbumId,
          name: DestinationName,
          countries: NonEmptyList[CountryId],
          period: ChapterMetadataManager.Period,
          folder: PhotoSource
      ): IO[ChapterMetadataManager.CreateResult] =
        IO.raiseError[ChapterMetadataManager.CreateResult](new AssertionError("Unexpected chapter creation"))
      override def update(
          album: AlbumId,
          chapter: ChapterId,
          name: DestinationName,
          countries: NonEmptyList[CountryId],
          period: ChapterMetadataManager.Period,
          folder: PhotoSource
      ): IO[ChapterMetadataManager.UpdateResult] =
        IO.raiseError[ChapterMetadataManager.UpdateResult](new AssertionError("Unexpected chapter update"))
