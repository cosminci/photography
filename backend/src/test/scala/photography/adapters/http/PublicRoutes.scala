package photography.adapters.http

import cats.effect.IO
import org.http4s.HttpRoutes
import photography.domain.editing.{EditorConnection, GrantId}
import photography.usecases.editing.EditingAccess

object PublicRoutes:
  def apply(definitions: Routes[IO]): HttpRoutes[IO] =
    val forbidden = new EditingAccess[IO]:
      override def run[A](
          connection: EditorConnection,
          protection: EditingAccess.Protection,
          maybeGrant: Option[GrantId]
      )(write: IO[A]): IO[EditingAccess.Result[A]] =
        IO.raiseError[EditingAccess.Result[A]](new AssertionError("Unexpected editor-protected route"))
    Routes.make(forbidden)(definitions)
