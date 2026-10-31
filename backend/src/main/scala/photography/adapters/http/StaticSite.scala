package photography.adapters.http

import cats.effect.{Async, Resource}
import cats.data.{Kleisli, OptionT}
import cats.syntax.all.*
import org.http4s.{HttpApp, HttpRoutes, MediaType, Method, Request, Response, StaticFile, Status}
import org.http4s.headers.`Content-Type`
import org.http4s.server.middleware.DefaultHead
import org.http4s.server.staticcontent.ResourceServiceBuilder
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

trait StaticSite[F[_]]:
  def app: HttpApp[F]

object StaticSite:
  def make[F[_]: Async](api: HttpRoutes[F], assetRoot: String): Resource[F, StaticSite[F]] =
    Resource.fromAutoCloseable(Async[F].blocking(Executors.newCachedThreadPool())).map: executor =>
      new LiveStaticSite(api, assetRoot, ExecutionContext.fromExecutor(executor))

  final private class LiveStaticSite[F[_]: Async](api: HttpRoutes[F], assetRoot: String, acquisitionContext: ExecutionContext) extends StaticSite[F]:
    override val app: HttpApp[F] = (api <+> buildMissingApiRoutes <+> DefaultHead(buildFrontendRoutes)).orNotFound

    private def buildMissingApiRoutes = HttpRoutes.of[F]:
      case request if request.pathInfo.segments.headOption.exists(_.decoded() === "api") =>
        Response[F](Status.NotFound).withEntity("""{"code":"not_found"}""").putHeaders(`Content-Type`(MediaType.application.json)).pure[F]

    private def buildFrontendRoutes =
      val resources = ResourceServiceBuilder[F](assetRoot).toRoutes
      Kleisli { (request: Request[F]) =>
        val segments = request.pathInfo.segments.map(_.decoded())
        val reserved = segments.headOption.contains("health") || segments.toList === List("assets")
        if request.method =!= Method.GET || reserved then OptionT.none[F, Response[F]]
        else OptionT(Async[F].evalOn(resources(request).orElse(serveEntryPoint(request)).value, acquisitionContext))
      }

    private def serveEntryPoint(request: Request[F]) =
      val segments   = request.pathInfo.segments.map(_.decoded())
      val navigation = !segments.exists(_.contains(".")) && !segments.headOption.contains("assets")
      if navigation then StaticFile.fromResource(s"$assetRoot/index.html", req = request.some) else OptionT.none[F, Response[F]]
