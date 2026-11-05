package photography.usecases.album

import cats.{Monad, MonadThrow}
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import photography.domain.album.*
import photography.domain.layout.*
import photography.domain.gear.GearCatalog
import photography.capabilities.Transaction
import photography.ports.album.AlbumRepository
import photography.ports.cover.CoverRepository
import photography.ports.gear.GearRepository
import photography.ports.photo.{PhotoRepository, PhotoStorage}
import photography.syntax.EitherSyntax.*

trait BookExport[F[_]]:
  def exportBook(album: AlbumId): F[BookExport.Result]

object BookExport:
  def make[F[_]: MonadThrow: Logger, Db[_]: Monad](
      albums: AlbumRepository.AggregateRead[Db],
      covers: CoverRepository[Db],
      photos: PhotoRepository[Db],
      gear: GearRepository.CatalogRead[Db],
      originals: PhotoStorage[F]
  )(using Transaction[Db, F]): BookExport[F] = new LiveBookExport(albums, covers, photos, gear, originals)

  final private class LiveBookExport[F[_]: MonadThrow: Logger, Db[_]: Monad](
      albums: AlbumRepository.AggregateRead[Db],
      covers: CoverRepository[Db],
      photos: PhotoRepository[Db],
      gear: GearRepository.CatalogRead[Db],
      originals: PhotoStorage[F]
  )(using Transaction[Db, F]) extends BookExport[F]:
    override def exportBook(album: AlbumId): F[Result] =
      Transaction[Db, F].run(readBook(album)).flatMap {
        case Left(ReadFailure.NotFound)    => Result.NotFound.pure[F]
        case Left(ReadFailure.Unavailable) =>
          Logger[F].error(s"Saved book acquisition failed for ${album.value}").as(Result.Failed)
        case Right(book) => validate(book)
      }.handleErrorWith(error => Logger[F].error(error)(s"Book export failed for ${album.value}").as(Result.Failed))

    private def readBook(album: AlbumId) =
      albums.readAlbum(album).flatMap:
        case AlbumRepository.GetResult.NotFound     => ReadFailure.NotFound.asLeft[SavedBook].pure[Db]
        case AlbumRepository.GetResult.Failed(_)    => ReadFailure.Unavailable.asLeft[SavedBook].pure[Db]
        case AlbumRepository.GetResult.Found(saved) => loadBook(saved).value

    private def loadBook(album: Album) =
      for
        cover <- covers.read(album.id).map {
          case CoverRepository.ReadResult.Found(composition) => composition.asRight[ReadFailure]
          case CoverRepository.ReadResult.NotFound           => ReadFailure.Unavailable.asLeft[CoverRepository.Composition]
        }.eitherT
        inventory <- album.chapters.traverse(chapter =>
          photos.listChapter(chapter.id).map {
            case PhotoRepository.ListResult.Listed(values) => values.asRight[ReadFailure]
            case PhotoRepository.ListResult.Failed         => ReadFailure.Unavailable.asLeft[Vector[Photo]]
          }.eitherT
        )
        catalog <- gear.readSavedCatalog.map {
          case GearRepository.CatalogReadResult.Found(value) => value.asRight[ReadFailure]
          case GearRepository.CatalogReadResult.Failed       => ReadFailure.Unavailable.asLeft[GearCatalog]
        }.eitherT
      yield SavedBook(album, cover, inventory.flatten, catalog)

    private def validate(book: SavedBook) =
      val references = book.album.collectSpreads.toList.flatMap(_.placements.map(_.photo)).toVector ++ book.cover.placements.map(_.photo)
      val photos     = book.photos.filter(photo => references.contains(photo.id)).distinctBy(_.id)
      photos.traverse(checkOriginal).map: sourceIssues =>
        val issues = checkContent(book) ++ checkInterior(book) ++ checkCover(book) ++ sourceIssues.flatten
        if issues.exists(_.severity === Severity.Blocking) then Result.Blocked(issues) else Result.Ready(book, warnings = issues)

    private def checkContent(book: SavedBook) =
      val synopsis     = Option.when(book.album.synopsis.isBlank)(Issue(Code.EmptySynopsis, Severity.Blocking, Target.Album)).toList.toVector
      val descriptions = book.album.chapters.filter(_.description.isBlank)
        .map(chapter => Issue(Code.EmptyDescription, Severity.Blocking, Target.Chapter(chapter.id)))
      val count    = book.album.collectSpreads.length
      val capacity = Option.when(count > InteriorSpreadCapacity.maximum || book.cover.dimensions.pageCount =!= count.toLong * 2L)(
        Issue(Code.InvalidPageCount, Severity.Blocking, Target.Album)
      ).toList.toVector
      synopsis ++ descriptions ++ capacity

    private def checkInterior(book: SavedBook) =
      book.album.collectSpreads.toList.toVector.flatMap: spread =>
        val maybeTextBox = spread match
          case value: Spread.AlbumStart   => value.textSlot.box.some
          case value: Spread.ChapterStart => value.textSlot.box.some
          case _: Spread.PhotosOnly       => none[SlotBox]
        val textIssues = maybeTextBox.filterNot(isWithinSpread)
          .map(_ => Issue(Code.InvalidGeometry, Severity.Blocking, Target.Spread(spread.id))).toList.toVector
        val photoIssues = spread.photos.flatMap: slot =>
          val target          = Target.Slot(spread.id, slot.id)
          val frame           = slot.visibleBox
          val geometry        = frame.toOption.filter(box => isWithinSpread(slot.box) && isWithinSpread(box))
          val geometryIssues  = Option.when(geometry.isEmpty)(Issue(Code.InvalidGeometry, Severity.Blocking, target)).toList.toVector
          val placementIssues = spread.placements.find(_.slot.value === slot.id.value) match
            case None            => Vector(Issue(Code.EmptyPhotoSlot, Severity.Blocking, target))
            case Some(placement) =>
              val photo = book.photos.find(_.id.value === placement.photo.value)
              geometry.fold(Vector.empty[Issue])(box =>
                val frame = InteriorDimensions.photoFrame(box, slot.bleed)
                checkPlacement(photo, placement.transform, frame.width, frame.height, target)
              )
          geometryIssues ++ placementIssues
        textIssues ++ photoIssues

    private def isWithinSpread(box: SlotBox) =
      box.x + box.width <= 100.0 && box.y + box.height <= 100.0

    private def checkCover(book: SavedBook) =
      val cover      = book.cover.cover
      val dimensions = book.cover.dimensions
      val frames     = Vector(cover.front.id -> dimensions.frontFrame(cover.spineOwner), cover.back.id -> dimensions.backFrame(cover.spineOwner))
      frames.flatMap { case (slot, frame) =>
        val target = Target.CoverSlot(slot)
        book.cover.placements.find(_.slot.value === slot.value) match
          case None            => Vector(Issue(Code.EmptyPhotoSlot, Severity.Blocking, target))
          case Some(placement) =>
            checkPlacement(book.photos.find(_.id.value === placement.photo.value), placement.transform, frame.width, frame.height, target)
      }

    private def checkPlacement(maybePhoto: Option[Photo], transform: PhotoTransform, width: Double, height: Double, target: Target) =
      maybePhoto match
        case None        => Vector(Issue(Code.MissingPhoto, Severity.Blocking, target))
        case Some(photo) =>
          if transform.zoom < 1.0 || transform.zoom > 5.0 then Vector(Issue(Code.InvalidFraming, Severity.Blocking, target))
          else
            val scale = math.max(width / photo.metadata.dimensions.width, height / photo.metadata.dimensions.height) * transform.zoom
            Option.when(72.0 / scale < 300.0)(Issue(Code.LowResolution, Severity.Warning, target)).toList.toVector

    private def checkOriginal(photo: Photo) =
      originals.readOriginal(photo.original).map: result =>
        val maybeCode = result match
          case PhotoStorage.ReadResult.Read(_, metadata) =>
            Option.when(
              (metadata.hash: String) =!= (photo.metadata.hash: String) ||
                (photo.metadata.dimensions.width: Int) =!= (metadata.dimensions.width: Int) ||
                (photo.metadata.dimensions.height: Int) =!= (metadata.dimensions.height: Int)
            )(Code.OriginalChanged)
          case PhotoStorage.ReadResult.Undecodable => Code.OriginalUndecodable.some
          case PhotoStorage.ReadResult.Unstable    => Code.OriginalUnstable.some
          case PhotoStorage.ReadResult.Failed      => Code.OriginalUnavailable.some
        maybeCode.map(code => Issue(code, Severity.Blocking, Target.Photo(photo.id))).toList.toVector

    private enum ReadFailure:
      case NotFound, Unavailable

  final case class SavedBook(album: Album, cover: CoverRepository.Composition, photos: Vector[Photo], gear: GearCatalog)

  enum Result:
    case Ready(book: SavedBook, warnings: Vector[Issue])
    case Blocked(issues: Vector[Issue])
    case NotFound, Failed

  enum Severity derives CanEqual:
    case Blocking, Warning

  object Severity:
    given cats.Eq[Severity] = cats.Eq.by(_.ordinal)

  enum Target:
    case Album
    case Chapter(chapter: ChapterId)
    case Spread(spread: SpreadId)
    case Slot(spread: SpreadId, slot: PhotoSpreadSlotId)
    case CoverSlot(slot: CoverSlotId)
    case Photo(photo: PhotoId)

  enum Code:
    case EmptySynopsis, EmptyDescription, EmptyPhotoSlot, MissingPhoto, InvalidPageCount, InvalidGeometry, InvalidFraming
    case LowResolution, OriginalChanged, OriginalUndecodable, OriginalUnstable, OriginalUnavailable

  final case class Issue(code: Code, severity: Severity, target: Target)
