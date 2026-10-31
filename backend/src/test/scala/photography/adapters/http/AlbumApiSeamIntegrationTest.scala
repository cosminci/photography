package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.parse
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.http4s.{HttpApp, MediaType, Method, Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import photography.domain.album.{Album, AlbumAuthors, AlbumId, AlbumTitle, AlbumYear, Author, Chapter}
import photography.domain.layout.*
import photography.usecases.album.AlbumManager
import photography.usecases.album.AlbumManager.*
import photography.ports.album.AlbumRepository
import org.http4s.headers.`Cache-Control`
import org.http4s.CacheDirective

import java.time.Instant
import java.util.UUID

final class AlbumApiSeamIntegrationTest extends CatsEffectSuite:
  private val id    = AlbumId(new UUID(0L, 1L))
  private val start =
    val templateId      = SpreadTemplateId(new UUID(0L, 2L))
    val textSlot        = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 4L)), SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0))
    val emptyPhotoSlots = Vector.empty[SpreadSlot.Photo]
    val emptyPlacements = Vector.empty[SpreadPlacement]
    Spread.AlbumStart(SpreadId(new UUID(0L, 3L)), templateId, photoSlots = emptyPhotoSlots, textSlot, placements = emptyPlacements)
  private val album =
    val createdAt         = Instant.parse("2026-10-24T10:00:00.123Z")
    val emptyChapters     = Vector.empty[Chapter]
    val title: AlbumTitle = "Journey of the Shepherds 2026"
    Album(id, year = 2026, title, synopsis = "A synopsis", authors = Author.all, createdAt, start, chapters = emptyChapters)
  private val metadata      = s"""{"id":"${id.value}","year":2026,"title":"Journey of the Shepherds 2026","createdAt":"2026-10-24T10:00:00.123Z"}"""
  private val albumPath     = s"/api/albums/${id.value}"
  private val jsonMediaType = MediaType.application.json.some

  Vector("Cluj — 山\nSecond line", "", " \t\n ").foreach { text =>
    val savedAlbum = album.copy(synopsis = text)
    val result     = SaveSynopsisResult.Saved(savedAlbum)
    test(s"should deliver ${result.productPrefix} synopsis with exact text ${Json.fromString(text).noSpaces}"):
      val refs     = Refs()
      val server   = buildAlbumApi(refs, synopsisResult = result.pure[IO])
      val expected = json(metadata).deepMerge(Json.obj(
        "synopsis" -> Json.fromString(text),
        "authors"  -> Json.arr(
          Json.fromString("Cosmin"),
          Json.fromString("Mara"),
          Json.fromString("Iacob")
        )
      ))
      val requestBody = Json.obj("text" -> Json.fromString(text)).noSpaces

      for
        response     <- send(server, Method.PUT, path = s"$albumPath/synopsis", body = requestBody)
        contentCalls <- refs.contentCalls.get
        otherCalls   <- refs.calls.get
      yield
        assertEquals(response, ApiResponse(Status.Ok, jsonMediaType, expected.some))
        assertEquals(contentCalls, Vector(ContentCall.Synopsis(id, text = text)))
        assertEquals(otherCalls, Vector.empty[String])
  }

  Author.all.subsets().filter(_.nonEmpty).foreach { selection =>
    val selected              = Author.values.toVector.filter(selection.contains)
    val encodedAuthors        = Json.arr(selected.map(encodeAuthor)*)
    val authors: AlbumAuthors = selection.refineUnsafe
    val result                = SaveAuthorsResult.Saved(album.copy(authors = authors))
    test(s"should deliver ${result.productPrefix} authors ${encodedAuthors.noSpaces} in canonical display order"):
      val refs        = Refs()
      val server      = buildAlbumApi(refs, authorsResult = result.pure[IO])
      val requestBody = Json.obj("authors" -> Json.arr(selected.reverse.map(encodeAuthor)*))
      val expected    = json(metadata).deepMerge(Json.obj("synopsis" -> Json.fromString(album.synopsis), "authors" -> encodedAuthors))

      for
        response     <- send(server, Method.PUT, path = s"$albumPath/authors", body = requestBody.noSpaces)
        contentCalls <- refs.contentCalls.get
        otherCalls   <- refs.calls.get
      yield
        assertEquals(response, ApiResponse(Status.Ok, jsonMediaType, expected.some))
        assertEquals(contentCalls, Vector(ContentCall.Authors(id, authors)))
        assertEquals(otherCalls, Vector.empty[String])
  }

  Vector(
    SynopsisScenario(SaveSynopsisResult.NotFound, Status.NotFound, code = "album_not_found"),
    SynopsisScenario(SaveSynopsisResult.Failed, Status.InternalServerError, code = "service_failure"),
    SynopsisScenario(SaveSynopsisResult.Uncertain, Status.InternalServerError, code = "mutation_uncertain")
  ).foreach { scenario =>
    test(s"should deliver synopsis ${scenario.result} without retry or postwrite read"):
      val refs   = Refs()
      val server = buildAlbumApi(refs, synopsisResult = scenario.result.pure[IO])

      for
        response     <- send(server, Method.PUT, path = s"$albumPath/synopsis", body = """{"text":"once"}""")
        contentCalls <- refs.contentCalls.get
        otherCalls   <- refs.calls.get
      yield
        val expected = ApiResponse(scenario.status, jsonMediaType, Json.obj("code" -> Json.fromString(scenario.code)).some)
        assertEquals(response, expected)
        assertEquals(contentCalls, Vector(ContentCall.Synopsis(id, text = "once")))
        assertEquals(otherCalls, Vector.empty[String])
  }

  Vector(
    AuthorsScenario(SaveAuthorsResult.NotFound, Status.NotFound, code = "album_not_found"),
    AuthorsScenario(SaveAuthorsResult.Failed, Status.InternalServerError, code = "service_failure"),
    AuthorsScenario(SaveAuthorsResult.Uncertain, Status.InternalServerError, code = "mutation_uncertain")
  ).foreach { scenario =>
    test(s"should deliver authors ${scenario.result} without retry or postwrite read"):
      val refs                   = Refs()
      val selected: AlbumAuthors = Set(Author.Mara).refineUnsafe
      val server                 = buildAlbumApi(refs, authorsResult = scenario.result.pure[IO])

      for
        response     <- send(server, Method.PUT, path = s"$albumPath/authors", body = """{"authors":["Mara"]}""")
        contentCalls <- refs.contentCalls.get
        otherCalls   <- refs.calls.get
      yield
        val expected = ApiResponse(scenario.status, jsonMediaType, Json.obj("code" -> Json.fromString(scenario.code)).some)
        assertEquals(response, expected)
        assertEquals(contentCalls, Vector(ContentCall.Authors(id, selected)))
        assertEquals(otherCalls, Vector.empty[String])
  }

  test("should reject malformed content and noncanonical targets before any application call"):
    val refs             = Refs()
    val server           = buildAlbumApi(refs)
    val malformedText    = Vector("{", "[]", "null", "{}", """{"text":null}""", """{"text":1}""", """{"text":[]}""")
    val malformedAuthors = Vector(
      "{",
      "[]",
      "null",
      "{}",
      """{"authors":null}""",
      """{"authors":1}""",
      """{"authors":[]}""",
      """{"authors":["Mara","Mara"]}""",
      """{"authors":["Unknown"]}""",
      """{"authors":[null]}"""
    )
    val invalidRequests = malformedText.map(value => InvalidContent(resource = "synopsis", body = value, message = "Supply a text string")) ++
      malformedAuthors.map(value =>
        InvalidContent(
          resource = "authors",
          body = value,
          message = "Select nonempty unique authors from Cosmin, Mara and Iacob"
        )
      )
    val checkPayloads = invalidRequests.traverse_ { scenario =>
      val expected = ApiResponse(Status.BadRequest, jsonMediaType, Json.obj("message" -> Json.fromString(scenario.message)).some)
      send(server, Method.PUT, path = s"$albumPath/${scenario.resource}", body = scenario.body).map(response => assertEquals(response, expected))
    }
    val checkTargets = Vector("bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A").traverse_ { target =>
      Vector("synopsis", "authors").traverse_ { resource =>
        val expected = ApiResponse(Status.NotFound, jsonMediaType, json("""{"code":"album_not_found"}""").some)
        send(server, Method.PUT, path = s"/api/albums/$target/$resource", body = "{}").map(response => assertEquals(response, expected))
      }
    }

    for
      _            <- checkPayloads
      _            <- checkTargets
      contentCalls <- refs.contentCalls.get
      otherCalls   <- refs.calls.get
    yield
      assertEquals(contentCalls, Vector.empty[ContentCall])
      assertEquals(otherCalls, Vector.empty[String])

  test("should read complete lightweight album metadata without invoking an aggregate operation"):
    val refs                          = Refs()
    val savedSynopsis                 = "Ș 日本語\nSecond line"
    val selectedAuthors: AlbumAuthors = Set(Author.Mara).refineUnsafe
    val savedMetadata                 = AlbumRepository.Metadata(album.id, album.year, album.title, savedSynopsis, selectedAuthors, album.createdAt)
    val server                        = buildAlbumApi(refs, metadataResult = AlbumRepository.MetadataResult.Found(savedMetadata))
    val encodedSynopsis               = Json.fromString(value = savedSynopsis).noSpaces
    val expected                      = json(
      s"""{"id":"${id.value}","year":2026,"title":"Journey of the Shepherds 2026",
         "synopsis":$encodedSynopsis,"authors":["Mara"],"createdAt":"2026-10-24T10:00:00.123Z"}"""
    )

    for
      response <- server.run(Request[IO](Method.GET, Uri.unsafeFromString(albumPath)))
      body     <- response.as[String]
      calls    <- refs.calls.get
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(json(body), expected)
      assertEquals(response.headers.get[`Cache-Control`], `Cache-Control`(CacheDirective.`no-store`).some)
      assertEquals(calls, Vector(s"metadata:${id.value}"))

  test("should preserve missing metadata and reject noncanonical identities before reading"):
    val refs   = Refs()
    val server = buildAlbumApi(refs, metadataResult = AlbumRepository.MetadataResult.NotFound)

    for
      missing <- send(server, Method.GET, albumPath, "")
      invalid <- send(server, Method.GET, "/api/albums/1-1-1-1-1", "")
      calls   <- refs.calls.get
    yield
      val expected = ApiResponse(Status.NotFound, jsonMediaType, json("""{"code":"album_not_found"}""").some)
      assertEquals(missing, expected)
      assertEquals(invalid, expected)
      assertEquals(calls, Vector(s"metadata:${id.value}"))

  test("should expose metadata database failures rather than empty successful responses"):
    val server = buildAlbumApi(Refs(), metadataResult = AlbumRepository.MetadataResult.Failed(AlbumRepository.Failure.DatabaseFailure))

    send(server, Method.GET, albumPath, "").map: response =>
      assertEquals(response, ApiResponse(Status.InternalServerError, jsonMediaType, json("""{"code":"service_failure"}""").some))

  test("should list exact album metadata and forward default and explicit pages"):
    val refs         = Refs()
    val page         = AlbumPage(albums = Vector(album), page = 2, pageSize = 6, totalAlbums = 7, totalPages = 2)
    val server       = buildAlbumApi(refs, listAlbumsResult = ListAlbumsResult.Listed(page))
    val paths        = Vector("/api/albums", "/api/albums?page=99", "/api/albums?page=0", "/api/albums?page=-1")
    val emptyServer  = buildAlbumApi(Refs())
    val expectedPage =
      parse(s"""{"albums":[$metadata],"page":2,"pageSize":6,"totalAlbums":7,"totalPages":2}""").fold(error => fail(error.message), identity)
    val expectedEmpty = parse("""{"albums":[],"page":1,"pageSize":6,"totalAlbums":0,"totalPages":0}""").fold(error => fail(error.message), identity)
    val expectedResponses = Vector.fill(paths.size)(ApiResponse(Status.Ok, jsonMediaType, expectedPage.some))

    for
      responses <- paths.traverse(path => send(server, Method.GET, path = path, body = ""))
      empty     <- send(emptyServer, Method.GET, path = "/api/albums", body = "")
      calls     <- refs.calls.get
    yield
      assertEquals(responses, expectedResponses)
      assertEquals(empty, ApiResponse(Status.Ok, jsonMediaType, expectedEmpty.some))
      assertEquals(calls, Vector("list:1", "list:99", "list:0", "list:-1"))

  test("should reject malformed and repeated page parameters without calling the application"):
    val refs    = Refs()
    val server  = buildAlbumApi(refs)
    val queries = Vector("page", "page=", "page=no", "page=1.5", "page=2147483648", "page=1&page=2", "page=1&page=no", "page=1&page")

    for
      responses <- queries.traverse(query => send(server, Method.GET, path = s"/api/albums?$query", body = ""))
      calls     <- refs.calls.get
    yield
      assertEquals(responses.map(_.status), Vector.fill(queries.size)(Status.BadRequest))
      assertEquals(responses.map(_.maybeContentType), Vector.fill(queries.size)(jsonMediaType))
      assert(responses.forall(hasValidationFeedback))
      assertEquals(calls, Vector.empty)

  test("should create with a required year and accept a trimmed free-form rename"):
    val refs             = Refs()
    val server           = buildAlbumApi(refs)
    val expectedMetadata = parse(metadata).fold(error => fail(error.message), identity).some

    for
      created <- send(server, Method.POST, path = "/api/albums", body = """{"year":2026}""")
      renamed <- send(server, Method.PATCH, path = albumPath, body = """{"title":"\tAsian capitals\n"}""")
      calls   <- refs.calls.get
    yield
      assertEquals(created, ApiResponse(Status.Created, jsonMediaType, expectedMetadata))
      assertEquals(renamed, ApiResponse(Status.Ok, jsonMediaType, expectedMetadata))
      assertEquals(calls, Vector("create:2026:Journey of the Shepherds 2026", s"rename:${id.value}:Asian capitals"))

  test("should return validation feedback for invalid title bodies without calling the application"):
    val refs         = Refs()
    val server       = buildAlbumApi(refs)
    val malformed    = Vector("{}", "{", "[]")
    val invalidYears =
      Vector("""{"title":"Coast"}""", """{"year":null}""", """{"year":"2026"}""", """{"year":2026.5}""", """{"year":1899}""", """{"year":2101}""")
    val invalidTitles = Vector("""{"title":""}""", """{"title":" \t\n "}""", """{"title":null}""", """{"title":3}""")
    val requests      = (malformed ++ invalidYears).map(body => (Method.POST, "/api/albums", body)) ++
      (malformed ++ invalidTitles).map(body => (Method.PATCH, albumPath, body))

    for
      responses <- requests.traverse { case (method, path, body) => send(server, method, path = path, body = body) }
      calls     <- refs.calls.get
    yield
      assertEquals(responses.map(_.status), Vector.fill(requests.size)(Status.BadRequest))
      assertEquals(responses.map(_.maybeContentType), Vector.fill(requests.size)(jsonMediaType))
      assert(responses.forall(hasValidationFeedback))
      assertEquals(calls, Vector.empty)

  test("should delete with an empty 204 response"):
    val refs   = Refs()
    val server = buildAlbumApi(refs)

    for
      deleted <- send(server, Method.DELETE, path = albumPath, body = "")
      calls   <- refs.calls.get
    yield
      assertEquals(deleted, ApiResponse(Status.NoContent, maybeContentType = None, maybeBody = None))
      assertEquals(calls, Vector(s"delete:${id.value}"))

  test("should give malformed and unknown album IDs the same missing-resource response"):
    val refs   = Refs()
    val server = buildAlbumApi(
      refs,
      renameResult = RenameResult.NotFound,
      deleteAlbumResult = DeleteAlbumResult.NotFound
    )
    val paths        = Vector(albumPath, "/api/albums/not-a-uuid", "/api/albums/1-1-1-1-1")
    val requests     = Vector(Method.PATCH, Method.DELETE).flatMap(method => paths.map(method -> _))
    val expectedBody = parse("""{"code":"album_not_found"}""").fold(error => fail(error.message), identity)
    val expected     = Vector.fill(requests.size)(ApiResponse(Status.NotFound, jsonMediaType, expectedBody.some))
    val responses    = requests.traverse { case (method, path) =>
      send(server, method, path = path, body = """{"year":2026,"title":"Journey of the Shepherds 2026"}""")
    }

    for
      actual <- responses
      calls  <- refs.calls.get
    yield
      assertEquals(actual, expected)
      assertEquals(calls, Vector(s"rename:${id.value}:Journey of the Shepherds 2026", s"delete:${id.value}"))

  test("should translate every application's service failure to the same JSON error"):
    val server = buildAlbumApi(
      Refs(),
      listAlbumsResult = ListAlbumsResult.Failed,
      createAlbumResult = CreateAlbumResult.Failed,
      renameResult = RenameResult.Failed,
      deleteAlbumResult = DeleteAlbumResult.Failed
    )
    val operations = Vector(
      Method.GET    -> "/api/albums",
      Method.POST   -> "/api/albums",
      Method.PATCH  -> albumPath,
      Method.DELETE -> albumPath
    )
    val expectedBody = parse("""{"code":"service_failure"}""").fold(error => fail(error.message), identity)
    val expected     = Vector.fill(operations.size)(ApiResponse(Status.InternalServerError, jsonMediaType, expectedBody.some))

    operations.traverse { case (method, path) =>
      send(server, method, path = path, body = """{"year":2026,"title":"Journey of the Shepherds 2026"}""")
    }
      .map(responses => assertEquals(responses, expected))

  test("should reject duplicate years and expose no year-edit operation"):
    val refs     = Refs()
    val server   = buildAlbumApi(refs)
    val taken    = buildAlbumApi(Refs(), createAlbumResult = CreateAlbumResult.YearAlreadyUsed)
    val yearEdit = Request[IO](Method.PUT, Uri.unsafeFromString(s"$albumPath/year")).withEntity("""{"year":2027}""")

    for
      changed   <- server.run(yearEdit)
      duplicate <- send(taken, Method.POST, path = "/api/albums", body = """{"year":2026}""")
      calls     <- refs.calls.get
    yield
      assertEquals(changed.status, Status.NotFound)
      assertEquals(duplicate.status, Status.BadRequest)
      assert(hasValidationFeedback(duplicate))
      assertEquals(calls, Vector.empty)

  final private case class Refs():
    val calls: Ref[IO, Vector[String]]             = Ref.unsafe(Vector.empty)
    val contentCalls: Ref[IO, Vector[ContentCall]] = Ref.unsafe(a = Vector.empty[ContentCall])

  private enum ContentCall:
    case Synopsis(album: AlbumId, text: String)
    case Authors(album: AlbumId, authors: AlbumAuthors)

  final private case class SynopsisScenario(result: SaveSynopsisResult, status: Status, code: String)
  final private case class AuthorsScenario(result: SaveAuthorsResult, status: Status, code: String)
  final private case class InvalidContent(resource: String, body: String, message: String)

  final private case class ApiResponse(status: Status, maybeContentType: Option[MediaType], maybeBody: Option[Json])

  // scalafix:off DisableSyntax.defaultArgs
  private def buildAlbumApi(
      refs: Refs,
      listAlbumsResult: ListAlbumsResult =
        ListAlbumsResult.Listed(AlbumPage(albums = Vector.empty[Album], page = 1, pageSize = 6, totalAlbums = 0, totalPages = 0)),
      createAlbumResult: CreateAlbumResult = CreateAlbumResult.Created(album),
      renameResult: RenameResult = RenameResult.Renamed(album),
      deleteAlbumResult: DeleteAlbumResult = DeleteAlbumResult.Deleted,
      metadataResult: AlbumRepository.MetadataResult =
        AlbumRepository.MetadataResult.Found(AlbumRepository.Metadata(
          album.id,
          album.year,
          album.title,
          album.synopsis,
          album.authors,
          album.createdAt
        )),
      synopsisResult: IO[SaveSynopsisResult] = IO.raiseError[SaveSynopsisResult](new AssertionError("Unexpected synopsis mutation")),
      authorsResult: IO[SaveAuthorsResult] = IO.raiseError[SaveAuthorsResult](new AssertionError("Unexpected author mutation"))
  ) =
    val albums = new AlbumManager[IO]:
      override def saveSynopsis(id: AlbumId, text: String): IO[AlbumManager.SaveSynopsisResult] =
        refs.contentCalls.update(_ :+ ContentCall.Synopsis(id, text = text)) >> synopsisResult
      override def saveAuthors(id: AlbumId, authors: AlbumAuthors): IO[AlbumManager.SaveAuthorsResult] =
        refs.contentCalls.update(_ :+ ContentCall.Authors(id, authors)) >> authorsResult
      override def list(page: Int): IO[ListAlbumsResult]                             = refs.calls.update(_ :+ s"list:$page").as(listAlbumsResult)
      override def create(year: AlbumYear, title: AlbumTitle): IO[CreateAlbumResult] =
        refs.calls.update(_ :+ s"create:$year:$title").as(createAlbumResult)
      override def rename(albumId: AlbumId, title: AlbumTitle): IO[RenameResult] =
        refs.calls.update(_ :+ s"rename:${albumId.value}:$title").as(renameResult)
      override def delete(albumId: AlbumId): IO[DeleteAlbumResult] = refs.calls.update(_ :+ s"delete:${albumId.value}").as(deleteAlbumResult)
    val metadata = new AlbumRepository.MetadataRead[IO]:
      override def metadata(album: AlbumId): IO[AlbumRepository.MetadataResult] =
        refs.calls.update(_ :+ s"metadata:${album.value}").as(metadataResult)
    AlbumApi.make(albums, metadata).routes.orNotFound
  // scalafix:on DisableSyntax.defaultArgs

  private def send(server: HttpApp[IO], method: Method, path: String, body: String) =
    val request = Request[IO](method, Uri.unsafeFromString(path)).withEntity(body).putHeaders(`Content-Type`(MediaType.application.json))

    for
      response <- server.run(request)
      body     <- response.as[String]
    yield
      val maybeBody = Option.when(body.nonEmpty)(parse(body).fold(error => fail(error.message), identity))
      ApiResponse(response.status, response.contentType.map(_.mediaType), maybeBody)

  private def hasValidationFeedback(response: ApiResponse) =
    response.maybeBody.exists: body =>
      body.asObject.exists(_.keys.toSet === Set("message")) && body.hcursor.get[String]("message").toOption.exists(_.nonEmpty)

  private def json(value: String)          = parse(value).fold(error => fail(error.message), identity)
  private def encodeAuthor(author: Author) = Json.fromString(value = author match
    case Author.Cosmin => "Cosmin"
    case Author.Mara   => "Mara"
    case Author.Iacob  => "Iacob")
