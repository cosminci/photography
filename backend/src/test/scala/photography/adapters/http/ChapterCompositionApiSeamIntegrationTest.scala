package photography.adapters.http

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.parse
import fs2.Stream
import java.nio.charset.StandardCharsets
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.{Header, Headers}
import org.typelevel.ci.CIString
import org.http4s.circe.CirceEntityCodec.*
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.editing.*
import photography.usecases.editing.EditingAccess
import photography.domain.layout.{SpreadId, SpreadTemplateId}
import photography.usecases.spread.SpreadManager
import photography.usecases.spread.SpreadManager.*
import cats.data.NonEmptyList
import java.util.UUID

final class ChapterCompositionApiSeamIntegrationTest extends CatsEffectSuite:
  private val album       = AlbumId(new UUID(0L, 1L))
  private val chapter     = ChapterId(new UUID(0L, 2L))
  private val opening     = SpreadId(new UUID(0L, 3L))
  private val connection  = EditorConnection(SessionId(new UUID(0L, 10L)), TabId(new UUID(0L, 11L)), ConnectionGeneration(new UUID(0L, 12L)))
  private val credentials = Headers(
    Header.Raw(CIString("X-Editor-Session"), connection.session.value.toString),
    Header.Raw(CIString("X-Editor-Tab"), connection.tab.value.toString),
    Header.Raw(CIString("X-Editor-Generation"), connection.generation.value.toString)
  )
  private val path = s"/api/albums/${album.value}/chapters/${chapter.value}/composition"
  private val body = Json.obj(
    "expectedSpreadIds" -> Json.arr(Json.fromString(opening.value.toString)),
    "spreadIds"         -> Json.arr(Json.fromString(opening.value.toString))
  )

  private val scenarios = Vector(
    Scenario("save the observed order", ReplacementResult.Saved, Status.NoContent, none[Json]),
    Scenario(
      "reject invalid membership",
      ReplacementResult.Invalid,
      Status.BadRequest,
      Json.obj("message" -> Json.fromString("Retain the opening and every spread exactly once")).some
    ),
    Scenario(
      "report missing scoped composition",
      ReplacementResult.NotFound,
      Status.NotFound,
      Json.obj("code" -> Json.fromString("chapter_not_found")).some
    ),
    Scenario(
      "reject stale observed order",
      ReplacementResult.Stale,
      Status.Conflict,
      Json.obj("code" -> Json.fromString("composition_changed")).some
    ),
    Scenario(
      "distinguish failed pre-write acquisition",
      ReplacementResult.Failed,
      Status.InternalServerError,
      Json.obj("code" -> Json.fromString("service_failure")).some
    ),
    Scenario(
      "preserve an uncertain write outcome",
      ReplacementResult.Uncertain,
      Status.InternalServerError,
      Json.obj("code" -> Json.fromString("write_uncertain")).some
    )
  )
  scenarios.foreach { scenario =>
    test(s"should ${scenario.name}"):
      val refs        = Refs()
      val program     = buildManager(refs, scenario.result)
      val definitions = ChapterCompositionApi.make(program)
      val declaration = definitions.routes.lift(Request[IO](Method.PUT, Uri.unsafeFromString(path))).map(_.access)
      assertEquals(declaration, Routes.Access.Editor(EditingAccess.Protection.ActiveTab).some)
      for
        response <- Routes.make(buildAccess(refs))(definitions).orNotFound.run(Request[IO](
          Method.PUT,
          Uri.unsafeFromString(path),
          headers = credentials
        ).withEntity(body))
        text       <- response.bodyText.compile.string
        calls      <- refs.orders.get
        admissions <- refs.admissions.get
      yield
        assertEquals(response.status, scenario.status)
        assertEquals(parse(text).toOption, scenario.maybeBody)
        assertEquals(calls, Vector(OrderCall(album, chapter, NonEmptyList.one(opening), NonEmptyList.one(opening))))
        assertEquals(admissions, Vector((connection, EditingAccess.Protection.ActiveTab, none[GrantId])))
  }

  test("should reject malformed JSON, empty orders and noncanonical member IDs before mutation"):
    val refs    = Refs()
    val program = buildManager(refs, ReplacementResult.Failed)
    val routes  = Routes.make(buildAccess(refs))(ChapterCompositionApi.make(program)).orNotFound
    val invalid = Vector(
      "{}",
      "{",
      """{"expectedSpreadIds":[],"spreadIds":[]}""",
      """{"expectedSpreadIds":["1-1-1-1-1"],"spreadIds":["invalid"]}"""
    )
    val malformed = invalid.traverse_ { value =>
      routes.run(Request[IO](
        Method.PUT,
        Uri.unsafeFromString(path),
        headers = credentials
      ).withEntity(value).putHeaders(org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json)))
        .map(response => assertEquals(response.status, Status.BadRequest))
    } *> routes.run(Request[IO](Method.PUT, Uri.unsafeFromString(path), headers = credentials)
      .withBodyStream(Stream.emits("{".getBytes(StandardCharsets.UTF_8)).covary[IO])
      .putHeaders(org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json))).flatMap: response =>
      response.as[Json].map: body =>
        assertEquals(response.status, Status.BadRequest)
        assertEquals(body, Json.obj("message" -> Json.fromString("Supply a valid composition")))
    *> routes.run(Request[IO](Method.PUT, Uri.unsafeFromString("/api/albums/1-1-1-1-1/chapters/bad/composition")))
      .map(response => assertEquals(response.status, Status.NotFound))
    for
      _          <- malformed
      calls      <- refs.orders.get
      admissions <- refs.admissions.get
    yield
      assertEquals(calls, Vector.empty[OrderCall])
      assertEquals(admissions, Vector.empty[(EditorConnection, EditingAccess.Protection, Option[GrantId])])

  final private case class Scenario(name: String, result: ReplacementResult, status: Status, maybeBody: Option[Json])
  final private case class OrderCall(album: AlbumId, chapter: ChapterId, expected: NonEmptyList[SpreadId], order: NonEmptyList[SpreadId])
  final private case class Refs():
    val orders: Ref[IO, Vector[OrderCall]] = Ref.unsafe(Vector.empty)
    val admissions: Ref[IO, Vector[(editor: EditorConnection, protection: EditingAccess.Protection, grant: Option[GrantId])]] =
      Ref.unsafe(Vector.empty)

  private def buildManager(refs: Refs, result: ReplacementResult): SpreadManager[IO] = new SpreadManager[IO]:
    override def setBleed(album: AlbumId, spread: SpreadId, enabled: Boolean): IO[SpreadManager.SetBleedResult] =
      new AssertionError("Unexpected bleed change").raiseError[IO, SpreadManager.SetBleedResult]
    override def replaceChapterOrder(
        id: AlbumId,
        target: ChapterId,
        previous: NonEmptyList[SpreadId],
        order: NonEmptyList[SpreadId]
    ): IO[ReplacementResult] =
      refs.orders.update(_ :+ OrderCall(id, target, previous, order)).as(result)
    override def add(album: AlbumId, chapter: ChapterId, after: SpreadId): IO[AddResult] =
      new AssertionError("Unexpected add").raiseError[IO, AddResult]
    override def changeTemplate(
        album: AlbumId,
        spread: SpreadId,
        template: SpreadTemplateId,
        confirmPlacements: Boolean
    ): IO[ChangeTemplateResult] =
      new AssertionError("Unexpected template change").raiseError[IO, ChangeTemplateResult]
    override def delete(
        album: AlbumId,
        spread: SpreadId,
        confirmPlacements: Boolean
    ): IO[DeleteResult] =
      new AssertionError("Unexpected deletion").raiseError[IO, DeleteResult]
    override def readPlacements(album: AlbumId, spread: SpreadId): IO[PlacementReadResult] =
      new AssertionError("Unexpected placement read").raiseError[IO, PlacementReadResult]
    override def editPlacements(album: AlbumId, spread: SpreadId, request: Edit): IO[PlacementResult] =
      new AssertionError("Unexpected placement edit").raiseError[IO, PlacementResult]

  private def buildAccess(refs: Refs): EditingAccess[IO] = new EditingAccess[IO]:
    override def run[A](
        editor: EditorConnection,
        protection: EditingAccess.Protection,
        maybeGrant: Option[GrantId]
    )(write: IO[A]): IO[EditingAccess.Result[A]] =
      refs.admissions.update(_ :+ (editor, protection, maybeGrant)) >> write.map(EditingAccess.Result.Completed(_))
