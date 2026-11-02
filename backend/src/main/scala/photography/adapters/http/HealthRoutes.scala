package photography.adapters.http

import cats.effect.Async
import cats.syntax.applicative.*
import org.http4s.{MediaType, Request, Response, Status}
import photography.adapters.http.Routes.Route
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`

object HealthRoutes:

  def routes[F[_]: Async]: Routes[F] = new Routes[F]:
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    override def routes: PartialFunction[Request[F], Route[F, ?]] = {
      case GET -> Root / "health" =>
        Route.public(Response[F](Status.Ok).withEntity("""{"status":"ok"}""").putHeaders(`Content-Type`(MediaType.application.json)).pure[F])
    }
