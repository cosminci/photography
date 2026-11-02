package photography.adapters.http

import cats.syntax.all.*
import org.http4s.Request
import org.typelevel.ci.CIString
import photography.domain.editing.*
import photography.usecases.editing.EditorSessionManager.Failure
import java.util.UUID

private[http] object EditorCredentials:
  def readConnection[F[_]](request: Request[F]): Either[String, EditorConnection] =
    (
      requiredId(request, "X-Editor-Session"),
      requiredId(request, "X-Editor-Tab"),
      requiredId(request, "X-Editor-Generation")
    ).mapN((session, tab, generation) => EditorConnection(SessionId(session), TabId(tab), ConnectionGeneration(generation)))

  def requiredId[F[_]](request: Request[F], name: String): Either[String, UUID] =
    optionalId(request, name).flatMap(_.toRight("missing_credentials"))

  def optionalId[F[_]](request: Request[F], name: String): Either[String, Option[UUID]] =
    request.headers.headers.filter(_.name === CIString(name)).map(_.value) match
      case Nil          => none[UUID].asRight[String]
      case value :: Nil => parseId(value).map(_.some)
      case _            => "invalid_credentials".asLeft[Option[UUID]]

  def parseId(value: String): Either[String, UUID] =
    Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value).toRight("invalid_credentials")

  def encodeFailure(reason: Failure): String = reason match
    case Failure.StaleSession       => "stale_session"
    case Failure.ObsoleteConnection => "obsolete_connection"
    case Failure.StaleTransition    => "stale_transition"
    case Failure.StaleGrant         => "stale_grant"
