package photography.usecases.photo

import cats.Id
import cats.data.Kleisli
import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.Transaction
import photography.domain.album.*
import photography.usecases.photo.ChapterPhotoImport.{ExecutionOutcome, Scheduling, Status}
import java.util.UUID

final class ChapterPhotoImportComponentTest extends CatsEffectSuite:
  private type Db[A] = Kleisli[IO, Unit, A]
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given Transaction[Db, IO] with
    override def run[A](program: Db[A]): IO[A]     = program.run(())
    override def resolve[A](program: Db[A]): IO[A] = program.run(())
  private val album               = AlbumId(new UUID(0L, 99L))
  private val chapter             = ChapterId(new UUID(0L, 1L))
  private val source: PhotoSource = "/photos/a"
  private val finished            = PhotoManager.RefreshResult(unsupportedCount = 0, issues = Vector.empty[PhotoManager.Issue])

  test("should queue other chapters while one source is processing and continue after a failed reconciliation"):
    val refs                      = Refs()
    val secondChapter             = ChapterId(new UUID(0L, 2L))
    val suspendedChapter          = ChapterId(new UUID(0L, 3L))
    val secondSource: PhotoSource = "/photos/b"
    (Mutex[IO], Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap { case (mutex, entered, release) =>
      val pending = entered.complete(()).void >> release.get.as(PhotoManager.RefreshResult(0, Vector(PhotoManager.Issue.Discovery)))
      val replies = Ref.unsafe[IO, Vector[IO[PhotoManager.RefreshResult]]](Vector(pending, finished.pure[IO]))
      val refresh = replies.getAndUpdate(_.drop(1)).flatMap(_.headOption.liftTo[IO](new AssertionError("Unarranged reconciliation"))).flatten
      val program = buildImport(refs, refresh)(using mutex).use { imports =>
        for
          first     <- imports.prepare(album, chapter, source)
          _         <- imports.launch(first)
          _         <- entered.get
          second    <- imports.prepare(album, secondChapter, secondSource)
          _         <- imports.launch(second)
          queued    <- imports.status(secondChapter)
          _         <- IO(assertEquals(queued.status, Status.Queued))
          suspended <- imports.prepare(album, suspendedChapter, secondSource)
          _         <- imports.launch(suspended)
          _         <- imports.suspend(suspendedChapter)
          _         <- release.get
          _         <- awaitFinished(imports, chapter)
          _         <- awaitFinished(imports, secondChapter)
          retained  <- imports.status(suspendedChapter)
          _         <- IO(assertEquals(retained, ExecutionOutcome(Status.Uncertain, Vector.empty)))
        yield ()
      }
      for
        control <- TestControl.execute(program)
        _       <- control.tick
        before  <- refs.refreshes.get
        _       <- release.complete(())
        _       <- control.tick
        after   <- refs.refreshes.get
        result  <- control.results
      yield
        assertEquals(before, Vector((chapter, source)))
        assertEquals(after, Vector((chapter, source), (secondChapter, secondSource)))
        assertEquals(result, Outcome.succeeded[Id, Throwable, Unit](()).some)
    }

  test("should coalesce active requests, ignore duplicate launches and allow a fresh pass after completion"):
    val refs = Refs()
    (Mutex[IO], Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap { case (mutex, entered, release) =>
      val refresh = entered.complete(()).void >> release.get.as(finished)
      buildImport(refs, refresh)(using mutex).use { imports =>
        for
          initial   <- imports.prepare(album, chapter, source)
          queued    <- imports.status(chapter)
          launched  <- imports.launch(initial)
          _         <- entered.get
          overlap   <- imports.prepare(album, chapter, source)
          duplicate <- imports.launch(overlap)
          running   <- imports.status(chapter)
          _         <- release.complete(())
          _         <- awaitFinished(imports, chapter)
          retry     <- imports.prepare(album, chapter, source)
          _         <- imports.launch(retry)
          _         <- awaitFinished(imports, chapter)
          calls     <- refs.refreshes.get
        yield
          assertEquals(queued, ExecutionOutcome(Status.Queued, Vector.empty))
          assertEquals(launched, Scheduling.Queued)
          assertEquals(overlap, initial)
          assertEquals(duplicate, Scheduling.Coalesced)
          assertEquals(running, ExecutionOutcome(Status.Running, Vector.empty))
          assertEquals(retry.generation, initial.generation + 1)
          assertEquals(calls, Vector.fill(2)((chapter, source)))
      }
    }

  test("should fence superseded generations even when the source returns to its original folder"):
    val refs                     = Refs()
    val replacement: PhotoSource = "/photos/replacement"
    (Mutex[IO], Deferred[IO, Unit], Deferred[IO, Unit], Ref.of[IO, Vector[PhotoSource]](Vector.empty)).tupled.flatMap {
      case (mutex, entered, release, published) =>
        val manager = (guarded: Transaction[Db, IO]) =>
          val write = guarded.run(Kleisli.liftF[IO, Unit, Unit](published.update(_ :+ source))).attempt >>
            guarded.resolve(Kleisli.liftF[IO, Unit, Unit](IO.unit)).as(finished)
          buildManager(refs, IO.uncancelable(_ => entered.complete(()).void >> release.get >> write))
        given Mutex[IO] = mutex
        val resource = ChapterPhotoImport.make(manager, buildRecovery(refs, PhotoWriteRecovery.RecoveryResult.Recovered), buildEvents(refs, IO.unit))
        val program  = resource.use { imports =>
          for
            old           <- imports.prepare(album, chapter, source)
            _             <- imports.launch(old)
            _             <- entered.get
            _             <- imports.prepare(album, chapter, replacement)
            fresh         <- imports.prepare(album, chapter, source)
            _             <- imports.launch(fresh)
            _             <- refs.invalidations.set(Vector.empty)
            _             <- release.get
            _             <- awaitFinished(imports, chapter)
            saved         <- published.get
            notifications <- refs.invalidations.get
            refreshes     <- refs.refreshes.get
          yield
            assertEquals(fresh.generation, old.generation + 2)
            assertEquals(saved, Vector(source))
            assertEquals(notifications, Vector.fill(5)((album, chapter)))
            assertEquals(refreshes, Vector.fill(2)((chapter, source)))
        }
        for
          control <- TestControl.execute(program)
          _       <- control.tick
          _       <- release.complete(())
          _       <- control.tick
          result  <- control.results
        yield assertEquals(result, Outcome.succeeded[Id, Throwable, Unit](()).some)
    }

  test("should suspend uncertain source work without allowing its queued request to launch"):
    Mutex[IO].flatMap { mutex =>
      val refs    = Refs()
      val refresh = finished.pure[IO]
      buildImport(refs, refresh)(using mutex).use { imports =>
        for
          idle       <- imports.status(chapter)
          request    <- imports.prepare(album, chapter, source)
          _          <- imports.suspend(chapter)
          scheduling <- imports.launch(request)
          status     <- imports.status(chapter)
          calls      <- refs.refreshes.get
        yield
          assertEquals(idle, ExecutionOutcome(Status.Idle, Vector.empty))
          assertEquals(scheduling, Scheduling.Coalesced)
          assertEquals(status, ExecutionOutcome(Status.Uncertain, Vector.empty))
          assertEquals(calls, Vector.empty[(ChapterId, PhotoSource)])
      }
    }

  test("should expose launch failures and keep invalidation delivery failures from losing processing outcomes"):
    Vector("delivery", "launch").traverse_ { scenario =>
      Mutex[IO].flatMap { mutex =>
        val refs         = Refs()
        val error        = new IllegalStateException(scenario)
        val refresh      = finished.pure[IO]
        val notification = IO.raiseError[Unit](error)
        val manager      = (_: Transaction[Db, IO]) => buildManager(refs, refresh)
        given Mutex[IO]  = mutex
        val resource     =
          ChapterPhotoImport.make(manager, buildRecovery(refs, PhotoWriteRecovery.RecoveryResult.Recovered), buildEvents(refs, notification))
        val acquireClosed = resource.allocated.flatMap { case (imports, close) => close.as(imports) }
        resource.use { live =>
          for
            imports <- if scenario === "launch" then acquireClosed else live.pure[IO]
            request <- imports.prepare(album, chapter, source)
            result  <- imports.launch(request)
            _       <- if scenario === "delivery" then awaitFinished(imports, chapter) else IO.unit
            status  <- imports.status(chapter)
          yield
            assertEquals(result, if scenario === "launch" then Scheduling.Failed else Scheduling.Queued)
            assertEquals(status.status, if scenario === "launch" then Status.LaunchFailed else Status.Finished(finished))
        }
      }
    }

  test("should hand retirements to recovery and retain recovery issues across subsequent reconciliation"):
    val intent: PhotoWriteIntent.Retirement = PhotoWriteIntent.Retirement(
      PhotoWriteIntentId(new UUID(0L, 3L)),
      chapter,
      PhotoId(new UUID(0L, 4L)),
      ThumbnailLocation(new UUID(0L, 5L))
    )
    val issue = PhotoWriteRecovery.Issue.Cleanup(intent.id, intent.location)
    Vector(PhotoWriteRecovery.RecoveryResult.Recovered, PhotoWriteRecovery.RecoveryResult.Failed(Vector(issue))).traverse_ { recovery =>
      Mutex[IO].flatMap { mutex =>
        val refs    = Refs()
        val refresh = finished.pure[IO]
        buildImport(refs, refresh, recovery)(using mutex).use { imports =>
          for
            _ <- imports.retire(album, Vector(intent))
            _ <- (IO.cede >> refs.retirements.get).iterateUntil(_.nonEmpty)
            _ <- recovery match
              case PhotoWriteRecovery.RecoveryResult.Recovered => IO.unit
              case PhotoWriteRecovery.RecoveryResult.Failed(_) =>
                (IO.cede >> imports.status(chapter)).iterateUntil(_.retirementIssues.nonEmpty).void
            request <- imports.prepare(album, chapter, source)
            _       <- imports.launch(request)
            _       <- awaitFinished(imports, chapter)
            status  <- imports.status(chapter)
            calls   <- refs.retirements.get
          yield
            val issues = recovery match
              case PhotoWriteRecovery.RecoveryResult.Recovered      => Vector.empty[PhotoWriteRecovery.Issue]
              case PhotoWriteRecovery.RecoveryResult.Failed(values) => values
            assertEquals(calls, Vector(intent))
            assertEquals(status, ExecutionOutcome(Status.Finished(finished), issues))
        }
      }
    }

  private def awaitFinished(imports: ChapterPhotoImport[IO], chapter: ChapterId) = (IO.cede >> imports.status(chapter)).iterateUntil(_.status match
    case _: Status.Finished => true
    case _                  => false).void

  final private case class Refs():
    val refreshes     = Ref.unsafe[IO, Vector[(ChapterId, PhotoSource)]](Vector.empty)
    val retirements   = Ref.unsafe[IO, Vector[PhotoWriteIntent]](Vector.empty)
    val invalidations = Ref.unsafe[IO, Vector[(AlbumId, ChapterId)]](Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildImport(
      refs: Refs,
      response: IO[PhotoManager.RefreshResult],
      recovery: PhotoWriteRecovery.RecoveryResult = PhotoWriteRecovery.RecoveryResult.Recovered
  )(using Mutex[IO]) =
    ChapterPhotoImport.make(
      (_: Transaction[Db, IO]) => buildManager(refs, response),
      buildRecovery(refs, recovery),
      buildEvents(refs, IO.unit)
    )
  // scalafix:on DisableSyntax.defaultArgs

  private def buildManager(
      refs: Refs,
      response: IO[PhotoManager.RefreshResult]
  ) = new PhotoManager[IO]:
    override def refresh(chapter: ChapterId, source: PhotoSource): IO[PhotoManager.RefreshResult] =
      refs.refreshes.update(_ :+ (chapter, source)) >> response
    override def read(photo: PhotoId): IO[PhotoManager.ReadResult]                                                 = fail("Scheduler only reconciles")
    override def saveCaption(chapter: ChapterId, photo: PhotoId, text: String): IO[PhotoManager.SaveCaptionResult] = fail("Scheduler only reconciles")

  private def buildRecovery(refs: Refs, response: PhotoWriteRecovery.RecoveryResult) = new PhotoWriteRecovery[IO]:
    override def recover: IO[PhotoWriteRecovery.RecoveryResult]                           = fail("Startup owns recovery")
    override def resolve(intent: PhotoWriteIntent): IO[PhotoWriteRecovery.RecoveryResult] = refs.retirements.update(_ :+ intent).as(response)

  private def buildEvents(refs: Refs, notification: IO[Unit]) = new AlbumPhotoEvents[IO]:
    override def subscribe(album: AlbumId): IO[AlbumPhotoEvents.SubscribeResult[IO]] = fail("Scheduler only invalidates")
    override def invalidate(album: AlbumId, chapter: ChapterId): IO[Unit] = refs.invalidations.update(_ :+ (album, chapter)) >> notification
