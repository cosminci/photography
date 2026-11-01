package photography.usecases.editing

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.capabilities.IdGenerator
import photography.domain.album.AlbumId
import photography.domain.editing.*
import photography.domain.layout.SpreadId
import photography.usecases.editing.EditorSessionManager.*

import java.util.UUID
import scala.concurrent.duration.*

final class EditorSessionAuthorizationComponentTest extends CatsEffectSuite:
  private val owner              = EditorId(new UUID(0L, 1L))
  private val otherOwner         = EditorId(new UUID(0L, 2L))
  private val tab                = TabId(new UUID(0L, 3L))
  private val alias: EditorAlias = "Cosmin"
  private val target             = EditingTarget.Spread(SpreadId(new UUID(0L, 10L)))
  private val cover              = EditingTarget.Cover(AlbumId(new UUID(0L, 11L)))

  Vector(target, cover).foreach { destination =>
    test(s"should authorize only the exact live grant for $destination"):
      for
        mutex      <- Mutex[IO]
        manager    <- buildManager(mutex)
        connection <- registerConnection(manager, owner)
        lock       <- manager.select(connection, TargetRevision.initial, destination).flatMap(requireLock)
        accepted   <- mutex.lock.surround(manager.authorizeTarget(connection, destination, lock.grant))
        wrong      <- mutex.lock.surround(manager.authorizeTarget(connection, destination, GrantId(new UUID(0L, 99L))))
        other <- mutex.lock.surround(manager.authorizeTarget(connection, if Set(target).contains(destination) then cover else target, lock.grant))
      yield
        assertEquals(accepted, AuthorizationResult.Authorized)
        assertEquals(wrong, AuthorizationResult.Rejected(Failure.StaleGrant))
        assertEquals(other, AuthorizationResult.Rejected(Failure.StaleGrant))
  }

  test("should reject an unowned direct target even when it is free"):
    for
      mutex      <- Mutex[IO]
      manager    <- buildManager(mutex)
      connection <- registerConnection(manager, owner)
      result     <- mutex.lock.surround(manager.authorizeTarget(connection, target, GrantId(new UUID(0L, 99L))))
    yield assertEquals(result, AuthorizationResult.Rejected(Failure.StaleGrant))

  test("should authorize independent mutations and unlocked affected targets without acquiring them"):
    for
      mutex       <- Mutex[IO]
      manager     <- buildManager(mutex)
      connection  <- registerConnection(manager, owner)
      independent <- mutex.lock.surround(manager.authorizeAffected(connection, targets = Set.empty, maybeGrant = None))
      shared      <- mutex.lock.surround(manager.authorizeAffected(connection, targets = Set(target, cover), maybeGrant = None))
      current     <- manager.readTarget(connection)
    yield
      assertEquals(independent, AuthorizationResult.Authorized)
      assertEquals(shared, AuthorizationResult.Authorized)
      assertEquals(current, ReadTargetResult.Current(TargetRevision.initial, None))

  test("should prove an affected owned grant and reject missing or obsolete evidence"):
    for
      mutex      <- Mutex[IO]
      manager    <- buildManager(mutex)
      connection <- registerConnection(manager, owner)
      lock       <- manager.select(connection, TargetRevision.initial, target).flatMap(requireLock)
      missing    <- mutex.lock.surround(manager.authorizeAffected(connection, Set(target, cover), maybeGrant = None))
      obsolete   <- mutex.lock.surround(manager.authorizeAffected(connection, Set(target), Some(GrantId(new UUID(0L, 99L)))))
      accepted   <- mutex.lock.surround(manager.authorizeAffected(connection, Set(target, cover), Some(lock.grant)))
      unrelated  <- mutex.lock.surround(manager.authorizeAffected(connection, Set(cover), maybeGrant = None))
    yield
      assertEquals(missing, AuthorizationResult.Rejected(Failure.StaleGrant))
      assertEquals(obsolete, AuthorizationResult.Rejected(Failure.StaleGrant))
      assertEquals(accepted, AuthorizationResult.Authorized)
      assertEquals(unrelated, AuthorizationResult.Authorized)

  test("should reject a shared mutation atomically if another session owns any affected target"):
    TestControl.executeEmbed {
      val otherAlias: EditorAlias = "Alex"

      for
        mutex      <- Mutex[IO]
        manager    <- buildManager(mutex)
        connection <- registerConnection(manager, owner)
        other      <- manager.register(otherOwner, otherAlias, maybeSession = None).flatMap(requireSession)
        competing  <- manager.bind(other.id, tab).flatMap(requireConnection)
        ownLock    <- manager.select(connection, TargetRevision.initial, target).flatMap(requireLock)
        _          <- manager.select(competing, TargetRevision.initial, cover)
        saved      <- Ref.of[IO, String]("saved")
        result     <- mutex.lock.surround {
          manager.authorizeAffected(connection, Set(target, cover), Some(ownLock.grant)).flatTap {
            case AuthorizationResult.Authorized => saved.set("changed")
            case _                              => IO.unit
          }
        }
        retained <- saved.get
        current  <- manager.readTarget(connection)
      yield
        assertEquals(result, AuthorizationResult.Blocked(cover, otherOwner, otherAlias))
        assertEquals(retained, "saved")
        assertEquals(current, ReadTargetResult.Current(TargetRevision.initial.next, Some(ownLock)))
    }

  test("should validate the current tab for independent and protected admissions"):
    for
      mutex       <- Mutex[IO]
      manager     <- buildManager(mutex)
      first       <- registerConnection(manager, owner)
      lock        <- manager.select(first, TargetRevision.initial, target).flatMap(requireLock)
      second      <- manager.bind(first.session, tab).flatMap(requireConnection)
      independent <- mutex.lock.surround(manager.authorizeAffected(first, targets = Set.empty, maybeGrant = None))
      obsolete    <- mutex.lock.surround(manager.authorizeTarget(first, target, lock.grant))
      accepted    <- mutex.lock.surround(manager.authorizeTarget(second, target, lock.grant))
      _           <- manager.unbind(second)
      released    <- mutex.lock.surround(manager.authorizeAffected(second, targets = Set.empty, maybeGrant = None))
    yield
      assertEquals(independent, AuthorizationResult.Rejected(Failure.ObsoleteConnection))
      assertEquals(obsolete, AuthorizationResult.Rejected(Failure.ObsoleteConnection))
      assertEquals(accepted, AuthorizationResult.Authorized)
      assertEquals(released, AuthorizationResult.Rejected(Failure.ObsoleteConnection))

  test("should reject expired sessions for every mutation without extending authority"):
    TestControl.executeEmbed {
      for
        mutex       <- Mutex[IO]
        manager     <- buildManager(mutex)
        connection  <- registerConnection(manager, owner)
        lock        <- manager.select(connection, TargetRevision.initial, target).flatMap(requireLock)
        _           <- IO.sleep(30.seconds)
        direct      <- mutex.lock.surround(manager.authorizeTarget(connection, target, lock.grant))
        shared      <- mutex.lock.surround(manager.authorizeAffected(connection, Set(cover), maybeGrant = None))
        independent <- mutex.lock.surround(manager.authorizeAffected(connection, targets = Set.empty, maybeGrant = None))
        current     <- manager.read(connection)
      yield
        val rejected = AuthorizationResult.Rejected(Failure.StaleSession)
        assertEquals(direct, rejected)
        assertEquals(shared, rejected)
        assertEquals(independent, rejected)
        assertEquals(current, ReadResult.Rejected(Failure.StaleSession))
    }

  test("should expire grants independently and reject late evidence even for now unlocked shared targets"):
    TestControl.executeEmbed {
      for
        mutex      <- Mutex[IO]
        manager    <- buildManager(mutex)
        connection <- registerConnection(manager, owner)
        lock       <- manager.select(connection, TargetRevision.initial, target).flatMap(requireLock)
        _          <- IO.sleep(20.seconds)
        _          <- manager.heartbeat(connection)
        _          <- IO.sleep(10.seconds)
        direct     <- mutex.lock.surround(manager.authorizeTarget(connection, target, lock.grant))
        stale      <- mutex.lock.surround(manager.authorizeAffected(connection, Set(target), Some(lock.grant)))
        shared     <- mutex.lock.surround(manager.authorizeAffected(connection, Set(target), maybeGrant = None))
        current    <- manager.readTarget(connection)
      yield
        assertEquals(direct, AuthorizationResult.Rejected(Failure.StaleGrant))
        assertEquals(stale, AuthorizationResult.Rejected(Failure.StaleGrant))
        assertEquals(shared, AuthorizationResult.Authorized)
        assertEquals(current, ReadTargetResult.Current(TargetRevision.initial.next.next, None))
    }

  test("should not let released or replaced grants authorize a later acquisition"):
    for
      mutex      <- Mutex[IO]
      manager    <- buildManager(mutex)
      connection <- registerConnection(manager, owner)
      first      <- manager.select(connection, TargetRevision.initial, target).flatMap(requireLock)
      _          <- manager.release(connection, TargetRevision.initial.next, first.grant)
      second     <- manager.select(connection, TargetRevision.initial.next.next, target).flatMap(requireLock)
      obsolete   <- mutex.lock.surround(manager.authorizeTarget(connection, target, first.grant))
      accepted   <- mutex.lock.surround(manager.authorizeTarget(connection, target, second.grant))
    yield
      assertEquals(obsolete, AuthorizationResult.Rejected(Failure.StaleGrant))
      assertEquals(accepted, AuthorizationResult.Authorized)

  test("should finish a live-admitted write across expiry before competing acquisition can observe it"):
    TestControl.executeEmbed {
      for
        mutex      <- Mutex[IO]
        manager    <- buildManager(mutex)
        connection <- registerConnection(manager, owner)
        competitor <- registerConnection(manager, otherOwner)
        lock       <- manager.select(connection, TargetRevision.initial, target).flatMap(requireLock)
        saved      <- Ref.of[IO, String]("before")
        admitted   <- Deferred[IO, Unit]
        finish     <- Deferred[IO, Unit]
        _          <- IO.sleep(20.seconds)
        _          <- manager.heartbeat(connection)
        _          <- manager.heartbeat(competitor)
        _          <- IO.sleep(9.seconds)
        writing    <- mutex.lock.surround {
          manager.authorizeTarget(connection, target, lock.grant).flatTap {
            case AuthorizationResult.Authorized => admitted.complete(()) >> finish.get >> saved.set("after")
            case _                              => IO.unit
          }
        }.start
        _         <- admitted.get
        _         <- IO.sleep(1.second)
        acquiring <- manager.select(competitor, TargetRevision.initial, target).flatMap(result => saved.get.tupleLeft(result)).start
        _         <- IO.cede
        _         <- finish.complete(())
        written   <- writing.joinWithNever
        observed  <- acquiring.joinWithNever
        acquired  <- observed match
          case (result, _) => requireLock(result)
        current <- manager.readTarget(competitor)
      yield
        val expectedLock = EditingLock(target, competitor.session, acquired.grant, 60.seconds)
        assertEquals(written, AuthorizationResult.Authorized)
        assertEquals(observed, (SelectResult.Owned(expectedLock, TargetRevision.initial.next), "after"))
        assertEquals(current, ReadTargetResult.Current(TargetRevision.initial.next, Some(expectedLock)))
    }

  test("should reject admission queued beyond expiry without running its write"):
    TestControl.executeEmbed {
      for
        mutex      <- Mutex[IO]
        manager    <- buildManager(mutex)
        connection <- registerConnection(manager, owner)
        lock       <- manager.select(connection, TargetRevision.initial, target).flatMap(requireLock)
        saved      <- Ref.of[IO, String]("saved")
        queued     <- Deferred[IO, Unit]
        pending    <- mutex.lock.use { _ =>
          for
            fiber <-
            (queued.complete(()) >> mutex.lock.surround {
              manager.authorizeTarget(connection, target, lock.grant).flatTap {
                case AuthorizationResult.Authorized => saved.set("changed")
                case _                              => IO.unit
              }
            }).start
            _ <- queued.get
            _ <- IO.sleep(30.seconds)
          yield fiber
        }
        result   <- pending.joinWithNever
        retained <- saved.get
      yield
        assertEquals(result, AuthorizationResult.Rejected(Failure.StaleSession))
        assertEquals(retained, "saved")
    }

  test("should preserve failed relationship reads instead of authorizing an empty affected set"):
    val unavailable = new RuntimeException("Affected targets unavailable")

    for
      mutex      <- Mutex[IO]
      manager    <- buildManager(mutex)
      connection <- registerConnection(manager, owner)
      saved      <- Ref.of[IO, String]("saved")
      result     <- mutex.lock.surround {
        IO.raiseError[Set[EditingTarget]](unavailable).flatMap(manager.authorizeAffected(connection, _, maybeGrant = None)).flatTap {
          case AuthorizationResult.Authorized => saved.set("changed")
          case _                              => IO.unit
        }
      }.attempt
      retained <- saved.get
      _        <- mutex.lock.use(_ => IO.unit)
    yield
      assertEquals(result, unavailable.asLeft[AuthorizationResult])
      assertEquals(retained, "saved")

  test("should read changed affected relationships inside the same coordination interval as admission and writing"):
    for
      mutex      <- Mutex[IO]
      manager    <- buildManager(mutex)
      connection <- registerConnection(manager, owner)
      competitor <- registerConnection(manager, otherOwner)
      _          <- manager.select(competitor, TargetRevision.initial, cover)
      references <- Ref.of[IO, Set[EditingTarget]](Set(target))
      saved      <- Ref.of[IO, String]("saved")
      queued     <- Deferred[IO, Unit]
      pending    <- mutex.lock.use { _ =>
        for
          fiber <-
          (queued.complete(()) >> mutex.lock.surround {
            references.get.flatMap(manager.authorizeAffected(connection, _, maybeGrant = None)).flatTap {
              case AuthorizationResult.Authorized => saved.set("changed")
              case _                              => IO.unit
            }
          }).start
          _ <- queued.get
          _ <- references.set(Set(target, cover))
        yield fiber
      }
      result   <- pending.joinWithNever
      retained <- saved.get
    yield
      assertEquals(result, AuthorizationResult.Blocked(cover, otherOwner, alias))
      assertEquals(retained, "saved")

  test("should reject another session's grant and restart credentials without changing saved state"):
    for
      mutex      <- Mutex[IO]
      manager    <- buildManager(mutex)
      connection <- registerConnection(manager, owner)
      competitor <- registerConnection(manager, otherOwner)
      lock       <- manager.select(connection, TargetRevision.initial, target).flatMap(requireLock)
      borrowed   <- mutex.lock.surround(manager.authorizeTarget(competitor, target, lock.grant))
      restarted  <- buildManager(mutex)
      oldTarget  <- mutex.lock.surround(restarted.authorizeTarget(connection, target, lock.grant))
      oldSession <- mutex.lock.surround(restarted.authorizeAffected(connection, targets = Set.empty, maybeGrant = None))
    yield
      assertEquals(borrowed, AuthorizationResult.Rejected(Failure.StaleGrant))
      assertEquals(oldTarget, AuthorizationResult.Rejected(Failure.StaleSession))
      assertEquals(oldSession, AuthorizationResult.Rejected(Failure.StaleSession))

  test("should never renew either lease through successful authorization"):
    TestControl.executeEmbed {
      for
        mutex      <- Mutex[IO]
        manager    <- buildManager(mutex)
        connection <- registerConnection(manager, owner)
        lock       <- manager.select(connection, TargetRevision.initial, target).flatMap(requireLock)
        _          <- IO.sleep(29.seconds)
        direct     <- mutex.lock.surround(manager.authorizeTarget(connection, target, lock.grant))
        shared     <- mutex.lock.surround(manager.authorizeAffected(connection, Set(target), Some(lock.grant)))
        _          <- IO.sleep(1.second)
        expired    <- mutex.lock.surround(manager.authorizeTarget(connection, target, lock.grant))
      yield
        assertEquals(direct, AuthorizationResult.Authorized)
        assertEquals(shared, AuthorizationResult.Authorized)
        assertEquals(expired, AuthorizationResult.Rejected(Failure.StaleSession))
    }

  private def buildManager(mutex: Mutex[IO]) =
    given Mutex[IO]       = mutex
    given IdGenerator[IO] = IdGenerator.make[IO]
    EditorSessionManager.make[IO](lease = 30L)

  private def registerConnection(manager: EditorSessionManager[IO], editor: EditorId) =
    manager.register(editor, alias, maybeSession = None).flatMap(requireSession).flatMap(session =>
      manager.bind(session.id, tab).flatMap(requireConnection)
    )

  private def requireSession(result: RegisterResult) = result match
    case RegisterResult.Ready(session) => IO.pure(session)
    case _                             => IO.raiseError[EditorSession](new AssertionError("Expected registered session"))

  private def requireConnection(result: BindResult) = result match
    case BindResult.Active(connection) => IO.pure(connection)
    case _                             => IO.raiseError[EditorConnection](new AssertionError("Expected active connection"))

  private def requireLock(result: SelectResult) = result match
    case SelectResult.Owned(lock, _) => IO.pure(lock)
    case _                           => IO.raiseError[EditingLock](new AssertionError("Expected target ownership"))
