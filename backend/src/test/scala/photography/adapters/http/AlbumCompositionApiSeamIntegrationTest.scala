package photography.adapters.http

import cats.effect.IO
import cats.data.NonEmptyList
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{CacheDirective, Method, Request, Status, Uri}
import org.http4s.headers.`Cache-Control`
import org.http4s.circe.CirceEntityCodec.*
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.layout.SpreadId
import photography.ports.album.AlbumCompositionRepository
import photography.ports.album.AlbumCompositionRepository.{CompositionResult, Failure, Member}
import java.util.UUID

final class AlbumCompositionApiSeamIntegrationTest extends CatsEffectSuite:
  private val album          = AlbumId(new UUID(0L, 1L))
  private val chapter        = ChapterId(new UUID(0L, 2L))
  private val opening        = Member(SpreadId(new UUID(0L, 3L)), maybeChapter = None)
  private val chapterOpening = Member(SpreadId(new UUID(0L, 4L)), maybeChapter = chapter.some)
  private val path           = s"/api/albums/${album.value}/composition"

  test("should expose ordered membership without acquiring spread geometry"):
    val members  = NonEmptyList.of(opening, chapterOpening)
    val expected = Json.obj("spreads" -> Json.arr(
      Json.obj("id" -> Json.fromString(opening.id.value.toString), "chapter"        -> Json.Null),
      Json.obj("id" -> Json.fromString(chapterOpening.id.value.toString), "chapter" -> Json.fromString(chapter.value.toString))
    ))

    for
      response <- read(buildRepository(CompositionResult.Found(members).pure[IO]), path)
      body     <- response.as[Json]
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(body, expected)
      assertEquals(response.headers.get[`Cache-Control`], `Cache-Control`(CacheDirective.`no-store`).some)

  test("should preserve absent ownership and reject noncanonical IDs before reading"):
    val absent    = buildRepository(CompositionResult.AlbumNotFound.pure[IO])
    val untouched = buildRepository(IO.raiseError[CompositionResult](new AssertionError("Unexpected read")))

    for
      missing     <- read(absent, path)
      missingBody <- missing.as[Json]
      invalid     <- read(untouched, "/api/albums/1-1-1-1-1/composition")
      invalidBody <- invalid.as[Json]
    yield
      val expected = Json.obj("code" -> Json.fromString("album_not_found"))
      assertEquals(missing.status, Status.NotFound)
      assertEquals(invalid.status, Status.NotFound)
      assertEquals(missingBody, expected)
      assertEquals(invalidBody, expected)

  test("should expose unavailable composition rather than successful empty membership"):
    val repository = buildRepository(CompositionResult.Failed(Failure.Unavailable).pure[IO])

    for
      response <- read(repository, path)
      body     <- response.as[Json]
    yield
      assertEquals(response.status, Status.InternalServerError)
      assertEquals(body, Json.obj("code" -> Json.fromString("service_failure")))

  private def buildRepository(result: IO[CompositionResult]): AlbumCompositionRepository.MembershipRead[IO] =
    new AlbumCompositionRepository.MembershipRead[IO]:
      override def composition(id: AlbumId): IO[CompositionResult] =
        IO(assertEquals(id.value, album.value)) *> result

  private def read(repository: AlbumCompositionRepository.MembershipRead[IO], path: String) =
    AlbumCompositionApi.make(repository).orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString(path)))
