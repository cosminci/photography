package photography.usecases.editing

import cats.effect.{Clock, Ref, Sync}
import cats.effect.std.Mutex
import cats.instances.uuid.*
import cats.syntax.all.*
import photography.capabilities.IdGenerator
import photography.domain.editing.*

import scala.concurrent.duration.*

trait EditorSessionManager[F[_]]:
  def register(owner: EditorId, alias: EditorAlias, maybeSession: Option[SessionId]): F[EditorSessionManager.RegisterResult]
  def bind(session: SessionId, tab: TabId): F[EditorSessionManager.BindResult]
  def unbind(connection: EditorConnection): F[EditorSessionManager.UnbindResult]
  def heartbeat(connection: EditorConnection): F[EditorSessionManager.HeartbeatResult]
  def read(connection: EditorConnection): F[EditorSessionManager.ReadResult]

object EditorSessionManager:
  def make[F[_]: Sync: IdGenerator: Mutex](lease: SessionLease): F[EditorSessionManager[F]] =
    Ref.of[F, EditorSessionState](EditorSessionState(Map.empty, Set.empty)).map(new LiveEditorSessionManager(_, lease))

  final private class LiveEditorSessionManager[F[_]: Sync: IdGenerator: Mutex](state: Ref[F, EditorSessionState], lease: SessionLease)
      extends EditorSessionManager[F]:
    override def register(owner: EditorId, alias: EditorAlias, maybeSession: Option[SessionId]): F[RegisterResult] =
      summon[Mutex[F]].lock.surround {
        for
          now     <- Clock[F].monotonic
          current <- expire(now)
          result  <- current.sessions.get(owner) match
            case Some(session) if maybeSession.contains(session.id) => RegisterResult.Ready(session).pure[F]
            case Some(_)                                            => RegisterResult.CredentialRequired.pure[F]
            case None                                               => create(owner, alias, now)
        yield result
      }

    override def bind(session: SessionId, tab: TabId): F[BindResult] =
      summon[Mutex[F]].lock.surround {
        for
          now     <- Clock[F].monotonic
          current <- expire(now)
          result  <- current.sessions.values.find(_.id.value === session.value) match
            case None                                                                                          => BindResult.StaleSession.pure[F]
            case Some(existing) if existing.maybeConnection.exists(binding => binding.tab.value =!= tab.value) =>
              BindResult.Blocked(existing.owner, existing.alias).pure[F]
            case Some(existing) => connect(existing, tab)
        yield result
      }

    override def unbind(connection: EditorConnection): F[UnbindResult] =
      summon[Mutex[F]].lock.surround {
        inspect(connection).flatMap {
          case Left(failure)  => UnbindResult.Rejected(failure).pure[F]
          case Right(session) => save(session.copy(maybeConnection = None)).as(UnbindResult.Released)
        }
      }

    override def heartbeat(connection: EditorConnection): F[HeartbeatResult] =
      summon[Mutex[F]].lock.surround {
        for
          now     <- Clock[F].monotonic
          current <- expire(now)
          result  <- findConnection(current, connection) match
            case Left(failure)  => HeartbeatResult.Rejected(failure).pure[F]
            case Right(session) => renew(session, now)
        yield result
      }

    override def read(connection: EditorConnection): F[ReadResult] =
      summon[Mutex[F]].lock.surround {
        inspect(connection).map {
          case Left(failure)  => ReadResult.Rejected(failure)
          case Right(session) => ReadResult.Current(session)
        }
      }

    private def create(owner: EditorId, alias: EditorAlias, now: FiniteDuration) =
      for
        id <- IdGenerator[F].generateId
        session = EditorSession(SessionId(id), owner, alias, now, now + lease.seconds, maybeConnection = None)
        _ <- save(session)
      yield RegisterResult.Ready(session)

    private def connect(session: EditorSession, tab: TabId) =
      for
        generation <- IdGenerator[F].generateId
        connection = EditorConnection(session.id, tab, ConnectionGeneration(generation))
        _ <- save(session.copy(maybeConnection = Some(connection)))
      yield BindResult.Active(connection)

    private def renew(session: EditorSession, now: FiniteDuration) =
      val renewed = session.copy(lastHeartbeat = now, expiresAt = now + lease.seconds)
      save(renewed).as(HeartbeatResult.Alive(renewed))

    private def inspect(connection: EditorConnection) =
      for
        now     <- Clock[F].monotonic
        current <- expire(now)
      yield findConnection(current, connection)

    private def findConnection(current: EditorSessionState, connection: EditorConnection) =
      current.sessions.values.find(_.id.value === connection.session.value) match
        case None                                                           => Failure.StaleSession.asLeft[EditorSession]
        case Some(session) if !session.maybeConnection.contains(connection) => Failure.ObsoleteConnection.asLeft[EditorSession]
        case Some(session)                                                  => session.asRight[Failure]

    private def save(session: EditorSession) =
      state.update(current => current.copy(sessions = current.sessions.updated(session.owner, session)))

    private def expire(now: FiniteDuration) =
      state.updateAndGet { current =>
        val sessions = current.sessions.filter { case (_, session) => now < session.expiresAt }
        current.copy(sessions = sessions)
      }

  enum RegisterResult:
    case Ready(session: EditorSession)
    case CredentialRequired

  enum BindResult:
    case Active(connection: EditorConnection)
    case Blocked(owner: EditorId, alias: EditorAlias)
    case StaleSession

  enum UnbindResult:
    case Released
    case Rejected(failure: Failure)

  enum HeartbeatResult:
    case Alive(session: EditorSession)
    case Rejected(failure: Failure)

  enum ReadResult:
    case Current(session: EditorSession)
    case Rejected(failure: Failure)

  enum Failure:
    case StaleSession
    case ObsoleteConnection
