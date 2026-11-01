package photography.usecases.editing

import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import photography.capabilities.IdGenerator
import photography.domain.editing.*
import photography.domain.album.AlbumId
import photography.domain.layout.SpreadId
import photography.usecases.editing.EditorSessionManager.*

import java.util.UUID
import scala.concurrent.duration.*

final class EditorSessionManagerComponentTest extends CatsEffectSuite:
  private val owner              = EditorId(new UUID(0L, 1L))
  private val tab                = TabId(new UUID(0L, 2L))
  private val duplicate          = TabId(new UUID(0L, 3L))
  private val alias: EditorAlias = "Cosmin"

  test("should require a nonblank identification alias"):
    assert(" \t\n".refineEither[EditorAliasConstraint].isLeft)
    assertEquals("Cosmin".refineEither[EditorAliasConstraint], alias.asRight[String])

  test("should resume only with the live private credential without extending its deadline"):
    TestControl.executeEmbed {
      val wrong = SessionId(new UUID(0L, 99L))

      for
        manager    <- buildManager()
        registered <- manager.register(owner, alias, maybeSession = None)
        session    <- requireSession(registered)
        _          <- IO.sleep(20.seconds)
        denied     <- manager.register(owner, alias, maybeSession = None)
        mismatched <- manager.register(owner, alias, maybeSession = Some(wrong))
        resumed    <- manager.register(owner, alias, maybeSession = Some(session.id))
        _          <- IO.sleep(10.seconds)
        expired    <- manager.bind(session.id, tab)
      yield
        assertEquals(denied, RegisterResult.CredentialRequired)
        assertEquals(mismatched, RegisterResult.CredentialRequired)
        assertEquals(resumed, RegisterResult.Ready(session))
        assertEquals(expired, BindResult.StaleSession)
    }

  test("should block a different tab without exposing private session credentials"):
    for
      manager    <- buildManager()
      registered <- manager.register(owner, alias, maybeSession = None)
      session    <- requireSession(registered)
      active     <- manager.bind(session.id, tab).flatMap(requireConnection)
      blocked    <- manager.bind(session.id, duplicate)
    yield
      assertEquals(active, EditorConnection(session.id, tab, active.generation))
      assertEquals(blocked, BindResult.Blocked(owner, alias))

  test("should fence old requests and finalizers when the same document reconnects"):
    TestControl.executeEmbed {
      for
        manager         <- buildManager()
        registered      <- manager.register(owner, alias, maybeSession = None)
        session         <- requireSession(registered)
        first           <- manager.bind(session.id, tab).flatMap(requireConnection)
        _               <- IO.sleep(20.seconds)
        second          <- manager.bind(session.id, tab).flatMap(requireConnection)
        oldHeartbeat    <- manager.heartbeat(first)
        oldCleanup      <- manager.unbind(first)
        duplicateResult <- manager.bind(session.id, duplicate)
        current         <- manager.read(second)
        _               <- IO.sleep(10.seconds)
        expired         <- manager.heartbeat(second)
      yield
        assertNotEquals(first.generation.value, second.generation.value)
        assertEquals(oldHeartbeat, HeartbeatResult.Rejected(Failure.ObsoleteConnection))
        assertEquals(oldCleanup, UnbindResult.Rejected(Failure.ObsoleteConnection))
        assertEquals(duplicateResult, BindResult.Blocked(owner, alias))
        assertEquals(current, ReadResult.Current(session.copy(maybeConnection = Some(second))))
        assertEquals(expired, HeartbeatResult.Rejected(Failure.StaleSession))
    }

  test("should release only the tab binding and allow reload to resume the live session"):
    TestControl.executeEmbed {
      for
        manager    <- buildManager()
        registered <- manager.register(owner, alias, maybeSession = None)
        session    <- requireSession(registered)
        connection <- manager.bind(session.id, tab).flatMap(requireConnection)
        _          <- IO.sleep(20.seconds)
        released   <- manager.unbind(connection)
        resumed    <- manager.register(owner, alias, maybeSession = Some(session.id))
        _          <- IO.sleep(5.seconds)
        reloaded   <- manager.bind(session.id, duplicate).flatMap(requireConnection)
        current    <- manager.read(reloaded)
        old        <- manager.heartbeat(connection)
        _          <- IO.sleep(5.seconds)
        expired    <- manager.heartbeat(reloaded)
      yield
        assertEquals(released, UnbindResult.Released)
        assertEquals(resumed, RegisterResult.Ready(session.copy(maybeConnection = None)))
        assertEquals(current, ReadResult.Current(session.copy(maybeConnection = Some(reloaded))))
        assertEquals(old, HeartbeatResult.Rejected(Failure.ObsoleteConnection))
        assertEquals(expired, HeartbeatResult.Rejected(Failure.StaleSession))
    }

  Vector(29.seconds, 30.seconds).foreach { elapsed =>
    test(s"should enforce the session expiry boundary at $elapsed"):
      TestControl.executeEmbed {
        for
          manager    <- buildManager()
          registered <- manager.register(owner, alias, maybeSession = None)
          session    <- requireSession(registered)
          connection <- manager.bind(session.id, tab).flatMap(requireConnection)
          _          <- IO.sleep(elapsed)
          result     <- manager.heartbeat(connection)
        yield
          if elapsed < 30.seconds then
            val renewed = session.copy(lastHeartbeat = elapsed, expiresAt = elapsed + 30.seconds, maybeConnection = Some(connection))
            assertEquals(result, HeartbeatResult.Alive(renewed))
          else assertEquals(result, HeartbeatResult.Rejected(Failure.StaleSession))
      }
  }

  test("should extend only accepted heartbeats and reject old credentials after expiry"):
    TestControl.executeEmbed {
      for
        manager     <- buildManager()
        registered  <- manager.register(owner, alias, maybeSession = None)
        session     <- requireSession(registered)
        connection  <- manager.bind(session.id, tab).flatMap(requireConnection)
        _           <- IO.sleep(20.seconds)
        renewed     <- manager.heartbeat(connection)
        _           <- IO.sleep(20.seconds)
        alive       <- manager.read(connection)
        _           <- IO.sleep(10.seconds)
        expired     <- manager.heartbeat(connection)
        replacement <- manager.register(owner, alias, maybeSession = Some(session.id)).flatMap(requireSession)
        oldCleanup  <- manager.unbind(connection)
      yield
        val expected = session.copy(lastHeartbeat = 20.seconds, expiresAt = 50.seconds, maybeConnection = Some(connection))
        assertEquals(renewed, HeartbeatResult.Alive(expected))
        assertEquals(alive, ReadResult.Current(expected))
        assertEquals(expired, HeartbeatResult.Rejected(Failure.StaleSession))
        assertNotEquals(replacement.id, session.id)
        assertEquals(oldCleanup, UnbindResult.Rejected(Failure.StaleSession))
    }

  test("should reject every previous credential after a new authority instance starts"):
    for
      manager     <- buildManager()
      registered  <- manager.register(owner, alias, maybeSession = None)
      session     <- requireSession(registered)
      connection  <- manager.bind(session.id, tab).flatMap(requireConnection)
      restarted   <- buildManager()
      oldRead     <- restarted.read(connection)
      oldBind     <- restarted.bind(session.id, tab)
      replacement <- restarted.register(owner, alias, maybeSession = Some(session.id)).flatMap(requireSession)
    yield
      assertEquals(oldRead, ReadResult.Rejected(Failure.StaleSession))
      assertEquals(oldBind, BindResult.StaleSession)
      assertNotEquals(replacement.id, session.id)
      assertEquals(replacement.owner, owner)
      assertEquals(replacement.alias, alias)

  test("should admit exactly one document when duplicate tabs bind concurrently"):
    for
      manager    <- buildManager()
      registered <- manager.register(owner, alias, maybeSession = None)
      session    <- requireSession(registered)
      results    <- List(tab, duplicate).parTraverse(manager.bind(session.id, _))
    yield
      val active  = results.collect { case BindResult.Active(connection) => connection }
      val blocked = results.collect { case result @ BindResult.Blocked(_, _) => result }
      assertEquals(active.map(_.session), List(session.id))
      assertEquals(blocked, List(BindResult.Blocked(owner, alias)))

  test("should create one session when registrations for the same browser race"):
    for
      manager <- buildManager()
      results <- List.fill(2)(manager.register(owner, alias, maybeSession = None)).parSequence
    yield
      val sessions = results.collect { case RegisterResult.Ready(session) => session }
      val rejected = results.collect { case RegisterResult.CredentialRequired => RegisterResult.CredentialRequired }
      assertEquals(sessions.map(_.owner), List(owner))
      assertEquals(rejected, List(RegisterResult.CredentialRequired))

  test("should allow distinct browsers to use the same display alias"):
    val other = EditorId(new UUID(0L, 4L))

    for
      manager <- buildManager()
      first   <- manager.register(owner, alias, maybeSession = None).flatMap(requireSession)
      second  <- manager.register(other, alias, maybeSession = None).flatMap(requireSession)
    yield
      assertNotEquals(first.id, second.id)
      assertEquals(List(first.owner, second.owner), List(owner, other))
      assertEquals(List(first.alias, second.alias), List(alias, alias))

  test("should not revive an expired session through binding or release retries"):
    TestControl.executeEmbed {
      for
        manager      <- buildManager()
        registered   <- manager.register(owner, alias, maybeSession = None)
        session      <- requireSession(registered)
        connection   <- manager.bind(session.id, tab).flatMap(requireConnection)
        _            <- IO.sleep(30.seconds)
        staleBind    <- manager.bind(session.id, tab)
        staleRelease <- manager.unbind(connection)
        staleRead    <- manager.read(connection)
      yield
        assertEquals(staleBind, BindResult.StaleSession)
        assertEquals(staleRelease, UnbindResult.Rejected(Failure.StaleSession))
        assertEquals(staleRead, ReadResult.Rejected(Failure.StaleSession))
    }

  test("should cancel a waiting tab admission without changing authority or stranding the shared mutex"):
    TestControl.executeEmbed {
      for
        mutex      <- Mutex[IO]
        submitted  <- Deferred[IO, Unit]
        manager    <- buildCoordinatedManager(mutex, IdGenerator.make[IO])
        registered <- manager.register(owner, alias, maybeSession = None)
        session    <- requireSession(registered)
        canceled   <- mutex.lock.use { _ =>
          for
            pending <- (submitted.complete(()).void *> manager.bind(session.id, tab)).start
            _       <- submitted.get
            _       <- IO.cede
            _       <- pending.cancel
            outcome <- pending.join
          yield outcome
        }
        retained <- manager.register(owner, alias, maybeSession = Some(session.id))
        accepted <- manager.bind(session.id, duplicate).flatMap(requireConnection)
        current  <- manager.read(accepted)
      yield
        assertEquals(canceled, Outcome.Canceled[IO, Throwable, BindResult]())
        assertEquals(retained, RegisterResult.Ready(session))
        assertEquals(current, ReadResult.Current(session.copy(maybeConnection = Some(accepted))))
    }

  test("should release shared coordination when connection identity generation is canceled"):
    TestControl.executeEmbed {
      for
        mutex      <- Mutex[IO]
        generating <- Deferred[IO, Unit]
        calls      <- Ref.of[IO, Int](0)
        identity = new IdGenerator[IO]:
          override def generateId: IO[UUID] = calls.getAndUpdate(_ + 1).flatMap {
            case 0 => IO.pure(new UUID(0L, 101L))
            case 1 => generating.complete(()).void *> IO.never[UUID]
            case _ => IO.pure(new UUID(0L, 102L))
          }
        manager    <- buildCoordinatedManager(mutex, identity)
        registered <- manager.register(owner, alias, maybeSession = None)
        session    <- requireSession(registered)
        pending    <- manager.bind(session.id, tab).start
        _          <- generating.get
        _          <- pending.cancel
        canceled   <- pending.join
        _          <- mutex.lock.use(_ => IO.unit)
        retained   <- manager.register(owner, alias, maybeSession = Some(session.id))
        accepted   <- manager.bind(session.id, duplicate).flatMap(requireConnection)
        current    <- manager.read(accepted)
      yield
        val expected = EditorConnection(session.id, duplicate, ConnectionGeneration(new UUID(0L, 102L)))
        assertEquals(canceled, Outcome.Canceled[IO, Throwable, BindResult]())
        assertEquals(retained, RegisterResult.Ready(session))
        assertEquals(accepted, expected)
        assertEquals(current, ReadResult.Current(session.copy(maybeConnection = Some(expected))))
    }

  test("should retain unbound authority and release coordination when identity generation fails"):
    val unavailable = new RuntimeException("Identity generation unavailable")

    for
      mutex <- Mutex[IO]
      calls <- Ref.of[IO, Int](0)
      identity = new IdGenerator[IO]:
        override def generateId: IO[UUID] = calls.getAndUpdate(_ + 1).flatMap {
          case 0 => IO.pure(new UUID(0L, 103L))
          case 1 => IO.raiseError[UUID](unavailable)
          case _ => IO.pure(new UUID(0L, 104L))
        }
      manager    <- buildCoordinatedManager(mutex, identity)
      registered <- manager.register(owner, alias, maybeSession = None)
      session    <- requireSession(registered)
      failed     <- manager.bind(session.id, tab).attempt
      _          <- mutex.lock.use(_ => IO.unit)
      retained   <- manager.register(owner, alias, maybeSession = Some(session.id))
      accepted   <- manager.bind(session.id, duplicate).flatMap(requireConnection)
    yield
      assertEquals(failed, unavailable.asLeft[BindResult])
      assertEquals(retained, RegisterResult.Ready(session))
      assertEquals(accepted, EditorConnection(session.id, duplicate, ConnectionGeneration(new UUID(0L, 104L))))

  test("should sample heartbeat admission time after shared coordination crosses the expiry boundary"):
    TestControl.executeEmbed {
      for
        mutex      <- Mutex[IO]
        submitted  <- Deferred[IO, Unit]
        manager    <- buildCoordinatedManager(mutex, IdGenerator.make[IO])
        registered <- manager.register(owner, alias, maybeSession = None)
        session    <- requireSession(registered)
        connection <- manager.bind(session.id, tab).flatMap(requireConnection)
        _          <- IO.sleep(20.seconds)
        pending    <- mutex.lock.use { _ =>
          for
            waiting <- (submitted.complete(()).void *> manager.heartbeat(connection)).start
            _       <- submitted.get
            _       <- IO.cede
            _       <- IO.sleep(10.seconds)
          yield waiting
        }
        result <- pending.joinWithNever
        read   <- manager.read(connection)
      yield
        assertEquals(result, HeartbeatResult.Rejected(Failure.StaleSession))
        assertEquals(read, ReadResult.Rejected(Failure.StaleSession))
    }

  test("should reconcile a lost reconnect acknowledgement before replacing its generation"):
    val lostAcknowledgement = new RuntimeException("Binding acknowledgement lost")

    for
      manager     <- buildManager()
      registered  <- manager.register(owner, alias, maybeSession = None)
      session     <- requireSession(registered)
      first       <- manager.bind(session.id, tab).flatMap(requireConnection)
      uncertain   <- (manager.bind(session.id, tab) *> IO.raiseError[BindResult](lostAcknowledgement)).attempt
      recovered   <- manager.register(owner, alias, maybeSession = Some(session.id)).flatMap(requireSession)
      lost        <- recovered.maybeConnection.fold(IO.raiseError[EditorConnection](new AssertionError("Expected retained binding")))(IO.pure)
      reconciled  <- manager.read(lost)
      blocked     <- manager.bind(session.id, duplicate)
      successor   <- manager.bind(session.id, tab).flatMap(requireConnection)
      oldCleanup  <- manager.unbind(first)
      lostCleanup <- manager.unbind(lost)
      current     <- manager.read(successor)
    yield
      assertEquals(uncertain, lostAcknowledgement.asLeft[BindResult])
      assertNotEquals(lost.generation, first.generation)
      assertNotEquals(successor.generation, lost.generation)
      assertEquals(reconciled, ReadResult.Current(session.copy(maybeConnection = Some(lost))))
      assertEquals(blocked, BindResult.Blocked(owner, alias))
      assertEquals(oldCleanup, UnbindResult.Rejected(Failure.ObsoleteConnection))
      assertEquals(lostCleanup, UnbindResult.Rejected(Failure.ObsoleteConnection))
      assertEquals(current, ReadResult.Current(session.copy(maybeConnection = Some(successor))))

  test("should reconcile lost release knowledge without replaying release against a new binding"):
    val lostAcknowledgement = new RuntimeException("Release acknowledgement lost")

    for
      manager     <- buildManager()
      registered  <- manager.register(owner, alias, maybeSession = None)
      session     <- requireSession(registered)
      connection  <- manager.bind(session.id, tab).flatMap(requireConnection)
      uncertain   <- (manager.unbind(connection) *> IO.raiseError[UnbindResult](lostAcknowledgement)).attempt
      recovered   <- manager.register(owner, alias, maybeSession = Some(session.id))
      reloaded    <- manager.bind(session.id, duplicate).flatMap(requireConnection)
      lateRelease <- manager.unbind(connection)
      current     <- manager.read(reloaded)
    yield
      assertEquals(uncertain, lostAcknowledgement.asLeft[UnbindResult])
      assertEquals(recovered, RegisterResult.Ready(session.copy(maybeConnection = None)))
      assertEquals(lateRelease, UnbindResult.Rejected(Failure.ObsoleteConnection))
      assertEquals(current, ReadResult.Current(session.copy(maybeConnection = Some(reloaded))))

  Vector(
    EditingTarget.Spread(SpreadId(new UUID(0L, 10L))),
    EditingTarget.Cover(AlbumId(new UUID(0L, 11L)))
  ).foreach { target =>
    test(s"should grant exactly one owner of $target and expose only public contender facts"):
      for
        manager <- buildManager()
        first   <- registerConnection(manager, owner, tab)
        second  <- registerConnection(manager, EditorId(new UUID(0L, 4L)), duplicate)
        results <- (
          manager.select(first, TargetRevision.initial, target),
          manager.select(second, TargetRevision.initial, target)
        ).parTupled
        firstState  <- manager.readTarget(first)
        secondState <- manager.readTarget(second)
      yield
        val revision = TargetRevision.initial.next
        (results, firstState, secondState) match
          case (
                (SelectResult.Owned(lock, `revision`), SelectResult.Blocked(`owner`, `alias`, deadline, `revision`)),
                ReadTargetResult.Current(`revision`, Some(saved)),
                ReadTargetResult.Current(`revision`, None)
              ) =>
            assertEquals(lock, saved)
            assertEquals(lock.target, target)
            assertEquals(lock.session, first.session)
            assertEquals(deadline, lock.expiresAt)
          case (
                (SelectResult.Blocked(_, `alias`, deadline, `revision`), SelectResult.Owned(lock, `revision`)),
                ReadTargetResult.Current(`revision`, None),
                ReadTargetResult.Current(`revision`, Some(saved))
              ) =>
            assertEquals(lock, saved)
            assertEquals(lock.target, target)
            assertEquals(lock.session, second.session)
            assertEquals(deadline, lock.expiresAt)
          case _ => fail(s"Expected one exact owner and one public contender: $results, $firstState, $secondState")
  }

  test("should release the previous target on contention and fence late navigation retries"):
    val firstTarget = EditingTarget.Spread(SpreadId(new UUID(0L, 10L)))
    val destination = EditingTarget.Cover(AlbumId(new UUID(0L, 11L)))
    val otherOwner  = EditorId(new UUID(0L, 4L))

    for
      manager     <- buildManager()
      first       <- registerConnection(manager, owner, tab)
      second      <- registerConnection(manager, otherOwner, duplicate)
      original    <- manager.select(first, TargetRevision.initial, firstTarget).flatMap(requireLock)
      busy        <- manager.select(second, TargetRevision.initial, destination).flatMap(requireLock)
      blocked     <- manager.select(first, TargetRevision.initial.next, destination)
      lateSelect  <- manager.select(first, TargetRevision.initial, firstTarget)
      lateRelease <- manager.release(first, TargetRevision.initial.next, original.grant)
      oldRenewal  <- manager.renewTarget(first, original.grant)
      current     <- manager.readTarget(first)
      acquired    <- manager.select(second, TargetRevision.initial.next, firstTarget).flatMap(requireLock)
    yield
      val revision = TargetRevision.initial.next.next
      assertEquals(blocked, SelectResult.Blocked(otherOwner, alias, busy.expiresAt, revision))
      assertEquals(lateSelect, SelectResult.Rejected(Failure.StaleTransition))
      assertEquals(lateRelease, ReleaseResult.Rejected(Failure.StaleTransition))
      assertEquals(oldRenewal, RenewTargetResult.Rejected(Failure.StaleGrant))
      assertEquals(current, ReadTargetResult.Current(revision, None))
      assertEquals(acquired.target, firstTarget)
      assertNotEquals(original.grant, acquired.grant)

  test("should expire a target independently of session heartbeat and fence old grants after reacquisition"):
    TestControl.executeEmbed {
      val target = EditingTarget.Spread(SpreadId(new UUID(0L, 10L)))

      for
        manager    <- buildManager()
        connection <- registerConnection(manager, owner, tab)
        original   <- manager.select(connection, TargetRevision.initial, target).flatMap(requireLock)
        _          <- IO.sleep(20.seconds)
        _          <- manager.heartbeat(connection)
        _          <- IO.sleep(10.seconds)
        expired    <- manager.readTarget(connection)
        late       <- manager.select(connection, TargetRevision.initial.next, target)
        renewed    <- manager.renewTarget(connection, original.grant)
        fresh      <- manager.select(connection, TargetRevision.initial.next.next, target).flatMap(requireLock)
        oldRelease <- manager.release(connection, TargetRevision.initial.next.next.next, original.grant)
        oldRenewal <- manager.renewTarget(connection, original.grant)
        current    <- manager.readTarget(connection)
      yield
        val expiredRevision = TargetRevision.initial.next.next
        val freshRevision   = expiredRevision.next
        assertEquals(expired, ReadTargetResult.Current(expiredRevision, None))
        assertEquals(late, SelectResult.Rejected(Failure.StaleTransition))
        assertEquals(renewed, RenewTargetResult.Rejected(Failure.StaleGrant))
        assertNotEquals(original.grant, fresh.grant)
        assertEquals(fresh.expiresAt, 60.seconds)
        assertEquals(oldRelease, ReleaseResult.Rejected(Failure.StaleGrant))
        assertEquals(oldRenewal, RenewTargetResult.Rejected(Failure.StaleGrant))
        assertEquals(current, ReadTargetResult.Current(freshRevision, Some(fresh)))
    }

  test("should renew only the target and remove it when its session expires"):
    TestControl.executeEmbed {
      val target = EditingTarget.Cover(AlbumId(new UUID(0L, 11L)))

      for
        manager      <- buildManager()
        connection   <- registerConnection(manager, owner, tab)
        lock         <- manager.select(connection, TargetRevision.initial, target).flatMap(requireLock)
        _            <- IO.sleep(29.seconds)
        renewed      <- manager.renewTarget(connection, lock.grant)
        _            <- IO.sleep(1.second)
        expired      <- manager.readTarget(connection)
        staleRelease <- manager.release(connection, TargetRevision.initial.next, lock.grant)
        replacement  <- registerConnection(manager, EditorId(new UUID(0L, 4L)), duplicate)
        acquired     <- manager.select(replacement, TargetRevision.initial, target).flatMap(requireLock)
      yield
        assertEquals(renewed, RenewTargetResult.Renewed(lock.copy(expiresAt = 59.seconds)))
        assertEquals(expired, ReadTargetResult.Rejected(Failure.StaleSession))
        assertEquals(staleRelease, ReleaseResult.Rejected(Failure.StaleSession))
        assertEquals(acquired.target, target)
        assertNotEquals(acquired.grant, lock.grant)
    }

  test("should retain a live grant across reload while fencing obsolete connection operations"):
    val target = EditingTarget.Cover(AlbumId(new UUID(0L, 11L)))

    for
      manager     <- buildManager()
      first       <- registerConnection(manager, owner, tab)
      lock        <- manager.select(first, TargetRevision.initial, target).flatMap(requireLock)
      _           <- manager.unbind(first)
      second      <- manager.bind(first.session, duplicate).flatMap(requireConnection)
      lateSelect  <- manager.select(first, TargetRevision.initial.next, target)
      lateRenewal <- manager.renewTarget(first, lock.grant)
      lateRelease <- manager.release(first, TargetRevision.initial.next, lock.grant)
      lateRead    <- manager.readTarget(first)
      current     <- manager.readTarget(second)
      released    <- manager.release(second, TargetRevision.initial.next, lock.grant)
      replay      <- manager.release(second, TargetRevision.initial.next, lock.grant)
      retained    <- manager.readTarget(second)
    yield
      assertEquals(lateSelect, SelectResult.Rejected(Failure.ObsoleteConnection))
      assertEquals(lateRenewal, RenewTargetResult.Rejected(Failure.ObsoleteConnection))
      assertEquals(lateRelease, ReleaseResult.Rejected(Failure.ObsoleteConnection))
      assertEquals(lateRead, ReadTargetResult.Rejected(Failure.ObsoleteConnection))
      assertEquals(current, ReadTargetResult.Current(TargetRevision.initial.next, Some(lock)))
      assertEquals(released, ReleaseResult.Released(TargetRevision.initial.next.next))
      assertEquals(replay, ReleaseResult.Rejected(Failure.StaleTransition))
      assertEquals(retained, ReadTargetResult.Current(TargetRevision.initial.next.next, None))

  test("should reconcile lost target acknowledgements without replaying obsolete transitions"):
    val target              = EditingTarget.Spread(SpreadId(new UUID(0L, 10L)))
    val lostAcknowledgement = new RuntimeException("Target acknowledgement lost")

    for
      manager    <- buildManager()
      connection <- registerConnection(manager, owner, tab)
      uncertain  <- (manager.select(connection, TargetRevision.initial, target) *> IO.raiseError[SelectResult](lostAcknowledgement)).attempt
      reconciled <- manager.readTarget(connection)
      lock       <- reconciled match
        case ReadTargetResult.Current(_, Some(lock)) => IO.pure(lock)
        case _                                       => IO.raiseError[EditingLock](new AssertionError("Expected retained acquisition"))
      replay         <- manager.select(connection, TargetRevision.initial, target)
      unknownRelease <-
      (manager.release(connection, TargetRevision.initial.next, lock.grant) *> IO.raiseError[ReleaseResult](lostAcknowledgement)).attempt
      released    <- manager.readTarget(connection)
      replacement <- manager.select(connection, TargetRevision.initial.next.next, target).flatMap(requireLock)
      lateRelease <- manager.release(connection, TargetRevision.initial.next, lock.grant)
      current     <- manager.readTarget(connection)
    yield
      assertEquals(uncertain, lostAcknowledgement.asLeft[SelectResult])
      assertEquals(reconciled, ReadTargetResult.Current(TargetRevision.initial.next, Some(lock)))
      assertEquals(replay, SelectResult.Rejected(Failure.StaleTransition))
      assertEquals(unknownRelease, lostAcknowledgement.asLeft[ReleaseResult])
      assertEquals(released, ReadTargetResult.Current(TargetRevision.initial.next.next, None))
      assertNotEquals(lock.grant, replacement.grant)
      assertEquals(lateRelease, ReleaseResult.Rejected(Failure.StaleTransition))
      assertEquals(current, ReadTargetResult.Current(TargetRevision.initial.next.next.next, Some(replacement)))

  test("should replace even a same-target acquisition and reject another session's grant"):
    val target      = EditingTarget.Spread(SpreadId(new UUID(0L, 10L)))
    val destination = EditingTarget.Cover(AlbumId(new UUID(0L, 11L)))

    for
      manager       <- buildManager()
      connection    <- registerConnection(manager, owner, tab)
      other         <- registerConnection(manager, EditorId(new UUID(0L, 4L)), duplicate)
      original      <- manager.select(connection, TargetRevision.initial, target).flatMap(requireLock)
      replacement   <- manager.select(connection, TargetRevision.initial.next, target).flatMap(requireLock)
      stolenRenewal <- manager.renewTarget(other, replacement.grant)
      stolenRelease <- manager.release(other, TargetRevision.initial, replacement.grant)
      switched      <- manager.select(connection, TargetRevision.initial.next.next, destination).flatMap(requireLock)
      staleRenewal  <- manager.renewTarget(connection, replacement.grant)
      current       <- manager.readTarget(connection)
    yield
      assertNotEquals(original.grant, replacement.grant)
      assertEquals(stolenRenewal, RenewTargetResult.Rejected(Failure.StaleGrant))
      assertEquals(stolenRelease, ReleaseResult.Rejected(Failure.StaleGrant))
      assertEquals(staleRenewal, RenewTargetResult.Rejected(Failure.StaleGrant))
      assertEquals(current, ReadTargetResult.Current(TargetRevision.initial.next.next.next, Some(switched)))
      assertEquals(switched.target, destination)

  test("should reject restarted authority and never retain a former target grant"):
    val target = EditingTarget.Cover(AlbumId(new UUID(0L, 11L)))

    for
      original   <- buildManager()
      connection <- registerConnection(original, owner, tab)
      lock       <- original.select(connection, TargetRevision.initial, target).flatMap(requireLock)
      restarted  <- buildManager()
      selection  <- restarted.select(connection, TargetRevision.initial.next, target)
      renewal    <- restarted.renewTarget(connection, lock.grant)
      released   <- restarted.release(connection, TargetRevision.initial.next, lock.grant)
      current    <- restarted.readTarget(connection)
      fresh      <- registerConnection(restarted, owner, tab)
      acquired   <- restarted.select(fresh, TargetRevision.initial, target).flatMap(requireLock)
    yield
      assertEquals(selection, SelectResult.Rejected(Failure.StaleSession))
      assertEquals(renewal, RenewTargetResult.Rejected(Failure.StaleSession))
      assertEquals(released, ReleaseResult.Rejected(Failure.StaleSession))
      assertEquals(current, ReadTargetResult.Rejected(Failure.StaleSession))
      assertNotEquals(lock.grant, acquired.grant)
      assertNotEquals(connection.session, acquired.session)

  test("should retain the previous grant if canceled while generating its replacement"):
    TestControl.executeEmbed {
      val target      = EditingTarget.Spread(SpreadId(new UUID(0L, 10L)))
      val destination = EditingTarget.Cover(AlbumId(new UUID(0L, 11L)))

      for
        mutex      <- Mutex[IO]
        generating <- Deferred[IO, Unit]
        calls      <- Ref.of[IO, Int](0)
        identity = new IdGenerator[IO]:
          override def generateId: IO[UUID] = calls.getAndUpdate(_ + 1).flatMap {
            case 3     => generating.complete(()).void *> IO.never[UUID]
            case index => IO.pure(new UUID(0L, index.toLong + 100L))
          }
        manager    <- buildCoordinatedManager(mutex, identity)
        connection <- registerConnection(manager, owner, tab)
        lock       <- manager.select(connection, TargetRevision.initial, target).flatMap(requireLock)
        pending    <- manager.select(connection, TargetRevision.initial.next, destination).start
        _          <- generating.get
        _          <- pending.cancel
        canceled   <- pending.join
        _          <- mutex.lock.use(_ => IO.unit)
        current    <- manager.readTarget(connection)
        accepted   <- manager.select(connection, TargetRevision.initial.next, destination).flatMap(requireLock)
      yield
        assertEquals(canceled, Outcome.Canceled[IO, Throwable, SelectResult]())
        assertEquals(current, ReadTargetResult.Current(TargetRevision.initial.next, Some(lock)))
        assertEquals(accepted.target, destination)
        assertNotEquals(accepted.grant, lock.grant)
    }

  test("should sample target renewal and acquisition after waiting coordination crosses expiry"):
    TestControl.executeEmbed {
      val target = EditingTarget.Spread(SpreadId(new UUID(0L, 10L)))

      for
        mutex     <- Mutex[IO]
        submitted <- Deferred[IO, Unit]
        manager   <- buildCoordinatedManager(mutex, IdGenerator.make[IO])
        first     <- registerConnection(manager, owner, tab)
        second    <- registerConnection(manager, EditorId(new UUID(0L, 4L)), duplicate)
        lock      <- manager.select(first, TargetRevision.initial, target).flatMap(requireLock)
        _         <- IO.sleep(20.seconds)
        _         <- manager.heartbeat(first)
        _         <- manager.heartbeat(second)
        pending   <- mutex.lock.use { _ =>
          for
            waiting <-
            (submitted.complete(()).void *> (
              manager.renewTarget(first, lock.grant),
              manager.select(second, TargetRevision.initial, target)
            ).parTupled).start
            _ <- submitted.get
            _ <- IO.cede
            _ <- IO.sleep(10.seconds)
          yield waiting
        }
        results <- pending.joinWithNever
        current <- manager.readTarget(first)
      yield
        results match
          case (RenewTargetResult.Rejected(Failure.StaleGrant), SelectResult.Owned(acquired, revision)) =>
            assertEquals(acquired.target, target)
            assertEquals(acquired.expiresAt, 60.seconds)
            assertEquals(revision, TargetRevision.initial.next)
            assertNotEquals(acquired.grant, lock.grant)
          case _ => fail(s"Expected expired renewal and a fresh acquisition: $results")
        assertEquals(current, ReadTargetResult.Current(TargetRevision.initial.next.next, None))
    }

  test("should reject acquisition when identity generation crosses the live session deadline"):
    TestControl.executeEmbed {
      val target = EditingTarget.Cover(AlbumId(new UUID(0L, 11L)))

      for
        mutex <- Mutex[IO]
        calls <- Ref.of[IO, Int](0)
        identity = new IdGenerator[IO]:
          override def generateId: IO[UUID] = calls.getAndUpdate(_ + 1).flatMap { index =>
            val id = new UUID(0L, index.toLong + 100L)
            if index === 2 then IO.sleep(30.seconds).as(id) else IO.pure(id)
          }
        manager    <- buildCoordinatedManager(mutex, identity)
        connection <- registerConnection(manager, owner, tab)
        result     <- manager.select(connection, TargetRevision.initial, target)
        current    <- manager.readTarget(connection)
      yield
        assertEquals(result, SelectResult.Rejected(Failure.StaleSession))
        assertEquals(current, ReadTargetResult.Rejected(Failure.StaleSession))
    }

  test("should fence a pending target switch if its prior grant expires during identity generation"):
    TestControl.executeEmbed {
      val target      = EditingTarget.Spread(SpreadId(new UUID(0L, 10L)))
      val destination = EditingTarget.Cover(AlbumId(new UUID(0L, 11L)))

      for
        mutex <- Mutex[IO]
        calls <- Ref.of[IO, Int](0)
        identity = new IdGenerator[IO]:
          override def generateId: IO[UUID] = calls.getAndUpdate(_ + 1).flatMap { index =>
            val id = new UUID(0L, index.toLong + 100L)
            if index === 3 then IO.sleep(10.seconds).as(id) else IO.pure(id)
          }
        manager    <- buildCoordinatedManager(mutex, identity)
        connection <- registerConnection(manager, owner, tab)
        _          <- manager.select(connection, TargetRevision.initial, target).flatMap(requireLock)
        _          <- IO.sleep(20.seconds)
        _          <- manager.heartbeat(connection)
        result     <- manager.select(connection, TargetRevision.initial.next, destination)
        current    <- manager.readTarget(connection)
      yield
        assertEquals(result, SelectResult.Rejected(Failure.StaleTransition))
        assertEquals(current, ReadTargetResult.Current(TargetRevision.initial.next.next, None))
    }

  private def registerConnection(manager: EditorSessionManager[IO], editor: EditorId, document: TabId) =
    manager.register(editor, alias, maybeSession = None).flatMap(requireSession).flatMap(session =>
      manager.bind(session.id, document).flatMap(requireConnection)
    )

  private def requireLock(result: SelectResult) = result match
    case SelectResult.Owned(lock, _) => IO.pure(lock)
    case _                           => IO.raiseError[EditingLock](new AssertionError("Expected target ownership"))

  private def buildManager() =
    Mutex[IO].flatMap(mutex => buildCoordinatedManager(mutex, IdGenerator.make[IO]))

  private def buildCoordinatedManager(mutex: Mutex[IO], identity: IdGenerator[IO]) =
    given Mutex[IO]       = mutex
    given IdGenerator[IO] = identity
    EditorSessionManager.make[IO](lease = 30L)

  private def requireSession(result: RegisterResult) = result match
    case RegisterResult.Ready(session)     => IO.pure(session)
    case RegisterResult.CredentialRequired => IO.raiseError[EditorSession](new AssertionError("Expected registered session"))

  private def requireConnection(result: BindResult) = result match
    case BindResult.Active(connection) => IO.pure(connection)
    case _                             => IO.raiseError[EditorConnection](new AssertionError("Expected active connection"))
