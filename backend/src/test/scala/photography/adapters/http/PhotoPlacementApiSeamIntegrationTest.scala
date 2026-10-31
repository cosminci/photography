package photography.adapters.http

import cats.effect.IO
import cats.data.NonEmptyList
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.http4s.{HttpRoutes, MediaType, Method, Request, Status, Uri}
import org.http4s.CacheDirective
import org.http4s.headers.`Cache-Control`
import org.http4s.headers.`Content-Type`
import org.http4s.circe.CirceEntityCodec.*
import photography.domain.album.{AlbumId, ChapterId, PhotoId}
import photography.domain.layout.*
import photography.ports.cover.CoverRepository.Composition
import photography.usecases.cover.CoverManager
import photography.usecases.spread.SpreadManager
import java.util.UUID
import java.nio.charset.StandardCharsets

class PhotoPlacementApiSeamIntegrationTest extends CatsEffectSuite:
  private val album         = AlbumId(new UUID(0L, 1L))
  private val cover         = Cover(album, SpineOwner.Back, CoverSlot(CoverSlotId(new UUID(0L, 2L))), CoverSlot(CoverSlotId(new UUID(0L, 3L))))
  private val transform     = PhotoTransform(Shift(x = 12.5, y = -7.25), mirrored = true, zoom = 3.0)
  private val placement     = CoverPlacement(CoverPlacementId(new UUID(0L, 4L)), cover.front.id, PhotoId(new UUID(0L, 5L)), transform)
  private val second        = placement.copy(id = CoverPlacementId(new UUID(0L, 8L)), slot = cover.back.id, photo = PhotoId(new UUID(0L, 9L)))
  private val composition   = Composition(cover, placements = Vector(placement, second))
  private val coverPath     = s"/api/albums/${album.value}/cover"
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
  private val editRequests = Vector(
    (
      Method.POST,
      "/placements",
      Json.obj(
        "slotId"  -> Json.fromString(cover.front.id.value.toString),
        "photoId" -> Json.fromString(placement.photo.value.toString)
      )
    ),
    (Method.DELETE, s"/placements/${placement.id.value}", Json.obj()),
    (
      Method.PATCH,
      s"/placements/${placement.id.value}",
      Json.obj(
        "slotId" -> Json.fromString(cover.back.id.value.toString)
      )
    ),
    (
      Method.PATCH,
      s"/placements/${placement.id.value}",
      Json.obj(
        "transform" -> transformJson
      )
    )
  )

  test("should expose either cover spine, both side associations and empty compositions and dispatch every edit"):
    val expectedEdits = Vector(
      CoverManager.Edit.Place(cover.front.id, placement.photo),
      CoverManager.Edit.Remove(placement.id),
      CoverManager.Edit.Move(placement.id, cover.back.id),
      CoverManager.Edit.Adjust(placement.id, transform)
    )
    val cases = Vector(
      (SpineOwner.Back, "back", Vector(placement, second), Json.arr(expectedPlacement, expectedSecond)),
      (SpineOwner.Front, "front", Vector(placement, second), Json.arr(expectedPlacement, expectedSecond)),
      (SpineOwner.Front, "front", Vector.empty[CoverPlacement], Json.arr())
    )

    cases.traverse_ { case (owner, encodedOwner, placements, encodedPlacements) =>
      val saved        = Composition(cover.copy(spineOwner = owner), placements = placements)
      val expectedBody = Json.obj(
        "frontSlotId" -> Json.fromString(cover.front.id.value.toString),
        "backSlotId"  -> Json.fromString(cover.back.id.value.toString),
        "spineOwner"  -> Json.fromString(encodedOwner),
        "placements"  -> encodedPlacements
      )
      val api          = buildApi(CoverManager.Result.Saved(saved), coverRead = CoverManager.ReadResult.Found(saved))
      val saveCommands = editRequests.zip(expectedEdits).traverse { case ((method, suffix, value), expectedEdit) =>
        val editing = buildApi(CoverManager.Result.Saved(saved), maybeCoverEdit = expectedEdit.some)
        send(editing, Request[IO](method, Uri.unsafeFromString(coverPath + suffix)).withEntity(value))
      }

      for
        read  <- send(api, Request[IO](Method.GET, Uri.unsafeFromString(coverPath)))
        saved <- saveCommands
      yield
        assertEquals(read, (Status.Ok, expectedBody))
        assertEquals(saved, Vector.fill(editRequests.size)((Status.Ok, expectedBody)))
    }

  test("should expose every interior caption corner and empty composition and dispatch every edit"):
    val slot = SpreadSlot.Photo(
      PhotoSpreadSlotId(cover.front.id.value),
      SlotBox(x = 0.0, y = 0.0, width = 50.0, height = 100.0),
      bleed = Set.empty[SpreadEdge]
    )
    val spread = Spread.PhotosOnly(
      SpreadId(new UUID(0L, 6L)),
      SpreadTemplateId(new UUID(0L, 7L)),
      photoSlots = NonEmptyList.one(slot),
      placements = Vector.empty[SpreadPlacement]
    )
    val expectedEdits = Vector(
      SpreadManager.Edit.Place(slot.id, placement.photo),
      SpreadManager.Edit.Remove(SpreadPlacementId(placement.id.value)),
      SpreadManager.Edit.Move(SpreadPlacementId(placement.id.value), PhotoSpreadSlotId(cover.back.id.value)),
      SpreadManager.Edit.Adjust(SpreadPlacementId(placement.id.value), transform)
    )
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
        "id"         -> Json.fromString(spread.id.value.toString),
        "kind"       -> Json.fromString("photos_only"),
        "templateId" -> Json.fromString(spread.template.value.toString),
        "slots"      -> Json.arr(Json.obj(
          "id"     -> Json.fromString(slot.id.value.toString),
          "kind"   -> Json.fromString("photo"),
          "x"      -> Json.fromInt(0),
          "y"      -> Json.fromInt(0),
          "width"  -> Json.fromInt(50),
          "height" -> Json.fromInt(100)
        )),
        "placements" -> encodedPlacements
      )
      val api = buildApi(
        CoverManager.Result.Failed,
        spreadResult = SpreadManager.PlacementResult.Saved(saved),
        spreadRead = SpreadManager.PlacementReadResult.Found(saved)
      )
      val saveCommands = editRequests.zip(expectedEdits).traverse { case ((method, suffix, value), expectedEdit) =>
        val editing = buildApi(
          CoverManager.Result.Failed,
          spreadResult = SpreadManager.PlacementResult.Saved(saved),
          maybeSpreadEdit = expectedEdit.some
        )
        send(editing, Request[IO](method, Uri.unsafeFromString(spreadPath + suffix)).withEntity(value))
      }

      for
        read      <- send(api, Request[IO](Method.GET, Uri.unsafeFromString(spreadPath)))
        responses <- saveCommands
      yield
        assertEquals(read, (Status.Ok, expectedBody))
        assertEquals(responses, Vector.fill(editRequests.size)((Status.Ok, expectedBody)))
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
      val api      = buildApi(result)
      val expected = (status, Json.obj("code" -> Json.fromString(code)))
      send(api, Request[IO](Method.DELETE, Uri.unsafeFromString(s"$coverPath/placements/${placement.id.value}"))).map(actual =>
        assertEquals(actual, expected)
      )
    }
    val spreadFailures = spreadEdits.traverse_ { case (result, status, code) =>
      val api      = buildApi(CoverManager.Result.Failed, spreadResult = result)
      val expected = (status, Json.obj("code" -> Json.fromString(code)))
      send(api, Request[IO](Method.DELETE, Uri.unsafeFromString(s"$spreadPath/placements/${placement.id.value}"))).map(actual =>
        assertEquals(actual, expected)
      )
    }
    val missingSpreads = spreadReads.traverse_ { case (result, status, code) =>
      val api      = buildApi(CoverManager.Result.Failed, spreadRead = result)
      val expected = (status, Json.obj("code" -> Json.fromString(code)))
      send(api, Request[IO](Method.GET, Uri.unsafeFromString(spreadPath))).map(actual => assertEquals(actual, expected))
    }
    val missingCovers = coverReads.traverse_ { case (result, status, code) =>
      val api      = buildApi(CoverManager.Result.Failed, coverRead = result)
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
      val api = buildApi(CoverManager.Result.Failed, spreadRead = SpreadManager.PlacementReadResult.Found(spread))
      send(api, Request[IO](Method.GET, Uri.unsafeFromString(spreadPath))).map { case (status, body) =>
        assertEquals(status, Status.Ok)
        assertEquals(body.hcursor.get[String]("kind").toOption, kind.some)
        assertEquals(body.hcursor.get[String]("templateId").toOption, template.value.toString.some)
        assertEquals(body.hcursor.get[Vector[Json]]("slots").toOption.map(_.flatMap(_.hcursor.get[String]("kind").toOption)), slots.some)
      }
    }

  test("should reject removed operation envelopes and ambiguous placement field replacements"):
    val api = buildApi(CoverManager.Result.Failed)
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
    val missingFields     = editRequests.filterNot(_._1 === Method.DELETE).flatMap { case (method, suffix, request) =>
      request.asObject.toList.flatMap(_.keys).map(field => (method, suffix, request.mapObject(_.remove(field))))
    }
    val invalidFields = editRequests.flatMap { case (method, suffix, request) =>
      request.asObject.toList.flatMap(_.keys.filter(field => field.endsWith("Id"))).flatMap: field =>
        val values = invalidIdentities.map(Json.fromString) ++ Vector(Json.Null, Json.fromInt(1))
        values.map(value => (method, suffix, request.mapObject(_.add(field, value))))
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
      (Method.PATCH, s"/placements/${placement.id.value}", Json.obj("transform" -> value))
    val requests = Vector(Json.Null, Json.arr(), Json.obj()).map(value => (Method.POST, "/placements", value)) ++
      missingFields ++ invalidFields ++ adjustments
    val api           = buildApi(CoverManager.Result.Failed)
    val malformedBody = Stream.emits("{invalid json".getBytes(StandardCharsets.UTF_8)).covary[IO]
    val malformed     = Request[IO](Method.POST, Uri.unsafeFromString(s"$coverPath/placements")).withBodyStream(malformedBody)
      .putHeaders(`Content-Type`(MediaType.application.json))
    val decodedFailures = Vector(coverPath, spreadPath).flatTraverse: path =>
      requests.traverse { case (method, suffix, value) => send(api, Request[IO](method, Uri.unsafeFromString(path + suffix)).withEntity(value)) }

    for
      failures <- decodedFailures
      syntax   <- send(api, malformed)
    yield
      val responses = failures :+ syntax
      val feedback  = responses.map { case (status, body) => (status, hasFeedback(body)) }
      assertEquals(feedback, Vector.fill(responses.size)((Status.BadRequest, true)))

  test("should exclude noncanonical path identities without calling application services"):
    val invalidIdentities = Vector("0-0-0-0-1", "AAAAAAAA-AAAA-4AAA-AAAA-AAAAAAAAAAAA")
    val paths             = invalidIdentities.flatMap: id =>
      Vector(
        s"/api/albums/$id/cover/placements",
        s"/api/albums/$id/spreads/${new UUID(0L, 6L)}/placements",
        s"/api/albums/${album.value}/spreads/$id/placements"
      )
    val api      = buildApi(CoverManager.Result.Failed)
    val requests = paths.flatMap(path => Vector(Method.GET, Method.POST).map(method => Request[IO](method, Uri.unsafeFromString(path))))

    requests.traverse(request => api.orNotFound.run(request).map(_.status)).map: statuses =>
      assertEquals(statuses, Vector.fill(requests.size)(Status.NotFound))

  test("should dispatch domain-valid zoom outside the editing range to the application's rejection owner"):
    val outside = transform.copy(zoom = 6.0)
    val request = Json.obj(
      "transform" -> transformJson.mapObject(_.add("zoom", Json.fromInt(6)))
    )
    val expected = (Status.Conflict, Json.obj("code" -> Json.fromString("invalid_transform")))
    val covers   = buildApi(
      CoverManager.Result.Rejected(CoverManager.Failure.InvalidTransform),
      maybeCoverEdit = CoverManager.Edit.Adjust(placement.id, outside).some
    )
    val spreads = buildApi(
      CoverManager.Result.Failed,
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

  // scalafix:off DisableSyntax.defaultArgs
  private def buildApi(
      result: CoverManager.Result,
      spreadResult: SpreadManager.PlacementResult = SpreadManager.PlacementResult.SpreadNotFound,
      coverRead: CoverManager.ReadResult = CoverManager.ReadResult.Found(composition),
      spreadRead: SpreadManager.PlacementReadResult = SpreadManager.PlacementReadResult.SpreadNotFound,
      maybeCoverEdit: Option[CoverManager.Edit] = None,
      maybeSpreadEdit: Option[SpreadManager.Edit] = None
  ) =
    val covers = new CoverManager[IO]:
      override def read(id: AlbumId): IO[CoverManager.ReadResult] =
        assertEquals(id, album)
        coverRead.pure[IO]
      override def edit(id: AlbumId, request: CoverManager.Edit): IO[CoverManager.Result] =
        assertEquals(id, album)
        maybeCoverEdit.foreach(expected => assertEquals(request, expected))
        result.pure[IO]
    val spreads = new SpreadManager[IO]:
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
      override def delete(album: AlbumId, spread: SpreadId, confirmPlacements: Boolean): IO[SpreadManager.DeleteResult] =
        IO.raiseError[SpreadManager.DeleteResult](new AssertionError("Unexpected structural delete"))
      override def readPlacements(id: AlbumId, spread: SpreadId): IO[SpreadManager.PlacementReadResult] =
        assertEquals(id, album)
        assertEquals(spread, SpreadId(new UUID(0L, 6L)))
        spreadRead.pure[IO]
      override def editPlacements(id: AlbumId, spread: SpreadId, request: SpreadManager.Edit): IO[SpreadManager.PlacementResult] =
        assertEquals(id, album)
        assertEquals(spread, SpreadId(new UUID(0L, 6L)))
        maybeSpreadEdit.foreach(expected => assertEquals(request, expected))
        spreadResult.pure[IO]
    PhotoPlacementApi.make(spreads, covers)
  // scalafix:on DisableSyntax.defaultArgs

  private def send(api: HttpRoutes[IO], request: Request[IO]) =
    for
      response <- api.orNotFound.run(request)
      body     <- response.as[Json]
    yield
      assertEquals(response.contentType.map(_.mediaType), MediaType.application.json.some)
      if request.method === Method.GET && response.status === Status.Ok then
        assertEquals(response.headers.get[`Cache-Control`], `Cache-Control`(CacheDirective.`no-store`).some)
      (response.status, body)

  private def hasFeedback(body: Json) =
    body.asObject.exists(_.keys.toSet === Set("message")) && body.hcursor.get[String]("message").toOption.exists(_.nonEmpty)
