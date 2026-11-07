package photography.usecases.photo

import cats.data.{NonEmptyList, Kleisli}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.Transaction
import photography.domain.album.*
import photography.ports.chapter.ChapterRepository
import photography.ports.photo.{PhotoPlacementRepository, PhotoRepository}
import java.time.LocalDateTime
import java.util.UUID

final class PhotoInventoryComponentTest extends CatsEffectSuite:
  private type Db[A] = Kleisli[IO, Unit, A]
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val chapter      = ChapterId(new UUID(0L, 1L))
  private val metadata     = PhotoMetadata(hash = "hash", maybeCapturedLocal = None, PhotoDimensions(width = 10, height = 10))
  private val thumbnail    =
    Thumbnail(ThumbnailLocation(new UUID(0L, 2L)), version = "v", ThumbnailDimensions(width = 5, height = 5), ThumbnailQuality.Low, bytes = 1)
  private val photo =
    Photo(PhotoId(new UUID(0L, 3L)), chapter, OriginalPhotoLocation(source = "/photos", filename = "z.jpg"), metadata, thumbnail, maybeCaption = None)

  test("should read ordered photos and placements in one type-separated database snapshot"):
    val dated     = photo.copy(id = PhotoId(new UUID(0L, 4L)), metadata = metadata.copy(maybeCapturedLocal = LocalDateTime.of(2025, 1, 1, 0, 0).some))
    val alpha     = photo.copy(id = PhotoId(new UUID(0L, 5L)), original = photo.original.copy(filename = "a.jpg"))
    val upper     = photo.copy(id = PhotoId(new UUID(0L, 6L)), original = photo.original.copy(filename = "A.jpg"))
    val inventory = PhotoInventory.Inventory(photos = Vector(dated, upper, alpha, photo), placed = Set(photo.id))
    val listed    = PhotoRepository.ListResult.Listed(photos = Vector(photo, alpha, dated, upper))
    val placed    = PhotoPlacementRepository.FindResult.Found(photos = Set(photo.id))
    val refs      = Refs()
    val manager   = buildInventory(refs, listed, placed)

    for
      actual <- manager.listChapter(chapter)
      calls  <- refs.calls.get
    yield
      assertEquals(actual, PhotoInventory.ListResult.Listed(inventory))
      assertEquals(calls, Vector(Call.Begin, Call.Source(chapter), Call.Photos(chapter), Call.Placements(chapter), Call.End))

  test("should report either inventory boundary failure"):
    Vector(
      (PhotoRepository.ListResult.Failed, PhotoPlacementRepository.FindResult.Found(Set.empty[PhotoId])),
      (PhotoRepository.ListResult.Listed(Vector.empty[Photo]), PhotoPlacementRepository.FindResult.Failed)
    ).traverse_ { case (photos, placed) =>
      buildInventory(Refs(), photos, placed).listChapter(chapter).map(actual => assertEquals(actual, PhotoInventory.ListResult.Failed))
    }

  test("should report a failed transaction as inventory failure"):
    val manager = buildInventory(
      Refs(),
      PhotoRepository.ListResult.Listed(Vector(photo)),
      PhotoPlacementRepository.FindResult.Found(Set.empty[PhotoId]),
      maybeTransactionError = new RuntimeException("transaction failed").some
    )
    manager.listChapter(chapter).map(actual => assertEquals(actual, PhotoInventory.ListResult.Failed))

  test("should distinguish a missing chapter from an existing empty inventory and failed source lookup"):
    Vector(
      (ChapterRepository.SourceResult.ChapterNotFound, PhotoInventory.ListResult.ChapterNotFound),
      (ChapterRepository.SourceResult.Failed, PhotoInventory.ListResult.Failed),
      (
        ChapterRepository.SourceResult.Found(AlbumId(new UUID(0L, 99L)), source = "/photos"),
        PhotoInventory.ListResult.Listed(PhotoInventory.Inventory(photos = Vector.empty[Photo], placed = Set.empty[PhotoId]))
      )
    ).traverse_ { case (source, expected) =>
      val refs    = Refs()
      val manager = buildInventory(
        refs,
        PhotoRepository.ListResult.Listed(photos = Vector.empty[Photo]),
        PhotoPlacementRepository.FindResult.Found(photos = Set.empty[PhotoId]),
        source = source
      )

      val queries = source match
        case _: ChapterRepository.SourceResult.Found => Vector(Call.Photos(chapter), Call.Placements(chapter))
        case _                                       => Vector.empty[Call]

      for
        actual <- manager.listChapter(chapter)
        calls  <- refs.calls.get
      yield
        assertEquals(actual, expected)
        assertEquals(calls, Vector(Call.Begin, Call.Source(chapter)) ++ queries :+ Call.End)
    }

  private enum Call:
    case Begin, End
    case Source(chapter: ChapterId)
    case Photos(chapter: ChapterId)
    case Placements(chapter: ChapterId)

  final private case class Refs():
    val calls: Ref[IO, Vector[Call]] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildInventory(
      refs: Refs,
      listed: PhotoRepository.ListResult,
      placed: PhotoPlacementRepository.FindResult,
      maybeTransactionError: Option[Throwable] = None,
      source: ChapterRepository.SourceResult = ChapterRepository.SourceResult.Found(AlbumId(new UUID(0L, 99L)), source = "/photos")
  ) =
    given Transaction[Db, IO] with
      override def run[A](program: Db[A]): IO[A] =
        refs.calls.update(_ :+ Call.Begin) >> maybeTransactionError.fold(
          program.run(()).flatTap(_ => refs.calls.update(_ :+ Call.End))
        )(IO.raiseError[A])
      override def resolve[A](program: Db[A]): IO[A] = program.run(())
    val photos = new PhotoRepository[Db]:
      override def find(photo: PhotoId): Db[PhotoRepository.FindResult] =
        unexpected
      override def saveCaption(chapter: ChapterId, id: PhotoId, text: String): Db[PhotoRepository.SaveCaptionResult] =
        unexpected
      override def listChapter(id: ChapterId): Db[PhotoRepository.ListResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.ListResult](refs.calls.update(_ :+ Call.Photos(id)).as(listed))
      override def save(value: Photo): Db[PhotoRepository.WriteResult] =
        unexpected
      override def remove(value: PhotoId): Db[PhotoRepository.WriteResult] =
        unexpected
      override def recordWrite(intent: PhotoWriteIntent): Db[PhotoRepository.RecordWriteResult] =
        unexpected
      override def completeWrite(id: PhotoWriteIntentId): Db[PhotoRepository.CompleteWriteResult] =
        unexpected
      override def listUnfinishedWrites: Db[PhotoRepository.ListUnfinishedWritesResult] =
        unexpected
    val placements = new PhotoPlacementRepository[Db]:
      override def findPlacedPhotos(id: ChapterId): Db[PhotoPlacementRepository.FindResult] =
        Kleisli.liftF[IO, Unit, PhotoPlacementRepository.FindResult](refs.calls.update(_ :+ Call.Placements(id)).as(placed))
      override def inspect(album: AlbumId, maybeChapter: Option[ChapterId], photo: PhotoId): Db[PhotoPlacementRepository.InspectResult] =
        unexpected
    val chapters = new ChapterRepository.Metadata[Db]:
      override def saveDescription(
          album: AlbumId,
          chapter: ChapterId,
          text: String
      ): Db[ChapterRepository.SaveDescriptionResult] =
        unexpected
      override def findSource(id: ChapterId): Db[ChapterRepository.SourceResult] =
        Kleisli.liftF[IO, Unit, ChapterRepository.SourceResult](refs.calls.update(_ :+ Call.Source(id)).as(source))
      override def create(album: AlbumId, chapter: ChapterRepository.NewChapter, order: NonEmptyList[ChapterId]): Db[ChapterRepository.CreateResult] =
        unexpected
      override def update(command: ChapterRepository.Update): Db[ChapterRepository.UpdateResult] = unexpected
    PhotoInventory.make[IO, Db](photos, placements, chapters)
  // scalafix:on DisableSyntax.defaultArgs

  private def unexpected[A]: Db[A] = Kleisli.liftF[IO, Unit, A](IO.raiseError[A](new AssertionError("Unexpected operation")))
