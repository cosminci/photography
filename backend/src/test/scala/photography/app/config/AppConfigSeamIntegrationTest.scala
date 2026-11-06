package photography.app.config

import munit.CatsEffectSuite
import cats.syntax.all.*
import pureconfig.ConfigSource
import java.nio.file.Path

final class AppConfigSeamIntegrationTest extends CatsEffectSuite:

  test("should load application configuration"):
    AppConfig.make.map(_ => ())

  test("should accept only normalized absolute thumbnail roots before startup"):
    Vector("/thumbnails", "thumbnails", "/thumbnails/child/../managed", "/thumbnails/./managed").foreach { root =>
      val loaded   = ConfigSource.string(s"""root = "$root"\nquality = standard""").load[ThumbnailsConfig]
      val expected = Option.when(root === "/thumbnails")(Path.of(root))

      assertEquals(loaded.toOption.map(_.root), expected)
    }
