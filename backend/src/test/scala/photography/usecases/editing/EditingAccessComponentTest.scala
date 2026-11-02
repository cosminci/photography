package photography.usecases.editing

import cats.data.NonEmptyList
import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.capabilities.IdGenerator
import photography.domain.album.AlbumId
import photography.domain.editing.*
import photography.domain.layout.SpreadId
import photography.ports.album.AlbumCompositionRepository
import photography.ports.album.AlbumCompositionRepository.{CompositionResult, Member}
import photography.usecases.editing.EditingAccess.{Protection, Result}
import photography.usecases.editing.EditorSessionManager.{BindResult, Failure, RegisterResult, SelectResult}

import java.util.UUID
import scala.concurrent.duration.*

final class EditingAccessComponentTest extends CatsEffectSuite:
  private given IdGenerator[IO] = IdGenerator.make[IO]
  private val album             = AlbumId(new UUID(0L, 1L))
  private val opening           = SpreadId(new UUID(0L, 3L))
  private val target            = EditingTarget.Spread(opening)
  private val found             = CompositionResult.Found(NonEmptyList.one(Member(opening, maybeChapter = None)))
  private val unusedMembership  = new AlbumCompositionRepository.MembershipRead[IO]:
    override def composition(album: AlbumId): IO[CompositionResult] =
      IO.raiseError[CompositionResult](new AssertionError("Unexpected membership acquisition"))

  test("should resolve current opening membership and preserve missing, failed and obsolete outcomes without writing"):
    Mutex[IO].flatMap: mutex =>
      given Mutex[IO] = mutex
      for
        sessions <- EditorSessionManager.make[IO](lease = 30L)
        owner    <- bind(sessions, owner = 1L)
        lock     <- sessions.select(owner, TargetRevision.initial, target).map(requireLock)
        current  <- Ref.of[IO, CompositionResult](found)
        reads    <- Ref.of[IO, Vector[AlbumId]](Vector.empty)
        written  <- Ref.of[IO, Int](0)
        membership = new AlbumCompositionRepository.MembershipRead[IO]:
          override def composition(id: AlbumId): IO[CompositionResult] = reads.update(_ :+ id) >> current.get
        access = EditingAccess.make(sessions, membership)
        save   = access.run(owner, Protection.OwnAlbumOpening(album), Some(lock.grant))(written.update(_ + 1))
        saved    <- save
        _        <- current.set(CompositionResult.Found(NonEmptyList.one(Member(SpreadId(new UUID(0L, 4L)), maybeChapter = None))))
        replaced <- save
        _        <- current.set(CompositionResult.AlbumNotFound)
        missing  <- save
        _        <- current.set(CompositionResult.Failed(AlbumCompositionRepository.Failure.Unavailable))
        failed   <- save
        _        <- sessions.bind(owner.session, owner.tab)
        obsolete <- save
        acquired <- reads.get
        count    <- written.get
      yield
        assertEquals(saved, Result.Completed(()))
        assertEquals(replaced, Result.Rejected[Unit](Failure.StaleGrant))
        assertEquals(missing, Result.AlbumNotFound[Unit]())
        assertEquals(failed, Result.Failed[Unit]())
        assertEquals(obsolete, Result.Rejected[Unit](Failure.ObsoleteConnection))
        assertEquals(acquired, Vector.fill(4)(album))
        assertEquals(count, 1)

  Vector(Failure.StaleSession, Failure.StaleGrant).foreach { failure =>
    test(s"should reject $failure expiring during relationship acquisition and keep coordination usable"):
      TestControl.executeEmbed:
        Mutex[IO].flatMap: mutex =>
          given Mutex[IO] = mutex
          for
            sessions <- EditorSessionManager.make[IO](lease = 30L)
            owner    <- bind(sessions, owner = 1L)
            lock     <- sessions.select(owner, TargetRevision.initial, target).map(requireLock)
            _        <- IO.sleep(20.seconds)
            _        <- failure match
              case Failure.StaleGrant => sessions.heartbeat(owner).void
              case _                  => IO.unit
            entered <- Deferred[IO, Unit]
            finish  <- Deferred[IO, Unit]
            written <- Ref.of[IO, Int](0)
            membership = new AlbumCompositionRepository.MembershipRead[IO]:
              override def composition(id: AlbumId): IO[CompositionResult] =
                IO(assertEquals(id, album)) >> entered.complete(()).void >> finish.get.as(found)
            access = EditingAccess.make(sessions, membership)
            writing  <- access.run(owner, Protection.OwnAlbumOpening(album), Some(lock.grant))(written.update(_ + 1)).start
            _        <- entered.get
            _        <- IO.sleep(10.seconds)
            _        <- finish.complete(())
            rejected <- writing.joinWithNever
            before   <- written.get
            fresh    <- bind(sessions, owner = 2L)
            next     <- sessions.select(fresh, TargetRevision.initial, target).map(requireLock)
            saved    <- access.run(fresh, Protection.OwnAlbumOpening(album), Some(next.grant))(written.update(_ + 1))
            after    <- written.get
          yield
            assertEquals(rejected, Result.Rejected[Unit](failure))
            assertEquals(before, 0)
            assertEquals(saved, Result.Completed(()))
            assertEquals(after, 1)
  }

  test("should finish live admission atomically across expiry and cancellation while waiting acquisition sees its result"):
    TestControl.executeEmbed:
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        for
          sessions   <- EditorSessionManager.make[IO](lease = 30L)
          owner      <- bind(sessions, owner = 1L)
          lock       <- sessions.select(owner, TargetRevision.initial, target).map(requireLock)
          _          <- IO.sleep(20.seconds)
          competitor <- bind(sessions, owner = 2L)
          entered    <- Deferred[IO, Unit]
          finish     <- Deferred[IO, Unit]
          canceled   <- Deferred[IO, Unit]
          acquired   <- Deferred[IO, (SelectResult, Int)]
          written    <- Ref.of[IO, Int](0)
          access = EditingAccess.make(sessions, unusedMembership)
          write  = access.run(owner, Protection.OwnSpread(target), Some(lock.grant))(entered.complete(()).void >> finish.get >> written.update(_ + 1))
          writing   <- write.start
          _         <- entered.get
          waiter    <- write.start
          _         <- IO.sleep(1.second)
          _         <- waiter.cancel
          waiting   <- waiter.join
          queued    <- write.start
          canceling <- (writing.cancel >> canceled.complete(())).start
          _         <- IO.sleep(10.seconds)
          acquiring <- sessions.select(competitor, TargetRevision.initial, target)
            .flatMap(result => written.get.flatMap(count => acquired.complete((result, count)).void)).start
          _             <- IO.sleep(1.second)
          maybeCanceled <- canceled.tryGet
          maybeAcquired <- acquired.tryGet
          _             <- IO(assertEquals(maybeCanceled, None))
          _             <- IO(assertEquals(maybeAcquired, None))
          _             <- finish.complete(())
          _             <- canceling.joinWithNever
          saved         <- writing.joinWithNever
          rejected      <- queued.joinWithNever
          _             <- acquiring.joinWithNever
          observed      <- acquired.get
        yield
          assertEquals(saved, Result.Completed(()))
          assertEquals(rejected, Result.Rejected[Unit](Failure.StaleSession))
          waiting match
            case Outcome.Canceled() => ()
            case other              => fail(s"Expected canceled waiter, obtained $other")
          observed match
            case (SelectResult.Owned(lock, revision), count) =>
              assertEquals(lock, EditingLock(target, competitor.session, lock.grant, 62.seconds))
              assertEquals(revision, TargetRevision.initial.next)
              assertEquals(count, 1)
            case other => fail(s"Expected acquisition after persistence, obtained $other")

  test("should keep relationship acquisition, admission and persistence in one interval before competing acquisition"):
    TestControl.executeEmbed:
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        for
          sessions <- EditorSessionManager.make[IO](lease = 30L)
          owner    <- bind(sessions, owner = 1L)
          other    <- bind(sessions, owner = 2L)
          lock     <- sessions.select(owner, TargetRevision.initial, target).map(requireLock)
          entered  <- Deferred[IO, Unit]
          finish   <- Deferred[IO, Unit]
          acquired <- Deferred[IO, (SelectResult, Int)]
          written  <- Ref.of[IO, Int](0)
          membership = new AlbumCompositionRepository.MembershipRead[IO]:
            override def composition(id: AlbumId): IO[CompositionResult] =
              IO(assertEquals(id, album)) >> entered.complete(()).void >> finish.get.as(found)
          access = EditingAccess.make(sessions, membership)
          writing   <- access.run(owner, Protection.OwnAlbumOpening(album), Some(lock.grant))(written.update(_ + 1)).start
          _         <- entered.get
          acquiring <- sessions.select(other, TargetRevision.initial, target)
            .flatMap(result => written.get.flatMap(count => acquired.complete((result, count)).void)).start
          _        <- IO.sleep(1.second)
          before   <- acquired.tryGet
          _        <- IO(assertEquals(before, None))
          _        <- finish.complete(())
          saved    <- writing.joinWithNever
          _        <- acquiring.joinWithNever
          observed <- acquired.get
        yield
          assertEquals(saved, Result.Completed(()))
          assertEquals(observed, (SelectResult.Blocked(EditorId(new UUID(0L, 1L)), "Owner", 30.seconds, TargetRevision.initial.next), 1))

  Vector(target, EditingTarget.Cover(album)).foreach { destination =>
    test(s"should protect $destination for direct and shared writes and never replay after grant replacement"):
      TestControl.executeEmbed:
        Mutex[IO].flatMap: mutex =>
          given Mutex[IO] = mutex
          for
            sessions <- EditorSessionManager.make[IO](lease = 30L)
            owner    <- bind(sessions, owner = 1L)
            other    <- bind(sessions, owner = 2L)
            lock     <- sessions.select(owner, TargetRevision.initial, destination).map(requireLock)
            written  <- Ref.of[IO, Int](0)
            access   = EditingAccess.make(sessions, unusedMembership)
            mutation = written.update(_ + 1)
            missing   <- access.run(owner, Protection.OwnSpread(destination), maybeGrant = None)(mutation)
            blocked   <- access.run(other, Protection.ChangeSpreads(Set(destination)), maybeGrant = None)(mutation)
            unproved  <- access.run(owner, Protection.ChangeSpreads(Set(destination)), maybeGrant = None)(mutation)
            borrowed  <- access.run(other, Protection.OwnSpread(destination), Some(lock.grant))(mutation)
            saved     <- access.run(owner, Protection.OwnSpread(destination), Some(lock.grant))(mutation)
            shared    <- access.run(owner, Protection.ChangeSpreads(Set(destination)), Some(lock.grant))(mutation)
            _         <- sessions.release(owner, TargetRevision.initial.next, lock.grant)
            successor <- sessions.select(owner, TargetRevision.initial.next.next, destination).map(requireLock)
            stale     <- access.run(owner, Protection.OwnSpread(destination), Some(lock.grant))(mutation)
            _         <- IO.sleep(20.seconds)
            _         <- sessions.heartbeat(owner)
            _         <- IO.sleep(10.seconds)
            expired   <- access.run(owner, Protection.OwnSpread(destination), Some(successor.grant))(mutation)
            count     <- written.get
          yield
            assertEquals(missing, Result.Rejected[Unit](Failure.StaleGrant))
            assertEquals(blocked, Result.Blocked[Unit](EditorId(new UUID(0L, 1L)), "Owner"))
            assertEquals(unproved, Result.Rejected[Unit](Failure.StaleGrant))
            assertEquals(borrowed, Result.Rejected[Unit](Failure.StaleGrant))
            assertEquals(saved, Result.Completed(()))
            assertEquals(shared, Result.Completed(()))
            assertEquals(stale, Result.Rejected[Unit](Failure.StaleGrant))
            assertEquals(expired, Result.Rejected[Unit](Failure.StaleGrant))
            assertEquals(count, 2)
  }

  test("should allow independent shared writes and release coordination after application failure"):
    Mutex[IO].flatMap: mutex =>
      given Mutex[IO] = mutex
      for
        sessions <- EditorSessionManager.make[IO](lease = 30L)
        owner    <- bind(sessions, owner = 1L)
        written  <- Ref.of[IO, Int](0)
        access = EditingAccess.make(sessions, unusedMembership)
        stale <- access.run(owner, Protection.ChangeSpreads(Set(target)), Some(GrantId(new UUID(0L, 99L))))(written.update(_ + 1))
        cause = new IllegalStateException("Persistence failed")
        failed <- access.run(owner, Protection.ChangeSpreads(Set(target)), maybeGrant = None)(IO.raiseError[Unit](cause)).attempt
        saved  <- access.run(owner, Protection.ChangeSpreads(Set(target)), maybeGrant = None)(written.update(_ + 1))
        count  <- written.get
      yield
        assertEquals(stale, Result.Rejected[Unit](Failure.StaleGrant))
        assertEquals(failed, cause.asLeft[Result[Unit]])
        assertEquals(saved, Result.Completed(()))
        assertEquals(count, 1)

  private def requireLock(result: SelectResult) = result match
    case SelectResult.Owned(lock, _) => lock
    case other                       => fail(s"Expected ownership, obtained $other")

  private def bind(sessions: EditorSessionManager[IO], owner: Long) =
    for
      registered <- sessions.register(EditorId(new UUID(0L, owner)), "Owner", maybeSession = None)
      session = registered match
        case RegisterResult.Ready(value) => value.id
        case other                       => fail(s"Expected session, obtained $other")
      bound <- sessions.bind(session, TabId(new UUID(0L, owner)))
    yield bound match
      case BindResult.Active(value) => value
      case other                    => fail(s"Expected binding, obtained $other")
