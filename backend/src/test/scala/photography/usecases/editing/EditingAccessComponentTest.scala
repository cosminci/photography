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
import photography.usecases.editing.EditorSessionManager.*

import java.util.UUID
import scala.concurrent.duration.*

final class EditingAccessComponentTest extends CatsEffectSuite:
  private given IdGenerator[IO] = IdGenerator.make[IO]
  private val album             = AlbumId(new UUID(0L, 1L))
  private val opening           = SpreadId(new UUID(0L, 3L))
  private val target            = EditingTarget.Spread(opening)
  private val connection        = EditorConnection(SessionId(new UUID(0L, 7L)), TabId(new UUID(0L, 8L)), ConnectionGeneration(new UUID(0L, 9L)))
  private val grant             = GrantId(new UUID(0L, 10L))
  private val found             = CompositionResult.Found(NonEmptyList.one(Member(opening, maybeChapter = None)))
  private val unusedMembership  = new AlbumCompositionRepository.MembershipRead[IO]:
    override def composition(album: AlbumId): IO[CompositionResult] =
      IO.raiseError[CompositionResult](new AssertionError("Unexpected membership acquisition"))

  test("should resolve current opening membership and preserve missing, failed and obsolete outcomes without writing"):
    Mutex[IO].flatMap: mutex =>
      given Mutex[IO] = mutex
      for
        calls      <- Ref.of[IO, Vector[Call]](Vector.empty)
        connected  <- Ref.of[IO, ConnectionAuthorizationResult](ConnectionAuthorizationResult.Authorized)
        authorized <- Ref.of[IO, AuthorizationResult](AuthorizationResult.Authorized)
        current    <- Ref.of[IO, CompositionResult](found)
        written    <- Ref.of[IO, Int](0)
        sessions   = new AuthorizationStub(calls, connected.get, authorized.get)
        membership = new AlbumCompositionRepository.MembershipRead[IO]:
          override def composition(id: AlbumId): IO[CompositionResult] = calls.update(_ :+ Call.Membership(id)) >> current.get
        access = EditingAccess.make(sessions, membership)
        save   = access.run(connection, Protection.OwnAlbumOpening(album), Some(grant))(calls.update(_ :+ Call.Written) >> written.update(_ + 1))
        saved    <- save
        _        <- current.set(CompositionResult.Found(NonEmptyList.one(Member(SpreadId(new UUID(0L, 4L)), maybeChapter = None))))
        _        <- authorized.set(AuthorizationResult.Rejected(Failure.StaleGrant))
        replaced <- save
        _        <- current.set(CompositionResult.AlbumNotFound)
        missing  <- save
        _        <- current.set(CompositionResult.Failed(AlbumCompositionRepository.Failure.Unavailable))
        failed   <- save
        _        <- connected.set(ConnectionAuthorizationResult.Rejected(Failure.ObsoleteConnection))
        obsolete <- save
        observed <- calls.get
        count    <- written.get
      yield
        assertEquals(saved, Result.Completed(()))
        assertEquals(replaced, Result.Rejected[Unit](Failure.StaleGrant))
        assertEquals(missing, Result.AlbumNotFound[Unit]())
        assertEquals(failed, Result.Failed[Unit]())
        assertEquals(obsolete, Result.Rejected[Unit](Failure.ObsoleteConnection))
        assertEquals(
          observed,
          Vector(
            Call.Connection(connection),
            Call.Membership(album),
            Call.Target(connection, target, grant),
            Call.Written,
            Call.Connection(connection),
            Call.Membership(album),
            Call.Target(connection, EditingTarget.Spread(SpreadId(new UUID(0L, 4L))), grant),
            Call.Connection(connection),
            Call.Membership(album),
            Call.Connection(connection),
            Call.Membership(album),
            Call.Connection(connection)
          )
        )
        assertEquals(count, 1)

  Vector(Failure.StaleSession, Failure.StaleGrant).foreach { failure =>
    test(s"should preserve $failure returned by authority after a paused relationship read and keep coordination usable"):
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        for
          calls         <- Ref.of[IO, Vector[Call]](Vector.empty)
          authorization <- Ref.of[IO, AuthorizationResult](AuthorizationResult.Authorized)
          entered       <- Deferred[IO, Unit]
          finish        <- Deferred[IO, Unit]
          written       <- Ref.of[IO, Int](0)
          sessions   = new AuthorizationStub(calls, ConnectionAuthorizationResult.Authorized.pure[IO], authorization.get)
          membership = new AlbumCompositionRepository.MembershipRead[IO]:
            override def composition(id: AlbumId): IO[CompositionResult] =
              calls.update(_ :+ Call.Membership(id)) >> entered.complete(()).void >> finish.get.as(found)
          access = EditingAccess.make(sessions, membership)
          writing  <- access.run(connection, Protection.OwnAlbumOpening(album), Some(grant))(written.update(_ + 1)).start
          _        <- entered.get
          paused   <- calls.get
          _        <- authorization.set(AuthorizationResult.Rejected(failure))
          _        <- finish.complete(())
          rejected <- writing.joinWithNever
          before   <- written.get
          _        <- authorization.set(AuthorizationResult.Authorized)
          saved    <- access.run(connection, Protection.OwnAlbumOpening(album), Some(grant))(written.update(_ + 1))
          after    <- written.get
          observed <- calls.get
        yield
          assertEquals(paused, Vector(Call.Connection(connection), Call.Membership(album)))
          assertEquals(rejected, Result.Rejected[Unit](failure))
          assertEquals(before, 0)
          assertEquals(saved, Result.Completed(()))
          assertEquals(after, 1)
          assertEquals(
            observed,
            Vector.fill(2)(Vector(Call.Connection(connection), Call.Membership(album), Call.Target(connection, target, grant))).flatten
          )
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
    test(s"should forward direct and shared $destination proof and preserve the authority's decisions without replay"):
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        for
          calls         <- Ref.of[IO, Vector[Call]](Vector.empty)
          authorization <- Ref.of[IO, AuthorizationResult](AuthorizationResult.Authorized)
          written       <- Ref.of[IO, Int](0)
          sessions = new AuthorizationStub(calls, ConnectionAuthorizationResult.Authorized.pure[IO], authorization.get)
          access   = EditingAccess.make(sessions, unusedMembership)
          mutation = written.update(_ + 1)
          missing  <- access.run(connection, Protection.OwnSpread(destination), maybeGrant = None)(mutation)
          saved    <- access.run(connection, Protection.OwnSpread(destination), Some(grant))(mutation)
          shared   <- access.run(connection, Protection.ChangeSpreads(Set(destination)), Some(grant))(mutation)
          unlocked <- access.run(connection, Protection.ChangeSpreads(Set(destination)), maybeGrant = None)(mutation)
          _        <- authorization.set(AuthorizationResult.Rejected(Failure.StaleGrant))
          rejected <- access.run(connection, Protection.OwnSpread(destination), Some(grant))(mutation)
          _        <- authorization.set(AuthorizationResult.Blocked(destination, EditorId(new UUID(0L, 6L)), "Other"))
          blocked  <- access.run(connection, Protection.ChangeSpreads(Set(destination)), maybeGrant = None)(mutation)
          count    <- written.get
          observed <- calls.get
        yield
          assertEquals(missing, Result.Rejected[Unit](Failure.StaleGrant))
          assertEquals(blocked, Result.Blocked[Unit](EditorId(new UUID(0L, 6L)), "Other"))
          assertEquals(saved, Result.Completed(()))
          assertEquals(shared, Result.Completed(()))
          assertEquals(unlocked, Result.Completed(()))
          assertEquals(rejected, Result.Rejected[Unit](Failure.StaleGrant))
          assertEquals(count, 3)
          assertEquals(
            observed,
            Vector(
              Call.Target(connection, destination, grant),
              Call.Affected(connection, Set(destination), Some(grant)),
              Call.Affected(connection, Set(destination), None),
              Call.Target(connection, destination, grant),
              Call.Affected(connection, Set(destination), None)
            )
          )
  }

  test("should allow independent shared writes and release coordination after application failure"):
    Mutex[IO].flatMap: mutex =>
      given Mutex[IO] = mutex
      for
        calls     <- Ref.of[IO, Vector[Call]](Vector.empty)
        connected <- Ref.of[IO, ConnectionAuthorizationResult](ConnectionAuthorizationResult.Authorized)
        written   <- Ref.of[IO, Int](0)
        sessions = new AuthorizationStub(calls, connected.get, AuthorizationResult.Authorized.pure[IO])
        access   = EditingAccess.make(sessions, unusedMembership)
        cause    = new IllegalStateException("Persistence failed")
        failed      <- access.run(connection, Protection.ChangeSpreads(Set(target)), maybeGrant = None)(IO.raiseError[Unit](cause)).attempt
        saved       <- access.run(connection, Protection.ChangeSpreads(Set(target)), maybeGrant = None)(written.update(_ + 1))
        independent <- access.run(connection, Protection.ActiveTab, maybeGrant = None)(written.update(_ + 1))
        _           <- connected.set(ConnectionAuthorizationResult.Rejected(Failure.ObsoleteConnection))
        obsolete    <- access.run(connection, Protection.ActiveTab, maybeGrant = None)(written.update(_ + 1))
        count       <- written.get
        observed    <- calls.get
      yield
        assertEquals(failed, cause.asLeft[Result[Unit]])
        assertEquals(saved, Result.Completed(()))
        assertEquals(independent, Result.Completed(()))
        assertEquals(obsolete, Result.Rejected[Unit](Failure.ObsoleteConnection))
        assertEquals(count, 2)
        assertEquals(
          observed,
          Vector(
            Call.Affected(connection, Set(target), None),
            Call.Affected(connection, Set(target), None),
            Call.Connection(connection),
            Call.Connection(connection)
          )
        )

  private enum Call:
    case Connection(connection: EditorConnection)
    case Target(connection: EditorConnection, target: EditingTarget, grant: GrantId)
    case Affected(connection: EditorConnection, targets: Set[EditingTarget], maybeGrant: Option[GrantId])
    case Membership(album: AlbumId)
    case Written

  private class AuthorizationStub(calls: Ref[IO, Vector[Call]], connected: IO[ConnectionAuthorizationResult], authorized: IO[AuthorizationResult])
      extends EditorSessionManager[IO]:
    override def authorizeConnection(connection: EditorConnection): IO[ConnectionAuthorizationResult] =
      calls.update(_ :+ Call.Connection(connection)) >> connected
    override def authorizeTarget(connection: EditorConnection, target: EditingTarget, grant: GrantId): IO[AuthorizationResult] =
      calls.update(_ :+ Call.Target(connection, target, grant)) >> authorized
    override def authorizeAffected(connection: EditorConnection, targets: Set[EditingTarget], maybeGrant: Option[GrantId]): IO[AuthorizationResult] =
      calls.update(_ :+ Call.Affected(connection, targets, maybeGrant)) >> authorized
    override def register(owner: EditorId, alias: EditorAlias, maybeSession: Option[SessionId]): IO[RegisterResult] =
      IO.raiseError[RegisterResult](new AssertionError("Unexpected registration"))
    override def bind(session: SessionId, tab: TabId): IO[BindResult] =
      IO.raiseError[BindResult](new AssertionError("Unexpected binding"))
    override def unbind(connection: EditorConnection): IO[UnbindResult] =
      IO.raiseError[UnbindResult](new AssertionError("Unexpected release"))
    override def heartbeat(connection: EditorConnection): IO[HeartbeatResult] =
      IO.raiseError[HeartbeatResult](new AssertionError("Unexpected heartbeat"))
    override def read(connection: EditorConnection): IO[ReadResult] =
      IO.raiseError[ReadResult](new AssertionError("Unexpected session read"))
    override def select(connection: EditorConnection, revision: TargetRevision, target: EditingTarget): IO[SelectResult] =
      IO.raiseError[SelectResult](new AssertionError("Unexpected acquisition"))
    override def release(connection: EditorConnection, revision: TargetRevision, grant: GrantId): IO[ReleaseResult] =
      IO.raiseError[ReleaseResult](new AssertionError("Unexpected target release"))
    override def renewTarget(connection: EditorConnection, grant: GrantId): IO[RenewTargetResult] =
      IO.raiseError[RenewTargetResult](new AssertionError("Unexpected renewal"))
    override def readTarget(connection: EditorConnection): IO[ReadTargetResult] =
      IO.raiseError[ReadTargetResult](new AssertionError("Unexpected ownership read"))

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
