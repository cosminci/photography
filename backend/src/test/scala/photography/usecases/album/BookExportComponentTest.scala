package photography.usecases.album

import cats.data.{NonEmptyList, NonEmptyVector}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.networknt.schema.{ExecutionConfig, InputFormat, SchemaLocation, SchemaRegistry, SpecificationVersion}
import io.circe.{DecodingFailure, Json}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.numeric.Positive
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.domain.album.*
import photography.domain.gear.*
import photography.domain.layout.*
import photography.ports.album.AlbumRepository
import photography.capabilities.Transaction
import photography.ports.cover.CoverRepository
import photography.ports.gear.GearRepository
import photography.ports.photo.PhotoRepository
import photography.usecases.album.BookExport.*

import java.time.{Instant, LocalDate}
import java.nio.file.Path
import java.util.UUID
import scala.jdk.CollectionConverters.*

final class BookExportComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given Transaction[IO, IO] with
    override def run[A](program: IO[A]): IO[A]     = program
    override def resolve[A](program: IO[A]): IO[A] = fail("Export must not resolve writes")
  private val chapterId = ChapterId(new UUID(0L, 2L))
  private val original  = OriginalPhotoLocation(source = "/photos/trip", filename = "one.jpg")
  private val metadata  = PhotoMetadata(hash = "original", maybeCapturedLocal = None, PhotoDimensions(width = 16000, height = 12000))
  private val thumbnail = Thumbnail(ThumbnailLocation(new UUID(0L, 3L)), "version", ThumbnailDimensions(60, 40), ThumbnailQuality.Standard, bytes = 1)
  private val photos    = Vector(1, 2, 3, 4).map: index =>
    Photo(PhotoId(new UUID(1L, index.toLong)), chapterId, original, metadata, thumbnail, maybeCaption = None)
  private val box       = SlotBox(x = 1.0, y = 1.0, width = 40.0, height = 90.0)
  private val transform = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)
  private val photoSlot = SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(0L, 4L)), box, Set.empty[SpreadEdge])
  private val textSlot  = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 5L)), box)
  private val placed    =
    SpreadPlacement(SpreadPlacementId(new UUID(0L, 6L)), photoSlot.id, PhotoId(new UUID(1L, 1L)), transform, CaptionCorner.BottomLeft)
  private val opening = Spread.AlbumStart(SpreadId(new UUID(0L, 7L)), SpreadTemplateId(new UUID(0L, 8L)), Vector(photoSlot), textSlot, Vector(placed))
  private val chapterSlot    = photoSlot.copy(id = PhotoSpreadSlotId(new UUID(0L, 9L)))
  private val chapterOpening = Spread.ChapterStart(
    SpreadId(new UUID(0L, 10L)),
    opening.template,
    Vector(chapterSlot),
    textSlot,
    Vector(placed.copy(id = SpreadPlacementId(new UUID(0L, 11L)), slot = chapterSlot.id, photo = PhotoId(new UUID(1L, 2L))))
  )
  private val country  = Country(CountryId(new UUID(0L, 12L)), "RO", "Romania", AbsoluteLocation(Path.of("/flags/ro.png")))
  private val camera   = Camera(CameraId(new UUID(3L, 1L)), "body", "Sony α6000", CameraKind.InterchangeableLens(LensMount.SonyE, SensorFormat.ApsC))
  private val phone    = Camera(CameraId(new UUID(3L, 2L)), "phone", "Samsung Galaxy S20 FE", CameraKind.FixedLens)
  private val lens     = Lens(LensId(new UUID(3L, 3L)), "lens", "Sigma 30mm f/2.8 DN Art", LensMount.SonyE, SensorFormat.ApsC)
  private val selected = Vector(
    CameraSelection(camera.id, CameraUse.Interchangeable(NonEmptyVector.one(lens.id))),
    CameraSelection(phone.id, CameraUse.FixedLens)
  )
  private val chapter = Chapter(
    chapterId,
    Destination(DestinationId(new UUID(0L, 13L)), "Hội An", NonEmptyList.one(country)),
    VisitPeriod(LocalDate.of(2025, 1, 1), days = 1L),
    "Retained Ș description",
    selected,
    "/photos/trip",
    chapterOpening,
    Vector.empty[Spread.PhotosOnly]
  )
  private val album = Album(AlbumId(new UUID(0L, 1L)), 2025, "Retained Hội An", "Synopsis Ș", Author.all, Instant.EPOCH, opening, Vector(chapter))
  private val cover = Cover(album.id, SpineOwner.Front, CoverSlot(CoverSlotId(new UUID(0L, 14L))), CoverSlot(CoverSlotId(new UUID(0L, 15L))))
  private val coverPlacements = Vector(
    CoverPlacement(CoverPlacementId(new UUID(0L, 16L)), cover.front.id, PhotoId(new UUID(1L, 3L)), transform),
    CoverPlacement(CoverPlacementId(new UUID(0L, 17L)), cover.back.id, PhotoId(new UUID(1L, 4L)), transform)
  )
  private val specification = CoverSpecification.from(pageLimit = 100, width = 10500, height = 3400, spine = 300)
    .fold(message => fail(message), identity)
  private val dimensions = CoverDimensions.forSpreads(2, cats.data.NonEmptyVector.one(specification))
  private val book       =
    SavedBook(album, CoverRepository.Composition(cover, coverPlacements, dimensions), photos, GearCatalog(Vector(camera, phone), Vector(lens)))

  test("should preserve exact saved text and permit empty captions without reading original files"):
    Ref.of[IO, Vector[AlbumId]](Vector.empty).flatMap: calls =>
      for
        exporter <- buildExporter(calls, book)
        result   <- exporter.exportBook(album.id)
        reads    <- calls.get
      yield
        result match
          case Result.Ready(json, filename) =>
            assertEquals(filename, "album-2025-indesign.json")
            assertEquals(
              json.asObject.map(_.keys.toSet),
              Set("formatVersion", "album", "sources", "interior", "chapters", "cover", "gear", "warnings").some
            )
            assertEquals(json.hcursor.downField("album").get[String]("synopsis"), album.synopsis.asRight[DecodingFailure])
            assertEquals(json.hcursor.downField("album").get[String]("title"), (album.title: String).asRight[DecodingFailure])
            assertEquals(json.hcursor.downField("album").get[Vector[String]]("authors"), Vector("Cosmin", "Mara", "Iacob").asRight[DecodingFailure])
            assertEquals(json.hcursor.get[Vector[Json]]("sources").map(_.size), photos.size.asRight[DecodingFailure])
            assertEquals(json.hcursor.downField("sources").downN(0).get[String]("source"), (original.source: String).asRight[DecodingFailure])
            assertEquals(json.hcursor.downField("sources").downN(0).get[String]("filename"), (original.filename: String).asRight[DecodingFailure])
            assertEquals(json.hcursor.downField("sources").downN(0).get[Option[String]]("caption"), none[String].asRight[DecodingFailure])
            assertEquals(json.hcursor.downField("chapters").downN(0).get[String]("description"), chapter.description.asRight[DecodingFailure])
            assertEquals(
              json.hcursor.downField("chapters").downN(0).downField("gear").downN(0).get[Vector[String]]("lensIds"),
              Vector(lens.id.value.toString).asRight[DecodingFailure]
            )
            assertEquals(
              json.hcursor.downField("chapters").downN(0).downField("gear").downN(1).get[Vector[String]]("lensIds"),
              Vector.empty[String].asRight[DecodingFailure]
            )
            assertEquals(json.hcursor.downField("gear").get[Vector[Json]]("cameras").map(_.size), 2.asRight[DecodingFailure])
            assertEquals(json.hcursor.downField("gear").get[Vector[Json]]("lenses").map(_.size), 1.asRight[DecodingFailure])
            assertEquals(json.hcursor.get[Vector[Json]]("warnings"), Vector.empty[Json].asRight[DecodingFailure])
            val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
              .getSchema(SchemaLocation.of("classpath:indesign-book.schema.json"))
            val config = ExecutionConfig.builder().formatAssertionsEnabled(true).build()
            assertEquals(
              schema.validate(json.noSpaces, InputFormat.JSON, context => context.setExecutionConfig(config)).asScala.toVector,
              Vector.empty
            )
          case other => fail(s"Expected validated artifact, got $other")
        assertEquals(reads, Vector(album.id))

  test("should aggregate missing content across the entire book before publishing"):
    Ref.of[IO, Vector[AlbumId]](Vector.empty).flatMap: calls =>
      val incomplete = book.copy(
        album = album.copy(synopsis = " ", chapters = Vector(chapter.copy(description = "\n"))),
        cover = book.cover.copy(placements = Vector.empty[CoverPlacement])
      )
      val exporter = buildExporter(calls, incomplete)
      val expected = Vector(
        Issue(Code.EmptySynopsis, Severity.Blocking, Target.Album, "Album synopsis is empty."),
        Issue(Code.EmptyDescription, Severity.Blocking, Target.Chapter(chapterId), "Hội An: chapter description is empty."),
        Issue(Code.EmptyPhotoSlot, Severity.Blocking, Target.CoverSlot(cover.front.id), "Front cover: no photograph placed."),
        Issue(Code.EmptyPhotoSlot, Severity.Blocking, Target.CoverSlot(cover.back.id), "Back cover: no photograph placed.")
      )

      exporter.flatMap(_.exportBook(album.id)).map(result => assertEquals(result, Result.Blocked(expected)))

  test("should validate photo-only spreads without requiring editorial text"):
    Ref.of[IO, Vector[AlbumId]](Vector.empty).flatMap: calls =>
      val slot   = photoSlot.copy(id = PhotoSpreadSlotId(new UUID(0L, 19L)))
      val spread = Spread.PhotosOnly(
        SpreadId(new UUID(0L, 18L)),
        opening.template,
        NonEmptyList.one(slot),
        Vector(placed.copy(id = SpreadPlacementId(new UUID(0L, 20L)), slot = slot.id))
      )
      val saved = book.copy(
        album = album.copy(chapters = Vector(chapter.copy(spreads = Vector(spread)))),
        cover = book.cover.copy(dimensions = CoverDimensions.forSpreads(3, cats.data.NonEmptyVector.one(specification)))
      )
      val exporter = buildExporter(calls, saved)

      exporter.flatMap(_.exportBook(album.id)).map:
        case Result.Ready(json, filename) =>
          assertEquals(filename, "album-2025-indesign.json")
          assertEquals(json.hcursor.downField("interior").get[Long]("pageCount"), 6L.asRight[DecodingFailure])
          assertEquals(json.hcursor.downField("interior").downField("spreads").downN(2).get[String]("kind"), "photos".asRight[DecodingFailure])
        case other => fail(s"Expected validated photo spread, got $other")

  test("should return explicit acquisition outcomes without reading originals"):
    val found     = AlbumRepository.GetResult.Found(album)
    val cover     = CoverRepository.ReadResult.Found(book.cover)
    val inventory = PhotoRepository.ListResult.Listed(book.photos)
    val catalog   = GearRepository.CatalogReadResult.Found(book.gear)
    val cases     = Vector(
      (album = AlbumRepository.GetResult.NotFound, cover = cover, photos = inventory, gear = catalog, expected = Result.NotFound),
      (
        album = AlbumRepository.GetResult.Failed(AlbumRepository.Failure.DatabaseFailure),
        cover = cover,
        photos = inventory,
        gear = catalog,
        expected = Result.Failed
      ),
      (album = found, cover = CoverRepository.ReadResult.NotFound, photos = inventory, gear = catalog, expected = Result.Failed),
      (album = found, cover = cover, photos = PhotoRepository.ListResult.Failed, gear = catalog, expected = Result.Failed),
      (album = found, cover = cover, photos = PhotoRepository.ListResult.Listed(Vector.empty), gear = catalog, expected = Result.Failed),
      (album = found, cover = cover, photos = inventory, gear = GearRepository.CatalogReadResult.Failed, expected = Result.Failed)
    )

    cases.traverse_ { case (albumRead, coverRead, photoRead, gearRead, expected) =>
      Ref.of[IO, Vector[AlbumId]](Vector.empty).flatMap: calls =>
        val buildExport = BookExport.make(
          buildAlbums(albumRead, calls.update(_ :+ album.id)),
          buildCovers(coverRead),
          buildPhotos(photoRead),
          buildGear(gearRead)
        )

        for
          exporter <- buildExport
          result   <- exporter.exportBook(album.id)
          reads    <- calls.get
        yield
          assertEquals(result, expected)
          assertEquals(reads, Vector(album.id))
    }

  test("should warn strictly below 300 effective DPI without blocking the complete book"):
    val sizes = Vector((width = 5400, height = 3400, warns = false), (width = 5399, height = 3399, warns = true))

    sizes.traverse_ { case (width, height, warns) =>
      Ref.of[IO, Vector[AlbumId]](Vector.empty).flatMap: calls =>
        val pixels = PhotoDimensions(
          width.refineEither[Positive].fold(message => fail(message), identity),
          height.refineEither[Positive].fold(message => fail(message), identity)
        )
        val current  = metadata.copy(dimensions = pixels)
        val saved    = book.copy(photos = photos.map(_.copy(metadata = current)))
        val exporter = buildExporter(calls, saved)
        val expected = if warns then
          Vector(cover.front.id -> "Front cover", cover.back.id -> "Back cover").map { case (slot, side) =>
            Issue(Code.LowResolution, Severity.Warning, Target.CoverSlot(slot), s"$side: one.jpg is 299 DPI (recommended 300 DPI).")
          }
        else Vector.empty[Issue]

        exporter.flatMap(_.exportBook(album.id)).map:
          case Result.Ready(json, filename) =>
            assertEquals(filename, "album-2025-indesign.json")
            assertEquals(json.hcursor.get[Vector[Json]]("warnings"), expected.map(_.asJson).asRight[DecodingFailure])
          case other => fail(s"Expected printable artifact, got $other")
    }

  test("should identify empty interior photo slots before publishing"):
    Ref.of[IO, Vector[AlbumId]](Vector.empty).flatMap: calls =>
      val saved    = book.copy(album = album.copy(opening = opening.copy(placements = Vector.empty[SpreadPlacement])))
      val exporter = buildExporter(calls, saved)
      val expected = Vector(Issue(
        Code.EmptyPhotoSlot,
        Severity.Blocking,
        Target.Slot(opening.id, photoSlot.id),
        "Album opening - spread 1, photo 1: no photograph placed."
      ))

      exporter.flatMap(_.exportBook(album.id)).map(result => assertEquals(result, Result.Blocked(expected)))

  test("should locate resolution warnings by chapter, album spread, photo position and cover side even with duplicate filenames"):
    Ref.of[IO, Vector[AlbumId]](Vector.empty).flatMap: calls =>
      val secondSlot = photoSlot.copy(id = PhotoSpreadSlotId(new UUID(0L, 21L)))
      val saved      = book.copy(
        album = album.copy(opening =
          opening.copy(
            photoSlots = Vector(photoSlot, secondSlot),
            placements = Vector(placed, placed.copy(id = SpreadPlacementId(new UUID(0L, 22L)), slot = secondSlot.id))
          )
        ),
        photos = photos.map(_.copy(metadata = metadata.copy(dimensions = PhotoDimensions(width = 1, height = 1))))
      )
      val expected = Vector(
        Target.Slot(opening.id, photoSlot.id)          -> "Album opening - spread 1, photo 1",
        Target.Slot(opening.id, secondSlot.id)         -> "Album opening - spread 1, photo 2",
        Target.Slot(chapterOpening.id, chapterSlot.id) -> "Hội An - spread 2, photo 1",
        Target.CoverSlot(cover.front.id)               -> "Front cover",
        Target.CoverSlot(cover.back.id)                -> "Back cover"
      ).map { case (target, location) =>
        Issue(Code.LowResolution, Severity.Warning, target, s"$location: one.jpg is 0 DPI (recommended 300 DPI).").asJson
      }

      buildExporter(calls, saved).flatMap(_.exportBook(album.id)).map:
        case Result.Ready(json, _) => assertEquals(json.hcursor.get[Vector[Json]]("warnings"), expected.asRight[DecodingFailure])
        case other                 => fail(s"Expected actionable warnings, got $other")

  test("should retry saved-state export without reading originals or exporting unused collection photos"):
    Ref.of[IO, Vector[AlbumId]](Vector.empty).flatMap: calls =>
      val unused = photos.take(1).map(_.copy(id = PhotoId(new UUID(9L, 9L))))
      val saved  = book.copy(photos = photos ++ unused)
      for
        exporter <- buildExporter(calls, saved)
        first    <- exporter.exportBook(album.id)
        second   <- exporter.exportBook(album.id)
        reads    <- calls.get
      yield
        first match
          case Result.Ready(json, filename) =>
            assertEquals(filename, "album-2025-indesign.json")
            assertEquals(json.hcursor.get[Vector[Json]]("sources").map(_.size), photos.size.asRight[DecodingFailure])
          case other => fail(s"Expected validated artifact, got $other")
        assertEquals(second, first)
        assertEquals(reads, Vector(album.id, album.id))

  test("should report unexpected saved-state acquisition failure explicitly"):
    Ref.of[IO, Vector[AlbumId]](Vector.empty).flatMap: calls =>
      val exporter = BookExport.make(
        buildAlbums(
          AlbumRepository.GetResult.Found(album),
          calls.update(_ :+ album.id) >> IO.raiseError[Unit](new IllegalStateException("Album unavailable"))
        ),
        buildCovers(CoverRepository.ReadResult.Found(book.cover)),
        buildPhotos(PhotoRepository.ListResult.Listed(book.photos)),
        buildGear(GearRepository.CatalogReadResult.Found(book.gear))
      )

      exporter.flatMap(_.exportBook(album.id)).map(result => assertEquals(result, Result.Failed))

  test("should fail export when generated JSON violates the handoff date format"):
    Ref.of[IO, Vector[AlbumId]](Vector.empty).flatMap: calls =>
      val extendedDate = book.copy(album =
        album.copy(chapters =
          Vector(chapter.copy(period = VisitPeriod(LocalDate.of(10000, 1, 1), days = 1L)))
        )
      )
      val exporter = buildExporter(calls, extendedDate)

      exporter.flatMap(_.exportBook(album.id)).map(result => assertEquals(result, Result.Failed))

  test("should retain authoritative frames, mirroring, every caption corner and either cover spine owner"):
    val corners = Vector(
      CaptionCorner.TopLeft     -> "top_left",
      CaptionCorner.TopRight    -> "top_right",
      CaptionCorner.BottomLeft  -> "bottom_left",
      CaptionCorner.BottomRight -> "bottom_right"
    )
    corners.traverse_ { case (corner, encodedCorner) =>
      Vector(SpineOwner.Front -> "front", SpineOwner.Back -> "back").traverse_ { case (owner, encodedOwner) =>
        Ref.of[IO, Vector[AlbumId]](Vector.empty).flatMap: calls =>
          val frame     = photoSlot.copy(box = SlotBox(x = 1.0, y = 1.0, width = 90.0, height = 90.0), bleed = SpreadEdge.values.toSet)
          val placement = placed.copy(captionCorner = corner, transform = PhotoTransform(Shift(x = 12.0, y = -8.0), mirrored = true, zoom = 1.5))
          val saved     = book.copy(
            album = album.copy(opening = opening.copy(photoSlots = Vector(frame), placements = Vector(placement))),
            cover = book.cover.copy(cover = cover.copy(spineOwner = owner))
          )

          buildExporter(calls, saved).flatMap(_.exportBook(album.id)).map:
            case Result.Ready(json, filename) =>
              assertEquals(filename, "album-2025-indesign.json")
              assertEquals(json.hcursor.downField("cover").get[String]("spineOwner"), encodedOwner.asRight[DecodingFailure])
              val slot = json.hcursor.downField("interior").downField("spreads").downN(0).downField("slots").downN(0)
              Vector("xPt" -> -3.5, "yPt" -> -3.5, "widthPt" -> 2388.102, "heightPt" -> 772.354).foreach { case (key, expected) =>
                assertEquals(
                  slot.downField("frame").get[Double](key).map(value => math.abs(value - expected) < 0.000001),
                  true.asRight[DecodingFailure]
                )
              }
              Vector("xPt" -> 23.81102, "yPt" -> 7.65354, "widthPt" -> 2142.9918, "heightPt" -> 688.8186).foreach { case (key, expected) =>
                assertEquals(
                  slot.downField("captionFrame").get[Double](key).map(value => math.abs(value - expected) < 0.000001),
                  true.asRight[DecodingFailure]
                )
              }
              val projected = json.hcursor.downField("interior").downField("spreads").downN(0).downField("placements").downN(0)
              assertEquals(projected.get[String]("captionCorner"), encodedCorner.asRight[DecodingFailure])
              assertEquals(projected.downField("transform").get[Boolean]("mirrored"), true.asRight[DecodingFailure])
              assertEquals(projected.downField("transform").get[Double]("xPt"), 12.0.asRight[DecodingFailure])
              assertEquals(projected.downField("transform").get[Double]("zoom"), 1.5.asRight[DecodingFailure])
            case other => fail(s"Expected validated print projection, got $other")
      }
    }

  test("should preserve each issue code, severity and target in the shared handoff representation"):
    val codes = Vector(
      Code.EmptySynopsis    -> "empty_synopsis",
      Code.EmptyDescription -> "empty_description",
      Code.EmptyPhotoSlot   -> "empty_photo_slot",
      Code.LowResolution    -> "low_resolution"
    )
    val targets = Vector(
      Target.Album                          -> Json.obj("kind" -> Json.fromString("album")),
      Target.Chapter(chapterId)             -> Json.obj("kind" -> Json.fromString("chapter"), "chapter" -> Json.fromString(chapterId.value.toString)),
      Target.Slot(opening.id, photoSlot.id) -> Json.obj(
        "kind"   -> Json.fromString("slot"),
        "spread" -> Json.fromString(opening.id.value.toString),
        "slot"   -> Json.fromString(photoSlot.id.value.toString)
      ),
      Target.CoverSlot(cover.front.id) -> Json.obj("kind" -> Json.fromString("cover"), "slot" -> Json.fromString(cover.front.id.value.toString)),
      Target.Photo(placed.photo)       -> Json.obj("kind" -> Json.fromString("photo"), "photo" -> Json.fromString(placed.photo.value.toString))
    )
    codes.foreach { case (code, encodedCode) =>
      targets.foreach { case (target, encodedTarget) =>
        Vector(Severity.Blocking -> "blocking", Severity.Warning -> "warning").foreach { case (severity, encodedSeverity) =>
          val issue    = Issue(code, severity, target, "Actionable saved-book location.")
          val expected = Json.obj(
            "code"     -> Json.fromString(encodedCode),
            "severity" -> Json.fromString(encodedSeverity),
            "target"   -> encodedTarget,
            "message"  -> Json.fromString("Actionable saved-book location.")
          )

          assertEquals(issue.asJson, expected)
        }
      }
    }

  test("should reject incomplete structure, invalid identities and invalid nested geometry against the packaged handoff schema"):
    Ref.of[IO, Vector[AlbumId]](Vector.empty).flatMap: calls =>
      buildExporter(calls, book).flatMap(_.exportBook(album.id)).map:
        case Result.Ready(json, _) =>
          val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(SchemaLocation.of("classpath:indesign-book.schema.json"))
          val config    = ExecutionConfig.builder().formatAssertionsEnabled(true).build()
          val mutations = Vector(
            "missing cover"  -> json.mapObject(_.remove("cover")).some,
            "extra property" -> json.mapObject(_.add("obsolete", Json.True)).some,
            "invalid UUID"   -> json.hcursor.downField("album").downField("id").withFocus(_ => Json.fromString("not-a-uuid")).top,
            "invalid date"   -> json.hcursor.downField("chapters").downN(0).downField("firstDay").withFocus(_ => Json.fromString("2025-02-30")).top,
            "invalid frame"  -> json.hcursor.downField("cover").downField("frontFrame").downField("widthPt").withFocus(_ => Json.fromInt(0)).top
          )
          mutations.foreach { case (scenario, maybeJson) =>
            val mutated = maybeJson.getOrElse(fail(s"Mutation failed: $scenario"))

            assert(!schema.validate(mutated.noSpaces, InputFormat.JSON, context => context.setExecutionConfig(config)).isEmpty, scenario)
          }
        case other => fail(s"Expected validated artifact, got $other")

  test("should fail projection instead of generating a partial handoff when geometry cannot be converted"):
    val invalidSlot = photoSlot.copy(box = SlotBox(x = 0.0, y = 0.0, width = 0.001, height = 0.001))
    val invalid     = book.copy(album = album.copy(opening = opening.copy(photoSlots = Vector(invalidSlot))))

    assert(BookExportJson.encode(invalid, warnings = Vector.empty[Issue]).isLeft)

  private def buildExporter(calls: Ref[IO, Vector[AlbumId]], saved: SavedBook)(using
      Transaction[IO, IO]
  ) =
    BookExport.make(
      buildAlbums(AlbumRepository.GetResult.Found(saved.album), calls.update(_ :+ saved.album.id)),
      buildCovers(CoverRepository.ReadResult.Found(saved.cover)),
      buildPhotos(PhotoRepository.ListResult.Listed(saved.photos)),
      buildGear(GearRepository.CatalogReadResult.Found(saved.gear))
    )

  private def buildAlbums(response: AlbumRepository.GetResult, onRead: IO[Unit]) = new AlbumRepository[IO]:
    override def get(id: AlbumId): IO[AlbumRepository.GetResult]                          = (onRead >> IO(assertEquals(id, album.id))).as(response)
    override def list(offset: Int, limit: Int): IO[AlbumRepository.ListResult]            = fail("Read the requested album")
    override def create(album: Album, cover: Cover): IO[AlbumRepository.CreateResult]     = fail("Read-only export")
    override def rename(id: AlbumId, title: AlbumTitle): IO[AlbumRepository.RenameResult] = fail("Read-only export")
    override def saveSynopsis(id: AlbumId, text: String): IO[AlbumRepository.SaveSynopsisResult]        = fail("Read-only export")
    override def saveAuthors(id: AlbumId, authors: AlbumAuthors): IO[AlbumRepository.SaveAuthorsResult] = fail("Read-only export")
    override def delete(id: AlbumId): IO[AlbumRepository.DeleteResult]                                  = fail("Read-only export")

  private def buildCovers(response: CoverRepository.ReadResult) = new CoverRepository[IO]:
    override def read(id: AlbumId): IO[CoverRepository.ReadResult] = IO(assertEquals(id, album.id)).as(response)
    override def replace(id: AlbumId, placements: Vector[CoverPlacement]): IO[CoverRepository.ReplaceResult] = fail("Read-only export")
    override def setSpineOwner(id: AlbumId, owner: SpineOwner): IO[CoverRepository.SetSpineOwnerResult]      = fail("Read-only export")

  private def buildPhotos(response: PhotoRepository.ListResult) = new PhotoRepository[IO]:
    override def listChapter(id: ChapterId): IO[PhotoRepository.ListResult] = IO(assertEquals(id, chapterId)).as(response)
    override def find(id: PhotoId): IO[PhotoRepository.FindResult]          = fail("Acquire chapter inventory")
    override def saveCaption(chapter: ChapterId, photo: PhotoId, text: String): IO[PhotoRepository.SaveCaptionResult] = fail("Read-only export")
    override def save(photo: Photo): IO[PhotoRepository.WriteResult]                                                  = fail("Read-only export")
    override def remove(id: PhotoId): IO[PhotoRepository.WriteResult]                                                 = fail("Read-only export")
    override def recordWrite(intent: PhotoWriteIntent): IO[PhotoRepository.RecordWriteResult]                         = fail("Read-only export")
    override def completeWrite(id: PhotoWriteIntentId): IO[PhotoRepository.CompleteWriteResult]                       = fail("Read-only export")
    override def listUnfinishedWrites: IO[PhotoRepository.ListUnfinishedWritesResult]                                 = fail("Read-only export")

  private def buildGear(response: GearRepository.CatalogReadResult) = new GearRepository[IO]:
    override def readCatalog: IO[GearRepository.CatalogReadResult]                   = response.pure[IO]
    override def createCamera(camera: Camera): IO[GearRepository.CameraCreateResult] = fail("Read-only export")
    override def updateCamera(camera: Camera): IO[GearRepository.CameraUpdateResult] = fail("Read-only export")
    override def deleteCamera(id: CameraId): IO[GearRepository.CameraDeleteResult]   = fail("Read-only export")
    override def createLens(lens: Lens): IO[GearRepository.LensCreateResult]         = fail("Read-only export")
    override def updateLens(lens: Lens): IO[GearRepository.LensUpdateResult]         = fail("Read-only export")
    override def deleteLens(id: LensId): IO[GearRepository.LensDeleteResult]         = fail("Read-only export")
