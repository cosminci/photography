package photography.adapters.http

import cats.data.{NonEmptyList, NonEmptyVector}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.parse
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.http4s.{HttpApp, MediaType, Method, Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import photography.domain.album.*
import photography.domain.layout.*
import photography.usecases.chapter.ChapterMetadataManager
import photography.domain.gear.*
import photography.usecases.chapter.ChapterManager
import photography.usecases.chapter.ChapterManager.{GearReadResult, GearReplaceResult}
import photography.usecases.gear.GearManager
import org.typelevel.ci.CIString
import photography.usecases.chapter.ChapterMetadataManager.{CreateResult, Period, UpdateResult}
import photography.usecases.photo.ChapterPhotoImport.Scheduling
import photography.ports.album.AlbumRepository

import java.nio.file.Path
import java.time.{Instant, LocalDate}
import java.util.UUID

final class ChapterApiSeamIntegrationTest extends CatsEffectSuite:
  private val country  = Country(CountryId(new UUID(0L, 8L)), code = "RO", name = "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
  private val box      = SlotBox(x = 10.0, y = 20.0, width = 30.0, height = 40.0)
  private val text     = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 6L)), box)
  private val photo    = SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(0L, 7L)), box, bleed = Set.empty)
  private val template = SpreadTemplateId(new UUID(0L, 4L))
  private val chapter  =
    val destination = Destination(DestinationId(new UUID(0L, 10L)), name = "Coast", countries = NonEmptyList.one(country))
    val opening     =
      Spread.ChapterStart(SpreadId(new UUID(0L, 3L)), template, photoSlots = Vector(photo), text, placements = Vector.empty[SpreadPlacement])
    val photos =
      Spread.PhotosOnly(SpreadId(new UUID(0L, 5L)), template, photoSlots = NonEmptyList.one(photo), placements = Vector.empty[SpreadPlacement])
    Chapter(
      ChapterId(new UUID(0L, 2L)),
      destination,
      VisitPeriod(LocalDate.of(2024, 2, 29), days = 2),
      description = "Sea air",
      gear = Vector.empty[CameraSelection],
      sourceFolder = "/photos/coast",
      opening,
      spreads = Vector(photos)
    )
  private val album =
    val emptyPhotos     = Vector.empty[SpreadSlot.Photo]
    val emptyPlacements = Vector.empty[SpreadPlacement]
    val opening         = Spread.AlbumStart(SpreadId(new UUID(0L, 11L)), template, photoSlots = emptyPhotos, text, placements = emptyPlacements)
    Album(
      AlbumId(new UUID(0L, 1L)),
      year = 2024,
      title = "Our year",
      synopsis = "",
      authors = Author.all,
      Instant.EPOCH,
      opening,
      chapters = Vector(chapter)
    )

  private val albumPath   = s"/api/albums/${album.id.value}"
  private val chapterPath = s"$albumPath/chapters/${chapter.id.value}"
  private val vacation    =
    s"""{"kind":"vacation","name":"  Coast  ","countries":["${country.id.value}"],"start":"2024-02-29","end":"2024-03-01","sourceFolder":"/photos/coast"}"""
  private val home = s"""{"kind":"home","countries":["${country.id.value}"],"sourceFolder":"/photos/Home"}"""

  private val fixed     = CameraId(value = new UUID(0L, 3L))
  private val body      = CameraId(value = new UUID(0L, 4L))
  private val second    = CameraId(value = new UUID(0L, 5L))
  private val lens      = LensId(value = new UUID(0L, 6L))
  private val other     = LensId(value = new UUID(0L, 7L))
  private val gearPath  = s"/api/albums/${album.id.value}/chapters/${chapter.id.value}/gear"
  private val selection = Vector(
    CameraSelection(fixed, CameraUse.FixedLens),
    CameraSelection(body, CameraUse.Interchangeable(lenses = NonEmptyVector.of(other, lens))),
    CameraSelection(second, CameraUse.Interchangeable(lenses = NonEmptyVector.one(lens)))
  )
  private val selectedJson =
    s"""{"cameras":[{"cameraId":"${fixed.value}","lensIds":[]},""" +
      s"""{"cameraId":"${body.value}","lensIds":["${other.value}","${lens.value}"]},""" +
      s"""{"cameraId":"${second.value}","lensIds":["${lens.value}"]}]}"""

  Vector("Sea — 山\nAnother line", "", " \t\n ").foreach { text =>
    val result = ChapterMetadataManager.SaveDescriptionResult.Saved
    test(s"should deliver description $result with exact text ${Json.fromString(text).noSpaces}"):
      val refs        = Refs()
      val server      = buildApi(refs, descriptionResult = result.pure[IO])
      val requestBody = Json.obj("text" -> Json.fromString(text)).noSpaces

      for
        response     <- sendRaw(server, Method.PUT, path = s"$chapterPath/description", body = requestBody)
        descriptions <- refs.descriptions.get
        otherCalls   <- refs.calls.get
        gearCalls    <- refs.gearCalls.get
      yield
        assertEquals(response, (Status.NoContent, "", None, None))
        assertEquals(descriptions, Vector(DescriptionCall(album.id, chapter.id, text = text)))
        assertEquals(otherCalls, Vector.empty[String])
        assertEquals(gearCalls, Vector.empty[GearCall])
  }

  Vector(
    DescriptionScenario(ChapterMetadataManager.SaveDescriptionResult.AlbumNotFound, Status.NotFound, code = "album_not_found"),
    DescriptionScenario(ChapterMetadataManager.SaveDescriptionResult.ChapterNotFound, Status.NotFound, code = "chapter_not_found"),
    DescriptionScenario(ChapterMetadataManager.SaveDescriptionResult.Failed, Status.InternalServerError, code = "service_failure"),
    DescriptionScenario(ChapterMetadataManager.SaveDescriptionResult.Uncertain, Status.InternalServerError, code = "mutation_uncertain")
  ).foreach { scenario =>
    test(s"should deliver description ${scenario.result} with exact independently scoped inputs"):
      val refs       = Refs()
      val otherAlbum = AlbumId(value = new UUID(0L, 99L))
      val server     = buildApi(refs, descriptionResult = scenario.result.pure[IO])
      val path       = s"/api/albums/${otherAlbum.value}/chapters/${chapter.id.value}/description"

      for
        response     <- sendRaw(server, Method.PUT, path = path, body = """{"text":"once"}""")
        descriptions <- refs.descriptions.get
        otherCalls   <- refs.calls.get
        gearCalls    <- refs.gearCalls.get
      yield
        assertEquals(response, encodeErrorResponse(scenario.status, code = scenario.code))
        assertEquals(descriptions, Vector(DescriptionCall(otherAlbum, chapter.id, text = "once")))
        assertEquals(otherCalls, Vector.empty[String])
        assertEquals(gearCalls, Vector.empty[GearCall])
  }

  test("should reject malformed description payloads and identities without metadata import or gear work"):
    val refs          = Refs()
    val server        = buildApi(refs)
    val malformed     = Vector("{", "[]", "null", "{}", """{"text":null}""", """{"text":1}""", """{"text":[]}""")
    val checkPayloads = malformed.traverse_ { body =>
      val expected = (Status.BadRequest, """{"message":"Supply a text string"}""", MediaType.application.json.some, None)
      sendRaw(server, Method.PUT, path = s"$chapterPath/description", body = body).map(response => assertEquals(response, expected))
    }
    val checkTargets = Vector("bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A").traverse_ { id =>
      val albumTarget   = s"/api/albums/$id/chapters/${chapter.id.value}/description"
      val chapterTarget = s"$albumPath/chapters/$id/description"
      for
        badAlbum   <- sendRaw(server, Method.PUT, path = albumTarget, body = "{}")
        badChapter <- sendRaw(server, Method.PUT, path = chapterTarget, body = "{}")
      yield
        assertEquals(badAlbum, encodeErrorResponse(Status.NotFound, code = "album_not_found"))
        assertEquals(badChapter, encodeErrorResponse(Status.NotFound, code = "chapter_not_found"))
    }

    for
      _            <- checkPayloads
      _            <- checkTargets
      descriptions <- refs.descriptions.get
      otherCalls   <- refs.calls.get
      gearCalls    <- refs.gearCalls.get
    yield
      assertEquals(descriptions, Vector.empty[DescriptionCall])
      assertEquals(otherCalls, Vector.empty[String])
      assertEquals(gearCalls, Vector.empty[GearCall])

  test("should read ordered chapter metadata with its exact inclusive period and source"):
    val refs               = Refs()
    val description        = "Ș 日本語\nSecond line"
    val savedMetadata      = AlbumRepository.ChapterMetadata(chapter.id, chapter.destination, chapter.period, chapter.sourceFolder, description)
    val server             = buildApi(refs, metadataResult = AlbumRepository.ChaptersResult.Found(Vector(savedMetadata)))
    val encodedDescription = Json.fromString(value = description).noSpaces
    val expected           = json(
      s"""{"chapters":[{"id":"${chapter.id.value}","kind":"vacation","name":"Coast","countries":["${country.id.value}"],
         "start":"2024-02-29","end":"2024-03-01","sourceFolder":"/photos/coast","description":$encodedDescription}]}"""
    )

    for
      response <- send(server, Method.GET, s"$albumPath/chapters")
      calls    <- refs.calls.get
    yield
      assertEquals(response, ApiResponse(Status.Ok, expected))
      assertEquals(calls, Vector(s"chapters:${album.id.value}"))

  test("should distinguish missing chapter collections from failed acquisition"):
    val missing = buildApi(Refs(), metadataResult = AlbumRepository.ChaptersResult.AlbumNotFound)
    val failed  = buildApi(Refs(), metadataResult = AlbumRepository.ChaptersResult.Failed(AlbumRepository.Failure.DatabaseFailure))

    for
      absent  <- send(missing, Method.GET, s"$albumPath/chapters")
      failure <- send(failed, Method.GET, s"$albumPath/chapters")
      invalid <- send(failed, Method.GET, "/api/albums/bad/chapters")
    yield
      assertEquals(absent, ApiResponse(Status.NotFound, json("""{"code":"album_not_found"}""")))
      assertEquals(invalid, absent)
      assertEquals(failure, ApiResponse(Status.InternalServerError, json("""{"code":"service_failure"}""")))

  test("should decode vacation and Home creation and type-specific updates into minimal manager inputs"):
    val refs             = Refs()
    val server           = buildApi(refs)
    val homeServer       = buildApi(refs)
    val createdId        = json(s"""{"id":"${chapter.id.value}","scheduling":"queued"}""")
    val updatedId        = json(s"""{"id":"${chapter.id.value}","scheduling":"not-requested"}""")
    val expectedVacation = s"Coast|Dates(${chapter.period})|/photos/coast|${country.id.value}"
    val expectedHome     = s"Home|AlbumYear|/photos/Home|${country.id.value}"
    val createPrefix     = s"create:${album.id.value}:"
    val updatePrefix     = s"update:${album.id.value}:${chapter.id.value}:"
    val expectedCalls    = Vector(
      s"$createPrefix$expectedVacation",
      s"$createPrefix$expectedHome",
      s"$updatePrefix$expectedVacation",
      s"$updatePrefix$expectedHome"
    )

    for
      createdVacation <- send(server, Method.POST, s"$albumPath/chapters", vacation)
      createdHome     <- send(server, Method.POST, s"$albumPath/chapters", home)
      updatedVacation <- send(server, Method.PUT, chapterPath, vacation)
      updatedHome     <- send(homeServer, Method.PUT, chapterPath, home)
      calls           <- refs.calls.get
    yield
      assertEquals(createdVacation, ApiResponse(Status.Created, createdId))
      assertEquals(createdHome, ApiResponse(Status.Created, createdId))
      assertEquals(updatedVacation, ApiResponse(Status.Ok, updatedId))
      assertEquals(updatedHome, ApiResponse(Status.Ok, updatedId))
      assertEquals(calls, expectedCalls)

  test("should preserve inclusive same-day and cross-year vacation periods"):
    val periods = Vector(("2024-02-29", "2024-02-29", 1L), ("2023-12-31", "2024-01-02", 3L))

    periods.traverse_ { case (start, end, days) =>
      val refs     = Refs()
      val body     = vacation.replace("2024-02-29", start).replace("2024-03-01", end)
      val period   = VisitPeriod(LocalDate.parse(start), days = days.refineUnsafe)
      val expected = s"create:${album.id.value}:Coast|Dates($period)|/photos/coast|${country.id.value}"

      for
        response <- send(buildApi(refs), Method.POST, s"$albumPath/chapters", body)
        calls    <- refs.calls.get
      yield
        assertEquals(response.status, Status.Created)
        assertEquals(calls, Vector(expected))
    }

  test("should reject malformed chapter fields and dates with readable JSON before mutation"):
    val invalidBodies = Vector(
      "{",
      "[]",
      "{}",
      vacation.replace("\"vacation\"", "\"trip\""),
      vacation.replace("  Coast  ", "  "),
      vacation.replace("  Coast  ", "Home"),
      vacation.replace("2024-02-29", "2023-02-29"),
      vacation.replace("2024-03-01", "2024-02-28"),
      vacation.replace("2024-02-29", "2024-2-29"),
      vacation.replace("2024-02-29", "2024-02-29T00:00:00Z"),
      vacation.replace(s""""${country.id.value}"""", "\"not-a-uuid\""),
      vacation.replace(s""""${country.id.value}"""", "\"1-1-1-1-1\""),
      vacation.replace(s"""["${country.id.value}"]""", "[]"),
      vacation.replace(s"""["${country.id.value}"]""", s"""["${country.id.value}","${country.id.value}"]"""),
      vacation.replace("/photos/coast", "photos/coast"),
      vacation.replace("/photos/coast", "/photos/../coast"),
      vacation.replace("\"sourceFolder\":\"/photos/coast\"", "\"sourceFolder\":null"),
      vacation.replace("\"name\":\"  Coast  \",", ""),
      home.replace("\"countries\"", "\"missingCountries\"")
    )
    val refs   = Refs()
    val server = buildApi(refs)

    for
      responses     <- invalidBodies.traverse(body => send(server, Method.POST, s"$albumPath/chapters", body))
      invalidUpdate <- send(server, Method.PUT, chapterPath, "{}")
      calls         <- refs.calls.get
    yield
      assert((responses :+ invalidUpdate).forall(response => response.status === Status.BadRequest && hasFeedback(response.body)))
      assertEquals(calls, Vector.empty)

  test("should map missing and malformed ownership targets to the exact resource errors"):
    val refs           = Refs()
    val missingAlbum   = buildApi(refs, updateResult = UpdateResult.AlbumNotFound)
    val foreignChapter = buildApi(refs, updateResult = UpdateResult.ChapterNotFound)
    val normal         = buildApi(refs)
    val albumError     = json("""{"code":"album_not_found"}""")
    val chapterError   = json("""{"code":"chapter_not_found"}""")

    for
      missingUpdate    <- send(missingAlbum, Method.PUT, chapterPath, vacation)
      foreign          <- send(foreignChapter, Method.PUT, chapterPath, vacation)
      malformedCreate  <- send(normal, Method.POST, "/api/albums/bad/chapters", vacation)
      malformedUpdate  <- send(normal, Method.PUT, "/api/albums/bad/chapters/bad", vacation)
      malformedChapter <- send(normal, Method.PUT, s"$albumPath/chapters/bad", vacation)
      calls            <- refs.calls.get
    yield
      val albumResponses = Vector(missingUpdate, malformedCreate, malformedUpdate)
      assertEquals(albumResponses, Vector.fill(3)(ApiResponse(Status.NotFound, albumError)))
      assertEquals(Vector(foreign, malformedChapter), Vector.fill(2)(ApiResponse(Status.NotFound, chapterError)))
      assertEquals(calls.size, 2)

  test("should translate every manager rejection and failure without inventing persistence behavior"):
    val conflicts = ChapterConflict.values.toVector
    val refs      = Refs()

    conflicts.traverse_ { conflict =>
      val server = buildApi(refs, createResult = CreateResult.Rejected(conflict), updateResult = UpdateResult.Rejected(conflict))

      for
        created <- send(server, Method.POST, s"$albumPath/chapters", vacation)
        updated <- send(server, Method.PUT, chapterPath, vacation)
      yield
        assertEquals(Vector(created.status, updated.status), Vector.fill(2)(Status.BadRequest))
        assert(hasFeedback(created.body) && hasFeedback(updated.body))
    } *> Vector(
      (CreateResult.AlbumNotFound, UpdateResult.AlbumNotFound, Status.NotFound, "album_not_found"),
      (CreateResult.Failed, UpdateResult.Failed, Status.InternalServerError, "service_failure")
    ).traverse_ { case (createResult, updateResult, status, code) =>
      val server   = buildApi(refs, createResult = createResult, updateResult = updateResult)
      val expected = ApiResponse(status, Json.obj("code" -> Json.fromString(code)))

      for
        created <- send(server, Method.POST, s"$albumPath/chapters", vacation)
        updated <- send(server, Method.PUT, chapterPath, vacation)
      yield
        assertEquals(created, expected)
        assertEquals(updated, expected)
    }

  test("should preserve chapter disappearance and pre-update service failures"):
    val refs         = Refs()
    val server       = buildApi(refs, updateResult = UpdateResult.Failed)
    val disappeared  = buildApi(refs, updateResult = UpdateResult.ChapterNotFound)
    val serviceError = ApiResponse(Status.InternalServerError, json("""{"code":"service_failure"}"""))

    for
      update  <- send(server, Method.PUT, chapterPath, vacation)
      missing <- send(disappeared, Method.PUT, chapterPath, vacation)
    yield
      assertEquals(update, serviceError)
      assertEquals(missing, ApiResponse(Status.NotFound, json("""{"code":"chapter_not_found"}""")))

  test("should adapt Refresh with explicit missing and failure outcomes"):
    val scenarios = Vector(
      (ChapterMetadataManager.RefreshResult.Scheduled(Scheduling.Queued), Status.Accepted),
      (ChapterMetadataManager.RefreshResult.Scheduled(Scheduling.Coalesced), Status.Accepted),
      (ChapterMetadataManager.RefreshResult.ChapterNotFound, Status.NotFound),
      (ChapterMetadataManager.RefreshResult.Failed, Status.InternalServerError),
      (ChapterMetadataManager.RefreshResult.Scheduled(Scheduling.Failed), Status.InternalServerError),
      (ChapterMetadataManager.RefreshResult.Scheduled(Scheduling.NotRequested), Status.InternalServerError)
    )

    scenarios.traverse_ { case (refresh, postStatus) =>
      val server = buildApi(Refs(), refreshResult = refresh)

      for
        scheduled <- send(server, Method.PUT, s"/api/chapters/${chapter.id.value}/photo-import")
        malformed <- send(server, Method.PUT, "/api/chapters/bad/photo-import")
      yield
        assertEquals(scheduled.status, postStatus)
        assertEquals(malformed, ApiResponse(Status.NotFound, json("""{"code":"chapter_not_found"}""")))
    }

  test("should preserve saved scheduling outcomes"):
    Scheduling.values.toVector.traverse_ { scheduling =>
      val server   = buildApi(Refs(), createResult = CreateResult.Created(chapter.id, scheduling), updateResult = UpdateResult.Updated(scheduling))
      val expected = scheduling match
        case Scheduling.Queued       => "queued"
        case Scheduling.Coalesced    => "coalesced"
        case Scheduling.NotRequested => "not-requested"
        case Scheduling.Failed       => "failed"

      for
        created <- send(server, Method.POST, s"$albumPath/chapters", vacation)
        updated <- send(server, Method.PUT, chapterPath, vacation)
      yield
        assertEquals((created.status, updated.status), (Status.Created, Status.Ok))
        assertEquals(created.body.hcursor.get[String]("scheduling"), expected.asRight)
        assertEquals(updated.body.hcursor.get[String]("scheduling"), expected.asRight)
    }

  test("should read and replace ordered shared selections with typed fixed and interchangeable cardinality and explicit empty success"):
    val savedSelections = Vector(
      SavedSelection(selection = selection, body = selectedJson),
      SavedSelection(selection = Vector.empty[CameraSelection], body = """{"cameras":[]}""")
    )

    savedSelections.traverse_ { case SavedSelection(values, json) =>
      val refs   = Refs()
      val server =
        buildApi(refs, gearReadResult = GearReadResult.Found(selection = values).pure[IO], gearReplaceResult = GearReplaceResult.Saved.pure[IO])

      for
        read       <- sendRaw(server, Method.GET, path = gearPath, body = "")
        saved      <- sendRaw(server, Method.PUT, path = gearPath, body = json)
        calls      <- refs.gearCalls.get
        otherCalls <- refs.calls.get
      yield
        assertEquals(read, (Status.Ok, json, MediaType.application.json.some, "no-store".some))
        assertEquals(saved, (Status.NoContent, "", None, None))
        assertEquals(otherCalls, expected = Vector.empty[String])
        assertEquals(calls, expected = Vector(GearCall.Read(album.id, chapter.id), GearCall.Replace(album.id, chapter.id, selection = values)))
    }

  Vector(
    ReadScenario(result = GearReadResult.NotFound, status = Status.NotFound, code = "chapter_not_found"),
    ReadScenario(result = GearReadResult.Failed, status = Status.InternalServerError, code = "service_failure")
  ).foreach { case ReadScenario(result, status, code) =>
    test(s"should translate chapter gear read $result"):
      val refs   = Refs()
      val server = buildApi(refs, gearReadResult = result.pure[IO])

      for
        response   <- sendRaw(server, Method.GET, path = gearPath, body = "")
        calls      <- refs.gearCalls.get
        otherCalls <- refs.calls.get
      yield
        assertEquals(response, encodeErrorResponse(status, code = code))
        assertEquals(otherCalls, expected = Vector.empty[String])
        assertEquals(calls, expected = Vector(GearCall.Read(album.id, chapter.id)))
  }
  Vector(
    ReplacementScenario(result = GearReplaceResult.Saved, status = Status.NoContent, code = ""),
    ReplacementScenario(result = GearReplaceResult.NotFound, status = Status.NotFound, code = "chapter_not_found"),
    ReplacementScenario(result = GearReplaceResult.Failed, status = Status.InternalServerError, code = "service_failure"),
    ReplacementScenario(result = GearReplaceResult.Uncertain, status = Status.InternalServerError, code = "mutation_uncertain")
  ).foreach { case ReplacementScenario(result, status, code) =>
    test(s"should translate chapter gear replacement $result"):
      val refs     = Refs()
      val server   = buildApi(refs, gearReplaceResult = result.pure[IO])
      val expected = if status === Status.NoContent then (status, "", None, None) else encodeErrorResponse(status, code = code)

      for
        response   <- sendRaw(server, Method.PUT, path = gearPath, body = selectedJson)
        calls      <- refs.gearCalls.get
        otherCalls <- refs.calls.get
      yield
        assertEquals(response, expected)
        assertEquals(otherCalls, expected = Vector.empty[String])
        assertEquals(calls, expected = Vector(GearCall.Replace(album.id, chapter.id, selection = selection)))
  }
  GearManager.Failure.values.foreach { reason =>
    test(s"should translate chapter gear rejection $reason"):
      val refs         = Refs()
      val server       = buildApi(refs, gearReplaceResult = GearReplaceResult.Rejected(reason).pure[IO])
      val expectedBody = Json.obj("message" -> Json.fromString(value = explain(reason))).noSpaces
      val expected     = (Status.BadRequest, expectedBody, MediaType.application.json.some, None)

      for
        response   <- sendRaw(server, Method.PUT, path = gearPath, body = selectedJson)
        calls      <- refs.gearCalls.get
        otherCalls <- refs.calls.get
      yield
        assertEquals(response, expected)
        assertEquals(otherCalls, expected = Vector.empty[String])
        assertEquals(calls, expected = Vector(GearCall.Replace(album.id, chapter.id, selection = selection)))
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
    val server        = buildApi(refs, gearReplaceResult = GearReplaceResult.Saved.pure[IO])
    val duplicate     = Vector.fill(n = 2)(CameraSelection(body, CameraUse.Interchangeable(lenses = NonEmptyVector.of(lens, lens))))
    val duplicateJson =
      s"""{"cameras":[{"cameraId":"${body.value}","lensIds":["${lens.value}","${lens.value}"]},""" +
        s"""{"cameraId":"${body.value}","lensIds":["${lens.value}","${lens.value}"]}]}"""

    val checkMalformed = malformed.traverse_ { case InvalidSelection(body, message) =>
      val expected = (Status.BadRequest, Json.obj("message" -> Json.fromString(value = message)).noSpaces, MediaType.application.json.some, None)

      sendRaw(server, Method.PUT, path = gearPath, body = body).map(response => assertEquals(response, expected, clue = body))
    }

    for
      _          <- checkMalformed
      before     <- refs.gearCalls.get
      forwarded  <- sendRaw(server, Method.PUT, path = gearPath, body = duplicateJson)
      after      <- refs.gearCalls.get
      otherCalls <- refs.calls.get
    yield
      assertEquals(otherCalls, expected = Vector.empty[String])
      assertEquals(before, expected = Vector.empty[GearCall])
      assertEquals(forwarded, (Status.NoContent, "", None, None))
      assertEquals(after, expected = Vector(GearCall.Replace(album.id, chapter.id, selection = duplicate)))

  test("should forward independent album and chapter scopes and reject malformed route identities"):
    val refs         = Refs()
    val server       = buildApi(refs, gearReadResult = GearReadResult.NotFound.pure[IO], gearReplaceResult = GearReplaceResult.NotFound.pure[IO])
    val otherAlbum   = AlbumId(value = new UUID(0L, 9L))
    val otherPath    = s"/api/albums/${otherAlbum.value}/chapters/${chapter.id.value}/gear"
    val malformedIds = Vector("bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A")

    val checkOtherScope = Vector(Method.GET, Method.PUT).traverse_ { method =>
      sendRaw(server, method, path = otherPath, body = selectedJson).map(response =>
        assertEquals(response, encodeErrorResponse(Status.NotFound, code = "chapter_not_found"))
      )
    }
    val checkMalformedScopes = malformedIds.traverse_ { id =>
      Vector(Method.GET, Method.PUT).traverse_ { method =>
        val chapterPath = s"/api/albums/${album.id.value}/chapters/$id/gear"
        val albumPath   = s"/api/albums/$id/chapters/${chapter.id.value}/gear"

        for
          badChapter <- sendRaw(server, method, path = chapterPath, body = "{}")
          badAlbum   <- sendRaw(server, method, path = albumPath, body = "{}")
        yield
          assertEquals(badChapter, encodeErrorResponse(Status.NotFound, code = "chapter_not_found"))
          assertEquals(badAlbum, encodeErrorResponse(Status.NotFound, code = "album_not_found"))
      }
    }

    for
      _          <- checkOtherScope
      _          <- checkMalformedScopes
      calls      <- refs.gearCalls.get
      otherCalls <- refs.calls.get
    yield
      val expectedCalls = Vector(GearCall.Read(otherAlbum, chapter.id), GearCall.Replace(otherAlbum, chapter.id, selection = selection))
      assertEquals(otherCalls, expected = Vector.empty[String])
      assertEquals(calls, expectedCalls)

  private enum GearCall:
    case Read(album: AlbumId, chapter: ChapterId)
    case Replace(album: AlbumId, chapter: ChapterId, selection: GearSelection)

  final private case class InvalidSelection(body: String, message: String)
  final private case class SavedSelection(selection: GearSelection, body: String)
  final private case class ReadScenario(result: GearReadResult, status: Status, code: String)
  final private case class ReplacementScenario(result: GearReplaceResult, status: Status, code: String)
  final private case class DescriptionCall(album: AlbumId, chapter: ChapterId, text: String)
  final private case class DescriptionScenario(result: ChapterMetadataManager.SaveDescriptionResult, status: Status, code: String)

  final private case class Refs():
    val calls: Ref[IO, Vector[String]]                 = Ref.unsafe(Vector.empty)
    val gearCalls: Ref[IO, Vector[GearCall]]           = Ref.unsafe(a = Vector.empty[GearCall])
    val descriptions: Ref[IO, Vector[DescriptionCall]] = Ref.unsafe(a = Vector.empty[DescriptionCall])

  final private case class ApiResponse(status: Status, body: Json)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildApi(
      refs: Refs,
      createResult: CreateResult = CreateResult.Created(chapter.id, Scheduling.Queued),
      updateResult: UpdateResult = UpdateResult.Updated(Scheduling.NotRequested),
      refreshResult: ChapterMetadataManager.RefreshResult = ChapterMetadataManager.RefreshResult.Failed,
      metadataResult: AlbumRepository.ChaptersResult =
        AlbumRepository.ChaptersResult.Found(Vector(AlbumRepository.ChapterMetadata(
          chapter.id,
          chapter.destination,
          chapter.period,
          chapter.sourceFolder,
          chapter.description
        ))),
      gearReadResult: IO[GearReadResult] = IO.raiseError[GearReadResult](new AssertionError("Unexpected gear read")),
      gearReplaceResult: IO[GearReplaceResult] = IO.raiseError[GearReplaceResult](new AssertionError("Unexpected gear replacement")),
      descriptionResult: IO[ChapterMetadataManager.SaveDescriptionResult] =
        IO.raiseError[ChapterMetadataManager.SaveDescriptionResult](new AssertionError("Unexpected description mutation"))
  ) =
    val chapters = new ChapterMetadataManager[IO]:
      override def saveDescription(album: AlbumId, chapter: ChapterId, text: String): IO[ChapterMetadataManager.SaveDescriptionResult] =
        refs.descriptions.update(_ :+ DescriptionCall(album, chapter, text = text)) >> descriptionResult
      override def refresh(chapter: ChapterId): IO[ChapterMetadataManager.RefreshResult] =
        refs.calls.update(_ :+ s"refresh:${chapter.value}").as(refreshResult)
      override def create(id: AlbumId, name: DestinationName, countries: NonEmptyList[CountryId], period: Period, folder: PhotoSource) =
        refs.calls.update(_ :+ s"create:${id.value}:$name|$period|$folder|${countries.toList.map(_.value).mkString(",")}").as(createResult)
      override def update(
          id: AlbumId,
          target: ChapterId,
          name: DestinationName,
          countries: NonEmptyList[CountryId],
          period: Period,
          folder: PhotoSource
      ) =
        val call = s"update:${id.value}:${target.value}:$name|$period|$folder|${countries.toList.map(_.value).mkString(",")}"
        refs.calls.update(_ :+ call).as(updateResult)
    val metadata = new AlbumRepository.ChapterRead[IO]:
      override def chapters(id: AlbumId): IO[AlbumRepository.ChaptersResult] =
        refs.calls.update(_ :+ s"chapters:${id.value}").as(metadataResult)
    val gear = new ChapterManager[IO]:
      override def readGear(album: AlbumId, chapter: ChapterId): IO[GearReadResult] =
        refs.gearCalls.update(_ :+ GearCall.Read(album, chapter)) >> gearReadResult
      override def replaceGear(album: AlbumId, chapter: ChapterId, selection: GearSelection): IO[GearReplaceResult] =
        refs.gearCalls.update(_ :+ GearCall.Replace(album, chapter, selection = selection)) >> gearReplaceResult
    ChapterApi.make(chapters, metadata, gear).routes.orNotFound
  // scalafix:on DisableSyntax.defaultArgs

  // scalafix:off DisableSyntax.defaultArgs
  private def send(server: HttpApp[IO], method: Method, path: String, body: String = "") =
    sendRaw(server, method, path = path, body = body).map { case (status, content, maybeMediaType, maybeCacheControl) =>
      assertEquals(maybeMediaType, MediaType.application.json.some)
      if method === Method.GET && status === Status.Ok then assertEquals(maybeCacheControl, "no-store".some)
      ApiResponse(status, json(content))
    }
  // scalafix:on DisableSyntax.defaultArgs

  private def sendRaw(server: HttpApp[IO], method: Method, path: String, body: String) =
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

  private def json(value: String)     = parse(value).fold(error => fail(error.message), identity)
  private def hasFeedback(body: Json) =
    body.asObject.exists(_.keys.toSet === Set("message")) && body.hcursor.get[String]("message").toOption.exists(_.nonEmpty)
