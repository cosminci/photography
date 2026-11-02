package photography.usecases.editing

import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import photography.domain.editing.*
import photography.domain.album.AlbumId
import photography.ports.album.AlbumCompositionRepository
import photography.ports.album.AlbumCompositionRepository.CompositionResult
import photography.usecases.editing.EditorSessionManager.{AuthorizationResult, ConnectionAuthorizationResult}

trait EditingAccess[F[_]]:
  def run[A](connection: EditorConnection, protection: EditingAccess.Protection, maybeGrant: Option[GrantId])(write: F[A]): F[EditingAccess.Result[A]]

object EditingAccess:
  def make[F[_]: MonadCancelThrow: Mutex](
      sessions: EditorSessionManager[F],
      membership: AlbumCompositionRepository.MembershipRead[F]
  ): EditingAccess[F] = new LiveEditingAccess(sessions, membership)

  final private class LiveEditingAccess[F[_]: MonadCancelThrow: Mutex](
      sessions: EditorSessionManager[F],
      membership: AlbumCompositionRepository.MembershipRead[F]
  ) extends EditingAccess[F]:
    override def run[A](connection: EditorConnection, protection: Protection, maybeGrant: Option[GrantId])(write: F[A]): F[Result[A]] =
      summon[Mutex[F]].lock.surround:
        MonadCancelThrow[F].uncancelable: _ =>
          protection match
            case Protection.ActiveTab =>
              sessions.authorizeConnection(connection).flatMap:
                case ConnectionAuthorizationResult.Authorized       => write.map(Result.Completed(_))
                case ConnectionAuthorizationResult.Rejected(reason) => Result.Rejected[A](reason).pure[F]
            case Protection.OwnAlbumOpening(album) =>
              sessions.authorizeConnection(connection).flatMap:
                case ConnectionAuthorizationResult.Rejected(reason) => Result.Rejected[A](reason).pure[F]
                case ConnectionAuthorizationResult.Authorized       =>
                  membership.composition(album).flatMap:
                    case CompositionResult.Found(members) =>
                      admit(connection, EditingTarget.Spread(members.head.id), maybeGrant)(write)
                    case CompositionResult.AlbumNotFound => Result.AlbumNotFound[A]().pure[F]
                    case CompositionResult.Failed(_)     => Result.Failed[A]().pure[F]
            case Protection.OwnSpread(spread)      => admit(connection, spread, maybeGrant)(write)
            case Protection.ChangeSpreads(spreads) =>
              complete(sessions.authorizeAffected(connection, spreads, maybeGrant))(write)

    private def admit[A](connection: EditorConnection, spread: EditingTarget, maybeGrant: Option[GrantId])(write: F[A]) =
      val authorization = maybeGrant match
        case Some(grant) => sessions.authorizeTarget(connection, spread, grant)
        case None        => AuthorizationResult.Rejected(EditorSessionManager.Failure.StaleGrant).pure[F]
      complete(authorization)(write)

    private def complete[A](authorization: F[AuthorizationResult])(write: F[A]) =
      authorization.flatMap:
        case AuthorizationResult.Authorized               => write.map(Result.Completed(_))
        case AuthorizationResult.Rejected(reason)         => Result.Rejected[A](reason).pure[F]
        case AuthorizationResult.Blocked(_, owner, alias) => Result.Blocked[A](owner, alias).pure[F]

  enum Protection:
    case ActiveTab
    case OwnSpread(spread: EditingTarget)
    case OwnAlbumOpening(album: AlbumId)
    case ChangeSpreads(spreads: Set[EditingTarget])

  enum Result[A]:
    case Completed(value: A)
    case Rejected(reason: EditorSessionManager.Failure)
    case Blocked(owner: EditorId, alias: EditorAlias)
    case AlbumNotFound()
    case Failed()
