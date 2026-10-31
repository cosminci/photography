package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.data.NonEmptyList
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.jawn.parse
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import org.http4s.{HttpApp, MediaType, Method, Request, Status, Uri}
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.layout.{SpreadId, SpreadTemplateId}
import photography.usecases.spread.SpreadManager
import photography.usecases.spread.SpreadManager.*

import java.nio.charset.StandardCharsets
import java.util.UUID

final class AlbumSpreadApiSeamIntegrationTest extends CatsEffectSuite:
  private val album    = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val chapter  = UUID.fromString("00000000-0000-0000-0000-000000000002")
  private val spread   = UUID.fromString("00000000-0000-0000-0000-000000000003")
  private val layout   = UUID.fromString("00000000-0000-0000-0000-000000000113")
  private val albumUrl = s"/api/albums/$album"

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
      (AddResult.Failed, Status.InternalServerError, parseJson("""{"code":"service_failure"}"""))
    ).traverse_ { case (outcome, status, expected) =>
      for
        calls <- Ref.of[IO, Vector[String]](Vector.empty)
        server = buildApi(calls, add = outcome)
        result <- respond(server, post(s"$albumUrl/chapters/$chapter/spreads", Json.obj("afterSpreadId" -> Json.fromString(spread.toString))))
      yield assertEquals(result, status -> expected.some)
    } >> {
      val malformed = Request[IO](Method.POST, Uri.unsafeFromString(s"$albumUrl/chapters/$chapter/spreads"))
        .withBodyStream(buildRawJson).putHeaders(`Content-Type`(MediaType.application.json))

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

  private def buildRawJson = Stream.emits("{".getBytes(StandardCharsets.UTF_8).toSeq).covary[IO]

  private def post(path: String, body: Json) =
    Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(body)

  private def put(path: String, body: Json) =
    Request[IO](Method.PUT, Uri.unsafeFromString(path)).withEntity(body)

  private def delete(path: String) = Request[IO](Method.DELETE, Uri.unsafeFromString(path))

  private def parseJson(text: String) = parse(text).fold(error => fail(error.message), identity)

  private def respond(app: HttpApp[IO], request: Request[IO]) =
    app.run(request).flatMap: response =>
      response.bodyText.compile.toList.map(chunks => (response.status, parse(chunks.mkString).toOption))

  // scalafix:off DisableSyntax.defaultArgs
  private def buildApi(
      calls: Ref[IO, Vector[String]],
      add: AddResult = AddResult.Failed,
      change: ChangeTemplateResult = ChangeTemplateResult.Failed,
      delete: DeleteResult = DeleteResult.Failed
  ) =
    val addOutcome    = add
    val changeOutcome = change
    val deleteOutcome = delete
    val spreads       = new SpreadManager[IO]:
      override def replaceChapterOrder(
          album: AlbumId,
          chapter: ChapterId,
          expected: NonEmptyList[SpreadId],
          order: NonEmptyList[SpreadId]
      ): IO[ReplacementResult] =
        IO.raiseError[ReplacementResult](new AssertionError("Unexpected chapter order replacement"))
      override def readPlacements(album: AlbumId, spread: SpreadId): IO[PlacementReadResult] =
        IO.raiseError[PlacementReadResult](new AssertionError("Unexpected placement read"))
      override def editPlacements(album: AlbumId, spread: SpreadId, request: Edit): IO[PlacementResult] =
        IO.raiseError[PlacementResult](new AssertionError("Unexpected placement edit"))
      override def add(album: AlbumId, chapter: ChapterId, after: SpreadId): IO[AddResult] =
        calls.update(_ :+ s"add:${album.value}:${chapter.value}:${after.value}").as(addOutcome)
      override def changeTemplate(
          album: AlbumId,
          spread: SpreadId,
          template: SpreadTemplateId,
          confirmPlacements: Boolean
      ): IO[ChangeTemplateResult] =
        calls.update(_ :+ s"change:${album.value}:${spread.value}:${template.value}:$confirmPlacements").as(changeOutcome)
      override def delete(album: AlbumId, spread: SpreadId, confirmPlacements: Boolean): IO[DeleteResult] =
        calls.update(_ :+ s"delete:${album.value}:${spread.value}:$confirmPlacements").as(deleteOutcome)
    AlbumSpreadApi.make(spreads).routes.orNotFound
  // scalafix:on DisableSyntax.defaultArgs
