package photography.usecases.editing

import cats.effect.{Clock, Ref, Sync}
import cats.effect.std.Mutex
import cats.instances.bigInt.*
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
  def select(connection: EditorConnection, revision: TargetRevision, target: EditingTarget): F[EditorSessionManager.SelectResult]
  def release(connection: EditorConnection, revision: TargetRevision, grant: GrantId): F[EditorSessionManager.ReleaseResult]
  def renewTarget(connection: EditorConnection, grant: GrantId): F[EditorSessionManager.RenewTargetResult]
  def readTarget(connection: EditorConnection): F[EditorSessionManager.ReadTargetResult]

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

    override def select(connection: EditorConnection, revision: TargetRevision, target: EditingTarget): F[SelectResult] =
      summon[Mutex[F]].lock.surround {
        for
          now     <- Clock[F].monotonic
          current <- expire(now)
          result  <- findTransition(current, connection, revision) match
            case Left(failure)  => SelectResult.Rejected(failure).pure[F]
            case Right(session) => acquire(current, session, connection, target)
        yield result
      }

    override def release(connection: EditorConnection, revision: TargetRevision, grant: GrantId): F[ReleaseResult] =
      summon[Mutex[F]].lock.surround {
        for
          now     <- Clock[F].monotonic
          current <- expire(now)
          result  <- findTransition(current, connection, revision).flatMap(session => findGrant(current, session, grant).as(session)) match
            case Left(failure)  => ReleaseResult.Rejected(failure).pure[F]
            case Right(session) =>
              changeTarget(session, maybeLock = None).as(ReleaseResult.Released(session.targetRevision.next))
        yield result
      }

    override def renewTarget(connection: EditorConnection, grant: GrantId): F[RenewTargetResult] =
      summon[Mutex[F]].lock.surround {
        for
          now     <- Clock[F].monotonic
          current <- expire(now)
          result  <- findConnection(current, connection).flatMap(session => findGrant(current, session, grant)) match
            case Left(failure) => RenewTargetResult.Rejected(failure).pure[F]
            case Right(lock)   => renewLock(lock, now)
        yield result
      }

    override def readTarget(connection: EditorConnection): F[ReadTargetResult] =
      summon[Mutex[F]].lock.surround {
        for
          now     <- Clock[F].monotonic
          current <- expire(now)
        yield findConnection(current, connection) match
          case Left(failure)  => ReadTargetResult.Rejected(failure)
          case Right(session) => ReadTargetResult.Current(session.targetRevision, current.locks.find(_.session.value === session.id.value))
      }

    private def acquire(current: EditorSessionState, session: EditorSession, connection: EditorConnection, target: EditingTarget) =
      val maybeOwner = current.locks.toList.flatMap { lock =>
        current.sessions.values.find(_.id.value === lock.session.value).map(existing => (lock, existing))
      }.find { case (lock, existing) =>
        existing.id.value =!= session.id.value && Set(target).contains(lock.target)
      }
      maybeOwner match
        case Some((lock, existing)) =>
          changeTarget(
            session,
            maybeLock = None
          ).as(SelectResult.Blocked(existing.owner, existing.alias, lock.expiresAt, session.targetRevision.next))
        case None =>
          IdGenerator[F].generateId.flatMap(id => finishAcquisition(session.targetRevision, connection, target, GrantId(id)))

    private def finishAcquisition(revision: TargetRevision, connection: EditorConnection, target: EditingTarget, grant: GrantId) =
      for
        now     <- Clock[F].monotonic
        current <- expire(now)
        result  <- findTransition(current, connection, revision) match
          case Left(failure)  => SelectResult.Rejected(failure).pure[F]
          case Right(session) =>
            val lock = EditingLock(target, session.id, grant, now + lease.seconds)
            changeTarget(session, Some(lock)).as(SelectResult.Owned(lock, session.targetRevision.next))
      yield result

    private def changeTarget(session: EditorSession, maybeLock: Option[EditingLock]) =
      state.update { current =>
        val sessions = current.sessions.updated(session.owner, session.copy(targetRevision = session.targetRevision.next))
        val locks    = current.locks.filterNot(_.session.value === session.id.value) ++ maybeLock
        current.copy(sessions = sessions, locks = locks)
      }

    private def renewLock(lock: EditingLock, now: FiniteDuration) =
      val renewed = lock.copy(expiresAt = now + lease.seconds)
      state.update(current => current.copy(locks = current.locks - lock + renewed)).as(RenewTargetResult.Renewed(renewed))

    private def findTransition(current: EditorSessionState, connection: EditorConnection, revision: TargetRevision) =
      findConnection(current, connection).flatMap { session =>
        if session.targetRevision.value === revision.value then session.asRight[Failure]
        else Failure.StaleTransition.asLeft[EditorSession]
      }

    private def findGrant(current: EditorSessionState, session: EditorSession, grant: GrantId) =
      current.locks.find(lock => lock.session.value === session.id.value && lock.grant.value === grant.value).toRight(Failure.StaleGrant)

    private def create(owner: EditorId, alias: EditorAlias, now: FiniteDuration) =
      for
        id <- IdGenerator[F].generateId
        session = EditorSession(SessionId(id), owner, alias, now, now + lease.seconds, maybeConnection = None, TargetRevision.initial)
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
        val liveSessions = current.sessions.filter { case (_, session) => now < session.expiresAt }
        val sessions     = liveSessions.map { case (owner, session) =>
          val expiredTarget = current.locks.exists(lock => lock.session.value === session.id.value && now >= lock.expiresAt)
          owner -> (if expiredTarget then session.copy(targetRevision = session.targetRevision.next) else session)
        }
        val locks = current.locks.filter(lock => now < lock.expiresAt && sessions.values.exists(_.id.value === lock.session.value))
        current.copy(sessions = sessions, locks = locks)
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

  enum SelectResult:
    case Owned(lock: EditingLock, revision: TargetRevision)
    case Blocked(owner: EditorId, alias: EditorAlias, expiresAt: FiniteDuration, revision: TargetRevision)
    case Rejected(failure: Failure)

  enum ReleaseResult:
    case Released(revision: TargetRevision)
    case Rejected(failure: Failure)

  enum RenewTargetResult:
    case Renewed(lock: EditingLock)
    case Rejected(failure: Failure)

  enum ReadTargetResult:
    case Current(revision: TargetRevision, maybeLock: Option[EditingLock])
    case Rejected(failure: Failure)

  enum Failure:
    case StaleSession
    case ObsoleteConnection
    case StaleTransition
    case StaleGrant
