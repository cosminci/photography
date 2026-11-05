package photography.usecases.album

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.{Blank, Not}
import io.github.iltotore.iron.constraint.collection.MinLength
import io.github.iltotore.iron.constraint.numeric.Positive
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.domain.album.*
import photography.domain.gear.*
import photography.domain.layout.*
import photography.ports.album.AlbumRepository
import photography.ports.album.AlbumRepository.{BookResult, SavedBook}
import photography.ports.cover.CoverRepository
import photography.ports.photo.{PhotoStorage, ThumbnailStorage}
import photography.usecases.album.BookExport.*

import java.time.{Instant, LocalDate}
import java.nio.file.Path
import java.util.UUID

final class BookExportComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val chapterId    = ChapterId(new UUID(0L, 2L))
  private val original     = OriginalPhotoLocation(source = "/photos/trip", filename = "one.jpg")
  private val metadata     = PhotoMetadata(hash = "original", maybeCapturedLocal = None, PhotoDimensions(width = 16000, height = 12000))
  private val content      = PhotoContent(Vector(1.toByte).refineEither[MinLength[1]]
    .fold(message => fail(message), identity))
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
  private val country = Country(CountryId(new UUID(0L, 12L)), "RO", "Romania", AbsoluteLocation(Path.of("/flags/ro.png")))
  private val chapter = Chapter(
    chapterId,
    Destination(DestinationId(new UUID(0L, 13L)), "Hội An", NonEmptyList.one(country)),
    VisitPeriod(LocalDate.of(2025, 1, 1), days = 1L),
    "Retained Ș description",
    Vector.empty[CameraSelection],
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
    SavedBook(album, CoverRepository.Composition(cover, coverPlacements, dimensions), photos, GearCatalog(Vector.empty, Vector.empty))

  test("should preserve exact saved text and permit empty captions while checking each referenced original"):
    Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
      val exporter = buildExporter(calls, BookResult.Found(book), PhotoStorage.ReadResult.Read(content, metadata).pure[IO])

      for
        result <- exporter.exportBook(album.id)
        reads  <- calls.get
      yield
        assertEquals(result, Result.Ready(book, warnings = Vector.empty[Issue]))
        assertEquals(reads, photos.map(_.original))

  test("should aggregate missing content across the entire book before publishing"):
    Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
      val incomplete = book.copy(
        album = album.copy(synopsis = " ", chapters = Vector(chapter.copy(description = "\n"))),
        cover = book.cover.copy(placements = Vector.empty[CoverPlacement])
      )
      val exporter = buildExporter(calls, BookResult.Found(incomplete), PhotoStorage.ReadResult.Read(content, metadata).pure[IO])
      val expected = Vector(
        Issue(Code.EmptySynopsis, Severity.Blocking, Target.Album),
        Issue(Code.EmptyDescription, Severity.Blocking, Target.Chapter(chapterId)),
        Issue(Code.EmptyPhotoSlot, Severity.Blocking, Target.CoverSlot(cover.front.id)),
        Issue(Code.EmptyPhotoSlot, Severity.Blocking, Target.CoverSlot(cover.back.id))
      )

      exporter.exportBook(album.id).map(result => assertEquals(result, Result.Blocked(expected)))

  test("should validate photo-only spreads without requiring editorial text"):
    Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
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
      val exporter = buildExporter(calls, BookResult.Found(saved), PhotoStorage.ReadResult.Read(content, metadata).pure[IO])

      exporter.exportBook(album.id).map(result => assertEquals(result, Result.Ready(saved, warnings = Vector.empty[Issue])))

  test("should block an opening text frame outside either physical spread boundary"):
    Vector(
      SlotBox(x = 99.0, y = 1.0, width = 2.0, height = 10.0),
      SlotBox(x = 1.0, y = 99.0, width = 10.0, height = 2.0)
    ).traverse_ { invalid =>
      Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
        val saved    = book.copy(album = album.copy(opening = opening.copy(textSlot = textSlot.copy(box = invalid))))
        val exporter = buildExporter(calls, BookResult.Found(saved), PhotoStorage.ReadResult.Read(content, metadata).pure[IO])
        exporter.exportBook(album.id).map(result =>
          assertEquals(
            result,
            Result.Blocked(Vector(Issue(Code.InvalidGeometry, Severity.Blocking, Target.Spread(opening.id))))
          )
        )
    }

  test("should return explicit acquisition outcomes without reading originals"):
    Vector(BookResult.NotFound -> Result.NotFound, BookResult.Failed(AlbumRepository.Failure.DatabaseFailure) -> Result.Failed).traverse_ {
      case (response, expected) =>
        Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
          val exporter = buildExporter(calls, response, PhotoStorage.ReadResult.Failed.pure[IO])

          for
            result <- exporter.exportBook(album.id)
            reads  <- calls.get
          yield
            assertEquals(result, expected)
            assertEquals(reads, Vector.empty[OriginalPhotoLocation])
    }

  test("should warn strictly below 300 effective DPI without blocking the complete book"):
    val sizes = Vector((width = 5400, height = 3400, warns = false), (width = 5399, height = 3399, warns = true))

    sizes.traverse_ { case (width, height, warns) =>
      Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
        val pixels = PhotoDimensions(
          width.refineEither[Positive].fold(message => fail(message), identity),
          height.refineEither[Positive].fold(message => fail(message), identity)
        )
        val current  = metadata.copy(dimensions = pixels)
        val saved    = book.copy(photos = photos.map(_.copy(metadata = current)))
        val exporter = buildExporter(calls, BookResult.Found(saved), PhotoStorage.ReadResult.Read(content, current).pure[IO])
        val expected = if warns then
          Vector(cover.front.id, cover.back.id).map(slot => Issue(Code.LowResolution, Severity.Warning, Target.CoverSlot(slot)))
        else Vector.empty[Issue]

        exporter.exportBook(album.id).map(result => assertEquals(result, Result.Ready(saved, expected)))
    }

  test("should report every unavailable changed unstable or undecodable referenced original"):
    val cases = Vector(
      PhotoStorage.ReadResult.Failed                                                                            -> Code.OriginalUnavailable,
      PhotoStorage.ReadResult.Unstable                                                                          -> Code.OriginalUnstable,
      PhotoStorage.ReadResult.Undecodable                                                                       -> Code.OriginalUndecodable,
      PhotoStorage.ReadResult.Read(content, metadata.copy(hash = "changed"))                                    -> Code.OriginalChanged,
      PhotoStorage.ReadResult.Read(content, metadata.copy(dimensions = PhotoDimensions(width = 1, height = 1))) -> Code.OriginalChanged
    )

    cases.traverse_ { case (response, code) =>
      Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
        val exporter = buildExporter(calls, BookResult.Found(book), response.pure[IO])
        val expected = photos.map(photo => Issue(code, Severity.Blocking, Target.Photo(photo.id)))

        exporter.exportBook(album.id).map(result => assertEquals(result, Result.Blocked(expected)))
    }

  test("should identify empty invalid or unresolved interior photo slots rather than publishing partial content"):
    val missing    = album.copy(opening = opening.copy(placements = Vector.empty[SpreadPlacement]))
    val unresolved = album.copy(opening = opening.copy(placements = Vector(placed.copy(photo = PhotoId(new UUID(9L, 9L))))))
    val invalid    = album.copy(opening = opening.copy(placements = Vector(placed.copy(transform = transform.copy(zoom = 6.0)))))
    val brokenBox  = photoSlot.copy(box = SlotBox(x = 40.0, y = 50.0, width = 20.0, height = 60.0), bleed = Set.empty[SpreadEdge])
    val malformed  = album.copy(opening = opening.copy(photoSlots = Vector(brokenBox)))
    val cases      =
      Vector(missing -> Code.EmptyPhotoSlot, unresolved -> Code.MissingPhoto, invalid -> Code.InvalidFraming, malformed -> Code.InvalidGeometry)

    cases.traverse_ { case (saved, code) =>
      Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
        val exporter = buildExporter(calls, BookResult.Found(book.copy(album = saved)), PhotoStorage.ReadResult.Read(content, metadata).pure[IO])
        val expected = Vector(Issue(code, Severity.Blocking, Target.Slot(opening.id, photoSlot.id)))

        exporter.exportBook(album.id).map(result => assertEquals(result, Result.Blocked(expected)))
    }

  test("should reread saved state and originals on retry while leaving unused collection photos unchecked"):
    Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
      val unused   = photos.take(1).map(_.copy(id = PhotoId(new UUID(9L, 9L))))
      val saved    = book.copy(photos = photos ++ unused)
      val exporter = buildExporter(calls, BookResult.Found(saved), PhotoStorage.ReadResult.Read(content, metadata).pure[IO])

      for
        first  <- exporter.exportBook(album.id)
        second <- exporter.exportBook(album.id)
        reads  <- calls.get
      yield
        assertEquals(first, Result.Ready(saved, warnings = Vector.empty[Issue]))
        assertEquals(second, first)
        assertEquals(reads, photos.map(_.original) ++ photos.map(_.original))

  test("should report an unexpected acquisition or source failure explicitly"):
    Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
      val exporter =
        buildExporter(calls, BookResult.Found(book), IO.raiseError[PhotoStorage.ReadResult](new IllegalStateException("Source unavailable")))

      exporter.exportBook(album.id).map(result => assertEquals(result, Result.Failed))

  test("should block a cover whose page count does not describe the saved interior"):
    Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
      val mismatched = book.copy(cover = book.cover.copy(dimensions = CoverDimensions.forSpreads(3, cats.data.NonEmptyVector.one(specification))))
      val exporter   = buildExporter(calls, BookResult.Found(mismatched), PhotoStorage.ReadResult.Read(content, metadata).pure[IO])
      val expected   = Vector(Issue(Code.InvalidPageCount, Severity.Blocking, Target.Album))

      exporter.exportBook(album.id).map(result => assertEquals(result, Result.Blocked(expected)))

  private def buildExporter(calls: Ref[IO, Vector[OriginalPhotoLocation]], response: BookResult, read: IO[PhotoStorage.ReadResult]) =
    val books = new AlbumRepository.BookRead[IO]:
      override def readBook(id: AlbumId): IO[BookResult] = IO(assertEquals(id, album.id)).as(response)
    val storage = new PhotoStorage[IO]:
      override def readOriginal(location: OriginalPhotoLocation): IO[PhotoStorage.ReadResult] = calls.update(_ :+ location) >> read
      override def discoverOriginals(source: PhotoSource): IO[PhotoStorage.DiscoveryResult]   = fail("Export must not discover sources")
      override def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): IO[ThumbnailStorage.StoreResult] = fail("Read-only export")
      override def readThumbnail(location: ThumbnailLocation, version: String :| Not[Blank]): IO[ThumbnailStorage.ReadResult] = fail("Read originals")
      override def removeThumbnail(location: ThumbnailLocation): IO[ThumbnailStorage.RemoveResult] = fail("Read-only export")
    BookExport.make(books, storage)
