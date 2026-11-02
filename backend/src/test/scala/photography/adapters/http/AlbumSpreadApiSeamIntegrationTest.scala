package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.data.NonEmptyList
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.jawn.parse
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import org.http4s.{HttpApp, MediaType, Method, Request, Status, Uri}
import org.http4s.{Header, Headers}
import org.typelevel.ci.CIString
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.editing.*
import photography.usecases.editing.EditorSessionManager
import photography.domain.layout.{SpreadId, SpreadTemplateId}
import photography.usecases.spread.SpreadManager
import photography.usecases.spread.SpreadManager.*

import java.nio.charset.StandardCharsets
import java.util.UUID

final class AlbumSpreadApiSeamIntegrationTest extends CatsEffectSuite:
  private val album       = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val chapter     = UUID.fromString("00000000-0000-0000-0000-000000000002")
  private val spread      = UUID.fromString("00000000-0000-0000-0000-000000000003")
  private val layout      = UUID.fromString("00000000-0000-0000-0000-000000000113")
  private val albumUrl    = s"/api/albums/$album"
  private val connection  = EditorConnection(SessionId(new UUID(0L, 10L)), TabId(new UUID(0L, 11L)), ConnectionGeneration(new UUID(0L, 12L)))
  private val grant       = GrantId(new UUID(0L, 13L))
  private val credentials = Headers(
    Header.Raw(CIString("X-Editor-Session"), connection.session.value.toString),
    Header.Raw(CIString("X-Editor-Tab"), connection.tab.value.toString),
    Header.Raw(CIString("X-Editor-Generation"), connection.generation.value.toString),
    Header.Raw(CIString("X-Editor-Grant"), grant.value.toString)
  )

  test("should create a spread after the target and answer with the inserted identity"):
    for
      calls <- Ref.of[IO, Vector[String]](Vector.empty)
      server = buildApi(calls, add = AddResult.Added(SpreadId(spread)))
      result <- respond(server, post(s"$albumUrl/chapters/$chapter/spreads", Json.obj("afterSpreadId" -> Json.fromString(spread.toString))))
      seen   <- calls.get
    yield
      assertEquals(result, Status.Created -> parseJson(s"""{"id":"$spread"}""").some)
      assertEquals(seen, Vector(s"add:$album:$chapter:$spread"))

  test("should explain rejected or unavailable additions and malformed targets"):
    Vector(
      (AddResult.Rejected(Conflict.AtCapacity), Status.BadRequest, parseJson("""{"message":"An album can contain at most 65 interior spreads"}""")),
      (
        AddResult.Rejected(Conflict.AddTarget),
        Status.BadRequest,
        parseJson("""{"message":"Add a spread after a chapter opening or photo spread"}""")
      ),
      (AddResult.AlbumNotFound, Status.NotFound, parseJson("""{"code":"album_not_found"}""")),
      (AddResult.ChapterNotFound, Status.NotFound, parseJson("""{"code":"chapter_not_found"}""")),
      (AddResult.SpreadNotFound, Status.NotFound, parseJson("""{"code":"spread_not_found"}""")),
      (AddResult.OwnershipRejected(EditorSessionManager.Failure.StaleSession), Status.Conflict, parseJson("""{"code":"stale_session"}""")),
      (
        AddResult.OwnershipRejected(EditorSessionManager.Failure.ObsoleteConnection),
        Status.Conflict,
        parseJson("""{"code":"obsolete_connection"}""")
      ),
      (AddResult.Failed, Status.InternalServerError, parseJson("""{"code":"service_failure"}"""))
    ).traverse_ { case (outcome, status, expected) =>
      for
        calls <- Ref.of[IO, Vector[String]](Vector.empty)
        server = buildApi(calls, add = outcome)
        result <- respond(server, post(s"$albumUrl/chapters/$chapter/spreads", Json.obj("afterSpreadId" -> Json.fromString(spread.toString))))
      yield assertEquals(result, status -> expected.some)
    } >> {
      val malformed = Request[IO](Method.POST, Uri.unsafeFromString(s"$albumUrl/chapters/$chapter/spreads"))
        .withHeaders(credentials).withBodyStream(buildRawJson).putHeaders(`Content-Type`(MediaType.application.json))

      for
        calls <- Ref.of[IO, Vector[String]](Vector.empty)
        server = buildApi(calls)
        invalid <- respond(server, post(s"$albumUrl/chapters/$chapter/spreads", Json.obj("afterSpreadId" -> Json.fromString("not-a-uuid"))))
        notJson <- respond(server, malformed)
        seen    <- calls.get
      yield
        assertEquals(invalid, Status.BadRequest -> parseJson("""{"message":"Spread IDs must be canonical UUIDs"}""").some)
        assertEquals(notJson, Status.BadRequest -> parseJson("""{"message":"Supply a valid JSON spread request"}""").some)
        assertEquals(seen, Vector.empty[String])
    }

  test("should change templates with explicit confirmation and report occupied and invalid targets"):
    val body     = Json.obj("templateId" -> Json.fromString(layout.toString))
    val declined = put(s"$albumUrl/spreads/$spread/template?confirmPlacements=false", body)

    for
      calls <- Ref.of[IO, Vector[String]](Vector.empty)
      server   = buildApi(calls, change = ChangeTemplateResult.Changed(SpreadId(spread), SpreadTemplateId(layout)))
      occupied = buildApi(calls, change = ChangeTemplateResult.Occupied(2))
      changed  <- respond(server, put(s"$albumUrl/spreads/$spread/template?confirmPlacements=true", body))
      warned   <- respond(occupied, put(s"$albumUrl/spreads/$spread/template", body))
      rejected <- respond(occupied, declined)
      seen     <- calls.get
    yield
      val expectedCalls =
        Vector(s"change:$album:$spread:$layout:true", s"change:$album:$spread:$layout:false", s"change:$album:$spread:$layout:false")
      assertEquals(changed, Status.Ok      -> parseJson(s"""{"id":"$spread","templateId":"$layout"}""").some)
      assertEquals(warned, Status.Conflict -> parseJson("""{"code":"spread_occupied","placements":2}""").some)
      assertEquals(rejected, warned)
      assertEquals(seen, expectedCalls)

  test("should explain rejected template changes, unknown identities and invalid template requests"):
    val body = Json.obj("templateId" -> Json.fromString(layout.toString))

    Vector(
      (
        ChangeTemplateResult.Rejected(Conflict.ContextMismatch),
        Status.BadRequest,
        parseJson("""{"message":"Choose a layout for this spread's context"}""")
      ),
      (ChangeTemplateResult.Rejected(Conflict.UnknownTemplate), Status.BadRequest, parseJson("""{"message":"Choose a layout from the catalog"}""")),
      (ChangeTemplateResult.SpreadNotFound, Status.NotFound, parseJson("""{"code":"spread_not_found"}""")),
      (ChangeTemplateResult.AlbumNotFound, Status.NotFound, parseJson("""{"code":"album_not_found"}""")),
      (ChangeTemplateResult.Failed, Status.InternalServerError, parseJson("""{"code":"service_failure"}"""))
    ).traverse_ { case (outcome, status, expected) =>
      respond(buildApi(Ref.unsafe[IO, Vector[String]](Vector.empty), change = outcome), put(s"$albumUrl/spreads/$spread/template", body))
        .map(result => assertEquals(result, status -> expected.some))
    } >> respond(
      buildApi(Ref.unsafe[IO, Vector[String]](Vector.empty)),
      put(s"$albumUrl/spreads/$spread/template", Json.obj("templateId" -> Json.fromString("layout")))
    )
      .map(result => assertEquals(result, Status.BadRequest -> parseJson("""{"message":"Template IDs must be canonical UUIDs"}""").some))

  test("should delete spreads with explicit confirmation and explain occupied, opening, and missing targets"):
    for
      calls    <- Ref.of[IO, Vector[String]](Vector.empty)
      deleted  <- respond(buildApi(calls, delete = DeleteResult.Deleted), delete(s"$albumUrl/spreads/$spread?confirmPlacements=true"))
      seen     <- calls.get
      occupied <- respond(buildApi(calls, delete = DeleteResult.Occupied(3)), delete(s"$albumUrl/spreads/$spread?confirmPlacements=false"))
      opening  <- respond(buildApi(calls, delete = DeleteResult.Rejected(Conflict.OpeningDelete)), delete(s"$albumUrl/spreads/$spread"))
      missing  <- respond(buildApi(calls, delete = DeleteResult.SpreadNotFound), delete(s"$albumUrl/spreads/$spread"))
      unknown  <- respond(buildApi(calls, delete = DeleteResult.AlbumNotFound), delete(s"$albumUrl/spreads/$spread"))
      failed   <- respond(buildApi(calls, delete = DeleteResult.Failed), delete(s"$albumUrl/spreads/$spread"))
    yield
      assertEquals(deleted, Status.NoContent -> None)
      assertEquals(seen, Vector(s"delete:$album:$spread:true"))
      assertEquals(occupied, Status.Conflict          -> parseJson("""{"code":"spread_occupied","placements":3}""").some)
      assertEquals(opening, Status.BadRequest         -> parseJson("""{"message":"Openings cannot be deleted"}""").some)
      assertEquals(missing, Status.NotFound           -> parseJson("""{"code":"spread_not_found"}""").some)
      assertEquals(unknown, Status.NotFound           -> parseJson("""{"code":"album_not_found"}""").some)
      assertEquals(failed, Status.InternalServerError -> parseJson("""{"code":"service_failure"}""").some)

  test("should reject malformed placement confirmation without invoking spread actions"):
    val body = Json.obj("templateId" -> Json.fromString(layout.toString))

    for
      calls <- Ref.of[IO, Vector[String]](Vector.empty)
      server = buildApi(calls)
      changed <- respond(server, put(s"$albumUrl/spreads/$spread/template?confirmPlacements=yes", body))
      deleted <- respond(server, delete(s"$albumUrl/spreads/$spread?confirmPlacements=yes"))
      seen    <- calls.get
    yield
      val expected = Status.BadRequest -> parseJson("""{"message":"confirmPlacements must be true or false"}""").some
      assertEquals(changed, expected)
      assertEquals(deleted, expected)
      assertEquals(seen, Vector.empty[String])

  test("should require canonical connection headers before decoding structural requests and allow an absent deletion grant"):
    val body            = Json.obj("templateId" -> Json.fromString(layout.toString))
    val templateRequest = put(s"$albumUrl/spreads/$spread/template", body)
    val requests        = Vector(templateRequest, delete(s"$albumUrl/spreads/$spread"))
    val invalid         = credentials.headers.flatMap { header =>
      val missing    = credentials.headers.filterNot(_.name === header.name)
      val malformed  = header.copy(value = "invalid") :: missing
      val duplicated = credentials.headers ++ List(header)
      val combined   = header.copy(value = s"${header.value},${header.value}") :: missing
      Vector(
        (Headers(missing), "missing_credentials"),
        (Headers(malformed), "invalid_credentials"),
        (Headers(duplicated), "invalid_credentials"),
        (Headers(combined), "invalid_credentials")
      ).filterNot { case (_, code) => header.name === CIString("X-Editor-Grant") && code === "missing_credentials" }
    }

    for
      calls <- Ref.of[IO, Vector[String]](Vector.empty)
      server = buildApi(calls)
      _ <- requests.traverse_ { request =>
        invalid.traverse_ { case (headers, code) =>
          respond(server, request.withHeaders(headers)).map(result =>
            assertEquals(result, Status.BadRequest -> Json.obj("code" -> Json.fromString(code)).some)
          )
        }
      }
      missingGrant <- respond(server, templateRequest.withHeaders(Headers(credentials.headers.filterNot(_.name === CIString("X-Editor-Grant")))))
      seen         <- calls.get
      unlocked     <- respond(
        buildApi(calls, delete = DeleteResult.Deleted, maybeDeleteGrant = None),
        delete(s"$albumUrl/spreads/$spread").withHeaders(Headers(credentials.headers.filterNot(_.name === CIString("X-Editor-Grant"))))
      )
    yield
      assertEquals(missingGrant, Status.BadRequest -> parseJson("""{"code":"missing_credentials"}""").some)
      assertEquals(seen, Vector.empty[String])
      assertEquals(unlocked, Status.NoContent -> None)

  test("should expose only the public locked owner and exact structural recovery failures"):
    val owner    = EditorId(new UUID(0L, 20L))
    val body     = Json.obj("templateId" -> Json.fromString(layout.toString))
    val failures = Vector(
      EditorSessionManager.Failure.StaleSession       -> "stale_session",
      EditorSessionManager.Failure.ObsoleteConnection -> "obsolete_connection",
      EditorSessionManager.Failure.StaleGrant         -> "stale_grant",
      EditorSessionManager.Failure.StaleTransition    -> "stale_transition"
    )

    for
      calls <- Ref.of[IO, Vector[String]](Vector.empty)
      server = buildApi(calls, change = ChangeTemplateResult.Locked(owner, "Other"), delete = DeleteResult.Locked(owner, "Other"))
      template <- respond(server, put(s"$albumUrl/spreads/$spread/template?confirmPlacements=true", body))
      deletion <- respond(server, delete(s"$albumUrl/spreads/$spread?confirmPlacements=true"))
      _        <- failures.traverse_ { case (failure, code) =>
        val rejected = buildApi(calls, change = ChangeTemplateResult.OwnershipRejected(failure), delete = DeleteResult.OwnershipRejected(failure))
        val expected = Status.Conflict -> Json.obj("code" -> Json.fromString(code)).some
        (respond(rejected, put(s"$albumUrl/spreads/$spread/template", body)), respond(rejected, delete(s"$albumUrl/spreads/$spread")))
          .tupled.map { case (changed, deleted) =>
            assertEquals(changed, expected)
            assertEquals(deleted, expected)
          }
      }
    yield
      val expected = Status.Conflict -> Json.obj(
        "code"    -> Json.fromString("target_locked"),
        "ownerId" -> Json.fromString(owner.value.toString),
        "alias"   -> Json.fromString("Other")
      ).some
      assertEquals(template, expected)
      assertEquals(deletion, expected)

  test("should reject missing or malformed insertion credentials before decoding or dispatch"):
    val request = post(s"$albumUrl/chapters/$chapter/spreads", Json.obj("afterSpreadId" -> Json.fromString("invalid")))
    val invalid = credentials.headers.filterNot(_.name === CIString("X-Editor-Grant")).flatMap { header =>
      val remaining = request.headers.headers.filterNot(_.name === header.name)
      Vector(
        (Headers(remaining), "missing_credentials"),
        (Headers(header.copy(value = "invalid") :: remaining), "invalid_credentials"),
        (Headers(request.headers.headers ++ List(header)), "invalid_credentials"),
        (Headers(header.copy(value = s"${header.value},${header.value}") :: remaining), "invalid_credentials")
      )
    }

    for
      calls <- Ref.of[IO, Vector[String]](Vector.empty)
      server = buildApi(calls)
      _ <- invalid.traverse_ { case (headers, code) =>
        respond(server, request.withHeaders(headers)).map(result =>
          assertEquals(result, Status.BadRequest -> Json.obj("code" -> Json.fromString(code)).some)
        )
      }
      seen <- calls.get
    yield assertEquals(seen, Vector.empty[String])

  private def buildRawJson = Stream.emits("{".getBytes(StandardCharsets.UTF_8).toSeq).covary[IO]

  private def post(path: String, body: Json) =
    Request[IO](Method.POST, Uri.unsafeFromString(path))
      .withHeaders(Headers(credentials.headers.filterNot(_.name === CIString("X-Editor-Grant")))).withEntity(body)

  private def put(path: String, body: Json) =
    Request[IO](Method.PUT, Uri.unsafeFromString(path), headers = credentials).withEntity(body)

  private def delete(path: String) = Request[IO](Method.DELETE, Uri.unsafeFromString(path), headers = credentials)

  private def parseJson(text: String) = parse(text).fold(error => fail(error.message), identity)

  private def respond(app: HttpApp[IO], request: Request[IO]) =
    app.run(request).flatMap: response =>
      assertEquals(response.headers.get(CIString("Cache-Control")).map(_.head.value), Some("no-store"))
      response.bodyText.compile.toList.map(chunks => (response.status, parse(chunks.mkString).toOption))

  // scalafix:off DisableSyntax.defaultArgs
  private def buildApi(
      calls: Ref[IO, Vector[String]],
      add: AddResult = AddResult.Failed,
      change: ChangeTemplateResult = ChangeTemplateResult.Failed,
      delete: DeleteResult = DeleteResult.Failed,
      maybeDeleteGrant: Option[GrantId] = Some(grant)
  ) =
    val addOutcome    = add
    val changeOutcome = change
    val deleteOutcome = delete
    val spreads       = new SpreadManager[IO]:
      override def replaceChapterOrder(
          album: AlbumId,
          chapter: ChapterId,
          expected: NonEmptyList[SpreadId],
          order: NonEmptyList[SpreadId],
          connection: EditorConnection
      ): IO[ReplacementResult] =
        IO.raiseError[ReplacementResult](new AssertionError("Unexpected chapter order replacement"))
      override def readPlacements(album: AlbumId, spread: SpreadId): IO[PlacementReadResult] =
        IO.raiseError[PlacementReadResult](new AssertionError("Unexpected placement read"))
      override def editPlacements(
          album: AlbumId,
          spread: SpreadId,
          request: Edit,
          connection: EditorConnection,
          grant: GrantId
      ): IO[PlacementResult] =
        IO.raiseError[PlacementResult](new AssertionError("Unexpected placement edit"))
      override def add(album: AlbumId, chapter: ChapterId, after: SpreadId, editor: EditorConnection): IO[AddResult] =
        assertEquals(editor, connection)
        calls.update(_ :+ s"add:${album.value}:${chapter.value}:${after.value}").as(addOutcome)
      override def changeTemplate(
          album: AlbumId,
          spread: SpreadId,
          template: SpreadTemplateId,
          confirmPlacements: Boolean,
          editor: EditorConnection,
          proof: GrantId
      ): IO[ChangeTemplateResult] =
        assertEquals(editor, connection)
        assertEquals(proof, grant)
        calls.update(_ :+ s"change:${album.value}:${spread.value}:${template.value}:$confirmPlacements").as(changeOutcome)
      override def delete(
          album: AlbumId,
          spread: SpreadId,
          confirmPlacements: Boolean,
          editor: EditorConnection,
          maybeGrant: Option[GrantId]
      ): IO[DeleteResult] =
        assertEquals(editor, connection)
        assertEquals(maybeGrant, maybeDeleteGrant)
        calls.update(_ :+ s"delete:${album.value}:${spread.value}:$confirmPlacements").as(deleteOutcome)
    AlbumSpreadApi.make(spreads).routes.orNotFound
  // scalafix:on DisableSyntax.defaultArgs
