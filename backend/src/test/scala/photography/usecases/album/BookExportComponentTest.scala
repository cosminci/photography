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
import photography.capabilities.Transaction
import photography.ports.cover.CoverRepository
import photography.ports.gear.GearRepository
import photography.ports.photo.{PhotoRepository, PhotoStorage, ThumbnailStorage}
import photography.usecases.album.BookExport.*

import java.time.{Instant, LocalDate}
import java.nio.file.Path
import java.util.UUID

final class BookExportComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given Transaction[IO, IO] with
    override def run[A](program: IO[A]): IO[A]     = program
    override def resolve[A](program: IO[A]): IO[A] = fail("Export must not resolve writes")
  private val chapterId = ChapterId(new UUID(0L, 2L))
  private val original  = OriginalPhotoLocation(source = "/photos/trip", filename = "one.jpg")
  private val metadata  = PhotoMetadata(hash = "original", maybeCapturedLocal = None, PhotoDimensions(width = 16000, height = 12000))
  private val content   = PhotoContent(Vector(1.toByte).refineEither[MinLength[1]]
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
      val exporter = buildExporter(calls, book, PhotoStorage.ReadResult.Read(content, metadata).pure[IO])

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
      val exporter = buildExporter(calls, incomplete, PhotoStorage.ReadResult.Read(content, metadata).pure[IO])
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
      val exporter = buildExporter(calls, saved, PhotoStorage.ReadResult.Read(content, metadata).pure[IO])

      exporter.exportBook(album.id).map(result => assertEquals(result, Result.Ready(saved, warnings = Vector.empty[Issue])))

  test("should block an opening text frame outside either physical spread boundary"):
    Vector(
      SlotBox(x = 99.0, y = 1.0, width = 2.0, height = 10.0),
      SlotBox(x = 1.0, y = 99.0, width = 10.0, height = 2.0)
    ).traverse_ { invalid =>
      Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
        val saved    = book.copy(album = album.copy(opening = opening.copy(textSlot = textSlot.copy(box = invalid))))
        val exporter = buildExporter(calls, saved, PhotoStorage.ReadResult.Read(content, metadata).pure[IO])
        exporter.exportBook(album.id).map(result =>
          assertEquals(
            result,
            Result.Blocked(Vector(Issue(Code.InvalidGeometry, Severity.Blocking, Target.Spread(opening.id))))
          )
        )
    }

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
      (album = found, cover = cover, photos = inventory, gear = GearRepository.CatalogReadResult.Failed, expected = Result.Failed)
    )

    cases.traverse_ { case (albumRead, coverRead, photoRead, gearRead, expected) =>
      Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
        val exporter = BookExport.make(
          buildAlbums(albumRead),
          buildCovers(coverRead),
          buildPhotos(photoRead),
          buildGear(gearRead),
          buildStorage(calls, PhotoStorage.ReadResult.Failed.pure[IO])
        )

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
        val exporter = buildExporter(calls, saved, PhotoStorage.ReadResult.Read(content, current).pure[IO])
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
        val exporter = buildExporter(calls, book, response.pure[IO])
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
        val exporter = buildExporter(calls, book.copy(album = saved), PhotoStorage.ReadResult.Read(content, metadata).pure[IO])
        val expected = Vector(Issue(code, Severity.Blocking, Target.Slot(opening.id, photoSlot.id)))

        exporter.exportBook(album.id).map(result => assertEquals(result, Result.Blocked(expected)))
    }

  test("should reread saved state and originals on retry while leaving unused collection photos unchecked"):
    Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
      val unused   = photos.take(1).map(_.copy(id = PhotoId(new UUID(9L, 9L))))
      val saved    = book.copy(photos = photos ++ unused)
      val exporter = buildExporter(calls, saved, PhotoStorage.ReadResult.Read(content, metadata).pure[IO])

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
        buildExporter(calls, book, IO.raiseError[PhotoStorage.ReadResult](new IllegalStateException("Source unavailable")))

      exporter.exportBook(album.id).map(result => assertEquals(result, Result.Failed))

  test("should block a cover whose page count does not describe the saved interior"):
    Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
      val mismatched = book.copy(cover = book.cover.copy(dimensions = CoverDimensions.forSpreads(3, cats.data.NonEmptyVector.one(specification))))
      val exporter   = buildExporter(calls, mismatched, PhotoStorage.ReadResult.Read(content, metadata).pure[IO])
      val expected   = Vector(Issue(Code.InvalidPageCount, Severity.Blocking, Target.Album))

      exporter.exportBook(album.id).map(result => assertEquals(result, Result.Blocked(expected)))

  test("should release the saved-state transaction before checking originals and fail explicitly if acquisition throws"):
    Ref.of[IO, Vector[OriginalPhotoLocation]](Vector.empty).flatMap: calls =>
      Ref.of[IO, Boolean](false).flatMap: reading =>
        given Transaction[IO, IO] with
          override def run[A](program: IO[A]): IO[A]     = reading.set(true) >> program.guarantee(reading.set(false))
          override def resolve[A](program: IO[A]): IO[A] = fail("Export must not resolve writes")
        val read        = reading.get.flatMap(active => IO(assertEquals(active, false))).as(PhotoStorage.ReadResult.Read(content, metadata))
        val exporter    = buildExporter(calls, book, read)
        val unavailable = new AlbumRepository.AggregateRead[IO]:
          override def readAlbum(id: AlbumId): IO[AlbumRepository.GetResult] =
            IO.raiseError[AlbumRepository.GetResult](new IllegalStateException("Database unavailable"))
        val failed = BookExport.make(
          unavailable,
          buildCovers(CoverRepository.ReadResult.Found(book.cover)),
          buildPhotos(PhotoRepository.ListResult.Listed(book.photos)),
          buildGear(GearRepository.CatalogReadResult.Found(book.gear)),
          buildStorage(calls, read)
        )

        for
          ready     <- exporter.exportBook(album.id)
          failure   <- failed.exportBook(album.id)
          retained  <- reading.get
          originals <- calls.get
        yield
          assertEquals(ready, Result.Ready(book, warnings = Vector.empty[Issue]))
          assertEquals(failure, Result.Failed)
          assertEquals(retained, false)
          assertEquals(originals, photos.map(_.original))

  private def buildExporter(calls: Ref[IO, Vector[OriginalPhotoLocation]], saved: SavedBook, read: IO[PhotoStorage.ReadResult])(using
      Transaction[IO, IO]
  ) =
    BookExport.make(
      buildAlbums(AlbumRepository.GetResult.Found(saved.album)),
      buildCovers(CoverRepository.ReadResult.Found(saved.cover)),
      buildPhotos(PhotoRepository.ListResult.Listed(saved.photos)),
      buildGear(GearRepository.CatalogReadResult.Found(saved.gear)),
      buildStorage(calls, read)
    )

  private def buildAlbums(response: AlbumRepository.GetResult) = new AlbumRepository.AggregateRead[IO]:
    override def readAlbum(id: AlbumId): IO[AlbumRepository.GetResult] = IO(assertEquals(id, album.id)).as(response)

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

  private def buildGear(response: GearRepository.CatalogReadResult) = new GearRepository.CatalogRead[IO]:
    override def readSavedCatalog: IO[GearRepository.CatalogReadResult] = response.pure[IO]

  private def buildStorage(calls: Ref[IO, Vector[OriginalPhotoLocation]], read: IO[PhotoStorage.ReadResult]) =
    new PhotoStorage[IO]:
      override def readOriginal(location: OriginalPhotoLocation): IO[PhotoStorage.ReadResult] = calls.update(_ :+ location) >> read
      override def discoverOriginals(source: PhotoSource): IO[PhotoStorage.DiscoveryResult]   = fail("Export must not discover sources")
      override def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): IO[ThumbnailStorage.StoreResult] = fail("Read-only export")
      override def readThumbnail(location: ThumbnailLocation, version: String :| Not[Blank]): IO[ThumbnailStorage.ReadResult] = fail("Read originals")
      override def removeThumbnail(location: ThumbnailLocation): IO[ThumbnailStorage.RemoveResult] = fail("Read-only export")
