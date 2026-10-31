package photography.adapters.http

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.parse
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.http4s.{MediaType, Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityCodec.*
import photography.domain.layout.*
import photography.ports.spread.SpreadTemplateRepository
import photography.ports.spread.SpreadTemplateRepository.{FindTemplateResult, ListAlbumStartTemplatesResult, ListChapterStartTemplatesResult, ListPhotosOnlyTemplatesResult}

import java.util.UUID

final class SpreadTemplateApiSeamIntegrationTest extends CatsEffectSuite:
  private val photoBox      = SlotBox(x = 0.0.refineUnsafe, y = 0.0.refineUnsafe, width = 21.0.refineUnsafe, height = 28.0.refineUnsafe)
  private val synopsisBox   = SlotBox(x = 21.0.refineUnsafe, y = 0.0.refineUnsafe, width = 21.0.refineUnsafe, height = 28.0.refineUnsafe)
  private val firstTemplate =
    SpreadTemplate.AlbumStart(SpreadTemplateId(new UUID(0L, 2L)), name = "Synopsis right".refineUnsafe, Vector(photoBox), synopsisBox)
  private val secondTemplate = firstTemplate.copy(id = SpreadTemplateId(new UUID(0L, 4L)), name = "Second".refineUnsafe)
  private val catalog        = NonEmptyList.of(firstTemplate, secondTemplate)

  test("should list album opening templates with ordered geometry and report catalog failures"):
    val server            = buildSpreadTemplateApi(ListAlbumStartTemplatesResult.Listed(catalog))
    val unavailableServer = buildSpreadTemplateApi(ListAlbumStartTemplatesResult.Failed(SpreadTemplateRepository.Failure.DatabaseFailure))
    val request           = Request[IO](Method.GET, Uri.unsafeFromString("/api/templates/album-start"))
    val expectedPhoto     = """{"kind":"photo","x":0.0,"y":0.0,"width":21.0,"height":28.0}"""
    val expectedText      = """{"kind":"text","x":21.0,"y":0.0,"width":21.0,"height":28.0}"""
    val expectedFirst     = s"""{"id":"${firstTemplate.id.value}","name":"Synopsis right","slots":[$expectedPhoto,$expectedText]}"""
    val expectedSecond    = s"""{"id":"${secondTemplate.id.value}","name":"Second","slots":[$expectedPhoto,$expectedText]}"""
    val expectedCatalog   = parse(s"""{"templates":[$expectedFirst,$expectedSecond]}""").fold(error => fail(error.message), identity)
    val expectedFailure   = Json.obj("code" -> Json.fromString("service_failure"))

    for
      listed      <- server.run(request)
      catalogBody <- listed.as[Json]
      unavailable <- unavailableServer.run(request)
      failureBody <- unavailable.as[Json]
    yield
      assertEquals(listed.status -> catalogBody, Status.Ok -> expectedCatalog)
      assertEquals(listed.contentType.map(_.mediaType), MediaType.application.json.some)
      assertEquals(unavailable.status -> failureBody, Status.InternalServerError -> expectedFailure)
      assertEquals(unavailable.contentType.map(_.mediaType), MediaType.application.json.some)

  test("should list photo layouts and chapter openings with ordered geometry and report catalog failures"):
    val photoLayout   = SpreadTemplate.PhotosOnly(SpreadTemplateId(new UUID(0L, 30L)), name = "Single".refineUnsafe, NonEmptyList.one(photoBox))
    val chapterLayout = SpreadTemplate.ChapterStart(SpreadTemplateId(new UUID(0L, 31L)), name = "Opening".refineUnsafe, Vector(photoBox), synopsisBox)
    val catalogs      = buildCatalogApi(
      ListPhotosOnlyTemplatesResult.Listed(NonEmptyList.one(photoLayout)),
      ListChapterStartTemplatesResult.Listed(NonEmptyList.one(chapterLayout))
    )
    val unavailable =
      buildCatalogApi(
        ListPhotosOnlyTemplatesResult.Failed(SpreadTemplateRepository.Failure.DatabaseFailure),
        ListChapterStartTemplatesResult.Failed(SpreadTemplateRepository.Failure.DatabaseFailure)
      )
    val photosRequest    = Request[IO](Method.GET, Uri.unsafeFromString("/api/templates/photos-only"))
    val chapterRequest   = Request[IO](Method.GET, Uri.unsafeFromString("/api/templates/chapter-start"))
    val expectedPhoto    = """{"kind":"photo","x":0.0,"y":0.0,"width":21.0,"height":28.0}"""
    val expectedText     = """{"kind":"text","x":21.0,"y":0.0,"width":21.0,"height":28.0}"""
    val expectedPhotos   = parse(s"""{"templates":[{"id":"${photoLayout.id.value}","name":"Single","slots":[$expectedPhoto]}]}""")
    val expectedChapters = parse(s"""{"templates":[{"id":"${chapterLayout.id.value}","name":"Opening","slots":[$expectedPhoto,$expectedText]}]}""")

    for
      photos            <- catalogs.run(photosRequest)
      photoBody         <- photos.as[Json]
      chapters          <- catalogs.run(chapterRequest)
      chapterBody       <- chapters.as[Json]
      failed            <- unavailable.run(photosRequest)
      failedBody        <- failed.as[Json]
      failedChapters    <- unavailable.run(chapterRequest)
      failedChapterBody <- failedChapters.as[Json]
    yield
      assertEquals(photos.status   -> photoBody, Status.Ok                   -> expectedPhotos.fold(error => fail(error.message), identity))
      assertEquals(chapters.status -> chapterBody, Status.Ok                 -> expectedChapters.fold(error => fail(error.message), identity))
      assertEquals(failed.status   -> failedBody, Status.InternalServerError -> Json.obj("code" -> Json.fromString("service_failure")))
      assertEquals(failedChapters.status -> failedChapterBody, Status.InternalServerError -> Json.obj("code" -> Json.fromString("service_failure")))

  private def buildCatalogApi(
      photos: ListPhotosOnlyTemplatesResult,
      chapters: ListChapterStartTemplatesResult
  ) =
    val templates = new SpreadTemplateRepository[IO]:
      override def listAlbumStartTemplates: IO[ListAlbumStartTemplatesResult] =
        IO.raiseError[ListAlbumStartTemplatesResult](new AssertionError("Unexpected album templates"))
      override def listChapterStartTemplates: IO[ListChapterStartTemplatesResult] = chapters.pure[IO]
      override def listPhotosOnlyTemplates: IO[ListPhotosOnlyTemplatesResult]     = photos.pure[IO]
      override def findTemplate(id: SpreadTemplateId): IO[FindTemplateResult]     =
        IO.raiseError[FindTemplateResult](new AssertionError("Unexpected template lookup"))
    SpreadTemplateApi.make(templates).routes.orNotFound

  private def buildSpreadTemplateApi(result: ListAlbumStartTemplatesResult) =
    val templates = new SpreadTemplateRepository[IO]:
      override def listPhotosOnlyTemplates: IO[ListPhotosOnlyTemplatesResult] =
        IO.raiseError[ListPhotosOnlyTemplatesResult](new AssertionError("Unexpected photo templates"))
      override def findTemplate(id: SpreadTemplateId): IO[FindTemplateResult] =
        IO.raiseError[FindTemplateResult](new AssertionError("Unexpected template lookup"))
      override def listAlbumStartTemplates: IO[ListAlbumStartTemplatesResult]     = result.pure[IO]
      override def listChapterStartTemplates: IO[ListChapterStartTemplatesResult] =
        ListChapterStartTemplatesResult.Failed(SpreadTemplateRepository.Failure.DatabaseFailure).pure[IO]
    SpreadTemplateApi.make(templates).routes.orNotFound
