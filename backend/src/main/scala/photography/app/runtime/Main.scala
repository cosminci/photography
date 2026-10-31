package photography.app.runtime

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import fs2.io.net.Network
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits.*
import photography.adapters.http.{AlbumApi, AlbumSpreadApi, AlbumCompositionApi, CountryApi, ChapterApi, HealthRoutes, SpreadTemplateApi, StaticSite}
import photography.adapters.filesystem.FileThumbnailStorage
import photography.adapters.http.{AlbumPhotoEventsApi, ThumbnailApi}
import photography.adapters.http.PhotoPlacementApi
import photography.adapters.http.ChapterCompositionApi
import photography.adapters.http.PhotoSourceApi
import photography.adapters.http.GearApi
import photography.adapters.sqlite.photo.SqlitePhotoRepository
import photography.app.config.{AppConfig, HttpServerConfig}
import photography.capabilities.{Clock, IdGenerator, Transaction}
import photography.usecases.photo.PhotoWriteRecovery
import photography.ports.photo.ThumbnailStorage
import scala.concurrent.duration.*

object Main extends IOApp.Simple:

  implicit val logger: StructuredLogger[IO] = Slf4jLogger.getLoggerFromName("[Photography]")
  private given IdGenerator[IO]             = IdGenerator.make[IO]
  private given Clock[IO]                   = Clock.make[IO]

  override def run: IO[Unit] =
    AppConfig.make.flatMap: config =>
      AppResourcesBuilder.make(config.sqlite).build[IO].use: resources =>
        given Transaction[ConnectionIO, IO] with
          override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(resources.sqlite)
          override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(resources.sqlite)
        val photos     = SqlitePhotoRepository.make
        val thumbnails = FileThumbnailStorage.make[IO](config.thumbnails.root)
        val recovery   = PhotoWriteRecovery.make(photos, thumbnails)
        val recover    = recovery.recover.flatMap:
          case PhotoWriteRecovery.RecoveryResult.Recovered       => IO.unit
          case failure: PhotoWriteRecovery.RecoveryResult.Failed =>
            IO.raiseError[Unit](new IllegalStateException(s"Photo write recovery failed: ${failure.issues}"))
        val pageSize      = config.albums.pageSize
        val photoRoot     = config.albums.photoRoot
        val quality       = config.thumbnails.quality
        val buildManagers = ProgramsBuilder.make[IO](resources.sqlite, pageSize, photos, thumbnails, quality).build
        val serve         = buildManagers.use: managers =>
          val server = buildHttpApp(managers, thumbnails, photoRoot).flatMap(httpApp => buildHttpServer(config.http, httpApp))
          server.useForever.void
        for
          _ <- resources.flyway.migrate
          _ <- recover
          _ <- logger.info(s"Migrations applied; serving on ${config.http.host}:${config.http.port}")
          _ <- serve
        yield ()

  private def buildHttpApp(managers: Programs[IO], thumbnails: ThumbnailStorage[IO], photoRoot: photography.domain.album.PhotoSource) =
    val albums       = AlbumApi.make(managers.albums, managers.metadata).routes
    val chapters     = ChapterApi.make(managers.inventories, managers.chapters, managers.chapterMetadata, managers.chapterGear).routes
    val thumbnailApi = ThumbnailApi.make(thumbnails).routes
    val events       = AlbumPhotoEventsApi.make(managers.photoEvents).routes
    val placements   = PhotoPlacementApi.make(managers.spreads, managers.coverPlacements)
    val api          = HealthRoutes.routes[IO] <+> albums <+> chapters <+> events <+> thumbnailApi <+>
      AlbumCompositionApi.make(managers.membership) <+> CountryApi.make(managers.countries) <+> PhotoSourceApi.make(photoRoot) <+>
      SpreadTemplateApi.make(managers.templates).routes <+> GearApi.make(managers.gear).routes <+>
      ChapterCompositionApi.make(managers.spreads) <+> AlbumSpreadApi.make(managers.spreads).routes <+> placements
    StaticSite.make(api, assetRoot = "frontend").map(_.app)

  private def buildHttpServer(config: HttpServerConfig, app: HttpApp[IO]) =
    EmberServerBuilder.default[IO].withHost(config.host).withPort(config.port).withIdleTimeout(60.seconds).withHttpApp(app).build
