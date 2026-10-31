package photography.adapters.http

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.parse
import fs2.Stream
import java.nio.charset.StandardCharsets
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityCodec.*
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.layout.{SpreadId, SpreadTemplateId}
import photography.usecases.spread.SpreadManager
import photography.usecases.spread.SpreadManager.*
import cats.data.NonEmptyList
import java.util.UUID

final class ChapterCompositionApiSeamIntegrationTest extends CatsEffectSuite:
  private val album   = AlbumId(new UUID(0L, 1L))
  private val chapter = ChapterId(new UUID(0L, 2L))
  private val opening = SpreadId(new UUID(0L, 3L))
  private val path    = s"/api/albums/${album.value}/chapters/${chapter.value}/composition"
  private val body    = Json.obj(
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
      val program = buildManager(scenario.result)
      ChapterCompositionApi.make(program).orNotFound.run(Request[IO](Method.PUT, Uri.unsafeFromString(path)).withEntity(body))
        .flatMap(response =>
          response.bodyText.compile.string.map: body =>
            assertEquals(response.status, scenario.status)
            assertEquals(parse(body).toOption, scenario.maybeBody)
        )
  }

  test("should reject malformed JSON, empty orders and noncanonical member IDs before mutation"):
    val program = buildManager(ReplacementResult.Failed)
    val routes  = ChapterCompositionApi.make(program).orNotFound
    val invalid = Vector(
      "{}",
      "{",
      """{"expectedSpreadIds":[],"spreadIds":[]}""",
      """{"expectedSpreadIds":["1-1-1-1-1"],"spreadIds":["invalid"]}"""
    )
    invalid.traverse_ { value =>
      routes.run(Request[IO](
        Method.PUT,
        Uri.unsafeFromString(path)
      ).withEntity(value).putHeaders(org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json)))
        .map(response => assertEquals(response.status, Status.BadRequest))
    } *> routes.run(Request[IO](Method.PUT, Uri.unsafeFromString(path))
      .withBodyStream(Stream.emits("{".getBytes(StandardCharsets.UTF_8)).covary[IO])
      .putHeaders(org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json))).flatMap: response =>
      response.as[Json].map: body =>
        assertEquals(response.status, Status.BadRequest)
        assertEquals(body, Json.obj("message" -> Json.fromString("Supply a valid composition")))
    *> routes.run(Request[IO](Method.PUT, Uri.unsafeFromString("/api/albums/1-1-1-1-1/chapters/bad/composition")))
      .map(response => assertEquals(response.status, Status.NotFound))

  final private case class Scenario(name: String, result: ReplacementResult, status: Status, maybeBody: Option[Json])

  private def buildManager(result: ReplacementResult): SpreadManager[IO] = new SpreadManager[IO]:
    override def replaceChapterOrder(
        id: AlbumId,
        target: ChapterId,
        previous: NonEmptyList[SpreadId],
        order: NonEmptyList[SpreadId]
    ): IO[ReplacementResult] =
      IO:
        assertEquals(id.value, album.value)
        assertEquals(target.value, chapter.value)
        assertEquals(previous.map(_.value), NonEmptyList.one(opening.value))
        assertEquals(order.map(_.value), NonEmptyList.one(opening.value))
      .as(result)
    override def add(album: AlbumId, chapter: ChapterId, after: SpreadId): IO[AddResult] =
      IO.raiseError[AddResult](new AssertionError("Unexpected add"))
    override def changeTemplate(album: AlbumId, spread: SpreadId, template: SpreadTemplateId, confirmPlacements: Boolean): IO[ChangeTemplateResult] =
      IO.raiseError[ChangeTemplateResult](new AssertionError("Unexpected template change"))
    override def delete(album: AlbumId, spread: SpreadId, confirmPlacements: Boolean): IO[DeleteResult] =
      IO.raiseError[DeleteResult](new AssertionError("Unexpected deletion"))
    override def readPlacements(album: AlbumId, spread: SpreadId): IO[PlacementReadResult] =
      IO.raiseError[PlacementReadResult](new AssertionError("Unexpected placement read"))
    override def editPlacements(album: AlbumId, spread: SpreadId, request: Edit): IO[PlacementResult] =
      IO.raiseError[PlacementResult](new AssertionError("Unexpected placement edit"))
