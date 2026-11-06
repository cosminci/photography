package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.data.{NonEmptyList, NonEmptyVector}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.http4s.{Header, HttpRoutes, MediaType, Method, Request, Status, Uri}
import org.http4s.CacheDirective
import org.http4s.headers.`Cache-Control`
import org.http4s.headers.`Content-Type`
import org.http4s.circe.CirceEntityCodec.*
import photography.domain.album.{AlbumId, ChapterId, PhotoId}
import photography.domain.editing.*
import photography.domain.layout.*
import photography.usecases.cover.CoverManager.Composition
import photography.usecases.cover.CoverManager
import photography.usecases.editing.EditingAccess
import photography.usecases.spread.SpreadManager
import java.util.UUID
import java.nio.charset.StandardCharsets

class PhotoPlacementApiSeamIntegrationTest extends CatsEffectSuite:
  private val album         = AlbumId(new UUID(0L, 1L))
  private val cover         = Cover(album, SpineOwner.Back, CoverSlot(CoverSlotId(new UUID(0L, 2L))), CoverSlot(CoverSlotId(new UUID(0L, 3L))))
  private val transform     = PhotoTransform(Shift(x = 12.5, y = -7.25), mirrored = true, zoom = 3.0)
  private val placement     = CoverPlacement(CoverPlacementId(new UUID(0L, 4L)), cover.front.id, PhotoId(new UUID(0L, 5L)), transform)
  private val second        = placement.copy(id = CoverPlacementId(new UUID(0L, 8L)), slot = cover.back.id, photo = PhotoId(new UUID(0L, 9L)))
  private val specification = CoverSpecification.from(pageLimit = 30, width = 10000, height = 3000, spine = 200).fold(fail(_), identity)
  private val dimensions    = CoverDimensions.forSpreads(spreads = 0, NonEmptyVector.one(specification))
  private val composition   = Composition(cover, placements = Vector(placement, second), dimensions = dimensions)
  private val coverPath     = s"/api/albums/${album.value}/cover"
  private val connection    = EditorConnection(SessionId(new UUID(0L, 20L)), TabId(new UUID(0L, 21L)), ConnectionGeneration(new UUID(0L, 22L)))
  private val grant         = GrantId(new UUID(0L, 23L))
  private val spreadPath    = s"/api/albums/${album.value}/spreads/${new UUID(0L, 6L)}"
  private val transformJson = Json.obj(
    "x"        -> Json.fromDoubleOrNull(12.5),
    "y"        -> Json.fromDoubleOrNull(-7.25),
    "zoom"     -> Json.fromInt(3),
    "mirrored" -> Json.True
  )
  private val expectedPlacement = Json.obj(
    "id"        -> Json.fromString(placement.id.value.toString),
    "slotId"    -> Json.fromString(cover.front.id.value.toString),
    "photoId"   -> Json.fromString(placement.photo.value.toString),
    "transform" -> transformJson
  )
  private val expectedSecond = Json.obj(
    "id"        -> Json.fromString(second.id.value.toString),
    "slotId"    -> Json.fromString(cover.back.id.value.toString),
    "photoId"   -> Json.fromString(second.photo.value.toString),
    "transform" -> transformJson
  )
  private val slot = SpreadSlot.Photo(
    PhotoSpreadSlotId(cover.front.id.value),
    SlotBox(x = 0.0, y = 0.0, width = 50.0, height = 100.0),
    bleed = Set.empty[SpreadEdge]
  )
  private val spread = Spread.PhotosOnly(
    SpreadId(new UUID(0L, 6L)),
    SpreadTemplateId(new UUID(0L, 7L)),
    photoSlots = NonEmptyList.one(slot),
    placements = Vector.empty[SpreadPlacement]
  )
  private val contentBox = Json.obj(
    "x"      -> Json.fromDoubleOrNull(700.0 / 2381.102),
    "y"      -> Json.fromDoubleOrNull(700.0 / 765.354),
    "width"  -> Json.fromDoubleOrNull(50.0 - 700.0 / 2381.102),
    "height" -> Json.fromDoubleOrNull((100.0 - 700.0 / 765.354) - 700.0 / 765.354)
  )
  private val expectedSlot = Json.obj(
    "id"         -> Json.fromString(slot.id.value.toString),
    "kind"       -> Json.fromString("photo"),
    "x"          -> Json.fromInt(0),
    "y"          -> Json.fromInt(0),
    "width"      -> Json.fromInt(50),
    "height"     -> Json.fromInt(100),
    "bleed"      -> Json.arr(),
    "contentBox" -> contentBox,
    "visibleBox" -> contentBox
  )

  private val editScenarios = Vector(
    EditScenario(
      request = PlacementRequest(
        method = Method.POST,
        suffix = "/placements",
        body = Json.obj(
          "slotId"  -> Json.fromString(cover.front.id.value.toString),
          "photoId" -> Json.fromString(placement.photo.value.toString)
        )
      ),
      coverEdit = CoverManager.Edit.Place(cover.front.id, placement.photo),
      spreadEdit = SpreadManager.Edit.Place(slot.id, placement.photo)
    ),
    EditScenario(
      request = PlacementRequest(method = Method.DELETE, suffix = s"/placements/${placement.id.value}", body = Json.obj()),
      coverEdit = CoverManager.Edit.Remove(placement.id),
      spreadEdit = SpreadManager.Edit.Remove(SpreadPlacementId(placement.id.value))
    ),
    EditScenario(
      request = PlacementRequest(
        method = Method.PATCH,
        suffix = s"/placements/${placement.id.value}",
        body = Json.obj("slotId" -> Json.fromString(cover.back.id.value.toString))
      ),
      coverEdit = CoverManager.Edit.Move(placement.id, cover.back.id),
      spreadEdit = SpreadManager.Edit.Move(SpreadPlacementId(placement.id.value), PhotoSpreadSlotId(cover.back.id.value))
    ),
    EditScenario(
      request = PlacementRequest(method = Method.PATCH, suffix = s"/placements/${placement.id.value}", body = Json.obj("transform" -> transformJson)),
      coverEdit = CoverManager.Edit.Adjust(placement.id, transform),
      spreadEdit = SpreadManager.Edit.Adjust(SpreadPlacementId(placement.id.value), transform)
    )
  )

  test("should dispatch bleed and spine ownership through their exact editing targets and return acknowledged state"):
    val refs = Refs()
    val api  = buildApi(
      refs,
      result = CoverManager.Result.Failed,
      bleedResult = SpreadManager.SetBleedResult.Saved(spread),
      spineResult = CoverManager.SetSpineOwnerResult.Saved(composition)
    )
    val requests = Vector(
      (s"$spreadPath/bleed", Json.obj("enabled" -> Json.True)),
      (s"$coverPath/spine-owner", Json.obj("owner" -> Json.fromString("back")))
    )

    for
      responses <- requests.traverse { case (path, body) =>
        send(api, Request[IO](Method.PUT, Uri.unsafeFromString(path)).withEntity(body))
      }
      calls <- refs.calls.get
    yield
      assertEquals(responses.map { case (status, _) => status }, Vector(Status.Ok, Status.Ok))
      assertEquals(calls, Vector(PlacementCall.SetBleed(album, spread.id, enabled = true), PlacementCall.SetSpineOwner(album, SpineOwner.Back)))

  test("should preserve explicit enabled and mixed saved edges and reject unusable frame geometry"):
    val cases = Vector(
      (Set(SpreadEdge.Top, SpreadEdge.Bottom, SpreadEdge.Left), "enabled", Set("top", "bottom", "left")),
      (SpreadEdge.values.toSet, "mixed", Set("top", "bottom", "left", "right"))
    )
    val reads = cases.traverse_ { case (directions, state, expectedDirections) =>
      val saved = spread.copy(photoSlots = NonEmptyList.one(slot.copy(bleed = directions)))
      val api   = buildApi(result = CoverManager.Result.Failed, spreadRead = SpreadManager.PlacementReadResult.Found(saved))
      send(api, Request[IO](Method.GET, Uri.unsafeFromString(spreadPath))).map { case (status, body) =>
        assertEquals(status, Status.Ok)
        assertEquals(body.hcursor.get[String]("bleedState").toOption, state.some)
        assertEquals(
          body.hcursor.downField("slots").downArray.get[Vector[String]]("bleed").toOption.map(_.toSet),
          expectedDirections.some
        )
      }
    }
    val invalid = spread.copy(photoSlots = NonEmptyList.one(slot.copy(box = SlotBox(x = 0.0, y = 0.0, width = 0.1, height = 100.0))))
    val api     = buildApi(result = CoverManager.Result.Failed, spreadRead = SpreadManager.PlacementReadResult.Found(invalid))

    reads >> send(api, Request[IO](Method.GET, Uri.unsafeFromString(spreadPath))).map { case (status, body) =>
      assertEquals(status, Status.InternalServerError)
      assertEquals(body.hcursor.get[String]("code").toOption, "invalid_spread_geometry".some)
    }

  test("should deliver bleed and spine rejection outcomes without retrying commands"):
    val bleedCases = Vector(
      (SpreadManager.SetBleedResult.Rejected, Status.Conflict, "bleed_unavailable"),
      (SpreadManager.SetBleedResult.AlbumNotFound, Status.NotFound, "album_not_found"),
      (SpreadManager.SetBleedResult.SpreadNotFound, Status.NotFound, "spread_not_found"),
      (SpreadManager.SetBleedResult.Failed, Status.InternalServerError, "service_failure")
    )
    val spineCases = Vector(
      (CoverManager.SetSpineOwnerResult.CoverNotFound, Status.NotFound, "cover_not_found"),
      (CoverManager.SetSpineOwnerResult.Failed, Status.InternalServerError, "service_failure")
    )
    val bleed = bleedCases.traverse_ { case (result, status, code) =>
      val refs = Refs()
      val api  = buildApi(refs, result = CoverManager.Result.Failed, bleedResult = result)
      for
        response <- send(api, Request[IO](Method.PUT, Uri.unsafeFromString(s"$spreadPath/bleed")).withEntity(Json.obj("enabled" -> Json.True)))
        calls    <- refs.calls.get
      yield
        assertEquals(response._1, status)
        assertEquals(response._2.hcursor.get[String]("code").toOption, code.some)
        assertEquals(calls, Vector(PlacementCall.SetBleed(album, spread.id, enabled = true)))
    }
    val spine = spineCases.traverse_ { case (result, status, code) =>
      val refs = Refs()
      val api  = buildApi(refs, result = CoverManager.Result.Failed, spineResult = result)
      for
        response <- send(
          api,
          Request[IO](Method.PUT, Uri.unsafeFromString(s"$coverPath/spine-owner")).withEntity(Json.obj("owner" -> Json.fromString("front")))
        )
        calls <- refs.calls.get
      yield
        assertEquals(response, (status, Json.obj("code" -> Json.fromString(code))))
        assertEquals(calls, Vector(PlacementCall.SetSpineOwner(album, SpineOwner.Front)))
    }
    bleed >> spine

  test("should reject malformed or ambiguous composition settings before application work"):
    val refs  = Refs()
    val api   = buildApi(refs, result = CoverManager.Result.Failed)
    val cases = Vector(
      (s"$spreadPath/bleed", "{}"),
      (s"$spreadPath/bleed", """{"enabled":"yes"}"""),
      (s"$spreadPath/bleed", """{"enabled":true,"other":1}"""),
      (s"$coverPath/spine-owner", "{}"),
      (s"$coverPath/spine-owner", """{"owner":"unknown"}"""),
      (s"$coverPath/spine-owner", """{"owner":null}"""),
      (s"$coverPath/spine-owner", """{"owner":"front","other":1}""")
    )
    for
      _ <- cases.traverse_ { case (path, body) =>
        send(
          api,
          Request[IO](Method.PUT, Uri.unsafeFromString(path))
            .withBodyStream(Stream.emits(body.getBytes(StandardCharsets.UTF_8)).covary[IO])
            .withContentType(`Content-Type`(MediaType.application.json))
        )
          .map { case (status, _) => assertEquals(status, Status.BadRequest) }
      }
      calls <- refs.calls.get
    yield assertEquals(calls, Vector.empty[PlacementCall])

  Vector(
    CornerScenario(CaptionCorner.TopLeft, encoded = "top_left"),
    CornerScenario(CaptionCorner.TopRight, encoded = "top_right"),
    CornerScenario(CaptionCorner.BottomLeft, encoded = "bottom_left"),
    CornerScenario(CaptionCorner.BottomRight, encoded = "bottom_right")
  ).foreach { scenario =>
    val target         = SpreadPlacementId(placement.id.value)
    val savedPlacement = SpreadPlacement(target, slot.id, placement.photo, transform, scenario.corner)
    val savedSpread    = spread.copy(placements = Vector(savedPlacement))
    val result         = SpreadManager.PlacementResult.Saved(savedSpread)
    test(s"should deliver ${result.productPrefix} ${scenario.corner} through only the interior placement owner"):
      val refs         = Refs()
      val api          = buildApi(refs, result = CoverManager.Result.Failed, spreadResult = result)
      val expectedBody = Json.obj(
        "id"             -> Json.fromString(spread.id.value.toString),
        "kind"           -> Json.fromString("photos_only"),
        "templateId"     -> Json.fromString(spread.template.value.toString),
        "bleedState"     -> Json.fromString("disabled"),
        "bleedAvailable" -> Json.True,
        "slots"          -> Json.arr(expectedSlot),
        "placements"     -> Json.arr(expectedPlacement.deepMerge(Json.obj("captionCorner" -> Json.fromString(scenario.encoded))))
      )
      val request = Request[IO](Method.PATCH, Uri.unsafeFromString(s = s"$spreadPath/placements/${target.value}"))
        .withEntity(b = Json.obj("captionCorner" -> Json.fromString(scenario.encoded)))

      for
        response <- send(api, request)
        calls    <- refs.calls.get
      yield
        assertEquals(response, (Status.Ok, expectedBody))
        assertEquals(calls, Vector(PlacementCall.Edit(album, spread.id, SpreadManager.Edit.SetCaptionCorner(target, scenario.corner))))
  }

  Vector(
    CornerResultScenario(SpreadManager.PlacementResult.AlbumNotFound, Status.NotFound, code = "album_not_found"),
    CornerResultScenario(SpreadManager.PlacementResult.SpreadNotFound, Status.NotFound, code = "spread_not_found"),
    CornerResultScenario(SpreadManager.PlacementResult.Failed, Status.InternalServerError, code = "service_failure"),
    CornerResultScenario(
      SpreadManager.PlacementResult.Rejected(SpreadManager.Failure.PlacementNotFound),
      Status.Conflict,
      code = "placement_not_found"
    )
  ).foreach { scenario =>
    test(s"should deliver corner ${scenario.result} without retry or postwrite read"):
      val refs    = Refs()
      val api     = buildApi(refs, result = CoverManager.Result.Failed, spreadResult = scenario.result)
      val target  = SpreadPlacementId(placement.id.value)
      val request = Request[IO](Method.PATCH, Uri.unsafeFromString(s = s"$spreadPath/placements/${target.value}"))
        .withEntity(b = Json.obj("captionCorner" -> Json.fromString("top_left")))

      for
        response <- send(api, request)
        calls    <- refs.calls.get
      yield
        assertEquals(response, (scenario.status, Json.obj("code" -> Json.fromString(scenario.code))))
        assertEquals(calls, Vector(PlacementCall.Edit(album, spread.id, SpreadManager.Edit.SetCaptionCorner(target, CaptionCorner.TopLeft))))
  }

  test("should reject malformed or ambiguous corners and keep Cover without caption corners"):
    val refs          = Refs()
    val api           = buildApi(refs, result = CoverManager.Result.Failed)
    val invalidBodies = Vector(
      InvalidCorner(
        Json.obj("captionCorner" -> Json.fromString("middle")),
        message = "DecodingFailure at .captionCorner: Select one of the four caption corners"
      ),
      InvalidCorner(
        Json.obj("captionCorner" -> Json.Null),
        message = "DecodingFailure at .captionCorner: Got value 'null' with wrong type, expecting string"
      ),
      InvalidCorner(
        Json.obj("captionCorner" -> Json.fromInt(1)),
        message = "DecodingFailure at .captionCorner: Got value '1' with wrong type, expecting string"
      ),
      InvalidCorner(
        Json.obj("captionCorner" -> Json.fromString("top_left"), "transform" -> transformJson),
        message = "DecodingFailure at : Supply either slotId, transform or captionCorner"
      ),
      InvalidCorner(
        Json.obj("captionCorner" -> Json.fromString("top_left"), "slotId" -> Json.fromString(slot.id.value.toString)),
        message = "DecodingFailure at : Supply either slotId, transform or captionCorner"
      )
    )
    val checkPayloads = invalidBodies.traverse_ { scenario =>
      val request = Request[IO](Method.PATCH, Uri.unsafeFromString(s = s"$spreadPath/placements/${placement.id.value}")).withEntity(b = scenario.body)
      val expected = (Status.BadRequest, Json.obj("message" -> Json.fromString(scenario.message)))
      send(api, request).map(response => assertEquals(response, expected))
    }
    val coverRequest = Request[IO](Method.PATCH, Uri.unsafeFromString(s = s"$coverPath/placements/${placement.id.value}"))
      .withEntity(b = Json.obj("captionCorner" -> Json.fromString("top_left")))
    val expectedCoverResponse = (Status.BadRequest, Json.obj("message" -> Json.fromString("DecodingFailure at : Supply either slotId or transform")))

    for
      _             <- checkPayloads
      coverResponse <- send(api, coverRequest)
      calls         <- refs.calls.get
    yield
      assertEquals(coverResponse, expectedCoverResponse)
      assertEquals(calls, Vector.empty[PlacementCall])

  test("should expose either cover spine, both side associations and empty compositions and dispatch every edit"):
    val cases = Vector(
      (SpineOwner.Back, "back", Vector(placement, second), Json.arr(expectedPlacement, expectedSecond)),
      (SpineOwner.Front, "front", Vector(placement, second), Json.arr(expectedPlacement, expectedSecond)),
      (SpineOwner.Front, "front", Vector.empty[CoverPlacement], Json.arr())
    )

    cases.traverse_ { case (owner, encodedOwner, placements, encodedPlacements) =>
      val frontWidth = owner match
        case SpineOwner.Front => 1224
        case SpineOwner.Back  => 1176
      val backWidth    = 2400 - frontWidth
      val saved        = Composition(cover.copy(spineOwner = owner), placements = placements, dimensions = dimensions)
      val expectedBody = Json.obj(
        "frontSlotId" -> Json.fromString(cover.front.id.value.toString),
        "backSlotId"  -> Json.fromString(cover.back.id.value.toString),
        "spineOwner"  -> Json.fromString(encodedOwner),
        "pageCount"   -> Json.fromInt(0),
        "placements"  -> encodedPlacements,
        "geometry"    -> Json.obj(
          "width"      -> Json.fromInt(2400),
          "height"     -> Json.fromInt(720),
          "spineStart" -> Json.fromInt(1176),
          "spineWidth" -> Json.fromInt(48),
          "front"      -> Json.obj(
            "x"      -> Json.fromInt(backWidth),
            "width"  -> Json.fromInt(frontWidth),
            "height" -> Json.fromInt(720)
          ),
          "back" -> Json.obj(
            "x"      -> Json.fromInt(0),
            "width"  -> Json.fromInt(backWidth),
            "height" -> Json.fromInt(720)
          )
        )
      )
      val api        = buildApi(result = CoverManager.Result.Saved(saved), coverRead = CoverManager.ReadResult.Found(saved))
      val checkEdits = editScenarios.traverse_ { scenario =>
        val refs    = Refs()
        val editing = buildApi(refs, result = CoverManager.Result.Saved(saved))
        val request = Request[IO](scenario.request.method, Uri.unsafeFromString(s = coverPath + scenario.request.suffix))
          .withEntity(b = scenario.request.body)
        val expectedCalls = Vector(PlacementCall.EditCover(album, scenario.coverEdit))

        for
          response <- send(editing, request)
          calls    <- refs.calls.get
        yield
          assertEquals(response, (Status.Ok, expectedBody))
          assertEquals(calls, expectedCalls)
      }

      for
        read <- send(api, Request[IO](Method.GET, Uri.unsafeFromString(coverPath)))
        _    <- checkEdits
      yield assertEquals(read, (Status.Ok, expectedBody))
    }

  test("should expose every interior caption corner and empty composition and dispatch every edit"):
    val corners = Vector(
      CaptionCorner.TopLeft     -> "top_left",
      CaptionCorner.TopRight    -> "top_right",
      CaptionCorner.BottomLeft  -> "bottom_left",
      CaptionCorner.BottomRight -> "bottom_right"
    )
    val populated = corners.map { case (corner, encodedCorner) =>
      val value   = SpreadPlacement(SpreadPlacementId(placement.id.value), slot.id, placement.photo, transform, corner)
      val encoded = expectedPlacement.deepMerge(Json.obj("captionCorner" -> Json.fromString(encodedCorner)))
      (Vector(value), Json.arr(encoded))
    }
    val cases = populated :+ (Vector.empty[SpreadPlacement], Json.arr())

    cases.traverse_ { case (placements, encodedPlacements) =>
      val saved        = spread.copy(placements = placements)
      val expectedBody = Json.obj(
        "id"             -> Json.fromString(spread.id.value.toString),
        "kind"           -> Json.fromString("photos_only"),
        "templateId"     -> Json.fromString(spread.template.value.toString),
        "bleedState"     -> Json.fromString("disabled"),
        "bleedAvailable" -> Json.True,
        "slots"          -> Json.arr(expectedSlot),
        "placements"     -> encodedPlacements
      )
      val api = buildApi(
        result = CoverManager.Result.Failed,
        spreadResult = SpreadManager.PlacementResult.Saved(saved),
        spreadRead = SpreadManager.PlacementReadResult.Found(saved)
      )
      val checkEdits = editScenarios.traverse_ { scenario =>
        val refs    = Refs()
        val editing = buildApi(
          refs,
          result = CoverManager.Result.Failed,
          spreadResult = SpreadManager.PlacementResult.Saved(saved)
        )
        val request = Request[IO](scenario.request.method, Uri.unsafeFromString(s = spreadPath + scenario.request.suffix))
          .withEntity(b = scenario.request.body)
        val expectedCalls = Vector(PlacementCall.Edit(album, spread.id, scenario.spreadEdit))

        for
          response <- send(editing, request)
          calls    <- refs.calls.get
        yield
          assertEquals(response, (Status.Ok, expectedBody))
          assertEquals(calls, expectedCalls)
      }

      for
        read <- send(api, Request[IO](Method.GET, Uri.unsafeFromString(spreadPath)))
        _    <- checkEdits
      yield assertEquals(read, (Status.Ok, expectedBody))
    }

  test("should translate every application rejection and missing or failed read into exact recovery responses"):
    val coverEdits = Vector(
      (CoverManager.Result.CoverNotFound, Status.NotFound, "cover_not_found"),
      (CoverManager.Result.Failed, Status.InternalServerError, "service_failure"),
      (CoverManager.Result.Rejected(CoverManager.Failure.PhotoNotInCollection), Status.Conflict, "photo_not_in_collection"),
      (CoverManager.Result.Rejected(CoverManager.Failure.PhotoAlreadyUsed), Status.Conflict, "photo_already_used"),
      (CoverManager.Result.Rejected(CoverManager.Failure.SlotNotFound), Status.Conflict, "slot_not_found"),
      (CoverManager.Result.Rejected(CoverManager.Failure.PlacementNotFound), Status.Conflict, "placement_not_found"),
      (CoverManager.Result.Rejected(CoverManager.Failure.InvalidTransform), Status.Conflict, "invalid_transform")
    )
    val spreadEdits = Vector(
      (SpreadManager.PlacementResult.AlbumNotFound, Status.NotFound, "album_not_found"),
      (SpreadManager.PlacementResult.SpreadNotFound, Status.NotFound, "spread_not_found"),
      (SpreadManager.PlacementResult.Failed, Status.InternalServerError, "service_failure"),
      (SpreadManager.PlacementResult.Rejected(SpreadManager.Failure.PhotoNotInCollection), Status.Conflict, "photo_not_in_collection"),
      (SpreadManager.PlacementResult.Rejected(SpreadManager.Failure.PhotoAlreadyUsed), Status.Conflict, "photo_already_used"),
      (SpreadManager.PlacementResult.Rejected(SpreadManager.Failure.SlotNotFound), Status.Conflict, "slot_not_found"),
      (SpreadManager.PlacementResult.Rejected(SpreadManager.Failure.PlacementNotFound), Status.Conflict, "placement_not_found"),
      (SpreadManager.PlacementResult.Rejected(SpreadManager.Failure.InvalidTransform), Status.Conflict, "invalid_transform")
    )
    val spreadReads = Vector(
      (SpreadManager.PlacementReadResult.AlbumNotFound, Status.NotFound, "album_not_found"),
      (SpreadManager.PlacementReadResult.SpreadNotFound, Status.NotFound, "spread_not_found"),
      (SpreadManager.PlacementReadResult.Failed, Status.InternalServerError, "service_failure")
    )
    val coverReads = Vector(
      (CoverManager.ReadResult.CoverNotFound, Status.NotFound, "cover_not_found"),
      (CoverManager.ReadResult.Failed, Status.InternalServerError, "service_failure")
    )
    val coverFailures = coverEdits.traverse_ { case (result, status, code) =>
      val api      = buildApi(result = result)
      val expected = (status, Json.obj("code" -> Json.fromString(code)))
      send(api, Request[IO](Method.DELETE, Uri.unsafeFromString(s"$coverPath/placements/${placement.id.value}"))).map(actual =>
        assertEquals(actual, expected)
      )
    }
    val spreadFailures = spreadEdits.traverse_ { case (result, status, code) =>
      val api      = buildApi(result = CoverManager.Result.Failed, spreadResult = result)
      val expected = (status, Json.obj("code" -> Json.fromString(code)))
      send(api, Request[IO](Method.DELETE, Uri.unsafeFromString(s"$spreadPath/placements/${placement.id.value}"))).map(actual =>
        assertEquals(actual, expected)
      )
    }
    val missingSpreads = spreadReads.traverse_ { case (result, status, code) =>
      val api      = buildApi(result = CoverManager.Result.Failed, spreadRead = result)
      val expected = (status, Json.obj("code" -> Json.fromString(code)))
      send(api, Request[IO](Method.GET, Uri.unsafeFromString(spreadPath))).map(actual => assertEquals(actual, expected))
    }
    val missingCovers = coverReads.traverse_ { case (result, status, code) =>
      val api      = buildApi(result = CoverManager.Result.Failed, coverRead = result)
      val expected = (status, Json.obj("code" -> Json.fromString(code)))
      send(api, Request[IO](Method.GET, Uri.unsafeFromString(coverPath))).map(actual => assertEquals(actual, expected))
    }

    coverFailures >> spreadFailures >> missingSpreads >> missingCovers

  test("should read album and chapter opening geometry from the individual spread resource"):
    val box      = SlotBox(x = 0.0, y = 0.0, width = 50.0, height = 100.0)
    val text     = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 11L)), box)
    val photo    = SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(0L, 12L)), box, bleed = Set.empty[SpreadEdge])
    val id       = SpreadId(new UUID(0L, 6L))
    val template = SpreadTemplateId(new UUID(0L, 7L))
    val cases    = Vector(
      (Spread.AlbumStart(id, template, Vector.empty[SpreadSlot.Photo], text, Vector.empty[SpreadPlacement]): Spread, "album_start", Vector("text")),
      (Spread.ChapterStart(id, template, Vector(photo), text, Vector.empty[SpreadPlacement]): Spread, "chapter_start", Vector("photo", "text"))
    )
    cases.traverse_ { case (spread, kind, slots) =>
      val api = buildApi(result = CoverManager.Result.Failed, spreadRead = SpreadManager.PlacementReadResult.Found(spread))
      send(api, Request[IO](Method.GET, Uri.unsafeFromString(spreadPath))).map { case (status, body) =>
        assertEquals(status, Status.Ok)
        assertEquals(body.hcursor.get[String]("kind").toOption, kind.some)
        assertEquals(body.hcursor.get[String]("templateId").toOption, template.value.toString.some)
        assertEquals(body.hcursor.get[Vector[Json]]("slots").toOption.map(_.flatMap(_.hcursor.get[String]("kind").toOption)), slots.some)
      }
    }

  test("should reject removed operation envelopes and ambiguous placement field replacements"):
    val api = buildApi(result = CoverManager.Result.Failed)
    Vector(coverPath, spreadPath).traverse_ { path =>
      val creation = Json.obj(
        "operation" -> Json.fromString("place"),
        "slotId"    -> Json.fromString(cover.front.id.value.toString),
        "photoId"   -> Json.fromString(placement.photo.value.toString)
      )
      val ambiguous = Json.obj("slotId" -> Json.fromString(cover.front.id.value.toString), "transform" -> transformJson)
      for
        old  <- send(api, Request[IO](Method.POST, Uri.unsafeFromString(s"$path/placements")).withEntity(creation))
        both <- send(api, Request[IO](Method.PATCH, Uri.unsafeFromString(s"$path/placements/${placement.id.value}")).withEntity(ambiguous))
      yield
        assertEquals(old._1, Status.BadRequest)
        assertEquals(both._1, Status.BadRequest)
    }

  test("should reject malformed fields, noncanonical identities and invalid transform representations before application calls"):
    val invalidIdentities = Vector("invalid", "0-0-0-0-1", "AAAAAAAA-AAAA-4AAA-AAAA-AAAAAAAAAAAA")
    val missingFields     = editScenarios.map(_.request).filterNot(_.method === Method.DELETE).flatMap { request =>
      request.body.asObject.toList.flatMap(_.keys).map(field => request.copy(body = request.body.mapObject(_.remove(field))))
    }
    val invalidFields = editScenarios.map(_.request).flatMap { request =>
      request.body.asObject.toList.flatMap(_.keys.filter(field => field.endsWith("Id"))).flatMap: field =>
        val values = invalidIdentities.map(Json.fromString) ++ Vector(Json.Null, Json.fromInt(1))
        values.map(value => request.copy(body = request.body.mapObject(_.add(field, value))))
    }
    val invalidTransforms = Vector(
      "x"        -> Json.fromString("invalid"),
      "y"        -> Json.fromString("invalid"),
      "x"        -> Json.fromBigDecimal(BigDecimal("1e400")),
      "y"        -> Json.fromBigDecimal(BigDecimal("-1e400")),
      "zoom"     -> Json.fromBigDecimal(BigDecimal("1e400")),
      "zoom"     -> Json.fromInt(0),
      "zoom"     -> Json.fromInt(-1),
      "mirrored" -> Json.fromInt(1)
    ).map { case (field, value) => transformJson.mapObject(_.add(field, value)) } ++
      Vector("x", "y", "zoom", "mirrored").map(field => transformJson.mapObject(_.remove(field)))
    val adjustments = invalidTransforms.map: value =>
      PlacementRequest(method = Method.PATCH, suffix = s"/placements/${placement.id.value}", body = Json.obj("transform" -> value))
    val requests =
      Vector(Json.Null, Json.arr(), Json.obj()).map(value => PlacementRequest(method = Method.POST, suffix = "/placements", body = value)) ++
        missingFields ++ invalidFields ++ adjustments
    val api           = buildApi(result = CoverManager.Result.Failed)
    val malformedBody = Stream.emits("{invalid json".getBytes(StandardCharsets.UTF_8)).covary[IO]
    val malformed     = Request[IO](Method.POST, Uri.unsafeFromString(s"$coverPath/placements")).withBodyStream(malformedBody)
      .putHeaders(`Content-Type`(MediaType.application.json))
    val decodedFailures = Vector(coverPath, spreadPath).flatTraverse: path =>
      requests.traverse { request =>
        send(api, Request[IO](request.method, Uri.unsafeFromString(s = path + request.suffix)).withEntity(b = request.body))
      }

    for
      failures <- decodedFailures
      syntax   <- send(api, malformed)
    yield
      val responses = failures :+ syntax
      val feedback  = responses.map { case (status, body) => (status, hasFeedback(body)) }
      assertEquals(feedback, Vector.fill(responses.size)((Status.BadRequest, true)))

  test("should exclude noncanonical path identities without calling application services"):
    val refs              = Refs()
    val invalidIdentities = Vector("0-0-0-0-1", "AAAAAAAA-AAAA-4AAA-AAAA-AAAAAAAAAAAA")
    val paths             = invalidIdentities.flatMap: id =>
      Vector(
        s"/api/albums/$id/cover/placements",
        s"/api/albums/$id/spreads/${new UUID(0L, 6L)}/placements",
        s"/api/albums/${album.value}/spreads/$id/placements"
      )
    val api         = buildApi(refs, result = CoverManager.Result.Failed)
    val cornerPaths = invalidIdentities.flatMap { id =>
      Vector(
        s"/api/albums/$id/spreads/${spread.id.value}/placements/${placement.id.value}",
        s"/api/albums/${album.value}/spreads/$id/placements/${placement.id.value}",
        s"$spreadPath/placements/$id"
      )
    }
    val cornerRequests = cornerPaths.map { path =>
      Request[IO](Method.PATCH, Uri.unsafeFromString(s = path)).withEntity(b = Json.obj("captionCorner" -> Json.fromString("top_left")))
    }
    val requests = paths.flatMap(path => Vector(Method.GET, Method.POST).map(method => Request[IO](method, Uri.unsafeFromString(path)))) ++
      cornerRequests

    for
      statuses <- requests.traverse(request => api.orNotFound.run(request).map(_.status))
      calls    <- refs.calls.get
    yield
      assertEquals(statuses, Vector.fill(n = requests.size)(Status.NotFound))
      assertEquals(calls, Vector.empty[PlacementCall])

  test("should dispatch domain-valid zoom outside the editing range to the application's rejection owner"):
    val outside = transform.copy(zoom = 6.0)
    val request = Json.obj(
      "transform" -> transformJson.mapObject(_.add("zoom", Json.fromInt(6)))
    )
    val expected = (Status.Conflict, Json.obj("code" -> Json.fromString("invalid_transform")))
    val covers   = buildApi(
      result = CoverManager.Result.Rejected(CoverManager.Failure.InvalidTransform),
      maybeCoverEdit = CoverManager.Edit.Adjust(placement.id, outside).some
    )
    val spreads = buildApi(
      result = CoverManager.Result.Failed,
      spreadResult = SpreadManager.PlacementResult.Rejected(SpreadManager.Failure.InvalidTransform),
      maybeSpreadEdit = SpreadManager.Edit.Adjust(SpreadPlacementId(placement.id.value), outside).some
    )

    val coverRequest  = Request[IO](Method.PATCH, Uri.unsafeFromString(s"$coverPath/placements/${placement.id.value}")).withEntity(request)
    val spreadRequest = Request[IO](Method.PATCH, Uri.unsafeFromString(s"$spreadPath/placements/${placement.id.value}")).withEntity(request)

    for
      coverResponse  <- send(covers, coverRequest)
      spreadResponse <- send(spreads, spreadRequest)
    yield
      assertEquals(coverResponse, expected)
      assertEquals(spreadResponse, expected)

  final private case class CornerScenario(corner: CaptionCorner, encoded: String)
  final private case class PlacementRequest(method: Method, suffix: String, body: Json)
  final private case class EditScenario(request: PlacementRequest, coverEdit: CoverManager.Edit, spreadEdit: SpreadManager.Edit)
  final private case class InvalidCorner(body: Json, message: String)
  final private case class CornerResultScenario(result: SpreadManager.PlacementResult, status: Status, code: String)
  private enum PlacementCall:
    case Read(album: AlbumId, spread: SpreadId)
    case Edit(album: AlbumId, spread: SpreadId, edit: SpreadManager.Edit)
    case ReadCover(album: AlbumId)
    case EditCover(album: AlbumId, edit: CoverManager.Edit)
    case SetBleed(album: AlbumId, spread: SpreadId, enabled: Boolean)
    case SetSpineOwner(album: AlbumId, owner: SpineOwner)
  final private case class Refs():
    val calls: Ref[IO, Vector[PlacementCall]] = Ref.unsafe(a = Vector.empty[PlacementCall])

  // scalafix:off DisableSyntax.defaultArgs
  private def buildApi(
      refs: Refs = Refs(),
      result: CoverManager.Result,
      spreadResult: SpreadManager.PlacementResult = SpreadManager.PlacementResult.SpreadNotFound,
      coverRead: CoverManager.ReadResult = CoverManager.ReadResult.Found(composition),
      spreadRead: SpreadManager.PlacementReadResult = SpreadManager.PlacementReadResult.SpreadNotFound,
      maybeCoverEdit: Option[CoverManager.Edit] = None,
      maybeSpreadEdit: Option[SpreadManager.Edit] = None,
      bleedResult: SpreadManager.SetBleedResult = SpreadManager.SetBleedResult.Failed,
      spineResult: CoverManager.SetSpineOwnerResult = CoverManager.SetSpineOwnerResult.Failed
  ) =
    val covers = new CoverManager[IO]:
      override def setSpineOwner(album: AlbumId, owner: SpineOwner): IO[CoverManager.SetSpineOwnerResult] =
        refs.calls.update(_ :+ PlacementCall.SetSpineOwner(album, owner)).as(spineResult)
      override def read(id: AlbumId): IO[CoverManager.ReadResult] =
        assertEquals(id, album)
        refs.calls.update(_ :+ PlacementCall.ReadCover(id)).as(coverRead)
      override def edit(id: AlbumId, request: CoverManager.Edit): IO[CoverManager.Result] =
        assertEquals(id, album)
        maybeCoverEdit.foreach(expected => assertEquals(request, expected))
        refs.calls.update(_ :+ PlacementCall.EditCover(id, request)).as(result)
    val spreads = new SpreadManager[IO]:
      override def setBleed(album: AlbumId, spread: SpreadId, enabled: Boolean): IO[SpreadManager.SetBleedResult] =
        refs.calls.update(_ :+ PlacementCall.SetBleed(album, spread, enabled)).as(bleedResult)
      override def replaceChapterOrder(
          album: AlbumId,
          chapter: ChapterId,
          expected: NonEmptyList[SpreadId],
          order: NonEmptyList[SpreadId]
      ): IO[SpreadManager.ReplacementResult] =
        IO.raiseError[SpreadManager.ReplacementResult](new AssertionError("Unexpected chapter order replacement"))
      override def add(album: AlbumId, chapter: ChapterId, after: SpreadId): IO[SpreadManager.AddResult] =
        IO.raiseError[SpreadManager.AddResult](new AssertionError("Unexpected structural add"))
      override def changeTemplate(
          album: AlbumId,
          spread: SpreadId,
          template: SpreadTemplateId,
          confirmPlacements: Boolean
      ): IO[SpreadManager.ChangeTemplateResult] =
        IO.raiseError[SpreadManager.ChangeTemplateResult](new AssertionError("Unexpected structural template change"))
      override def delete(
          album: AlbumId,
          spread: SpreadId,
          confirmPlacements: Boolean
      ): IO[SpreadManager.DeleteResult] =
        IO.raiseError[SpreadManager.DeleteResult](new AssertionError("Unexpected structural delete"))
      override def readPlacements(id: AlbumId, spread: SpreadId): IO[SpreadManager.PlacementReadResult] =
        assertEquals(id, album)
        assertEquals(spread, SpreadId(new UUID(0L, 6L)))
        refs.calls.update(_ :+ PlacementCall.Read(id, spread)).as(spreadRead)
      override def editPlacements(
          id: AlbumId,
          spread: SpreadId,
          request: SpreadManager.Edit
      ): IO[SpreadManager.PlacementResult] =
        assertEquals(id, album)
        assertEquals(spread, SpreadId(new UUID(0L, 6L)))
        maybeSpreadEdit.foreach(expected => assertEquals(request, expected))
        refs.calls.update(_ :+ PlacementCall.Edit(id, spread, request)).as(spreadResult)
    val access = new EditingAccess[IO]:
      override def run[A](
          editor: EditorConnection,
          protection: EditingAccess.Protection,
          maybeGrant: Option[GrantId]
      )(write: IO[A]): IO[EditingAccess.Result[A]] =
        assertEquals(editor, connection)
        assertEquals(maybeGrant, Some(grant))
        protection match
          case EditingAccess.Protection.OwnSpread(target) =>
            assert(Set(EditingTarget.Cover(album), EditingTarget.Spread(spread.id)).contains(target))
          case other => fail(s"Expected spread ownership declaration, obtained $other")
        write.map(EditingAccess.Result.Completed(_))
    val definitions = PhotoPlacementApi.make(spreads, covers)
    Vector(s"$coverPath/spine-owner" -> EditingTarget.Cover(album), s"$spreadPath/bleed" -> EditingTarget.Spread(spread.id)).foreach {
      case (path, target) =>
        val request = Request[IO](Method.PUT, Uri.unsafeFromString(path))
        assertEquals(definitions.routes.lift(request).map(_.access), Some(Routes.Access.Editor(EditingAccess.Protection.OwnSpread(target))))
    }
    Vector(coverPath -> EditingTarget.Cover(album), spreadPath -> EditingTarget.Spread(spread.id)).foreach { case (path, target) =>
      editScenarios.foreach { scenario =>
        val request = Request[IO](scenario.request.method, Uri.unsafeFromString(path + scenario.request.suffix))
        assertEquals(definitions.routes.lift(request).map(_.access), Some(Routes.Access.Editor(EditingAccess.Protection.OwnSpread(target))))
      }
    }
    Routes.make(access)(definitions)
  // scalafix:on DisableSyntax.defaultArgs

  private def send(api: HttpRoutes[IO], request: Request[IO]) =
    for
      response <- api.orNotFound.run(request.putHeaders(
        "X-Editor-Session"    -> connection.session.value.toString,
        "X-Editor-Tab"        -> connection.tab.value.toString,
        "X-Editor-Generation" -> connection.generation.value.toString,
        "X-Editor-Grant"      -> grant.value.toString
      ))
      body <- response.as[Json]
    yield
      assertEquals(response.contentType.map(_.mediaType), MediaType.application.json.some)
      if request.method === Method.GET && response.status === Status.Ok then
        assertEquals(response.headers.get[`Cache-Control`], `Cache-Control`(CacheDirective.`no-store`).some)
      if request.method =!= Method.GET then
        assertEquals(response.headers.get[`Cache-Control`], `Cache-Control`(CacheDirective.`no-store`).some)
      (response.status, body)

  private def hasFeedback(body: Json) =
    body.asObject.exists(_.keys.toSet === Set("message")) && body.hcursor.get[String]("message").toOption.exists(_.nonEmpty)
