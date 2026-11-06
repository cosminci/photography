package photography.usecases.photo

import cats.MonadThrow
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import photography.capabilities.Transaction
import photography.domain.album.*
import photography.ports.photo.{PhotoRepository, ThumbnailStorage}
import photography.usecases.photo.PhotoWriteRecovery.*

trait PhotoWriteRecovery[F[_]]:
  def recover: F[RecoveryResult]
  def resolve(intent: PhotoWriteIntent): F[RecoveryResult]

object PhotoWriteRecovery:
  def make[F[_]: MonadThrow: Logger, Db[_]](
      photos: PhotoRepository[Db],
      storage: ThumbnailStorage[F]
  )(using Transaction[Db, F]): PhotoWriteRecovery[F] = new LivePhotoWriteRecovery(photos, storage)

  final private class LivePhotoWriteRecovery[F[_]: MonadThrow: Logger, Db[_]](
      photos: PhotoRepository[Db],
      storage: ThumbnailStorage[F]
  )(using Transaction[Db, F]) extends PhotoWriteRecovery[F]:
    override def recover: F[RecoveryResult] =
      Transaction[Db, F].run(photos.listUnfinishedWrites).flatMap:
        case PhotoRepository.ListUnfinishedWritesResult.Failed          => RecoveryResult.Failed(issues = Vector(Issue.Listing)).pure[F]
        case PhotoRepository.ListUnfinishedWritesResult.Listed(intents) =>
          intents.traverse(recoverWrite).map: results =>
            val issues = results.flatten
            if issues.isEmpty then RecoveryResult.Recovered else RecoveryResult.Failed(issues = issues)
      .handleErrorWith(error => Logger[F].error(error)("Photo write listing failed").as(RecoveryResult.Failed(issues = Vector(Issue.Listing))))

    override def resolve(intent: PhotoWriteIntent): F[RecoveryResult] =
      recoverWrite(intent).map: issues =>
        if issues.isEmpty then RecoveryResult.Recovered else RecoveryResult.Failed(issues = issues)

    private def recoverWrite(intent: PhotoWriteIntent) =
      Transaction[Db, F].resolve(photos.listChapter(intent.chapter)).flatMap:
        case PhotoRepository.ListResult.Failed          => Vector(Issue.References(intent.id)).pure[F]
        case PhotoRepository.ListResult.Listed(current) =>
          val locations = intent match
            case publication: PhotoWriteIntent.Publication => publication.location +: publication.maybePrevious.toList.toVector
            case retirement: PhotoWriteIntent.Retirement   => Vector(retirement.location)
          val unused = locations.distinct.filterNot(location => current.exists(_.thumbnail.location.value === location.value))
          unused.traverse(location => cleanup(intent.id, location)).map(_.flatten).flatMap: issues =>
            if issues.nonEmpty then issues.pure[F] else complete(intent.id)
      .handleErrorWith(error => Logger[F].error(error)("Photo write reference read failed").as(Vector(Issue.References(intent.id))))

    private def cleanup(id: PhotoWriteIntentId, location: ThumbnailLocation) =
      storage.removeThumbnail(location).flatMap:
        case ThumbnailStorage.RemoveResult.Removed => Vector.empty[Issue].pure[F]
        case ThumbnailStorage.RemoveResult.Failed  => Vector(Issue.Cleanup(id, location)).pure[F]
      .handleErrorWith(error => Logger[F].error(error)("Photo write cleanup failed").as(Vector(Issue.Cleanup(id, location))))

    private def complete(id: PhotoWriteIntentId) =
      Transaction[Db, F].resolve(photos.completeWrite(id)).flatMap:
        case PhotoRepository.CompleteWriteResult.Completed => Vector.empty[Issue].pure[F]
        case PhotoRepository.CompleteWriteResult.Failed    => Vector(Issue.Completion(id)).pure[F]
      .handleErrorWith(error => Logger[F].error(error)("Photo write completion failed").as(Vector(Issue.Completion(id))))

  enum RecoveryResult:
    case Recovered
    case Failed(issues: Vector[Issue])

  enum Issue:
    case Listing
    case References(id: PhotoWriteIntentId)
    case Cleanup(id: PhotoWriteIntentId, location: ThumbnailLocation)
    case Completion(id: PhotoWriteIntentId)
