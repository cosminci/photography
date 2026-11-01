package photography.usecases.editing

import cats.effect.IO
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
  private given IdGenerator[IO] = IdGenerator.make[IO]

  private val owner              = EditorId(new UUID(0L, 1L))
  private val tab                = TabId(new UUID(0L, 2L))
  private val duplicate          = TabId(new UUID(0L, 3L))
  private val alias: EditorAlias = "Cosmin"

  test("should require a nonblank identification alias"):
    assert(" \t\n".refineEither[EditorAliasConstraint].isLeft)
    assertEquals("Cosmin".refineEither[EditorAliasConstraint], alias.asRight[String])

  test("should resume only with the live private credential without extending its deadline"):
    for
      manager    <- buildManager
      registered <- manager.register(owner, alias, maybeSession = None)
      session    <- requireSession(registered)
      denied     <- manager.register(owner, alias, maybeSession = None)
      resumed    <- manager.register(owner, alias, maybeSession = Some(session.id))
    yield
      assertEquals(denied, RegisterResult.CredentialRequired)
      assertEquals(resumed, RegisterResult.Ready(session))

  test("should block a different tab without exposing private session credentials"):
    for
      manager    <- buildManager
      registered <- manager.register(owner, alias, maybeSession = None)
      session    <- requireSession(registered)
      active     <- manager.bind(session.id, tab).flatMap(requireConnection)
      blocked    <- manager.bind(session.id, duplicate)
    yield
      assertEquals(active, EditorConnection(session.id, tab, active.generation))
      assertEquals(blocked, BindResult.Blocked(owner, alias))

  test("should fence old requests and finalizers when the same document reconnects"):
    for
      manager         <- buildManager
      registered      <- manager.register(owner, alias, maybeSession = None)
      session         <- requireSession(registered)
      first           <- manager.bind(session.id, tab).flatMap(requireConnection)
      second          <- manager.bind(session.id, tab).flatMap(requireConnection)
      oldHeartbeat    <- manager.heartbeat(first)
      oldCleanup      <- manager.unbind(first)
      duplicateResult <- manager.bind(session.id, duplicate)
      current         <- manager.heartbeat(second).flatMap(requireAlive)
    yield
      assertNotEquals(first.generation.value, second.generation.value)
      assertEquals(oldHeartbeat, HeartbeatResult.Rejected(Failure.ObsoleteConnection))
      assertEquals(oldCleanup, UnbindResult.Rejected(Failure.ObsoleteConnection))
      assertEquals(duplicateResult, BindResult.Blocked(owner, alias))
      assertEquals(
        current,
        session.copy(lastHeartbeat = current.lastHeartbeat, expiresAt = current.lastHeartbeat + 30.seconds, maybeConnection = Some(second))
      )

  test("should release only the tab binding and allow reload to resume the live session"):
    for
      manager    <- buildManager
      registered <- manager.register(owner, alias, maybeSession = None)
      session    <- requireSession(registered)
      connection <- manager.bind(session.id, tab).flatMap(requireConnection)
      released   <- manager.unbind(connection)
      resumed    <- manager.register(owner, alias, maybeSession = Some(session.id))
      reloaded   <- manager.bind(session.id, duplicate).flatMap(requireConnection)
      old        <- manager.heartbeat(connection)
    yield
      assertEquals(released, UnbindResult.Released)
      assertEquals(resumed, RegisterResult.Ready(session.copy(maybeConnection = None)))
      assertEquals(reloaded.session, session.id)
      assertEquals(old, HeartbeatResult.Rejected(Failure.ObsoleteConnection))

  Vector(29.seconds, 30.seconds).foreach { elapsed =>
    test(s"should enforce the session expiry boundary at $elapsed"):
      TestControl.executeEmbed {
        for
          manager    <- buildManager
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
        manager     <- buildManager
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
      manager     <- buildManager
      registered  <- manager.register(owner, alias, maybeSession = None)
      session     <- requireSession(registered)
      connection  <- manager.bind(session.id, tab).flatMap(requireConnection)
      restarted   <- buildManager
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
      manager    <- buildManager
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
      manager <- buildManager
      results <- List.fill(2)(manager.register(owner, alias, maybeSession = None)).parSequence
    yield
      val sessions = results.collect { case RegisterResult.Ready(session) => session }
      val rejected = results.collect { case RegisterResult.CredentialRequired => RegisterResult.CredentialRequired }
      assertEquals(sessions.map(_.owner), List(owner))
      assertEquals(rejected, List(RegisterResult.CredentialRequired))

  test("should allow distinct browsers to use the same display alias"):
    val other = EditorId(new UUID(0L, 4L))

    for
      manager <- buildManager
      first   <- manager.register(owner, alias, maybeSession = None).flatMap(requireSession)
      second  <- manager.register(other, alias, maybeSession = None).flatMap(requireSession)
    yield
      assertNotEquals(first.id, second.id)
      assertEquals(List(first.owner, second.owner), List(owner, other))
      assertEquals(List(first.alias, second.alias), List(alias, alias))

  test("should not revive an expired session through binding or release retries"):
    TestControl.executeEmbed {
      for
        manager      <- buildManager
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

  private def buildManager =
    Mutex[IO].flatMap { mutex =>
      given Mutex[IO] = mutex
      EditorSessionManager.make[IO](lease = 30L)
    }

  private def requireSession(result: RegisterResult) = result match
    case RegisterResult.Ready(session)     => IO.pure(session)
    case RegisterResult.CredentialRequired => IO.raiseError[EditorSession](new AssertionError("Expected registered session"))

  private def requireConnection(result: BindResult) = result match
    case BindResult.Active(connection) => IO.pure(connection)
    case _                             => IO.raiseError[EditorConnection](new AssertionError("Expected active connection"))

  private def requireAlive(result: HeartbeatResult) = result match
    case HeartbeatResult.Alive(session) => IO.pure(session)
    case HeartbeatResult.Rejected(_)    => IO.raiseError[EditorSession](new AssertionError("Expected live heartbeat"))
