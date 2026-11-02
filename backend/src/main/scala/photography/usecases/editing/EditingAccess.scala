package photography.usecases.editing

import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import photography.domain.editing.*
import photography.usecases.editing.EditorSessionManager.{AuthorizationResult, ConnectionAuthorizationResult}

trait EditingAccess[F[_]]:
  def run[A](connection: EditorConnection, protection: EditingAccess.Protection, maybeGrant: Option[GrantId])(write: F[A]): F[EditingAccess.Result[A]]

object EditingAccess:
  def make[F[_]: MonadCancelThrow: Mutex](sessions: EditorSessionManager[F]): EditingAccess[F] = new LiveEditingAccess(sessions)

  final private class LiveEditingAccess[F[_]: MonadCancelThrow: Mutex](sessions: EditorSessionManager[F]) extends EditingAccess[F]:
    override def run[A](connection: EditorConnection, protection: Protection, maybeGrant: Option[GrantId])(write: F[A]): F[Result[A]] =
      summon[Mutex[F]].lock.surround:
        MonadCancelThrow[F].uncancelable: _ =>
          val authorization = protection match
            case Protection.ActiveTab =>
              sessions.authorizeConnection(connection).map:
                case ConnectionAuthorizationResult.Authorized       => AuthorizationResult.Authorized
                case ConnectionAuthorizationResult.Rejected(reason) => AuthorizationResult.Rejected(reason)
            case Protection.OwnSpread(spread) => maybeGrant match
                case Some(grant) => sessions.authorizeTarget(connection, spread, grant)
                case None        => AuthorizationResult.Rejected(EditorSessionManager.Failure.StaleGrant).pure[F]
            case Protection.ChangeSpreads(spreads) => sessions.authorizeAffected(connection, spreads, maybeGrant)
          authorization.flatMap:
            case AuthorizationResult.Authorized               => write.map(Result.Completed(_))
            case AuthorizationResult.Rejected(reason)         => Result.Rejected[A](reason).pure[F]
            case AuthorizationResult.Blocked(_, owner, alias) => Result.Blocked[A](owner, alias).pure[F]

  enum Protection:
    case ActiveTab
    case OwnSpread(spread: EditingTarget)
    case ChangeSpreads(spreads: Set[EditingTarget])

  enum Result[A]:
    case Completed(value: A)
    case Rejected(reason: EditorSessionManager.Failure)
    case Blocked(owner: EditorId, alias: EditorAlias)
