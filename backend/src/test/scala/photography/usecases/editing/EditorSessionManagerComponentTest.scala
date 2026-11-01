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
        manager    <- buildManager(maybeMutex = Some(mutex))
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
        manager    <- buildManager(maybeMutex = Some(mutex), identity = identity)
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
      manager    <- buildManager(maybeMutex = Some(mutex), identity = identity)
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
        manager    <- buildManager(maybeMutex = Some(mutex))
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

  private def buildManager(maybeMutex: Option[Mutex[IO]] = None, identity: IdGenerator[IO] = IdGenerator.make[IO]) =
    maybeMutex.fold(Mutex[IO])(IO.pure).flatMap { mutex =>
      given Mutex[IO]       = mutex
      given IdGenerator[IO] = identity
      EditorSessionManager.make[IO](lease = 30L)
    }

  private def requireSession(result: RegisterResult) = result match
    case RegisterResult.Ready(session)     => IO.pure(session)
    case RegisterResult.CredentialRequired => IO.raiseError[EditorSession](new AssertionError("Expected registered session"))

  private def requireConnection(result: BindResult) = result match
    case BindResult.Active(connection) => IO.pure(connection)
    case _                             => IO.raiseError[EditorConnection](new AssertionError("Expected active connection"))
