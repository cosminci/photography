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
import org.http4s.{Header, Headers}
import org.typelevel.ci.CIString
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.editing.*
import photography.usecases.editing.EditingAccess
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
      server = buildApi(Refs(calls), add = AddResult.Added(SpreadId(spread)))
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
        server = buildApi(Refs(calls), add = outcome)
        result <- respond(server, post(s"$albumUrl/chapters/$chapter/spreads", Json.obj("afterSpreadId" -> Json.fromString(spread.toString))))
      yield assertEquals(result, status -> expected.some)
    } >> {
      val malformed = Request[IO](Method.POST, Uri.unsafeFromString(s"$albumUrl/chapters/$chapter/spreads"))
        .withHeaders(credentials).withBodyStream(buildRawJson).putHeaders(`Content-Type`(MediaType.application.json))

      for
        calls <- Ref.of[IO, Vector[String]](Vector.empty)
        server = buildApi(Refs(calls))
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
      server   = buildApi(Refs(calls), change = ChangeTemplateResult.Changed(SpreadId(spread), SpreadTemplateId(layout)))
      occupied = buildApi(Refs(calls), change = ChangeTemplateResult.Occupied(2))
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
      respond(buildApi(Refs(Ref.unsafe[IO, Vector[String]](Vector.empty)), change = outcome), put(s"$albumUrl/spreads/$spread/template", body))
        .map(result => assertEquals(result, status -> expected.some))
    } >> respond(
      buildApi(Refs(Ref.unsafe[IO, Vector[String]](Vector.empty))),
      put(s"$albumUrl/spreads/$spread/template", Json.obj("templateId" -> Json.fromString("layout")))
    )
      .map(result => assertEquals(result, Status.BadRequest -> parseJson("""{"message":"Template IDs must be canonical UUIDs"}""").some))

  test("should delete spreads with explicit confirmation and explain occupied, opening, and missing targets"):
    for
      calls    <- Ref.of[IO, Vector[String]](Vector.empty)
      deleted  <- respond(buildApi(Refs(calls), delete = DeleteResult.Deleted), delete(s"$albumUrl/spreads/$spread?confirmPlacements=true"))
      seen     <- calls.get
      occupied <- respond(buildApi(Refs(calls), delete = DeleteResult.Occupied(3)), delete(s"$albumUrl/spreads/$spread?confirmPlacements=false"))
      opening  <- respond(buildApi(Refs(calls), delete = DeleteResult.Rejected(Conflict.OpeningDelete)), delete(s"$albumUrl/spreads/$spread"))
      missing  <- respond(buildApi(Refs(calls), delete = DeleteResult.SpreadNotFound), delete(s"$albumUrl/spreads/$spread"))
      unknown  <- respond(buildApi(Refs(calls), delete = DeleteResult.AlbumNotFound), delete(s"$albumUrl/spreads/$spread"))
      failed   <- respond(buildApi(Refs(calls), delete = DeleteResult.Failed), delete(s"$albumUrl/spreads/$spread"))
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
      server = buildApi(Refs(calls))
      changed <- respond(server, put(s"$albumUrl/spreads/$spread/template?confirmPlacements=yes", body))
      deleted <- respond(server, delete(s"$albumUrl/spreads/$spread?confirmPlacements=yes"))
      seen    <- calls.get
    yield
      val expected = Status.BadRequest -> parseJson("""{"message":"confirmPlacements must be true or false"}""").some
      assertEquals(changed, expected)
      assertEquals(deleted, expected)
      assertEquals(seen, Vector.empty[String])

  test("should declare and admit additions, template changes and removals at their exact editing targets"):
    val refs   = Refs(Ref.unsafe[IO, Vector[String]](Vector.empty))
    val api    = buildApi(refs)
    val target = EditingTarget.Spread(SpreadId(spread))
    val cases  = Vector(
      EditRoute(
        post(s"$albumUrl/chapters/$chapter/spreads", Json.obj("afterSpreadId" -> Json.fromString(spread.toString))),
        EditingAccess.Protection.ActiveTab,
        none[GrantId]
      ),
      EditRoute(
        put(s"$albumUrl/spreads/$spread/template", Json.obj("templateId" -> Json.fromString(layout.toString))),
        EditingAccess.Protection.OwnSpread(target),
        grant.some
      ),
      EditRoute(delete(s"$albumUrl/spreads/$spread"), EditingAccess.Protection.ChangeSpreads(Set(target)), grant.some)
    )
    val declarations = cases.map(edit => api.definition.routes.lift(edit.request).map(_.access))

    for
      _          <- cases.traverse_(edit => respond(api, edit.request))
      admissions <- refs.admissions.get
    yield
      assertEquals(declarations, cases.map(edit => Routes.Access.Editor(edit.protection).some))
      assertEquals(admissions, cases.map(edit => Admission(connection, edit.protection, edit.grant)))

  final private case class EditRoute(request: Request[IO], protection: EditingAccess.Protection, grant: Option[GrantId])

  private def buildRawJson = Stream.emits("{".getBytes(StandardCharsets.UTF_8).toSeq).covary[IO]

  private def post(path: String, body: Json) =
    Request[IO](Method.POST, Uri.unsafeFromString(path))
      .withHeaders(Headers(credentials.headers.filterNot(_.name === CIString("X-Editor-Grant")))).withEntity(body)

  private def put(path: String, body: Json) =
    Request[IO](Method.PUT, Uri.unsafeFromString(path), headers = credentials).withEntity(body)

  private def delete(path: String) = Request[IO](Method.DELETE, Uri.unsafeFromString(path), headers = credentials)

  private def parseJson(text: String) = parse(text).fold(error => fail(error.message), identity)

  private def respond(api: Api, request: Request[IO]) =
    api.app.run(request).flatMap: response =>
      response.bodyText.compile.toList.map(chunks => (response.status, parse(chunks.mkString).toOption))

  // scalafix:off DisableSyntax.defaultArgs
  final private case class Refs(calls: Ref[IO, Vector[String]]):
    val admissions: Ref[IO, Vector[Admission]] = Ref.unsafe(Vector.empty)
  final private case class Admission(editor: EditorConnection, protection: EditingAccess.Protection, grant: Option[GrantId])
  final private case class Api(definition: Routes[IO], app: HttpApp[IO])

  private def buildApi(
      refs: Refs,
      add: AddResult = AddResult.Failed,
      change: ChangeTemplateResult = ChangeTemplateResult.Failed,
      delete: DeleteResult = DeleteResult.Failed
  ) =
    val addOutcome    = add
    val changeOutcome = change
    val deleteOutcome = delete
    val spreads       = new SpreadManager[IO]:
      override def setBleed(album: AlbumId, spread: SpreadId, enabled: Boolean): IO[SpreadManager.SetBleedResult] =
        new AssertionError("Unexpected bleed change").raiseError[IO, SpreadManager.SetBleedResult]
      override def replaceChapterOrder(
          album: AlbumId,
          chapter: ChapterId,
          expected: NonEmptyList[SpreadId],
          order: NonEmptyList[SpreadId]
      ): IO[ReplacementResult] =
        new AssertionError("Unexpected chapter order replacement").raiseError[IO, ReplacementResult]
      override def readPlacements(album: AlbumId, spread: SpreadId): IO[PlacementReadResult] =
        new AssertionError("Unexpected placement read").raiseError[IO, PlacementReadResult]
      override def editPlacements(
          album: AlbumId,
          spread: SpreadId,
          request: Edit
      ): IO[PlacementResult] =
        new AssertionError("Unexpected placement edit").raiseError[IO, PlacementResult]
      override def add(album: AlbumId, chapter: ChapterId, after: SpreadId): IO[AddResult] =
        refs.calls.update(_ :+ s"add:${album.value}:${chapter.value}:${after.value}").as(addOutcome)
      override def changeTemplate(
          album: AlbumId,
          spread: SpreadId,
          template: SpreadTemplateId,
          confirmPlacements: Boolean
      ): IO[ChangeTemplateResult] =
        refs.calls.update(_ :+ s"change:${album.value}:${spread.value}:${template.value}:$confirmPlacements").as(changeOutcome)
      override def delete(
          album: AlbumId,
          spread: SpreadId,
          confirmPlacements: Boolean
      ): IO[DeleteResult] =
        refs.calls.update(_ :+ s"delete:${album.value}:${spread.value}:$confirmPlacements").as(deleteOutcome)
    val access = new EditingAccess[IO]:
      override def run[A](
          editor: EditorConnection,
          protection: EditingAccess.Protection,
          maybeGrant: Option[GrantId]
      )(write: IO[A]): IO[EditingAccess.Result[A]] =
        refs.admissions.update(_ :+ Admission(editor, protection, maybeGrant)) >> write.map(EditingAccess.Result.Completed(_))
    val definitions = AlbumSpreadApi.make(spreads)
    Api(definitions, Routes.make(access)(definitions).orNotFound)
  // scalafix:on DisableSyntax.defaultArgs
