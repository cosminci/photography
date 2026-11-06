package photography.usecases.photo

import cats.Monad
import cats.syntax.all.*
import photography.domain.album.ChapterId

trait ChapterPhotoInventory[F[_]]:
  def read(chapter: ChapterId): F[ChapterPhotoInventory.Result]

object ChapterPhotoInventory:
  def make[F[_]: Monad](photos: PhotoInventory[F], imports: ChapterPhotoImport[F]): ChapterPhotoInventory[F] =
    new LiveChapterPhotoInventory(photos, imports)

  final private class LiveChapterPhotoInventory[F[_]: Monad](photos: PhotoInventory[F], imports: ChapterPhotoImport[F])
      extends ChapterPhotoInventory[F]:
    override def read(chapter: ChapterId): F[Result] =
      photos.listChapter(chapter).flatMap:
        case PhotoInventory.ListResult.Listed(inventory) =>
          imports.status(chapter).map(execution => Result.Found(Snapshot(inventory, execution)))
        case PhotoInventory.ListResult.ChapterNotFound => Result.ChapterNotFound.pure[F]
        case PhotoInventory.ListResult.Failed          => Result.Failed.pure[F]

  final case class Snapshot(inventory: PhotoInventory.Inventory, execution: ChapterPhotoImport.ExecutionOutcome):
    def processingState: ProcessingState =
      if execution.retirementIssues.nonEmpty then ProcessingState.Failed
      else
        execution.status match
          case ChapterPhotoImport.Status.Idle             => ProcessingState.Idle
          case ChapterPhotoImport.Status.Queued           => ProcessingState.Queued
          case ChapterPhotoImport.Status.Running          => ProcessingState.Running
          case ChapterPhotoImport.Status.LaunchFailed     => ProcessingState.Failed
          case ChapterPhotoImport.Status.Uncertain        => ProcessingState.Failed
          case ChapterPhotoImport.Status.Finished(result) =>
            if result.issues.exists(issue => !isSkipped(issue)) then ProcessingState.Failed else ProcessingState.Completed

    def unsupportedCount: Int = execution.status match
      case ChapterPhotoImport.Status.Finished(result) => result.unsupportedCount
      case _                                          => 0

    def skippedCount: Int = execution.status match
      case ChapterPhotoImport.Status.Finished(result) => result.issues.count(isSkipped)
      case _                                          => 0

    private def isSkipped(issue: PhotoManager.Issue) = issue match
      case _: PhotoManager.Issue.Undecodable | _: PhotoManager.Issue.Unstable => true
      case _                                                                  => false

  enum ProcessingState:
    case Idle, Queued, Running, Completed, Failed

  enum Result:
    case Found(snapshot: Snapshot)
    case ChapterNotFound, Failed
