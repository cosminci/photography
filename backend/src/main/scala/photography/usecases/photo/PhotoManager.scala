package photography.usecases.photo

import photography.ports.photo.PhotoThumbnailGenerator

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.numeric.GreaterEqual
import org.typelevel.log4cats.Logger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.ports.photo.{PhotoRepository, PhotoStorage, ThumbnailStorage}
import photography.usecases.photo.PhotoManager.*

trait PhotoManager[F[_]]:
  def saveCaption(chapter: ChapterId, photo: PhotoId, text: String): F[SaveCaptionResult]
  def refresh(chapter: ChapterId, source: PhotoSource): F[RefreshResult]

object PhotoManager:
  def make[F[_]: Async: IdGenerator: Logger, Db[_]: MonadThrow](
      photoStorage: PhotoStorage[F],
      photos: PhotoRepository[Db],
      generator: PhotoThumbnailGenerator[F]
  )(using Transaction[Db, F]): PhotoManager[F] = new LivePhotoManager(photoStorage, photos, generator)

  final private class LivePhotoManager[F[_]: Async: IdGenerator: Logger, Db[_]: MonadThrow](
      photoStorage: PhotoStorage[F],
      photos: PhotoRepository[Db],
      thumbnails: PhotoThumbnailGenerator[F]
  )(using Transaction[Db, F]) extends PhotoManager[F]:
    override def saveCaption(chapter: ChapterId, photo: PhotoId, text: String): F[SaveCaptionResult] =
      Async[F].uncancelable: _ =>
        Transaction[Db, F].run(photos.saveCaption(chapter, photo, text = text)).flatMap:
          case PhotoRepository.SaveCaptionResult.Saved         => SaveCaptionResult.Saved.pure[F]
          case PhotoRepository.SaveCaptionResult.PhotoNotFound => SaveCaptionResult.PhotoNotFound.pure[F]
          case PhotoRepository.SaveCaptionResult.Failed(cause) => Logger[F].error(cause)("Photo caption write failed").as(SaveCaptionResult.Failed)
          case PhotoRepository.SaveCaptionResult.Uncertain     => SaveCaptionResult.Uncertain.pure[F]
        .handleErrorWith(error => Logger[F].error(error)("Photo caption commit acknowledgement failed").as(SaveCaptionResult.Uncertain))

    override def refresh(chapter: ChapterId, source: PhotoSource): F[RefreshResult] =
      photoStorage.discoverOriginals(source).flatMap:
        case PhotoStorage.DiscoveryResult.Failed => RefreshResult(unsupportedCount = 0, issues = Vector(Issue.Discovery)).pure[F]
        case PhotoStorage.DiscoveryResult.Complete(originals, unsupported) =>
          reconcile(chapter, originals = originals).map(issues => RefreshResult(unsupportedCount = unsupported, issues = issues))
      .handleErrorWith: error =>
        Logger[F].error(error)("Photo reconciliation failed").as(RefreshResult(unsupportedCount = 0, issues = Vector(Issue.Unexpected)))

    private def reconcile(chapter: ChapterId, originals: Vector[OriginalPhotoLocation]) =
      Transaction[Db, F].run(photos.listChapter(chapter)).flatMap:
        case PhotoRepository.ListResult.Failed           => Vector(Issue.Persistence).pure[F]
        case PhotoRepository.ListResult.Listed(existing) =>
          val removed = existing.filterNot(photo => originals.contains(photo.original))
          val updates = originals.traverse: original =>
            val maybePrevious = existing.find(_.original === original)
            refreshPhoto(chapter, original, maybePrevious = maybePrevious)
          (updates, removed.traverse(retire)).mapN((updated, retired) => updated.flatten ++ retired.flatten)
      .handleErrorWith(error => Logger[F].error(error)("Photo reconciliation failed").as(Vector(Issue.Persistence)))

    private def refreshPhoto(chapter: ChapterId, original: OriginalPhotoLocation, maybePrevious: Option[Photo]) =
      val refresh = photoStorage.readOriginal(original).flatMap:
        case PhotoStorage.ReadResult.Read(content, metadata) =>
          val unchanged = maybePrevious.exists: photo =>
            (photo.metadata.hash: String) === (metadata.hash: String) && photo.thumbnail.quality === thumbnails.quality
          if unchanged then Vector.empty[Issue].pure[F]
          else generateThumbnail(chapter, original, content, metadata, maybePrevious = maybePrevious)
        case PhotoStorage.ReadResult.Undecodable => Vector(Issue.Undecodable(original.filename)).pure[F]
        case PhotoStorage.ReadResult.Unstable    => Vector(Issue.Unstable(original.filename)).pure[F]
        case PhotoStorage.ReadResult.Failed      => Vector(Issue.Read(original.filename)).pure[F]
      refresh.handleErrorWith(error => Logger[F].error(error)("Photo reconciliation failed").as(Vector(Issue.Unexpected)))

    private def generateThumbnail(
        chapter: ChapterId,
        original: OriginalPhotoLocation,
        content: PhotoContent,
        metadata: PhotoMetadata,
        maybePrevious: Option[Photo]
    ) =
      thumbnails.generate(content).flatMap:
        case PhotoThumbnailGenerator.GenerationResult.Failed           => Vector(Issue.Generation(original.filename)).pure[F]
        case PhotoThumbnailGenerator.GenerationResult.Generated(image) =>
          val maybeCaption                                 = maybePrevious.flatMap(_.maybeCaption)
          val identity                                     = maybePrevious.fold(IdGenerator[F].generateId.map(PhotoId(_)))(_.id.pure[F])
          val publication: F[PhotoWriteIntent.Publication] =
            for
              id       <- identity
              location <- IdGenerator[F].generateId.map(ThumbnailLocation(_))
              writeId  <- IdGenerator[F].generateId.map(PhotoWriteIntentId(_))
            yield PhotoWriteIntent.Publication(writeId, chapter, id, location, maybePrevious = maybePrevious.map(_.thumbnail.location))
          publication.flatMap(intent => publish(intent, Photo(intent.photo, chapter, original, metadata, _, maybeCaption = maybeCaption), image))

    private def publish(intent: PhotoWriteIntent.Publication, photo: Thumbnail => Photo, image: EncodedThumbnail) =
      Async[F].uncancelable: _ =>
        Transaction[Db, F].run(recordWrite(intent)) >>
          photoStorage.storeThumbnail(intent.location, image).attempt.flatMap:
            case Right(ThumbnailStorage.StoreResult.Failed) => finishWrite(intent, locations = Vector(intent.location)).map(Issue.Storage +: _)
            case Left(error)                                =>
              Logger[F].error(error)("Photo thumbnail storage failed") >>
                finishWrite(intent, locations = Vector(intent.location)).map(Issue.Storage +: _)
            case Right(ThumbnailStorage.StoreResult.Stored(thumbnail)) =>
              val locations = intent.location +: intent.maybePrevious.toList.toVector
              Transaction[Db, F].run(photos.save(photo(thumbnail))).attempt.flatMap:
                case Right(PhotoRepository.WriteResult.Applied) => finishWrite(intent, locations = locations)
                case Right(PhotoRepository.WriteResult.Failed)  => finishWrite(intent, locations = locations).map(Issue.Persistence +: _)
                case Left(error)                                =>
                  Logger[F].error(error)("Photo persistence failed") >> finishWrite(intent, locations = locations).map(Issue.Persistence +: _)
      .handleErrorWith(error => Logger[F].error(error)("Photo reconciliation failed").as(Vector(Issue.Persistence)))

    private def retire(photo: Photo) =
      Async[F].uncancelable: _ =>
        IdGenerator[F].generateId.map(PhotoWriteIntentId(_)).flatMap: id =>
          val intent: PhotoWriteIntent.Retirement = PhotoWriteIntent.Retirement(id, photo.chapter, photo.id, photo.thumbnail.location)
          val remove                              = recordWrite(intent) >> photos.remove(photo.id).flatMap:
            case PhotoRepository.WriteResult.Applied => ().pure[Db]
            case PhotoRepository.WriteResult.Failed  => new IllegalStateException("Photo removal rejected").raiseError[Db, Unit]
          Transaction[Db, F].run(remove).attempt.flatMap:
            case Right(())   => finishWrite(intent, locations = Vector(intent.location))
            case Left(error) =>
              Logger[F].error(error)("Photo retirement failed") >>
                finishWrite(intent, locations = Vector(intent.location)).map(Issue.Persistence +: _)
      .handleErrorWith(error => Logger[F].error(error)("Photo reconciliation failed").as(Vector(Issue.Persistence)))

    private def recordWrite(intent: PhotoWriteIntent) =
      photos.recordWrite(intent).flatMap:
        case PhotoRepository.RecordWriteResult.Recorded => ().pure[Db]
        case PhotoRepository.RecordWriteResult.Failed   => new IllegalStateException("Photo write evidence rejected").raiseError[Db, Unit]

    private def finishWrite(intent: PhotoWriteIntent, locations: Vector[ThumbnailLocation]) =
      Transaction[Db, F].resolve(photos.listChapter(intent.chapter)).flatMap:
        case PhotoRepository.ListResult.Failed          => Vector(Issue.WriteReferences(intent.id)).pure[F]
        case PhotoRepository.ListResult.Listed(current) =>
          val unused = locations.distinct.filterNot(location => current.exists(_.thumbnail.location.value === location.value))
          unused.traverse(cleanup).map(_.flatten).flatMap: issues =>
            if issues.nonEmpty then issues.pure[F] else completeWrite(intent.id)
      .handleErrorWith(error => Logger[F].error(error)("Photo write reference read failed").as(Vector(Issue.WriteReferences(intent.id))))

    private def completeWrite(id: PhotoWriteIntentId) =
      Transaction[Db, F].resolve(photos.completeWrite(id)).flatMap:
        case PhotoRepository.CompleteWriteResult.Completed => Vector.empty[Issue].pure[F]
        case PhotoRepository.CompleteWriteResult.Failed    => Vector(Issue.WriteCompletion(id)).pure[F]
      .handleErrorWith(error => Logger[F].error(error)("Photo write completion failed").as(Vector(Issue.WriteCompletion(id))))

    private def cleanup(location: ThumbnailLocation) =
      photoStorage.removeThumbnail(location).attempt.flatMap:
        case Right(ThumbnailStorage.RemoveResult.Removed) => Vector.empty[Issue].pure[F]
        case Right(ThumbnailStorage.RemoveResult.Failed)  => Vector(Issue.Cleanup(location)).pure[F]
        case Left(error) => Logger[F].error(error)("Photo thumbnail cleanup failed").as(Vector(Issue.Cleanup(location)))

  enum SaveCaptionResult:
    case Saved, PhotoNotFound, Failed, Uncertain

  final case class RefreshResult(unsupportedCount: Int :| GreaterEqual[0], issues: Vector[Issue])

  enum Issue:
    case Discovery, Persistence, Storage, Unexpected
    case Read(filename: PhotoFilename)
    case Generation(filename: PhotoFilename)
    case Undecodable(filename: PhotoFilename)
    case Unstable(filename: PhotoFilename)
    case Cleanup(location: ThumbnailLocation)
    case WriteReferences(id: PhotoWriteIntentId)
    case WriteCompletion(id: PhotoWriteIntentId)
