package photography.adapters.http

import cats.effect.IO
import cats.data.{NonEmptyList, NonEmptyVector}
import cats.syntax.all.*
import io.circe.{DecodingFailure, Json}
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityCodec.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.domain.album.*
import photography.domain.gear.*
import photography.domain.layout.*
import photography.ports.cover.CoverRepository
import photography.usecases.album.BookExport
import photography.usecases.album.BookExport.*
import java.util.UUID
import java.time.{Instant, LocalDate}
import java.nio.file.Path

final class BookExportApiSeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val album        = AlbumId(new UUID(0L, 1L))
  private val path         = s"/api/albums/${album.value}/indesign-export"
  private val spread       = SpreadId(new UUID(0L, 2L))
  private val slot         = PhotoSpreadSlotId(new UUID(0L, 3L))

  test("should download one versioned complete book with exact authored characters and no saved-session payload"):
    val book    = buildBook
    val warning = Issue(Code.LowResolution, Severity.Warning, Target.CoverSlot(book.cover.cover.front.id))

    for
      response <- read(Result.Ready(book, Vector(warning)), path)
      body     <- response.as[Json]
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(
        body.asObject.map(_.keys.toSet),
        Set("formatVersion", "album", "sources", "interior", "chapters", "cover", "gear", "warnings").some
      )
      assertEquals(body.hcursor.get[Int]("formatVersion"), 1.asRight[DecodingFailure])
      assertEquals(body.hcursor.downField("album").get[String]("synopsis"), book.album.synopsis.asRight[DecodingFailure])
      assertEquals(body.hcursor.downField("album").get[String]("title"), (book.album.title: String).asRight[DecodingFailure])
      assertEquals(body.hcursor.downField("interior").get[Long]("pageCount"), 6L.asRight[DecodingFailure])
      assertEquals(body.hcursor.get[Vector[Json]]("sources").map(_.size), 3.asRight[DecodingFailure])
      assertEquals(body.hcursor.get[Vector[Json]]("chapters").map(_.size), 1.asRight[DecodingFailure])
      assertEquals(body.hcursor.get[Vector[Json]]("warnings").map(_.size), 1.asRight[DecodingFailure])
      assertEquals(body.hcursor.downField("cover").get[String]("spineOwner"), "front".asRight[DecodingFailure])
      val photoSlot = body.hcursor.downField("interior").downField("spreads").downN(2).downField("slots").downN(0)
      Vector("xPt" -> -3.5, "yPt" -> -3.5, "widthPt" -> 2388.102, "heightPt" -> 772.354).foreach { case (key, expected) =>
        assertEquals(photoSlot.downField("frame").get[Double](key).map(value => math.abs(value - expected) < 0.000001), true.asRight[DecodingFailure])
      }
      Vector("xPt" -> 23.81102, "yPt" -> 7.65354, "widthPt" -> 2142.9918, "heightPt" -> 688.8186).foreach { case (key, expected) =>
        assertEquals(
          photoSlot.downField("captionFrame").get[Double](key).map(value => math.abs(value - expected) < 0.000001),
          true.asRight[DecodingFailure]
        )
      }
      assertEquals(
        response.headers.get(org.typelevel.ci.CIString("Content-Disposition")).map(_.head.value),
        """attachment; filename="album-2025-indesign.json"""".some
      )

  test("should fail explicitly instead of emitting a partial book when ready geometry cannot be projected"):
    val original = buildBook
    val invalid  = SlotBox(x = 0.0, y = 0.0, width = 0.001, height = 0.001)
    val chapters = original.album.chapters.map(chapter =>
      chapter.copy(spreads =
        chapter.spreads.map(spread =>
          spread.copy(photoSlots = spread.photoSlots.map(_.copy(box = invalid)))
        )
      )
    )
    for
      response <- read(Result.Ready(original.copy(album = original.album.copy(chapters = chapters)), Vector.empty[Issue]), path)
      body     <- response.as[Json]
    yield
      assertEquals(response.status, Status.InternalServerError)
      assertEquals(body, Json.obj("code" -> Json.fromString("service_failure")))
      assertEquals(response.headers.get(org.typelevel.ci.CIString("Content-Disposition")), None)

  test("should report complete scoped blocking issues without returning a printable artifact or acquiring editing access"):
    val issue    = Issue(Code.EmptyPhotoSlot, Severity.Blocking, Target.Slot(spread, slot))
    val expected = Json.obj(
      "code"   -> Json.fromString("export_blocked"),
      "issues" -> Json.arr(Json.obj(
        "code"     -> Json.fromString("empty_photo_slot"),
        "severity" -> Json.fromString("blocking"),
        "target"   -> Json.obj(
          "kind"   -> Json.fromString("slot"),
          "spread" -> Json.fromString(spread.value.toString),
          "slot"   -> Json.fromString(slot.value.toString)
        )
      ))
    )

    for
      response <- read(Result.Blocked(Vector(issue)), path)
      body     <- response.as[Json]
    yield
      assertEquals(response.status, Status.UnprocessableContent)
      assertEquals(body, expected)
      assertEquals(response.headers.get(org.typelevel.ci.CIString("Cache-Control")).map(_.head.value), "no-store".some)
      assertEquals(response.headers.get(org.typelevel.ci.CIString("Content-Disposition")), None)

  test("should distinguish unavailable saved state from an absent album"):
    Vector(Result.NotFound -> Status.NotFound, Result.Failed -> Status.InternalServerError).traverse_ { case (result, status) =>
      for
        response <- read(result, path)
        body     <- response.as[Json]
      yield
        val code = if status === Status.NotFound then "album_not_found" else "service_failure"
        assertEquals(response.status, status)
        assertEquals(body, Json.obj("code" -> Json.fromString(code)))
    }

  test("should reject a noncanonical album identity without invoking export"):
    val forbidden = new BookExport[IO]:
      override def exportBook(id: AlbumId): IO[Result] = fail("Invalid IDs must not trigger acquisition")

    for
      response <- PublicRoutes(BookExportApi.make(forbidden)).orNotFound.run(Request[IO](
        Method.GET,
        Uri.unsafeFromString("/api/albums/1-1-1-1-1/indesign-export")
      ))
      body <- response.as[Json]
    yield
      assertEquals(response.status, Status.NotFound)
      assertEquals(body, Json.obj("code" -> Json.fromString("album_not_found")))

  test("should preserve every validation code and affected owner in the HTTP response"):
    val codes = Vector(
      Code.EmptySynopsis       -> "empty_synopsis",
      Code.EmptyDescription    -> "empty_description",
      Code.EmptyPhotoSlot      -> "empty_photo_slot",
      Code.LowResolution       -> "low_resolution",
      Code.OriginalChanged     -> "original_changed",
      Code.OriginalUndecodable -> "original_undecodable",
      Code.OriginalUnstable    -> "original_unstable",
      Code.OriginalUnavailable -> "original_unavailable"
    )
    val targets = Vector(
      Target.Album                                -> Json.obj("kind" -> Json.fromString("album")),
      Target.Chapter(ChapterId(new UUID(0L, 7L))) ->
        Json.obj("kind" -> Json.fromString("chapter"), "chapter" -> Json.fromString(new UUID(0L, 7L).toString)),
      Target.CoverSlot(CoverSlotId(new UUID(0L, 10L))) ->
        Json.obj("kind" -> Json.fromString("cover"), "slot" -> Json.fromString(new UUID(0L, 10L).toString)),
      Target.Photo(PhotoId(new UUID(1L, 1L))) -> Json.obj("kind" -> Json.fromString("photo"), "photo" -> Json.fromString(new UUID(1L, 1L).toString))
    )
    val issues   = codes.flatMap { case (code, _) => targets.map { case (target, _) => Issue(code, Severity.Blocking, target) } }
    val expected = codes.flatMap { case (_, name) =>
      targets.map { case (_, scope) =>
        Json.obj("code" -> Json.fromString(name), "severity" -> Json.fromString("blocking"), "target" -> scope)
      }
    }

    for
      response <- read(Result.Blocked(issues), path)
      body     <- response.as[Json]
    yield assertEquals(body.hcursor.get[Vector[Json]]("issues"), expected.asRight[DecodingFailure])

  test("should retain every caption corner and either cover spine owner in the print handoff"):
    val corners = Vector(
      CaptionCorner.TopLeft     -> "top_left",
      CaptionCorner.TopRight    -> "top_right",
      CaptionCorner.BottomLeft  -> "bottom_left",
      CaptionCorner.BottomRight -> "bottom_right"
    )

    corners.traverse_ { case (corner, encodedCorner) =>
      Vector(SpineOwner.Front -> "front", SpineOwner.Back -> "back").traverse_ { case (owner, encodedOwner) =>
        val original = buildBook
        val chapters = original.album.chapters.map(chapter =>
          chapter.copy(spreads =
            chapter.spreads.map(spread => spread.copy(placements = spread.placements.map(_.copy(captionCorner = corner))))
          )
        )
        val saved = original.copy(
          album = original.album.copy(chapters = chapters),
          cover = original.cover.copy(cover = original.cover.cover.copy(spineOwner = owner))
        )

        for
          response <- read(Result.Ready(saved, warnings = Vector.empty[Issue]), path)
          body     <- response.as[Json]
        yield
          assertEquals(body.hcursor.downField("cover").get[String]("spineOwner"), encodedOwner.asRight[DecodingFailure])
          assertEquals(
            body.hcursor.downField("interior").downField("spreads").downN(2)
              .downField("placements").downN(0).get[String]("captionCorner"),
            encodedCorner.asRight[DecodingFailure]
          )
      }
    }

  private def read(result: Result, path: String) =
    val exports = new BookExport[IO]:
      override def exportBook(id: AlbumId): IO[Result] = IO(assertEquals(id, album)).as(result)
    PublicRoutes(BookExportApi.make(exports)).orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString(path)))

  private def buildBook =
    val box            = SlotBox(x = 1.0, y = 1.0, width = 90.0, height = 90.0)
    val textSlot       = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 4L)), box)
    val template       = SpreadTemplateId(new UUID(0L, 5L))
    val opening        = Spread.AlbumStart(spread, template, Vector.empty[SpreadSlot.Photo], textSlot, Vector.empty[SpreadPlacement])
    val country        = Country(CountryId(new UUID(0L, 6L)), "RO", "Romania", AbsoluteLocation(Path.of("/flags/ro.png")))
    val chapterId      = ChapterId(new UUID(0L, 7L))
    val chapterOpening =
      Spread.ChapterStart(SpreadId(new UUID(0L, 8L)), template, Vector.empty[SpreadSlot.Photo], textSlot, Vector.empty[SpreadPlacement])
    val camera   = Camera(CameraId(new UUID(3L, 1L)), "body", "Sony α6000", CameraKind.InterchangeableLens(LensMount.SonyE, SensorFormat.ApsC))
    val phone    = Camera(CameraId(new UUID(3L, 2L)), "phone", "Samsung Galaxy S20 FE", CameraKind.FixedLens)
    val lens     = Lens(LensId(new UUID(3L, 3L)), "lens", "Sigma 30mm f/2.8 DN Art", LensMount.SonyE, SensorFormat.ApsC)
    val selected =
      Vector(CameraSelection(camera.id, CameraUse.Interchangeable(NonEmptyVector.one(lens.id))), CameraSelection(phone.id, CameraUse.FixedLens))
    val photoSlot      = SpreadSlot.Photo(slot, box, SpreadEdge.values.toSet)
    val photoPlacement = SpreadPlacement(
      SpreadPlacementId(new UUID(0L, 20L)),
      slot,
      PhotoId(new UUID(1L, 3L)),
      PhotoTransform(Shift(x = 12.0, y = -8.0), mirrored = true, zoom = 1.5),
      CaptionCorner.BottomLeft
    )
    val photoSpread = Spread.PhotosOnly(SpreadId(new UUID(0L, 21L)), template, NonEmptyList.one(photoSlot), Vector(photoPlacement))
    val chapter     = Chapter(
      chapterId,
      Destination(DestinationId(new UUID(0L, 9L)), "Hội An", NonEmptyList.one(country)),
      VisitPeriod(LocalDate.of(2025, 1, 1), days = 1L),
      "Ș description",
      selected,
      "/photos/trip",
      chapterOpening,
      Vector(photoSpread)
    )
    val saved     = Album(album, 2025, "Ș title", "Exact Hội An", Author.all, Instant.EPOCH, opening, Vector(chapter))
    val cover     = Cover(album, SpineOwner.Front, CoverSlot(CoverSlotId(new UUID(0L, 10L))), CoverSlot(CoverSlotId(new UUID(0L, 11L))))
    val transform = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = true, zoom = 1.5)
    val photos    = Vector(1, 2, 3).map: index =>
      Photo(
        PhotoId(new UUID(1L, index.toLong)),
        chapterId,
        OriginalPhotoLocation("/photos/trip", s"photo-$index.jpg".refineEither[PhotoFilenameConstraint].fold(message => fail(message), identity)),
        PhotoMetadata("hash", None, PhotoDimensions(16000, 12000)),
        Thumbnail(ThumbnailLocation(new UUID(2L, index.toLong)), "v1", ThumbnailDimensions(10, 10), ThumbnailQuality.Standard, bytes = 1),
        maybeCaption = None
      )
    val placements = Vector(
      CoverPlacement(CoverPlacementId(new UUID(0L, 12L)), cover.front.id, PhotoId(new UUID(1L, 1L)), transform),
      CoverPlacement(CoverPlacementId(new UUID(0L, 13L)), cover.back.id, PhotoId(new UUID(1L, 2L)), transform)
    )
    val specification = CoverSpecification.from(pageLimit = 100, width = 10500, height = 3400, spine = 300).fold(message => fail(message), identity)
    SavedBook(
      saved,
      CoverRepository.Composition(cover, placements, CoverDimensions.forSpreads(3, NonEmptyVector.one(specification))),
      photos,
      GearCatalog(Vector(camera, phone), Vector(lens))
    )
