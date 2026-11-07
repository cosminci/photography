package photography.usecases.album

import cats.data.{NonEmptyList, NonEmptyVector}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.networknt.schema.{ExecutionConfig, InputFormat, SchemaLocation, SchemaRegistry, SpecificationVersion}
import io.circe.{DecodingFailure, Json}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.numeric.Positive
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.domain.album.*
import photography.domain.gear.*
import photography.domain.layout.*
import photography.capabilities.Transaction
import photography.usecases.cover.CoverManager
import photography.ports.gear.GearRepository
import photography.ports.photo.PhotoRepository
import photography.ports.album.{AlbumRepository, AlbumCompositionRepository}
import photography.ports.album.AlbumCompositionRepository.{Located, Member, SpreadResult, CompositionResult}
import photography.ports.chapter.ChapterRepository
import photography.usecases.album.BookExport.*

import java.time.{Instant, LocalDate}
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
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
    Destination("Hội An", NonEmptyList.one(country)),
    VisitPeriod(LocalDate.of(2025, 1, 1), days = 1L),
    "Retained Ș description",
    selected,
    "/photos/trip",
    chapterOpening,
    Vector.empty[Spread.PhotosOnly]
  )
  private val album = Album(AlbumId(new UUID(0L, 1L)), 2025, "Retained Hội An", "Synopsis Ș", Author.all, Instant.EPOCH, opening, Vector(chapter))
  private val albumMetadata = AlbumRepository.Metadata(album.id, album.year, album.title, album.synopsis, album.authors, album.createdAt)
  private val cover         = Cover(album.id, SpineOwner.Front, CoverSlot(CoverSlotId(new UUID(0L, 14L))), CoverSlot(CoverSlotId(new UUID(0L, 15L))))
  private val coverPlacements = Vector(
    CoverPlacement(CoverPlacementId(new UUID(0L, 16L)), cover.front.id, PhotoId(new UUID(1L, 3L)), transform),
    CoverPlacement(CoverPlacementId(new UUID(0L, 17L)), cover.back.id, PhotoId(new UUID(1L, 4L)), transform)
  )
  private val specification = CoverSpecification.from(pageLimit = 100, width = 10500, height = 3400, spine = 300)
    .fold(message => fail(message), identity)
  private val dimensions = CoverDimensions.forSpreads(2, cats.data.NonEmptyVector.one(specification))
  private val book       =
    SavedBook(album, CoverManager.Composition(cover, coverPlacements, dimensions), photos, GearCatalog(Vector(camera, phone), Vector(lens)))

  test("should preserve exact saved text and permit empty captions without reading original files"):
    Ref.of[IO, Vector[Read]](Vector.empty).flatMap: calls =>
      for
        exporter <- buildExporter(calls, book)
        result   <- exporter.exportBook(album.id)
        reads    <- calls.get
      yield
        result match
          case Result.Ready(json, filename) =>
            val expectedSections = Set("formatVersion", "album", "sources", "interior", "chapters", "cover", "gear", "warnings")
            val chapterGear      = json.hcursor.downField("chapters").downN(0).downField("gear")
            val selectedLenses   = chapterGear.downN(0).get[Vector[String]]("lensIds")
            val fixedLenses      = chapterGear.downN(1).get[Vector[String]]("lensIds")
            val schema           = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
              .getSchema(SchemaLocation.of("classpath:indesign-book.schema.json"))
            val config           = ExecutionConfig.builder().formatAssertionsEnabled(true).build()
            val schemaViolations = schema.validate(json.noSpaces, InputFormat.JSON, context => context.setExecutionConfig(config)).asScala.toVector

            assertEquals(filename, "album-2025-indesign.json")
            assertEquals(json.asObject.map(_.keys.toSet), expectedSections.some)
            assertEquals(json.hcursor.downField("album").get[String]("synopsis"), album.synopsis.asRight[DecodingFailure])
            assertEquals(json.hcursor.downField("album").get[String]("title"), (album.title: String).asRight[DecodingFailure])
            assertEquals(json.hcursor.downField("album").get[Vector[String]]("authors"), Vector("Cosmin", "Mara", "Iacob").asRight[DecodingFailure])
            assertEquals(json.hcursor.get[Vector[Json]]("sources").map(_.size), photos.size.asRight[DecodingFailure])
            assertEquals(json.hcursor.downField("sources").downN(0).get[String]("source"), (original.source: String).asRight[DecodingFailure])
            assertEquals(json.hcursor.downField("sources").downN(0).get[String]("filename"), (original.filename: String).asRight[DecodingFailure])
            assertEquals(json.hcursor.downField("sources").downN(0).get[Option[String]]("caption"), none[String].asRight[DecodingFailure])
            assertEquals(json.hcursor.downField("chapters").downN(0).get[String]("description"), chapter.description.asRight[DecodingFailure])
            assertEquals(selectedLenses, Vector(lens.id.value.toString).asRight[DecodingFailure])
            assertEquals(fixedLenses, Vector.empty[String].asRight[DecodingFailure])
            assertEquals(json.hcursor.downField("gear").get[Vector[Json]]("cameras").map(_.size), 2.asRight[DecodingFailure])
            assertEquals(json.hcursor.downField("gear").get[Vector[Json]]("lenses").map(_.size), 1.asRight[DecodingFailure])
            assertEquals(json.hcursor.get[Vector[Json]]("warnings"), Vector.empty[Json].asRight[DecodingFailure])
            assertEquals(schemaViolations, Vector.empty)
          case other => fail(s"Expected validated artifact, got $other")
        assertEquals(
          reads,
          Vector(
            Read.Metadata(album.id),
            Read.Chapters(album.id),
            Read.Composition(album.id),
            Read.Spread(album.id, opening.id),
            Read.Spread(album.id, chapterOpening.id),
            Read.Gear(album.id, chapterId),
            Read.Cover(album.id),
            Read.Photos(chapterId),
            Read.Catalog
          )
        )

  test("should aggregate missing content across the entire book before publishing"):
    Ref.of[IO, Vector[Read]](Vector.empty).flatMap: calls =>
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

  test("should preserve saved chapter and spread order with their own text, gear and photographs"):
    Ref.of[IO, Vector[Read]](Vector.empty).flatMap: calls =>
      val slot   = photoSlot.copy(id = PhotoSpreadSlotId(new UUID(0L, 19L)))
      val spread = Spread.PhotosOnly(
        SpreadId(new UUID(0L, 18L)),
        opening.template,
        NonEmptyList.one(slot),
        Vector(placed.copy(id = SpreadPlacementId(new UUID(0L, 20L)), slot = slot.id))
      )
      val nextChapterId = ChapterId(new UUID(0L, 24L))
      val nextPhotos    = Vector(5, 6, 7).map(index =>
        Photo(PhotoId(new UUID(1L, index.toLong)), nextChapterId, original, metadata, thumbnail, maybeCaption = None)
      )
      val nextSlot   = slot.copy(id = PhotoSpreadSlotId(new UUID(0L, 26L)))
      val nextSpread = spread.copy(
        id = SpreadId(new UUID(0L, 23L)),
        photoSlots = NonEmptyList.one(nextSlot),
        placements = Vector(placed.copy(id = SpreadPlacementId(new UUID(0L, 27L)), slot = nextSlot.id, photo = PhotoId(new UUID(1L, 6L))))
      )
      val lastSlot   = slot.copy(id = PhotoSpreadSlotId(new UUID(0L, 28L)))
      val lastSpread = spread.copy(
        photoSlots = NonEmptyList.one(lastSlot),
        placements = Vector(placed.copy(id = SpreadPlacementId(new UUID(0L, 29L)), slot = lastSlot.id, photo = PhotoId(new UUID(1L, 7L))))
      )
      val nextOpeningSlot = chapterSlot.copy(id = PhotoSpreadSlotId(new UUID(0L, 30L)))
      val nextChapter     = chapter.copy(
        id = nextChapterId,
        destination = chapter.destination.copy(name = "Coast"),
        description = "Coast description",
        gear = Vector(CameraSelection(phone.id, CameraUse.FixedLens)),
        opening = chapterOpening.copy(
          id = SpreadId(new UUID(0L, 25L)),
          photoSlots = Vector(nextOpeningSlot),
          textSlot = textSlot.copy(id = TextSpreadSlotId(new UUID(0L, 31L))),
          placements = Vector(placed.copy(id = SpreadPlacementId(new UUID(0L, 32L)), slot = nextOpeningSlot.id, photo = PhotoId(new UUID(1L, 5L))))
        ),
        spreads = Vector(nextSpread, lastSpread)
      )
      val orderedChapters =
        Vector(nextChapter, chapter.copy(gear = Vector(CameraSelection(camera.id, CameraUse.Interchangeable(NonEmptyVector.one(lens.id))))))
      val saved = book.copy(
        album = album.copy(chapters = orderedChapters),
        photos = nextPhotos ++ photos,
        cover = book.cover.copy(dimensions = CoverDimensions.forSpreads(5, cats.data.NonEmptyVector.one(specification)))
      )
      val exporter        = buildExporter(calls, saved)
      val expectedSpreads = Vector(opening.id, nextChapter.opening.id, nextSpread.id, lastSpread.id, chapterOpening.id).map(_.value.toString)
      val expectedPhotos  = Vector(1L, 5L, 6L, 7L, 2L).map(index => new UUID(1L, index).toString)

      exporter.flatMap(_.exportBook(album.id)).map:
        case Result.Ready(json, filename) =>
          val chapters = json.hcursor.get[Vector[Json]]("chapters")
          val spreads  = json.hcursor.downField("interior").get[Vector[Json]]("spreads")
          assertEquals(filename, "album-2025-indesign.json")
          assertEquals(
            chapters.flatMap(_.traverse(_.hcursor.get[String]("id"))),
            Vector(nextChapterId, chapterId).map(_.value.toString).asRight[DecodingFailure]
          )
          assertEquals(chapters.flatMap(_.traverse(_.hcursor.get[String]("name"))), Vector("Coast", "Hội An").asRight[DecodingFailure])
          assertEquals(
            chapters.flatMap(_.traverse(_.hcursor.get[String]("description"))),
            Vector("Coast description", "Retained Ș description").asRight[DecodingFailure]
          )
          assertEquals(
            chapters.flatMap(_.traverse(_.hcursor.get[String]("opening"))),
            Vector(nextChapter.opening.id, chapterOpening.id).map(_.value.toString).asRight[DecodingFailure]
          )
          assertEquals(
            chapters.flatMap(_.traverse(_.hcursor.downField("gear").downN(0).get[String]("cameraId"))),
            Vector(phone.id, camera.id).map(_.value.toString).asRight[DecodingFailure]
          )
          assertEquals(
            chapters.flatMap(_.traverse(_.hcursor.downField("gear").downN(0).get[Vector[String]]("lensIds"))),
            Vector(Vector.empty[String], Vector(lens.id.value.toString)).asRight[DecodingFailure]
          )
          assertEquals(spreads.flatMap(_.traverse(_.hcursor.get[String]("id"))), expectedSpreads.asRight[DecodingFailure])
          assertEquals(
            spreads.flatMap(_.traverse(_.hcursor.downField("placements").downN(0).get[String]("photo"))),
            expectedPhotos.asRight[DecodingFailure]
          )
          assertEquals(json.hcursor.downField("interior").get[Long]("pageCount"), 10L.asRight[DecodingFailure])
          assertEquals(json.hcursor.downField("interior").downField("spreads").downN(2).get[String]("kind"), "photos".asRight[DecodingFailure])
        case other => fail(s"Expected validated photo spread, got $other")

  test("should return explicit acquisition outcomes without reading originals"):
    val found     = AlbumRepository.MetadataResult.Found(albumMetadata)
    val cover     = CoverManager.ReadResult.Found(book.cover)
    val inventory = PhotoRepository.ListResult.Listed(book.photos)
    val catalog   = GearRepository.CatalogReadResult.Found(book.gear)
    val cases     = Vector(
      (album = AlbumRepository.MetadataResult.NotFound, cover = cover, photos = inventory, gear = catalog, expected = Result.NotFound),
      (
        album = AlbumRepository.MetadataResult.Failed(AlbumRepository.Failure.DatabaseFailure),
        cover = cover,
        photos = inventory,
        gear = catalog,
        expected = Result.Failed
      ),
      (album = found, cover = CoverManager.ReadResult.CoverNotFound, photos = inventory, gear = catalog, expected = Result.Failed),
      (album = found, cover = CoverManager.ReadResult.Failed, photos = inventory, gear = catalog, expected = Result.Failed),
      (album = found, cover = cover, photos = PhotoRepository.ListResult.Failed, gear = catalog, expected = Result.Failed),
      (album = found, cover = cover, photos = PhotoRepository.ListResult.Listed(Vector.empty), gear = catalog, expected = Result.Failed),
      (album = found, cover = cover, photos = inventory, gear = GearRepository.CatalogReadResult.Failed, expected = Result.Failed)
    )

    cases.traverse_ { case (albumRead, coverRead, photoRead, gearRead, expected) =>
      Ref.of[IO, Vector[Read]](Vector.empty).flatMap: calls =>
        val buildExport = BookExport.make(
          buildAlbums(calls, albumRead.pure[IO]),
          buildComposition(calls, album),
          buildChapters(calls, album),
          buildCovers(calls, coverRead),
          buildPhotos(calls, photoRead),
          buildGear(calls, gearRead)
        )

        for
          exporter <- buildExport
          result   <- exporter.exportBook(album.id)
          reads    <- calls.get
        yield
          assertEquals(result, expected)
          assertEquals(reads.collect { case Read.Metadata(id) => id }, Vector(album.id))
    }

  test("should fail export when saved chapter metadata is unavailable"):
    Vector(
      AlbumRepository.ChaptersResult.AlbumNotFound,
      AlbumRepository.ChaptersResult.Failed(AlbumRepository.Failure.DatabaseFailure)
    ).traverse_ { response =>
      for
        calls    <- Ref.of[IO, Vector[Read]](Vector.empty)
        exporter <- BookExport.make(
          buildAlbums(calls, AlbumRepository.MetadataResult.Found(albumMetadata).pure[IO], maybeChapters = response.some),
          buildComposition(calls, album),
          buildChapters(calls, album),
          buildCovers(calls, CoverManager.ReadResult.Found(book.cover)),
          buildPhotos(calls, PhotoRepository.ListResult.Listed(book.photos)),
          buildGear(calls, GearRepository.CatalogReadResult.Found(book.gear))
        )
        result <- exporter.exportBook(album.id)
      yield assertEquals(result, Result.Failed)
    }

  test("should fail export when saved membership or spread geometry is unavailable"):
    val cases = Vector(
      (membership = CompositionResult.AlbumNotFound, spread = SpreadResult.NotFound),
      (membership = CompositionResult.Failed(AlbumCompositionRepository.Failure.Unavailable), spread = SpreadResult.NotFound),
      (membership = CompositionResult.Found(NonEmptyList.one(Member(opening.id, maybeChapter = None))), spread = SpreadResult.NotFound)
    )
    cases.traverse_ { case (membership, spread) =>
      for
        calls    <- Ref.of[IO, Vector[Read]](Vector.empty)
        exporter <- BookExport.make(
          buildAlbums(calls, AlbumRepository.MetadataResult.Found(albumMetadata).pure[IO]),
          buildComposition(calls, album, maybeMembership = membership.some, maybeSpread = spread.some),
          buildChapters(calls, album),
          buildCovers(calls, CoverManager.ReadResult.Found(book.cover)),
          buildPhotos(calls, PhotoRepository.ListResult.Listed(book.photos)),
          buildGear(calls, GearRepository.CatalogReadResult.Found(book.gear))
        )
        result <- exporter.exportBook(album.id)
      yield assertEquals(result, Result.Failed)
    }

  test("should reject incoherent saved openings and unavailable chapter gear"):
    val complete = Vector[Located](Located.AlbumOpening(opening), Located.ChapterOpening(chapterId, chapterOpening))
    val cases    = Vector(
      (members = Vector[Located](Located.ChapterOpening(chapterId, chapterOpening)), gear = ChapterRepository.GearReadResult.Found(selected)),
      (
        members = Vector[Located](Located.AlbumOpening(opening), Located.AlbumOpening(opening.copy(id = chapterOpening.id))),
        gear = ChapterRepository.GearReadResult.Found(selected)
      ),
      (members = Vector[Located](Located.AlbumOpening(opening)), gear = ChapterRepository.GearReadResult.Found(selected)),
      (
        members = complete :+ Located.ChapterOpening(chapterId, chapterOpening.copy(id = SpreadId(new UUID(8L, 1L)))),
        gear = ChapterRepository.GearReadResult.Found(selected)
      ),
      (members = complete, gear = ChapterRepository.GearReadResult.NotFound),
      (members = complete, gear = ChapterRepository.GearReadResult.Failed)
    )
    cases.traverse_ { case (members, gearRead) =>
      for
        calls    <- Ref.of[IO, Vector[Read]](Vector.empty)
        exporter <- BookExport.make(
          buildAlbums(calls, AlbumRepository.MetadataResult.Found(albumMetadata).pure[IO]),
          buildComposition(calls, album, maybeLocated = members.some),
          buildChapters(calls, album, maybeGear = gearRead.some),
          buildCovers(calls, CoverManager.ReadResult.Found(book.cover)),
          buildPhotos(calls, PhotoRepository.ListResult.Listed(book.photos)),
          buildGear(calls, GearRepository.CatalogReadResult.Found(book.gear))
        )
        result <- exporter.exportBook(album.id)
      yield assertEquals(result, Result.Failed)
    }

  test("should warn strictly below 300 effective DPI without blocking the complete book"):
    val sizes = Vector((width = 5400, height = 3400, warns = false), (width = 5399, height = 3399, warns = true))

    sizes.traverse_ { case (width, height, warns) =>
      Ref.of[IO, Vector[Read]](Vector.empty).flatMap: calls =>
        val pixels = PhotoDimensions(
          width.refineEither[Positive].fold(message => fail(message), identity),
          height.refineEither[Positive].fold(message => fail(message), identity)
        )
        val current  = metadata.copy(dimensions = pixels)
        val saved    = book.copy(photos = photos.map(_.copy(metadata = current)))
        val exporter = buildExporter(calls, saved)
        val expected = if warns then
          Vector(cover.front.id -> "Front cover", cover.back.id -> "Back cover").map { case (slot, side) =>
            Json.obj(
              "code"     -> Json.fromString("low_resolution"),
              "severity" -> Json.fromString("warning"),
              "target"   -> Json.obj("kind" -> Json.fromString("cover"), "slot" -> Json.fromString(slot.value.toString)),
              "message"  -> Json.fromString(s"$side: one.jpg is 299 DPI (recommended 300 DPI).")
            )
          }
        else Vector.empty[Json]

        exporter.flatMap(_.exportBook(album.id)).map:
          case Result.Ready(json, filename) =>
            assertEquals(filename, "album-2025-indesign.json")
            assertEquals(json.hcursor.get[Vector[Json]]("warnings"), expected.asRight[DecodingFailure])
          case other => fail(s"Expected printable artifact, got $other")
    }

  test("should identify empty interior photo slots before publishing"):
    Ref.of[IO, Vector[Read]](Vector.empty).flatMap: calls =>
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
    Ref.of[IO, Vector[Read]](Vector.empty).flatMap: calls =>
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
      val interiorTargets = Vector(
        (spread = opening.id, slot = photoSlot.id, location = "Album opening - spread 1, photo 1"),
        (spread = opening.id, slot = secondSlot.id, location = "Album opening - spread 1, photo 2"),
        (spread = chapterOpening.id, slot = chapterSlot.id, location = "Hội An - spread 2, photo 1")
      ).map { case (spread, slot, location) =>
        Json.obj(
          "kind"   -> Json.fromString("slot"),
          "spread" -> Json.fromString(spread.value.toString),
          "slot"   -> Json.fromString(slot.value.toString)
        ) -> location
      }
      val coverTargets = Vector(cover.front.id -> "Front cover", cover.back.id -> "Back cover").map { case (slot, location) =>
        Json.obj("kind" -> Json.fromString("cover"), "slot" -> Json.fromString(slot.value.toString)) -> location
      }
      val expected = (interiorTargets ++ coverTargets).map { case (target, location) =>
        Json.obj(
          "code"     -> Json.fromString("low_resolution"),
          "severity" -> Json.fromString("warning"),
          "target"   -> target,
          "message"  -> Json.fromString(s"$location: one.jpg is 0 DPI (recommended 300 DPI).")
        )
      }

      buildExporter(calls, saved).flatMap(_.exportBook(album.id)).map:
        case Result.Ready(json, _) => assertEquals(json.hcursor.get[Vector[Json]]("warnings"), expected.asRight[DecodingFailure])
        case other                 => fail(s"Expected actionable warnings, got $other")

  test("should retry saved-state export without reading originals or exporting unused collection photos"):
    Ref.of[IO, Vector[Read]](Vector.empty).flatMap: calls =>
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
        assertEquals(reads.collect { case Read.Metadata(id) => id }, Vector(album.id, album.id))

  test("should report unexpected saved-state acquisition failure explicitly"):
    Ref.of[IO, Vector[Read]](Vector.empty).flatMap: calls =>
      val exporter = BookExport.make(
        buildAlbums(calls, new IllegalStateException("Album unavailable").raiseError[IO, AlbumRepository.MetadataResult]),
        buildComposition(calls, album),
        buildChapters(calls, album),
        buildCovers(calls, CoverManager.ReadResult.Found(book.cover)),
        buildPhotos(calls, PhotoRepository.ListResult.Listed(book.photos)),
        buildGear(calls, GearRepository.CatalogReadResult.Found(book.gear))
      )

      exporter.flatMap(_.exportBook(album.id)).map(result => assertEquals(result, Result.Failed))

  test("should fail export when generated JSON violates the handoff date format"):
    Ref.of[IO, Vector[Read]](Vector.empty).flatMap: calls =>
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
        Ref.of[IO, Vector[Read]](Vector.empty).flatMap: calls =>
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
                val matchesFrame = slot.downField("frame").get[Double](key).map(value => math.abs(value - expected) < 0.000001)
                assertEquals(matchesFrame, true.asRight[DecodingFailure])
              }
              Vector("xPt" -> 23.81102, "yPt" -> 7.65354, "widthPt" -> 2142.9918, "heightPt" -> 688.8186).foreach { case (key, expected) =>
                val matchesCaptionFrame = slot.downField("captionFrame").get[Double](key).map(value => math.abs(value - expected) < 0.000001)
                assertEquals(matchesCaptionFrame, true.asRight[DecodingFailure])
              }
              val projected = json.hcursor.downField("interior").downField("spreads").downN(0).downField("placements").downN(0)
              assertEquals(projected.get[String]("captionCorner"), encodedCorner.asRight[DecodingFailure])
              assertEquals(projected.downField("transform").get[Boolean]("mirrored"), true.asRight[DecodingFailure])
              assertEquals(projected.downField("transform").get[Double]("xPt"), 12.0.asRight[DecodingFailure])
              assertEquals(projected.downField("transform").get[Double]("zoom"), 1.5.asRight[DecodingFailure])
            case other => fail(s"Expected validated print projection, got $other")
      }
    }

  test("should fail projection instead of generating a partial handoff when geometry cannot be converted"):
    Ref.of[IO, Vector[Read]](Vector.empty).flatMap: calls =>
      val invalidSlot = photoSlot.copy(box = SlotBox(x = 0.0, y = 0.0, width = 0.001, height = 0.001))
      val invalid     = book.copy(album = album.copy(opening = opening.copy(photoSlots = Vector(invalidSlot))))
      buildExporter(calls, invalid).flatMap(_.exportBook(album.id)).map(result => assertEquals(result, Result.Failed))

  private enum Read:
    case Metadata(album: AlbumId)
    case Chapters(album: AlbumId)
    case Composition(album: AlbumId)
    case Spread(album: AlbumId, spread: SpreadId)
    case Gear(album: AlbumId, chapter: ChapterId)
    case Cover(album: AlbumId)
    case Photos(chapter: ChapterId)
    case Catalog

  private def buildExporter(calls: Ref[IO, Vector[Read]], saved: SavedBook)(using
      Transaction[IO, IO]
  ) =
    BookExport.make(
      buildAlbums(
        calls,
        AlbumRepository.MetadataResult.Found(AlbumRepository.Metadata(
          saved.album.id,
          saved.album.year,
          saved.album.title,
          saved.album.synopsis,
          saved.album.authors,
          saved.album.createdAt
        )).pure[IO],
        saved.album
      ),
      buildComposition(calls, saved.album),
      buildChapters(calls, saved.album),
      buildCovers(calls, CoverManager.ReadResult.Found(saved.cover)),
      buildPhotos(calls, PhotoRepository.ListResult.Listed(saved.photos), saved.album.chapters.map(_.id)),
      buildGear(calls, GearRepository.CatalogReadResult.Found(saved.gear))
    )

  // scalafix:off DisableSyntax.defaultArgs
  private def buildAlbums(
      calls: Ref[IO, Vector[Read]],
      response: IO[AlbumRepository.MetadataResult],
      saved: Album = album,
      maybeChapters: Option[AlbumRepository.ChaptersResult] = None
  ) =
    new AlbumRepository.MetadataRead[IO] with AlbumRepository.ChapterRead[IO]:
      override def metadata(id: AlbumId): IO[AlbumRepository.MetadataResult] = calls.update(_ :+ Read.Metadata(id)) >> response
      override def chapters(id: AlbumId): IO[AlbumRepository.ChaptersResult] =
        calls.update(_ :+ Read.Chapters(id)).as(maybeChapters.getOrElse(AlbumRepository.ChaptersResult.Found(saved.chapters.map(chapter =>
          AlbumRepository.ChapterMetadata(chapter.id, chapter.destination, chapter.period, chapter.sourceFolder, chapter.description)
        ))))

  private def buildComposition(
      calls: Ref[IO, Vector[Read]],
      saved: Album,
      maybeMembership: Option[CompositionResult] = None,
      maybeSpread: Option[SpreadResult] = None,
      maybeLocated: Option[Vector[Located]] = None
  ) =
    val located = maybeLocated.getOrElse(Vector[Located](Located.AlbumOpening(saved.opening)) ++ saved.chapters.flatMap(chapter =>
      Vector[Located](Located.ChapterOpening(chapter.id, chapter.opening)) ++ chapter.spreads.map(Located.Photo(chapter.id, _))
    ))
    val spreads = next(located.map(value => maybeSpread.getOrElse(SpreadResult.Found(value))))
    new AlbumCompositionRepository[IO] with AlbumCompositionRepository.MembershipRead[IO]:
      override def opening(id: AlbumId, maybeChapter: Option[ChapterId]): IO[AlbumCompositionRepository.OpeningResult] =
        fail("Export reads membership")
      override def composition(id: AlbumId): IO[CompositionResult] =
        calls.update(_ :+ Read.Composition(id)).as(maybeMembership.getOrElse(CompositionResult.Found(NonEmptyList.fromList(located.map(value =>
          Member(value.spread.id, maybeChapter = None)
        ).toList).getOrElse(fail("Expected members")))))
      override def findSpread(id: AlbumId, spread: SpreadId): IO[SpreadResult] =
        calls.update(_ :+ Read.Spread(id, spread)) >> spreads
      override def hasAlbum(id: AlbumId): IO[Boolean]                                                    = fail("Export reads metadata")
      override def countInteriorSpreads(id: AlbumId): IO[Int]                                            = fail("Export acquires geometry")
      override def findChapterOrder(id: AlbumId, chapter: ChapterId): IO[Option[NonEmptyList[SpreadId]]] = fail("Export acquires membership")

  private def buildChapters(calls: Ref[IO, Vector[Read]], saved: Album, maybeGear: Option[ChapterRepository.GearReadResult] = None) =
    val responses = next(saved.chapters.map(value => maybeGear.getOrElse(ChapterRepository.GearReadResult.Found(value.gear))))
    new ChapterRepository[IO]:
      override def readGear(id: AlbumId, chapter: ChapterId): IO[ChapterRepository.GearReadResult] =
        calls.update(_ :+ Read.Gear(id, chapter)) >> responses
      override def readSelections: IO[ChapterRepository.SelectionsReadResult] = fail("Export reads chapter gear")
      override def replaceGear(id: AlbumId, chapter: ChapterId, selection: GearSelection): IO[ChapterRepository.GearWriteResult] = fail("Read-only")
      override def saveDescription(id: AlbumId, chapter: ChapterId, text: String): IO[ChapterRepository.SaveDescriptionResult]   = fail("Read-only")
      override def findSource(chapter: ChapterId): IO[ChapterRepository.SourceResult] = fail("Export reads chapter metadata")
      override def create(id: AlbumId, chapter: ChapterRepository.NewChapter, order: NonEmptyList[ChapterId]): IO[ChapterRepository.CreateResult] =
        fail("Read-only")
      override def update(command: ChapterRepository.Update): IO[ChapterRepository.UpdateResult] = fail("Read-only")
  // scalafix:on DisableSyntax.defaultArgs

  private def buildCovers(calls: Ref[IO, Vector[Read]], response: CoverManager.ReadResult) = new CoverManager[IO]:
    override def read(id: AlbumId): IO[CoverManager.ReadResult]                                      = calls.update(_ :+ Read.Cover(id)).as(response)
    override def edit(id: AlbumId, request: CoverManager.Edit): IO[CoverManager.Result]              = fail("Read-only export")
    override def setSpineOwner(id: AlbumId, owner: SpineOwner): IO[CoverManager.SetSpineOwnerResult] = fail("Read-only export")

  // scalafix:off DisableSyntax.defaultArgs
  private def buildPhotos(calls: Ref[IO, Vector[Read]], response: PhotoRepository.ListResult, chapters: Vector[ChapterId] = Vector(chapterId)) =
    val responses = next(chapters.map(chapter =>
      response match
        case PhotoRepository.ListResult.Listed(values) => PhotoRepository.ListResult.Listed(values.filter(_.chapter.value === chapter.value))
        case PhotoRepository.ListResult.Failed         => PhotoRepository.ListResult.Failed
    ))
    new PhotoRepository[IO]:
      override def listChapter(id: ChapterId): IO[PhotoRepository.ListResult] = calls.update(_ :+ Read.Photos(id)) >> responses
      override def find(id: PhotoId): IO[PhotoRepository.FindResult]          = fail("Acquire chapter inventory")
      override def saveCaption(chapter: ChapterId, photo: PhotoId, text: String): IO[PhotoRepository.SaveCaptionResult] = fail("Read-only export")
      override def save(photo: Photo): IO[PhotoRepository.WriteResult]                                                  = fail("Read-only export")
      override def remove(id: PhotoId): IO[PhotoRepository.WriteResult]                                                 = fail("Read-only export")
      override def recordWrite(intent: PhotoWriteIntent): IO[PhotoRepository.RecordWriteResult]                         = fail("Read-only export")
      override def completeWrite(id: PhotoWriteIntentId): IO[PhotoRepository.CompleteWriteResult]                       = fail("Read-only export")
      override def listUnfinishedWrites: IO[PhotoRepository.ListUnfinishedWritesResult]                                 = fail("Read-only export")
  // scalafix:on DisableSyntax.defaultArgs

  private def buildGear(calls: Ref[IO, Vector[Read]], response: GearRepository.CatalogReadResult) = new GearRepository[IO]:
    override def readCatalog: IO[GearRepository.CatalogReadResult]                   = calls.update(_ :+ Read.Catalog).as(response)
    override def createCamera(camera: Camera): IO[GearRepository.CameraCreateResult] = fail("Read-only export")
    override def updateCamera(camera: Camera): IO[GearRepository.CameraUpdateResult] = fail("Read-only export")
    override def deleteCamera(id: CameraId): IO[GearRepository.CameraDeleteResult]   = fail("Read-only export")
    override def createLens(lens: Lens): IO[GearRepository.LensCreateResult]         = fail("Read-only export")
    override def updateLens(lens: Lens): IO[GearRepository.LensUpdateResult]         = fail("Read-only export")
    override def deleteLens(id: LensId): IO[GearRepository.LensDeleteResult]         = fail("Read-only export")

  private def next[A](responses: Vector[A]): IO[A] =
    val remaining = new AtomicReference(responses)
    IO(remaining.getAndUpdate(values => if values.size === 1 then responses else values.drop(1)))
      .flatMap(_.headOption.liftTo[IO](new AssertionError("Unarranged export read")))
