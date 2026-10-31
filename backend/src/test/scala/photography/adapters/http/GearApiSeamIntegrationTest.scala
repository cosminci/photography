package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.http4s.{HttpApp, MediaType, Method, Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import photography.domain.gear.*
import photography.usecases.gear.GearManager
import photography.usecases.gear.GearManager.*
import java.util.UUID

final class GearApiSeamIntegrationTest extends CatsEffectSuite:
  private val camera     = Camera(CameraId(value = new UUID(0L, 1L)), code = " code ", friendlyName = "\nName ", CameraKind.FixedLens)
  private val lens       = Lens(LensId(value = new UUID(0L, 2L)), code = "lens", friendlyName = "Lens", LensMount.SonyE, SensorFormat.FullFrame)
  private val cameraPath = s"/api/cameras/${camera.id.value}"
  private val lensPath   = s"/api/lenses/${lens.id.value}"
  private val fixedInput = """{"code":" code ","name":"\nName ","kind":"fixed_lens"}"""
  private val lensInput  = """{"code":"lens","name":"Lens","mount":"sony_e","coverage":"full_frame"}"""

  test("should publish ordered catalogs with every camera optic and lens enum and explicit empty success"):
    val cameras = Vector(camera) ++ LensMount.values.toVector.flatMap: mount =>
      SensorFormat.values.toVector.map: sensor =>
        val id                   = CameraId(value = new UUID(1L, (mount.ordinal * 2 + sensor.ordinal).toLong))
        val code: CameraExifCode = s"body-${mount.ordinal}-${sensor.ordinal}".refineUnsafe
        camera.copy(id = id, code = code, kind = CameraKind.InterchangeableLens(mount, sensor))
    val lenses = LensMount.values.toVector.flatMap: mount =>
      SensorFormat.values.toVector.map: coverage =>
        val id                 = LensId(value = new UUID(2L, (mount.ordinal * 2 + coverage.ordinal).toLong))
        val code: LensExifCode = s"lens-${mount.ordinal}-${coverage.ordinal}".refineUnsafe
        lens.copy(id = id, code = code, mount = mount, coverage = coverage)
    val catalog  = GearCatalog(cameras = cameras, lenses = lenses)
    val refs     = Refs()
    val server   = buildGearApi(refs, catalogResult = CatalogResult.Found(catalog))
    val expected = Json.obj("cameras" -> Json.arr(cameras.map(encodeCamera)*), "lenses" -> Json.arr(lenses.map(encodeLens)*))

    for
      response <- send(server, Method.GET, path = "/api/gear", body = "")
      empty    <- send(buildGearApi(Refs()), Method.GET, path = "/api/gear", body = "")
      calls    <- refs.calls.get
    yield
      assertEquals(response, (Status.Ok, expected.noSpaces, MediaType.application.json.some, "no-store".some))
      assertEquals(empty, (Status.Ok, """{"cameras":[],"lenses":[]}""", MediaType.application.json.some, "no-store".some))
      assertEquals(calls, expected = Vector(Call.Read))

  test("should create and completely edit typed optics without trimming or reading after acknowledgement"):
    val cameras = Vector(camera) ++ LensMount.values.toVector.flatMap: mount =>
      SensorFormat.values.toVector.map(sensor => camera.copy(kind = CameraKind.InterchangeableLens(mount, sensor)))
    val lenses = LensMount.values.toVector.flatMap: mount =>
      SensorFormat.values.toVector.map(coverage => lens.copy(mount = mount, coverage = coverage))

    val checkCameras = cameras.traverse_ { value =>
      val refs           = Refs()
      val server         = buildGearApi(refs, cameraCreate = CameraCreateResult.Created(value))
      val expectedCamera = encodeCamera(value)
      val creationInput  = expectedCamera.mapObject(_.remove(key = "id"))
      val updateInput    = creationInput.deepMerge(Json.obj("id" -> Json.fromString(value = new UUID(0L, 99L).toString)))
      val expectedCalls  = Vector(Call.CreateCamera(value.code, value.friendlyName, value.kind), Call.UpdateCamera(value))

      for
        created <- send(server, Method.POST, path = "/api/cameras", body = creationInput.noSpaces)
        updated <- send(server, Method.PUT, path = cameraPath, body = updateInput.noSpaces)
        calls   <- refs.calls.get
      yield
        assertEquals(created, (Status.Created, expectedCamera.noSpaces, MediaType.application.json.some, None))
        assertEquals(updated, (Status.NoContent, "", None, None))
        assertEquals(calls, expectedCalls)
    }
    val checkLenses = lenses.traverse_ { value =>
      val refs          = Refs()
      val server        = buildGearApi(refs, lensCreate = LensCreateResult.Created(value))
      val expectedLens  = encodeLens(value)
      val creationInput = expectedLens.mapObject(_.remove(key = "id"))
      val updateInput   = creationInput.deepMerge(Json.obj("id" -> Json.fromString(value = new UUID(0L, 99L).toString)))
      val expectedCalls = Vector(Call.CreateLens(value.code, value.friendlyName, value.mount, value.coverage), Call.UpdateLens(value))

      for
        created <- send(server, Method.POST, path = "/api/lenses", body = creationInput.noSpaces)
        updated <- send(server, Method.PUT, path = lensPath, body = updateInput.noSpaces)
        calls   <- refs.calls.get
      yield
        assertEquals(created, (Status.Created, expectedLens.noSpaces, MediaType.application.json.some, None))
        assertEquals(updated, (Status.NoContent, "", None, None))
        assertEquals(calls, expectedCalls)
    }

    checkCameras >> checkLenses

  Vector(
    CreationScenario(
      cameraResult = CameraCreateResult.Conflict,
      lensResult = LensCreateResult.Conflict,
      status = Status.Conflict,
      code = "catalog_conflict"
    ),
    CreationScenario(
      cameraResult = CameraCreateResult.Failed,
      lensResult = LensCreateResult.Failed,
      status = Status.InternalServerError,
      code = "service_failure"
    ),
    CreationScenario(
      cameraResult = CameraCreateResult.Uncertain,
      lensResult = LensCreateResult.Uncertain,
      status = Status.InternalServerError,
      code = "mutation_uncertain"
    )
  ).foreach { case CreationScenario(cameraResult, lensResult, status, code) =>
    test(s"should translate camera creation $cameraResult and lens creation $lensResult"):
      val refs          = Refs()
      val server        = buildGearApi(refs, cameraCreate = cameraResult, lensCreate = lensResult)
      val expectedCalls = Vector(
        Call.CreateCamera(camera.code, camera.friendlyName, camera.kind),
        Call.CreateLens(lens.code, lens.friendlyName, lens.mount, lens.coverage)
      )

      for
        cameraResponse <- send(server, Method.POST, path = "/api/cameras", body = fixedInput)
        lensResponse   <- send(server, Method.POST, path = "/api/lenses", body = lensInput)
        calls          <- refs.calls.get
      yield
        assertEquals(cameraResponse, encodeErrorResponse(status, code = code))
        assertEquals(lensResponse, encodeErrorResponse(status, code = code))
        assertEquals(calls, expectedCalls)
  }
  Vector(
    UpdateScenario(cameraResult = CameraUpdateResult.Updated, lensResult = LensUpdateResult.Updated, status = Status.NoContent, code = ""),
    UpdateScenario(cameraResult = CameraUpdateResult.NotFound, lensResult = LensUpdateResult.NotFound, status = Status.NotFound, code = "not_found"),
    UpdateScenario(
      cameraResult = CameraUpdateResult.Conflict,
      lensResult = LensUpdateResult.Conflict,
      status = Status.Conflict,
      code = "catalog_conflict"
    ),
    UpdateScenario(
      cameraResult = CameraUpdateResult.InvalidatesSelection,
      lensResult = LensUpdateResult.InvalidatesSelection,
      status = Status.Conflict,
      code = "invalidates_selection"
    ),
    UpdateScenario(
      cameraResult = CameraUpdateResult.Failed,
      lensResult = LensUpdateResult.Failed,
      status = Status.InternalServerError,
      code = "service_failure"
    ),
    UpdateScenario(
      cameraResult = CameraUpdateResult.Uncertain,
      lensResult = LensUpdateResult.Uncertain,
      status = Status.InternalServerError,
      code = "mutation_uncertain"
    )
  ).foreach { case UpdateScenario(cameraResult, lensResult, status, code) =>
    test(s"should translate camera update $cameraResult and lens update $lensResult"):
      val refs   = Refs()
      val server = buildGearApi(refs, cameraUpdate = cameraResult, lensUpdate = lensResult)

      for
        cameraResponse <- send(server, Method.PUT, path = cameraPath, body = fixedInput)
        lensResponse   <- send(server, Method.PUT, path = lensPath, body = lensInput)
        calls          <- refs.calls.get
      yield
        assertEquals(cameraResponse, encodeMutationResponse(status, code = code, resource = "camera"))
        assertEquals(lensResponse, encodeMutationResponse(status, code = code, resource = "lens"))
        assertEquals(calls, expected = Vector(Call.UpdateCamera(camera), Call.UpdateLens(lens)))
  }
  Vector(
    DeletionScenario(cameraResult = CameraDeleteResult.Deleted, lensResult = LensDeleteResult.Deleted, status = Status.NoContent, code = ""),
    DeletionScenario(
      cameraResult = CameraDeleteResult.NotFound,
      lensResult = LensDeleteResult.NotFound,
      status = Status.NotFound,
      code = "not_found"
    ),
    DeletionScenario(cameraResult = CameraDeleteResult.Used, lensResult = LensDeleteResult.Used, status = Status.Conflict, code = "used"),
    DeletionScenario(
      cameraResult = CameraDeleteResult.Failed,
      lensResult = LensDeleteResult.Failed,
      status = Status.InternalServerError,
      code = "service_failure"
    ),
    DeletionScenario(
      cameraResult = CameraDeleteResult.Uncertain,
      lensResult = LensDeleteResult.Uncertain,
      status = Status.InternalServerError,
      code = "mutation_uncertain"
    )
  ).foreach { case DeletionScenario(cameraResult, lensResult, status, code) =>
    test(s"should translate camera deletion $cameraResult and lens deletion $lensResult"):
      val refs   = Refs()
      val server = buildGearApi(refs, cameraDelete = cameraResult, lensDelete = lensResult)

      for
        cameraResponse <- send(server, Method.DELETE, path = cameraPath, body = "")
        lensResponse   <- send(server, Method.DELETE, path = lensPath, body = "")
        calls          <- refs.calls.get
      yield
        assertEquals(cameraResponse, encodeMutationResponse(status, code = code, resource = "camera"))
        assertEquals(lensResponse, encodeMutationResponse(status, code = code, resource = "lens"))
        assertEquals(calls, expected = Vector(Call.DeleteCamera(camera.id), Call.DeleteLens(lens.id)))
  }

  test("should reject malformed bodies and contradictory fixed optics before calling the application"):
    val common = Vector(
      InvalidGear(body = "{", message = "Supply a valid JSON gear object"),
      InvalidGear(body = "[]", message = "DecodingFailure at .code: Missing required field"),
      InvalidGear(body = "null", message = "DecodingFailure at .code: Missing required field"),
      InvalidGear(body = "{}", message = "DecodingFailure at .code: Missing required field"),
      InvalidGear(body = """{"code":"","name":"Name"}""", message = "DecodingFailure at .code: !(Should be empty)"),
      InvalidGear(body = """{"code":1,"name":"Name"}""", message = "DecodingFailure at .code: Got value '1' with wrong type, expecting string"),
      InvalidGear(body = """{"code":"code","name":null}""", message = "DecodingFailure at .name: Got value 'null' with wrong type, expecting string")
    )
    val fixedJson          = encodeCamera(camera).mapObject(_.remove(key = "id"))
    val lensJsonInput      = encodeLens(lens).mapObject(_.remove(key = "id"))
    val invalidFixedFields = Vector("code", "name", "kind").flatMap { field =>
      val emptyMessage = if field === "kind" then "DecodingFailure at : Choose fixed_lens or interchangeable_lens"
      else s"DecodingFailure at .$field: !(Should be empty)"
      buildInvalidFields(fixedJson, field = field, emptyMessage = emptyMessage)
    }
    val invalidLensFields = Vector("code", "name", "mount", "coverage").flatMap { field =>
      val emptyMessage = field match
        case "mount"    => "DecodingFailure at .mount: Choose sony_e or nikon_z"
        case "coverage" => "DecodingFailure at .coverage: Choose aps_c or full_frame"
        case _          => s"DecodingFailure at .$field: !(Should be empty)"
      buildInvalidFields(lensJsonInput, field = field, emptyMessage = emptyMessage)
    }
    val invalidCameras = common ++ invalidFixedFields ++ Vector(
      InvalidGear(
        body = """{"code":"code","name":"Name","kind":"unknown"}""",
        message = "DecodingFailure at : Choose fixed_lens or interchangeable_lens"
      ),
      InvalidGear(
        body = """{"code":"code","name":"Name","kind":1}""",
        message = "DecodingFailure at .kind: Got value '1' with wrong type, expecting string"
      ),
      InvalidGear(body = """{"code":"code","name":"","kind":"fixed_lens"}""", message = "DecodingFailure at .name: !(Should be empty)"),
      InvalidGear(
        body = """{"code":"code","name":"Name","kind":"fixed_lens","mount":null}""",
        message = "DecodingFailure at : Fixed-lens cameras must omit mount and sensorFormat"
      ),
      InvalidGear(
        body = """{"code":"code","name":"Name","kind":"fixed_lens","sensorFormat":"aps_c"}""",
        message = "DecodingFailure at : Fixed-lens cameras must omit mount and sensorFormat"
      ),
      InvalidGear(
        body = """{"code":"code","name":"Name","kind":"interchangeable_lens"}""",
        message = "DecodingFailure at .mount: Missing required field"
      ),
      InvalidGear(
        body = """{"code":"code","name":"Name","kind":"interchangeable_lens","sensorFormat":"aps_c"}""",
        message = "DecodingFailure at .mount: Missing required field"
      ),
      InvalidGear(
        body = """{"code":"code","name":"Name","kind":"interchangeable_lens","mount":null,"sensorFormat":"aps_c"}""",
        message = "DecodingFailure at .mount: Got value 'null' with wrong type, expecting string"
      ),
      InvalidGear(
        body = """{"code":"code","name":"Name","kind":"interchangeable_lens","mount":"sony_e"}""",
        message = "DecodingFailure at .sensorFormat: Missing required field"
      ),
      InvalidGear(
        body = """{"code":"code","name":"Name","kind":"interchangeable_lens","mount":"sony_e","sensorFormat":null}""",
        message = "DecodingFailure at .sensorFormat: Got value 'null' with wrong type, expecting string"
      ),
      InvalidGear(
        body = """{"code":"code","name":"Name","kind":"interchangeable_lens","mount":"bad","sensorFormat":"aps_c"}""",
        message = "DecodingFailure at .mount: Choose sony_e or nikon_z"
      ),
      InvalidGear(
        body = """{"code":"code","name":"Name","kind":"interchangeable_lens","mount":1,"sensorFormat":"aps_c"}""",
        message = "DecodingFailure at .mount: Got value '1' with wrong type, expecting string"
      ),
      InvalidGear(
        body = """{"code":"code","name":"Name","kind":"interchangeable_lens","mount":"sony_e","sensorFormat":false}""",
        message = "DecodingFailure at .sensorFormat: Got value 'false' with wrong type, expecting string"
      ),
      InvalidGear(
        body = """{"code":"code","name":"Name","kind":"interchangeable_lens","mount":"sony_e","sensorFormat":"bad"}""",
        message = "DecodingFailure at .sensorFormat: Choose aps_c or full_frame"
      )
    )
    val invalidLenses = common ++ invalidLensFields ++ Vector(
      InvalidGear(
        body = """{"code":"code","name":"","mount":"sony_e","coverage":"aps_c"}""",
        message = "DecodingFailure at .name: !(Should be empty)"
      ),
      InvalidGear(body = """{"code":"code","name":"Name","mount":"sony_e"}""", message = "DecodingFailure at .coverage: Missing required field"),
      InvalidGear(
        body = """{"code":"code","name":"Name","mount":null,"coverage":"aps_c"}""",
        message = "DecodingFailure at .mount: Got value 'null' with wrong type, expecting string"
      ),
      InvalidGear(
        body = """{"code":"code","name":"Name","mount":"bad","coverage":"aps_c"}""",
        message = "DecodingFailure at .mount: Choose sony_e or nikon_z"
      ),
      InvalidGear(
        body = """{"code":"code","name":"Name","mount":"sony_e","coverage":1}""",
        message = "DecodingFailure at .coverage: Got value '1' with wrong type, expecting string"
      ),
      InvalidGear(
        body = """{"code":"code","name":"Name","mount":"sony_e","coverage":"bad"}""",
        message = "DecodingFailure at .coverage: Choose aps_c or full_frame"
      )
    )
    val refs   = Refs()
    val server = buildGearApi(refs)

    Vector(Method.POST, Method.PUT).traverse_ { method =>
      val cameraTarget = if method === Method.POST then "/api/cameras" else cameraPath
      val lensTarget   = if method === Method.POST then "/api/lenses" else lensPath
      val requests     = invalidCameras.map(scenario => InvalidRequest(path = cameraTarget, invalid = scenario)) ++
        invalidLenses.map(scenario => InvalidRequest(path = lensTarget, invalid = scenario))

      requests.traverse_ { case InvalidRequest(path, InvalidGear(body, message)) =>
        val expected = (Status.BadRequest, Json.obj("message" -> Json.fromString(value = message)).noSpaces, MediaType.application.json.some, None)

        for
          response <- send(server, method, path = path, body = body)
          calls    <- refs.calls.get
        yield
          assertEquals(response, expected, clue = s"$method $path $body")
          assertEquals(calls, expected = Vector.empty[Call])
      }
    }

  test("should reject noncanonical route identities and keep read failure distinct from empty success"):
    val refs     = Refs()
    val server   = buildGearApi(refs, catalogResult = CatalogResult.Failed)
    val ids      = Vector("bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A")
    val requests = Vector(Method.PUT, Method.DELETE).flatMap(method =>
      ids.flatMap(id =>
        Vector(
          MissingTarget(method = method, path = s"/api/cameras/$id", resource = "camera"),
          MissingTarget(method = method, path = s"/api/lenses/$id", resource = "lens")
        )
      )
    )
    val readMissingTargets = requests.traverse_ { case MissingTarget(method, path, resource) =>
      val expected = encodeErrorResponse(Status.NotFound, code = s"${resource}_not_found")

      send(server, method, path = path, body = "{}").map(response => assertEquals(response, expected, clue = s"$method $path"))
    }

    for
      _      <- readMissingTargets
      failed <- send(server, Method.GET, path = "/api/gear", body = "")
      calls  <- refs.calls.get
    yield
      assertEquals(failed, encodeErrorResponse(Status.InternalServerError, code = "service_failure"))
      assertEquals(calls, expected = Vector(Call.Read))

  private enum Call:
    case Read
    case CreateCamera(code: CameraExifCode, name: CameraName, kind: CameraKind)
    case UpdateCamera(value: Camera)
    case DeleteCamera(id: CameraId)
    case CreateLens(code: LensExifCode, name: LensName, mount: LensMount, coverage: SensorFormat)
    case UpdateLens(value: Lens)
    case DeleteLens(id: LensId)

  final private case class InvalidGear(body: String, message: String)
  final private case class InvalidRequest(path: String, invalid: InvalidGear)
  final private case class MissingTarget(method: Method, path: String, resource: String)
  final private case class CreationScenario(cameraResult: CameraCreateResult, lensResult: LensCreateResult, status: Status, code: String)
  final private case class UpdateScenario(cameraResult: CameraUpdateResult, lensResult: LensUpdateResult, status: Status, code: String)
  final private case class DeletionScenario(cameraResult: CameraDeleteResult, lensResult: LensDeleteResult, status: Status, code: String)

  private def buildInvalidFields(input: Json, field: String, emptyMessage: String) =
    Vector(
      InvalidGear(body = input.mapObject(_.remove(key = field)).noSpaces, message = s"DecodingFailure at .$field: Missing required field"),
      InvalidGear(
        body = input.mapObject(_.add(key = field, value = Json.Null)).noSpaces,
        message = s"DecodingFailure at .$field: Got value 'null' with wrong type, expecting string"
      ),
      InvalidGear(
        body = input.mapObject(_.add(key = field, value = Json.fromInt(value = 1))).noSpaces,
        message = s"DecodingFailure at .$field: Got value '1' with wrong type, expecting string"
      ),
      InvalidGear(body = input.mapObject(_.add(key = field, value = Json.fromString(value = ""))).noSpaces, message = emptyMessage)
    )

  final private case class Refs():
    val calls: Ref[IO, Vector[Call]] = Ref.unsafe(a = Vector.empty[Call])

  // scalafix:off DisableSyntax.defaultArgs
  private def buildGearApi(
      refs: Refs,
      catalogResult: CatalogResult = CatalogResult.Found(GearCatalog(cameras = Vector.empty[Camera], lenses = Vector.empty[Lens])),
      cameraCreate: CameraCreateResult = CameraCreateResult.Created(camera),
      cameraUpdate: CameraUpdateResult = CameraUpdateResult.Updated,
      cameraDelete: CameraDeleteResult = CameraDeleteResult.Deleted,
      lensCreate: LensCreateResult = LensCreateResult.Created(lens),
      lensUpdate: LensUpdateResult = LensUpdateResult.Updated,
      lensDelete: LensDeleteResult = LensDeleteResult.Deleted
  ) =
    val manager = new GearManager[IO]:
      override def readCatalog: IO[CatalogResult] = refs.calls.update(_ :+ Call.Read).as(catalogResult)
      override def createCamera(code: CameraExifCode, name: CameraName, kind: CameraKind): IO[CameraCreateResult] =
        refs.calls.update(_ :+ Call.CreateCamera(code, name, kind)).as(cameraCreate)
      override def updateCamera(value: Camera): IO[CameraUpdateResult] = refs.calls.update(_ :+ Call.UpdateCamera(value)).as(cameraUpdate)
      override def deleteCamera(id: CameraId): IO[CameraDeleteResult]  = refs.calls.update(_ :+ Call.DeleteCamera(id)).as(cameraDelete)
      override def createLens(code: LensExifCode, name: LensName, mount: LensMount, coverage: SensorFormat): IO[LensCreateResult] =
        refs.calls.update(_ :+ Call.CreateLens(code, name, mount, coverage)).as(lensCreate)
      override def updateLens(value: Lens): IO[LensUpdateResult] = refs.calls.update(_ :+ Call.UpdateLens(value)).as(lensUpdate)
      override def deleteLens(id: LensId): IO[LensDeleteResult]  = refs.calls.update(_ :+ Call.DeleteLens(id)).as(lensDelete)
      override def validateSelection(catalog: GearCatalog, selection: GearSelection): ValidationResult =
        fail(message = "HTTP must not validate business selections")
    GearApi.make(manager).routes.orNotFound
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

  private def encodeMutationResponse(status: Status, code: String, resource: String) =
    if status === Status.NoContent then (status, "", None, None)
    else encodeErrorResponse(status, code = if code === "not_found" || code === "used" then s"${resource}_$code" else code)

  private def encodeErrorResponse(status: Status, code: String) =
    (status, Json.obj("code" -> Json.fromString(value = code)).noSpaces, MediaType.application.json.some, None)

  private def encodeCamera(value: Camera) =
    val optics = value.kind match
      case CameraKind.FixedLens                          => Vector("kind" -> Json.fromString(value = "fixed_lens"))
      case CameraKind.InterchangeableLens(mount, sensor) =>
        Vector(
          "kind"         -> Json.fromString(value = "interchangeable_lens"),
          "mount"        -> Json.fromString(value = encodeMount(mount)),
          "sensorFormat" -> Json.fromString(value = encodeSensor(sensor))
        )
    Json.obj((Vector(
      "id"   -> Json.fromString(value = value.id.value.toString),
      "code" -> Json.fromString(value = value.code),
      "name" -> Json.fromString(value = value.friendlyName)
    ) ++ optics)*)

  private def encodeLens(value: Lens) =
    Json.obj(
      "id"       -> Json.fromString(value = value.id.value.toString),
      "code"     -> Json.fromString(value = value.code),
      "name"     -> Json.fromString(value = value.friendlyName),
      "mount"    -> Json.fromString(value = encodeMount(value.mount)),
      "coverage" -> Json.fromString(value = encodeSensor(value.coverage))
    )

  private def encodeMount(value: LensMount) = value match
    case LensMount.SonyE  => "sony_e"
    case LensMount.NikonZ => "nikon_z"

  private def encodeSensor(value: SensorFormat) = value match
    case SensorFormat.ApsC      => "aps_c"
    case SensorFormat.FullFrame => "full_frame"
