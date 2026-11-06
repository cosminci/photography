package photography.adapters.filesystem

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.domain.album.*
import photography.ports.photo.ThumbnailStorage.*
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.{HexFormat, UUID}

final class FileThumbnailStorageSeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val location     = ThumbnailLocation(new UUID(0L, 1L))
  private val bytes        = Vector(1.toByte, 2.toByte).refineEither[ThumbnailBytesConstraint].fold(message => fail(message), identity)
  private val image        = EncodedThumbnail(bytes, ThumbnailDimensions(width = 1, height = 2), ThumbnailQuality.Standard)

  test("should store one exclusive thumbnail and idempotently remove only its final and partial artifacts"):
    buildDirectory.use: root =>
      val storage  = FileThumbnailStorage.make[IO](root)
      val file     = root.resolve(s"${location.value}.jpg")
      val partial  = root.resolve(s"${location.value}.tmp")
      val original = root.resolve("unrelated.jpg")
      val expected =
        Thumbnail(location, version = "a12871fee210fb8619291eaea194581cbd2531e4b23759d225f6806923f63222", image.dimensions, image.quality, bytes = 2)

      for
        stored    <- storage.storeThumbnail(location, image)
        duplicate <- storage.storeThumbnail(location, image)
        content   <- IO.blocking(Files.readAllBytes(file).toVector)
        _         <- IO.blocking(Files.write(partial, Array(3.toByte)))
        _         <- IO.blocking(Files.write(original, Array(4.toByte)))
        removed   <- storage.removeThumbnail(location)
        repeated  <- storage.removeThumbnail(location)
        remains   <- IO.blocking((Files.exists(file), Files.exists(partial), Files.readAllBytes(original).toVector))
        missing   <- FileThumbnailStorage.make[IO](root.resolve("absent")).removeThumbnail(location)
      yield
        assertEquals(stored, StoreResult.Stored(expected))
        assertEquals(failureCause(duplicate).getMessage, "requirement failed: Thumbnail location already exists")
        assertEquals(content, bytes: Vector[Byte])
        assertEquals(removed, RemoveResult.Removed)
        assertEquals(repeated, RemoveResult.Removed)
        assertEquals(remains, (false, false, Vector(4.toByte)))
        assertEquals(missing, RemoveResult.Removed)

  test("should report invalid storage and cleanup for relative and non-normalized roots"):
    buildDirectory.use: root =>
      val invalidRoots = Vector(Path.of("relative"), root.resolve("child/../other"))

      invalidRoots.traverse_ { invalidRoot =>
        for
          stored  <- FileThumbnailStorage.make[IO](invalidRoot).storeThumbnail(location, image)
          removed <- FileThumbnailStorage.make[IO](invalidRoot).removeThumbnail(location)
        yield
          assertEquals(failureCause(stored).getMessage, "Expected an absolute normalized thumbnail root")
          assertEquals(failureCause(removed).getMessage, "Expected an absolute normalized thumbnail root")
      }

  test("should publish each preset at its exact byte and dimension ceilings"):
    ThumbnailQuality.values.toVector.traverse_ { quality =>
      buildDirectory.use: root =>
        val dimensions = ThumbnailDimensions(width = quality.longestEdge, height = quality.longestEdge)
        val content    = Vector.fill(quality.maximumBytes)(1.toByte).refineUnsafe[ThumbnailBytesConstraint]
        val encoded    = EncodedThumbnail(bytes = content, dimensions = dimensions, quality = quality)
        val version    = IO.blocking:
          val digest = MessageDigest.getInstance("SHA-256").digest(content.toArray)
          HexFormat.of().formatHex(digest).refineUnsafe[Not[Blank]]

        for
          hash   <- version
          stored <- FileThumbnailStorage.make[IO](root).storeThumbnail(location, encoded)
          actual <- IO.blocking(Files.readAllBytes(root.resolve(s"${location.value}.jpg")).toVector)
        yield
          val expected = Thumbnail(location, version = hash, dimensions = dimensions, quality = quality, bytes = quality.maximumBytes)
          assertEquals(stored, StoreResult.Stored(expected))
          assertEquals(actual, content: Vector[Byte])
    }

  test("should reject preset ceiling plus one bytes and either dimension on every construction path"):
    ThumbnailQuality.values.foreach { quality =>
      val dimensions     = ThumbnailDimensions(width = quality.longestEdge, height = quality.longestEdge)
      val content        = Vector.fill(quality.maximumBytes)(1.toByte).refineUnsafe[ThumbnailBytesConstraint]
      val encoded        = EncodedThumbnail(bytes = content, dimensions = dimensions, quality = quality)
      val thumbnail      = Thumbnail(location, version = "version", dimensions = dimensions, quality = quality, bytes = quality.maximumBytes)
      val excessiveCount = quality.maximumBytes + 1
      val _              = intercept[IllegalArgumentException]:
        EncodedThumbnail(bytes = (content :+ 1.toByte).refineUnsafe[ThumbnailBytesConstraint], dimensions = dimensions, quality = quality)
      val _ = intercept[IllegalArgumentException]:
        thumbnail.copy(bytes = excessiveCount.refineUnsafe[ThumbnailByteCountConstraint])
      val _ = intercept[IllegalArgumentException]:
        encoded.copy(bytes = (content :+ 1.toByte).refineUnsafe[ThumbnailBytesConstraint])
      val _ = intercept[IllegalArgumentException]:
        val tooMany = excessiveCount.refineUnsafe[ThumbnailByteCountConstraint]
        Thumbnail(location, version = "version", dimensions = dimensions, quality = quality, bytes = tooMany)
      Vector(true, false).foreach { width =>
        val _ = intercept[IllegalArgumentException]:
          val larger  = (quality.longestEdge + 1).refineUnsafe[ThumbnailPixelCountConstraint]
          val invalid = if width then dimensions.copy(width = larger) else dimensions.copy(height = larger)
          encoded.copy(dimensions = invalid)
        val _ = intercept[IllegalArgumentException]:
          val larger  = (quality.longestEdge + 1).refineUnsafe[ThumbnailPixelCountConstraint]
          val invalid = if width then dimensions.copy(width = larger) else dimensions.copy(height = larger)
          thumbnail.copy(dimensions = invalid)
      }
      val _ = intercept[IllegalArgumentException]:
        new EncodedThumbnail(bytes = (content :+ 1.toByte).refineUnsafe[ThumbnailBytesConstraint], dimensions = dimensions, quality = quality)
      val _ = intercept[IllegalArgumentException]:
        val tooMany = excessiveCount.refineUnsafe[ThumbnailByteCountConstraint]
        new Thumbnail(location, version = "version", dimensions = dimensions, quality = quality, bytes = tooMany)
    }

  test("should read the stored content version and distinguish absence from read failure"):
    buildDirectory.use: root =>
      val file                          = root.resolve(s"${location.value}.jpg")
      val storage                       = FileThumbnailStorage.make[IO](root)
      val version: String :| Not[Blank] = "a12871fee210fb8619291eaea194581cbd2531e4b23759d225f6806923f63222"

      for
        absent      <- storage.readThumbnail(location, version = version)
        _           <- storage.storeThumbnail(location, image)
        found       <- storage.readThumbnail(location, version = version)
        stale       <- storage.readThumbnail(location, version = "wrong-version")
        missingRoot <- FileThumbnailStorage.make[IO](root.resolve("absent")).readThumbnail(location, version = version)
        invalidRoot <- FileThumbnailStorage.make[IO](file).readThumbnail(location, version = version)
        relative    <- FileThumbnailStorage.make[IO](Path.of("relative")).readThumbnail(location, version = version)
        _           <- IO.blocking(Files.write(file, Array.emptyByteArray))
        empty       <- storage.readThumbnail(location, version = version)
        _           <- IO.blocking(Files.write(file, Array.fill[Byte](300001)(1)))
        excessive   <- storage.readThumbnail(location, version = version)
      yield
        assertEquals(found, ReadResult.Read(bytes))
        assertEquals(Vector(absent, stale, missingRoot), Vector.fill(3)(ReadResult.NotFound))
        assertEquals(Vector(invalidRoot, relative, empty, excessive), Vector.fill(4)(ReadResult.Failed))

  test("should reject a managed filename pointing outside its configured root"):
    buildDirectory.use: root =>
      val nested  = root.resolve("managed")
      val outside = root.resolve("original.jpg")
      val link    = nested.resolve(s"${location.value}.jpg")

      for
        _      <- IO.blocking(Files.createDirectory(nested))
        _      <- IO.blocking(Files.write(outside, bytes.toArray))
        _      <- IO.blocking(Files.createSymbolicLink(link, outside))
        result <- FileThumbnailStorage.make[IO](nested).readThumbnail(location, version = "version")
        _      <- IO.blocking(Files.delete(link))
        _      <- IO.blocking(Files.delete(nested))
        _      <- IO.blocking(Files.delete(outside))
      yield assertEquals(result, ReadResult.NotFound)

  private def failureCause(result: StoreResult | RemoveResult) = result match
    case StoreResult.Failed(cause)  => cause
    case RemoveResult.Failed(cause) => cause
    case other                      => fail(s"Expected a storage failure, got $other")

  private def buildDirectory =
    Resource.make(IO.blocking(Files.createTempDirectory(Path.of("target"), "thumbnails-").toAbsolutePath)): root =>
      val artifacts = Vector(
        s"${location.value}.jpg",
        s"${location.value}.tmp",
        "unrelated.jpg"
      )
      artifacts.traverse_ { name =>
        IO.blocking(Files.deleteIfExists(root.resolve(name))).void
      } >> IO.blocking(Files.deleteIfExists(root)).void
