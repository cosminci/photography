package photography.app.config

import cats.effect.IO
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import io.github.iltotore.iron.pureconfig.given
import photography.usecases.album.AlbumPageSize
import photography.domain.album.{PhotoSource, ThumbnailQuality}
import pureconfig.ConfigReader
import pureconfig.ConfigSource
import pureconfig.generic.semiauto.deriveEnumerationReader
import pureconfig.module.catseffect.syntax.*
import pureconfig.module.ip4s.*

import java.nio.file.Path

final case class AppConfig(sqlite: SqliteConfig, albums: AlbumsConfig, thumbnails: ThumbnailsConfig, http: HttpServerConfig) derives ConfigReader

object AppConfig:

  def make: IO[AppConfig] =
    ConfigSource.defaultApplication.at("photography").loadF[IO, AppConfig]()

final case class SqliteConfig(path: Path, connectionAcquisitionThreads: Int, maxSessions: Int) derives ConfigReader

final case class AlbumsConfig(pageSize: AlbumPageSize, photoRoot: PhotoSource) derives ConfigReader

final case class ThumbnailsConfig(root: Path, quality: ThumbnailQuality)

object ThumbnailsConfig:
  // $COVERAGE-OFF$
  given ConfigReader[ThumbnailsConfig] =
    ConfigReader.forProduct2("root", "quality")(ThumbnailsConfig.apply)(using
      ConfigReader[Path].ensure(
        root => root.isAbsolute && root.normalize().toString === root.toString,
        _ => "Expected an absolute normalized thumbnail root"
      ),
      deriveEnumerationReader[ThumbnailQuality]
    )
  // $COVERAGE-ON$

final case class HttpServerConfig(host: Host, port: Port) derives ConfigReader
