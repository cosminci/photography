package photography.usecases.photo

import photography.ports.photo.PhotoThumbnailGenerator

import cats.{Eq, MonadThrow}
import cats.effect.{Async, Fiber, Ref, Resource}
import cats.effect.std.{Mutex, Supervisor}
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.ports.photo.{PhotoRepository, PhotoStorage}
import photography.usecases.photo.ChapterPhotoImport.*

trait ChapterPhotoImport[F[_]]:
  def prepare(album: AlbumId, chapter: ChapterId, source: PhotoSource): F[Request]
  def launch(request: Request): F[Scheduling]
  def retire(album: AlbumId, retirements: Vector[PhotoWriteIntent.Retirement]): F[Unit]
  def suspend(chapter: ChapterId): F[Unit]
  def status(chapter: ChapterId): F[ExecutionOutcome]

object ChapterPhotoImport:
  def make[F[_]: Async: IdGenerator: Logger: Mutex, Db[_]: MonadThrow](
      storage: PhotoStorage[F],
      photos: PhotoRepository[Db],
      generator: PhotoThumbnailGenerator[F],
      events: AlbumPhotoEvents[F]
  )(using Transaction[Db, F]): Resource[F, ChapterPhotoImport[F]] =
    val recovery     = PhotoWriteRecovery.make(photos, storage)
    val buildManager = (guarded: Transaction[Db, F]) =>
      given Transaction[Db, F] = guarded
      PhotoManager.make(storage, photos, generator)
    for
      state      <- Resource.eval(Ref.of[F, Map[ChapterId, Feedback[F]]](Map.empty))
      supervisor <- Supervisor[F](await = false)
    yield new LiveChapterPhotoImport(buildManager, recovery, state, events, supervisor)

  final private class LiveChapterPhotoImport[F[_]: Async: Logger: Mutex, Db[_]](
      buildManager: Transaction[Db, F] => PhotoManager[F],
      recovery: PhotoWriteRecovery[F],
      state: Ref[F, Map[ChapterId, Feedback[F]]],
      events: AlbumPhotoEvents[F],
      supervisor: Supervisor[F]
  )(using transaction: Transaction[Db, F]) extends ChapterPhotoImport[F]:
    override def prepare(album: AlbumId, chapter: ChapterId, source: PhotoSource): F[Request] =
      state.modify { current =>
        val feedback = current.getOrElse(chapter, Feedback(maybeEntry = None, retirementIssues = Vector.empty[PhotoWriteRecovery.Issue]))
        feedback.maybeEntry match
          case Some(entry) if (entry.request.source: String) === source && isActive(entry.status) => (current, (entry.request, false))
          case maybePrevious                                                                      =>
            val generation = maybePrevious.fold(BigInt(0))(_.request.generation + 1)
            val request    = Request(album, chapter, source, generation = generation)
            val previous   = maybePrevious.toList.flatMap(entry => entry.previous ++ entry.maybeWorker.toList).toVector
            val queued     = Entry(request, Status.Queued, maybeWorker = None, previous = previous)
            (current.updated(chapter, feedback.copy(maybeEntry = queued.some)), (request, true))
      }.flatMap { case (request, changed) => invalidateInventory(album, chapter).whenA(changed).as(request) }

    override def launch(request: Request): F[Scheduling] =
      val launch = summon[Mutex[F]].lock.surround:
        state.get.flatMap: current =>
          current.get(request.chapter).flatMap(_.maybeEntry) match
            case Some(entry) if entry.request === request && entry.maybeWorker.isEmpty && isActive(entry.status) =>
              supervisor.supervise(run(request)).flatMap: worker =>
                val register = state.update: current =>
                  current.updatedWith(request.chapter): maybeEntry =>
                    maybeEntry.map: feedback =>
                      feedback.copy(maybeEntry =
                        feedback.maybeEntry.map(_.copy(maybeWorker = worker.some, previous = Vector.empty[Fiber[F, Throwable, Unit]]))
                      )
                register.as((Scheduling.Queued, entry.previous))
            case _ => (Scheduling.Coalesced, Vector.empty[Fiber[F, Throwable, Unit]]).pure[F]
      Async[F].uncancelable: _ =>
        launch.flatMap { case (scheduling, previous) =>
          val cancelPrevious = previous.traverse_(worker => supervisor.supervise(worker.cancel).void)
          cancelPrevious.as(scheduling)
        }.handleErrorWith: error =>
          Logger[F].error(error)("Photo import launch failed") >> recordStatus(request, Status.LaunchFailed).as(Scheduling.Failed)

    override def retire(album: AlbumId, retirements: Vector[PhotoWriteIntent.Retirement]): F[Unit] =
      Async[F].uncancelable: _ =>
        retirements.traverse_(intent => supervisor.supervise(retirePhoto(album, intent)).void) >>
          retirements.map(_.chapter).distinct.traverse_(chapter => invalidateInventory(album, chapter))

    override def suspend(chapter: ChapterId): F[Unit] =
      state.modify { current =>
        val maybeRequest = current.get(chapter).flatMap(_.maybeEntry).map(_.request)
        val suspended    =
          current.updatedWith(chapter)(_.map(feedback => feedback.copy(maybeEntry = feedback.maybeEntry.map(_.copy(status = Status.Uncertain)))))
        (suspended, maybeRequest)
      }.flatMap(_.traverse_(request => invalidateInventory(request.album, chapter)))

    override def status(chapter: ChapterId): F[ExecutionOutcome] =
      state.get.map: current =>
        val feedback = current.getOrElse(chapter, Feedback(maybeEntry = None, retirementIssues = Vector.empty[PhotoWriteRecovery.Issue]))
        ExecutionOutcome(feedback.maybeEntry.fold[Status](Status.Idle)(_.status), feedback.retirementIssues)

    private def run(request: Request) =
      val guarded = ImportTransaction.make(request)
      val manager = buildManager(guarded)
      val started = summon[Mutex[F]].lock.surround(recordStatus(request, Status.Running))
      started >> manager.refresh(request.chapter, request.source).flatMap(result => recordStatus(request, Status.Finished(result)))

    private object ImportTransaction:
      def make(request: Request): Transaction[Db, F] = new LiveImportTransaction(request)

      final private class LiveImportTransaction(request: Request) extends Transaction[Db, F]:
        override def run[A](program: Db[A]): F[A] =
          val execute = summon[Mutex[F]].lock.surround:
            state.get.flatMap: current =>
              val isCurrent = current.get(request.chapter).flatMap(_.maybeEntry).exists(entry => entry.request === request && isActive(entry.status))
              if isCurrent then transaction.run(program)
              else Async[F].raiseError(new IllegalStateException("Photo import source was superseded"))
          execute.flatTap(_ => invalidateInventory(request.album, request.chapter))
        override def resolve[A](program: Db[A]): F[A] = transaction.resolve(program).flatTap(_ => invalidateInventory(request.album, request.chapter))

    private def retirePhoto(album: AlbumId, intent: PhotoWriteIntent.Retirement) =
      recovery.resolve(intent).flatMap:
        case PhotoWriteRecovery.RecoveryResult.Recovered        => ().pure[F]
        case PhotoWriteRecovery.RecoveryResult.Failed(failures) =>
          state.update { current =>
            val feedback = current.getOrElse(intent.chapter, Feedback(maybeEntry = None, retirementIssues = Vector.empty[PhotoWriteRecovery.Issue]))
            current.updated(intent.chapter, feedback.copy(retirementIssues = feedback.retirementIssues ++ failures))
          } >> invalidateInventory(album, intent.chapter)

    private def recordStatus(request: Request, status: Status) =
      state.modify { current =>
        val applies = current.get(request.chapter).flatMap(_.maybeEntry).exists(entry => entry.request === request && isActive(entry.status))
        val updated = if !applies then current
        else
          current.updatedWith(request.chapter):
            _.map(feedback => feedback.copy(maybeEntry = feedback.maybeEntry.map(_.copy(status = status))))
        (updated, applies)
      }.flatMap(changed => invalidateInventory(request.album, request.chapter).whenA(changed))

    private def invalidateInventory(album: AlbumId, chapter: ChapterId) =
      events.invalidate(album, chapter).handleErrorWith(error => Logger[F].error(error)("Photo invalidation delivery failed"))

    private def isActive(status: Status) = status match
      case Status.Queued | Status.Running => true
      case _                              => false

  final case class Request(album: AlbumId, chapter: ChapterId, source: PhotoSource, generation: BigInt)
  object Request:
    given Eq[Request] = Eq.by(request => (request.album.value, request.chapter.value, request.source: String, request.generation))

  enum Scheduling:
    case Queued, Coalesced, NotRequested, Failed

  enum Status:
    case Idle, Queued, Running, LaunchFailed, Uncertain
    case Finished(result: PhotoManager.RefreshResult)

  final case class ExecutionOutcome(status: Status, retirementIssues: Vector[PhotoWriteRecovery.Issue])

  final private case class Feedback[F[_]](maybeEntry: Option[Entry[F]], retirementIssues: Vector[PhotoWriteRecovery.Issue])

  final private case class Entry[F[_]](
      request: Request,
      status: Status,
      maybeWorker: Option[Fiber[F, Throwable, Unit]],
      previous: Vector[Fiber[F, Throwable, Unit]]
  )
