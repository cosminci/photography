package photography.adapters.filesystem

import cats.arrow.FunctionK
import cats.effect.{Deferred, IO, Outcome, Ref, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.ImageIOTestResources
import photography.domain.album.*
import photography.ports.photo.ThumbnailStorage
import photography.ports.photo.PhotoStorage.*
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.nio.file.attribute.{FileTime, PosixFilePermission}
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.{HexFormat, Iterator as JavaIterator, List as JavaList, Locale, UUID}
import java.util.concurrent.{CompletableFuture, CompletionException, CountDownLatch}
import javax.imageio.{ImageIO, ImageReadParam, ImageReader, ImageTypeSpecifier}
import javax.imageio.metadata.IIOMetadata
import javax.imageio.spi.{IIORegistry, ImageReaderSpi}
import javax.imageio.stream.ImageInputStream
import scala.jdk.CollectionConverters.*

final class FilePhotoStorageSeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO]     = Slf4jLogger.getLogger[IO]
  private lazy val undatedJpeg = buildJpeg(maybeDate = None, orientation = 1, maybeFraction = None)
  private val imageIO          = ResourceTestLocalFixture("image-io", ImageIOTestResources.acquire)

  override def munitFixtures = List(imageIO)

  test("should enumerate only immediate regular JPEGs and count unsupported regular files"):
    buildDirectory.use: root =>
      val source   = buildSource(root)
      val names    = Vector("a.jpg", "B.JpEg", "note.txt", "nested")
      val expected = DiscoveryResult.Complete(
        originals = Vector(buildLocation(root, filename = "a.jpg"), buildLocation(root, filename = "B.JpEg")),
        unsupportedCount = 1
      )

      for
        empty <- buildStorage(root).discoverOriginals(source)
        _     <- IO.blocking(Files.write(root.resolve("a.jpg"), undatedJpeg))
        _     <- IO.blocking(Files.write(root.resolve("B.JpEg"), undatedJpeg))
        _     <- IO.blocking(Files.writeString(root.resolve("note.txt"), "unsupported"))
        _     <- IO.blocking(Files.createDirectory(root.resolve("nested")))
        _     <- IO.blocking(Files.write(root.resolve("nested/hidden.jpg"), undatedJpeg))
        found <- buildStorage(root).discoverOriginals(source)
        files <- IO.blocking(Files.list(root)).bracket(stream => IO.blocking(stream.count()))(stream => IO.blocking(stream.close()))
      yield
        assertEquals(empty, DiscoveryResult.Complete(originals = Vector.empty[OriginalPhotoLocation], unsupportedCount = 0))
        assertEquals(found, expected)
        assertEquals(files, names.size.toLong)

  test("should derive immutable content hash dimensions and camera-local dates from the same original without changing it"):
    buildDirectory.use: root =>
      val cases = Vector(
        (None, None),
        ("2024:02:29 23:59:58".some, LocalDateTime.of(2024, 2, 29, 23, 59, 58).some),
        ("2023:02:29 23:59:58".some, None),
        ("2024:13:01 00:00:00".some, None),
        ("0000:01:01 00:00:00".some, None),
        ("0000:00:00 00:00:00".some, None)
      )

      cases.traverse_ { case (maybeDate, maybeCaptured) =>
        val bytes    = buildJpeg(maybeDate = maybeDate, orientation = 1, maybeFraction = None)
        val path     = root.resolve("original.jpg")
        val content  = PhotoContent(bytes = bytes.toVector.refineUnsafe[MinLength[1]])
        val hash     = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).refineUnsafe[Not[Blank]]
        val metadata = PhotoMetadata(hash, maybeCapturedLocal = maybeCaptured, dimensions = PhotoDimensions(width = 3, height = 2))
        val expected = ReadResult.Read(content, metadata)

        for
          _      <- IO.blocking(Files.write(path, bytes))
          _      <- IO.blocking(Files.setLastModifiedTime(path, FileTime.fromMillis(123456000L)))
          before <- IO.blocking((Files.readAllBytes(path).toVector, Files.getLastModifiedTime(path)))
          read   <- buildStorage(root).readOriginal(buildLocation(root, filename = "original.jpg"))
          after  <- IO.blocking((Files.readAllBytes(path).toVector, Files.getLastModifiedTime(path)))
          files  <- IO.blocking(Files.list(root)).bracket(stream => IO.blocking(stream.count()))(stream => IO.blocking(stream.close()))
        yield
          assertEquals(read, expected)
          assertEquals(after, before)
          assertEquals(files, 1L)
      }

  test("should distinguish stable malformed JPEGs from filesystem read and discovery failures"):
    buildDirectory.use: root =>
      val storage   = buildStorage(root)
      val malformed = Vector(
        Array.emptyByteArray,
        Array(1.toByte, 2.toByte),
        undatedJpeg.take(100),
        undatedJpeg.dropRight(2)
      )
      val missing         = root.resolve("missing")
      val rejectMalformed = malformed.traverse_ { bytes =>
        IO.blocking(Files.write(root.resolve("bad.jpg"), bytes)) >>
          storage.readOriginal(buildLocation(root, filename = "bad.jpg")).map(result => assertEquals(result, ReadResult.Undecodable))
      }

      for
        discovery  <- storage.discoverOriginals(buildSource(missing))
        absent     <- storage.readOriginal(buildLocation(root, filename = "missing.jpg"))
        _          <- IO.blocking(Files.createDirectory(root.resolve("folder.jpg")))
        directory  <- storage.readOriginal(buildLocation(root, filename = "folder.jpg"))
        notFolder  <- storage.discoverOriginals(buildSource(root.resolve("folder.jpg/missing")))
        _          <- IO.blocking(Files.writeString(root.resolve("not-a-directory.txt"), "not a source"))
        fileSource <- storage.discoverOriginals(buildSource(root.resolve("not-a-directory.txt")))
        wrongKind  <- storage.readOriginal(buildLocation(root, filename = "not-a-directory.txt"))
        _          <- IO.blocking(Files.write(root.resolve("invalid:name.jpg"), undatedJpeg))
        incomplete <- storage.discoverOriginals(buildSource(root))
        _          <- rejectMalformed
      yield
        assertEquals(discovery, DiscoveryResult.Failed)
        assertEquals(absent, ReadResult.Failed)
        assertEquals(directory, ReadResult.Failed)
        assertEquals(notFolder, DiscoveryResult.Failed)
        assertEquals(fileSource, DiscoveryResult.Failed)
        assertEquals(wrongKind, ReadResult.Failed)
        assertEquals(incomplete, DiscoveryResult.Failed)

  test("should preserve EXIF display dimensions and valid fractional local time without timezone conversion"):
    buildDirectory.use: root =>
      val cases = Vector(
        (1, "123", PhotoDimensions(width = 3, height = 2), 123000000),
        (6, "123456789", PhotoDimensions(width = 2, height = 3), 123456789),
        (8, "invalid", PhotoDimensions(width = 2, height = 3), 0)
      )

      cases.traverse_ { case (orientation, fraction, dimensions, nanos) =>
        val bytes    = buildJpeg(maybeDate = "2024:02:29 23:59:58".some, orientation = orientation, maybeFraction = fraction.some)
        val content  = PhotoContent(bytes = bytes.toVector.refineUnsafe[MinLength[1]])
        val hash     = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).refineUnsafe[Not[Blank]]
        val date     = LocalDateTime.of(2024, 2, 29, 23, 59, 58, nanos)
        val expected = ReadResult.Read(content, PhotoMetadata(hash, maybeCapturedLocal = date.some, dimensions = dimensions))

        for
          _      <- IO.blocking(Files.write(root.resolve("dated.jpg"), bytes))
          result <- buildStorage(root).readOriginal(buildLocation(root, filename = "dated.jpg"))
        yield assertEquals(result, expected)
      }

  test("should retain explicit access failures when the filesystem denies directory or original reads"):
    buildDirectory.use: root =>
      val path                 = root.resolve("private.jpg")
      val storage              = buildStorage(root)
      val noPermissions        = Set.empty[PosixFilePermission].asJava
      val directoryPermissions = Resource.make(IO.blocking(Files.getPosixFilePermissions(root)))(permissions =>
        IO.blocking(Files.setPosixFilePermissions(root, permissions)).void
      )
      val filePermissions = Resource.make(IO.blocking(Files.getPosixFilePermissions(path)))(permissions =>
        IO.blocking(Files.setPosixFilePermissions(path, permissions)).void
      )

      IO.blocking(Files.write(path, undatedJpeg)) >> filePermissions.use: _ =>
        directoryPermissions.use: _ =>
          for
            _         <- IO.blocking(Files.setPosixFilePermissions(path, noPermissions))
            readable  <- IO.blocking(Files.isReadable(path))
            original  <- storage.readOriginal(buildLocation(root, filename = "private.jpg"))
            _         <- IO.blocking(Files.setPosixFilePermissions(root, noPermissions))
            listable  <- IO.blocking(Files.isReadable(root))
            discovery <- storage.discoverOriginals(buildSource(root))
          yield
            val wasRead = original match
              case _: ReadResult.Read => true
              case _                  => false
            val wasComplete = discovery match
              case _: DiscoveryResult.Complete => true
              case DiscoveryResult.Failed      => false
            assertEquals(wasRead, readable)
            assertEquals(wasComplete, listable)

  test("should not turn cancelled incomplete discovery into an empty successful scan"):
    buildDirectory.use: root =>
      for
        _      <- IO.blocking(Files.write(root.resolve("valid.jpg"), undatedJpeg))
        _      <- IO.blocking(Files.write(root.resolve("invalid:name.jpg"), undatedJpeg))
        result <- cancelIncompleteDiscovery(root)
      yield assertEquals(result, Outcome.canceled[IO, Throwable, DiscoveryResult])

  test("should ignore other EXIF dates and reject non-JPEG content despite a JPEG filename"):
    buildDirectory.use: root =>
      val originalDate  = buildJpeg(maybeDate = "2024:02:29 23:59:58".some, orientation = 1, maybeFraction = None)
      val digitizedDate = originalDate.updated(52, 4.toByte)
      val content       = PhotoContent(bytes = digitizedDate.toVector.refineUnsafe[MinLength[1]])
      val hash          = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(digitizedDate)).refineUnsafe[Not[Blank]]
      val metadata      = PhotoMetadata(hash, maybeCapturedLocal = None, dimensions = PhotoDimensions(width = 3, height = 2))
      val expected      = ReadResult.Read(content, metadata)
      val png           = new ByteArrayOutputStream()
      require(ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", png))

      for
        _       <- IO.blocking(Files.write(root.resolve("digitized.jpg"), digitizedDate))
        read    <- buildStorage(root).readOriginal(buildLocation(root, filename = "digitized.jpg"))
        _       <- IO.blocking(Files.write(root.resolve("not-jpeg.jpg"), png.toByteArray))
        notJpeg <- buildStorage(root).readOriginal(buildLocation(root, filename = "not-jpeg.jpg"))
      yield
        assertEquals(read, expected)
        assertEquals(notJpeg, ReadResult.Undecodable)

  test("should reject observable in-place and replacement mutations and read settled originals on retry"):
    buildDirectory.use: root =>
      val cases = Vector("same-size-restored-time", "replacement", "size-change")

      cases.traverse_ { mutation =>
        val path        = root.resolve("changing.jpg")
        val original    = buildJpeg(maybeDate = "2024:02:29 23:59:58".some, orientation = 1, maybeFraction = None)
        val replacement = buildJpeg(maybeDate = "2024:02:28 23:59:58".some, orientation = 1, maybeFraction = None)
        val change      = mutation match
          case "replacement" =>
            IO.blocking(Files.write(root.resolve("replacement.jpg"), original)) >>
              IO.blocking(Files.move(root.resolve("replacement.jpg"), path, StandardCopyOption.REPLACE_EXISTING)).void
          case "size-change" => IO.blocking(Files.write(path, original ++ Array(0.toByte))).void
          case _             => IO.blocking(Files.write(path, replacement)).void
        val settled = mutation match
          case "replacement" => original
          case "size-change" => original ++ Array(0.toByte)
          case _             => replacement
        val day      = if mutation === "same-size-restored-time" then 28 else 29
        val captured = LocalDateTime.of(2024, 2, day, 23, 59, 58)
        val hash     = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(settled)).refineUnsafe[Not[Blank]]
        val content  = PhotoContent(bytes = settled.toVector.refineUnsafe[MinLength[1]])
        val metadata = PhotoMetadata(hash, maybeCapturedLocal = captured.some, dimensions = PhotoDimensions(width = 3, height = 2))
        val expected = ReadResult.Read(content, metadata)
        val read     = buildStorage(root).readOriginal(buildLocation(root, filename = "changing.jpg"))

        for
          _      <- IO.blocking(Files.write(path, original))
          before <- IO.blocking(Files.getLastModifiedTime(path))
          result <- readDuringChange(read, change = change >> IO.blocking(Files.setLastModifiedTime(path, before)).void)
          retry  <- read
        yield
          assertEquals(result, ReadResult.Unstable, mutation)
          assertEquals(retry, expected)
      }

  test("should reuse the supplied managed thumbnail boundary"):
    val location = ThumbnailLocation(new UUID(0L, 7L))
    val image    = EncodedThumbnail(
      bytes = Vector(1.toByte).refineUnsafe[ThumbnailBytesConstraint],
      ThumbnailDimensions(width = 1, height = 1),
      ThumbnailQuality.Standard
    )
    val expected      = Thumbnail(location, version = "supplied-version", image.dimensions, image.quality, bytes = 1)
    val expectedCalls = Vector(ThumbnailCall.Store(location, image), ThumbnailCall.Read(location, expected.version), ThumbnailCall.Remove(location))

    Ref.of[IO, Vector[ThumbnailCall]](Vector.empty[ThumbnailCall]).flatMap: calls =>
      val storage = buildForwardingStorage(calls, expected)
      for
        stored      <- storage.storeThumbnail(location, image)
        read        <- storage.readThumbnail(location, version = expected.version)
        removed     <- storage.removeThumbnail(location)
        actualCalls <- calls.get
      yield
        assertEquals(stored, ThumbnailStorage.StoreResult.Stored(expected))
        assertEquals(read, ThumbnailStorage.ReadResult.Read(image.bytes))
        assertEquals(removed, ThumbnailStorage.RemoveResult.Failed)
        assertEquals(actualCalls, expectedCalls)

  test("should preserve decode failures when releasing the image reader also fails"):
    buildDirectory.use: root =>
      val reached = new CountDownLatch(0)
      val resume  = new CountDownLatch(0)
      val read    = buildStorage(root).readOriginal(buildLocation(root, filename = "original.jpg"))

      IO.blocking(Files.write(root.resolve("original.jpg"), undatedJpeg)) >>
        Vector(false, true).traverse_ { failRead =>
          buildPausedReader(reached, resume, failRead = failRead, failRelease = true)
            .use(_ => read).map: actual =>
              val expected = if failRead then ReadResult.Undecodable else ReadResult.Failed
              assertEquals(actual, expected)
        }

  test("should reject a JPEG when its image provider cannot construct a reader"):
    buildDirectory.use: root =>
      val reached = new CountDownLatch(0)
      val resume  = new CountDownLatch(0)
      val read    = buildStorage(root).readOriginal(buildLocation(root, filename = "original.jpg"))

      IO.blocking(Files.write(root.resolve("original.jpg"), undatedJpeg)) >>
        buildPausedReader(reached, resume, failConstruction = true).use(_ => read).map: actual =>
          assertEquals(actual, ReadResult.Undecodable)

  test("should retain a reader failure when its release reports the same exception"):
    buildDirectory.use: root =>
      val reached = new CountDownLatch(0)
      val resume  = new CountDownLatch(0)
      val failure = new CompletionException(new IllegalStateException("shared reader and release failure"))
      val channel = CompletableFuture.failedFuture[Unit](failure)
      val read    = buildStorage(root).readOriginal(buildLocation(root, filename = "original.jpg"))

      IO.blocking(Files.write(root.resolve("original.jpg"), undatedJpeg)) >>
        buildPausedReader(reached, resume, maybeSharedFailure = channel.some).use(_ => read).map: actual =>
          assertEquals(actual, ReadResult.Failed)
          assertEquals(failure.getSuppressed.toVector, Vector.empty[Throwable])

  private enum ThumbnailCall:
    case Store(location: ThumbnailLocation, image: EncodedThumbnail)
    case Read(location: ThumbnailLocation, version: String :| Not[Blank])
    case Remove(location: ThumbnailLocation)

  private def buildForwardingStorage(calls: Ref[IO, Vector[ThumbnailCall]], stored: Thumbnail) =
    val thumbnails = new ThumbnailStorage[IO]:
      override def readThumbnail(location: ThumbnailLocation, version: String :| Not[Blank]): IO[ThumbnailStorage.ReadResult] =
        val bytes = Vector(1.toByte).refineUnsafe[ThumbnailBytesConstraint]
        calls.update(_ :+ ThumbnailCall.Read(location, version)).as(ThumbnailStorage.ReadResult.Read(bytes))
      override def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): IO[ThumbnailStorage.StoreResult] =
        calls.update(_ :+ ThumbnailCall.Store(location, image)).as(ThumbnailStorage.StoreResult.Stored(stored))
      override def removeThumbnail(location: ThumbnailLocation): IO[ThumbnailStorage.RemoveResult] =
        calls.update(_ :+ ThumbnailCall.Remove(location)).as(ThumbnailStorage.RemoveResult.Failed)
    FilePhotoStorage.make[IO](thumbnails)

  private def cancelIncompleteDiscovery(root: Path) =
    val underlying = summon[Logger[IO]]
    Deferred[IO, Unit].flatMap: reached =>
      given Logger[IO] = underlying.mapK(new FunctionK[IO, IO]:
        override def apply[A](action: IO[A]): IO[A] = reached.complete(()).void >> IO.never[Unit] >> action)
      val thumbnails = FileThumbnailStorage.make[IO](root)
      val discovery  = FilePhotoStorage.make[IO](thumbnails).discoverOriginals(buildSource(root))
      discovery.start.bracket(fiber => reached.get >> fiber.cancel >> fiber.join)(_.cancel)

  private def readDuringChange(read: IO[ReadResult], change: IO[Unit]) =
    val reached = new CountDownLatch(1)
    val resume  = new CountDownLatch(1)
    buildPausedReader(reached, resume).use: _ =>
      read.start.bracket { fiber =>
        IO.interruptible(reached.await()) >> change >> IO.blocking(resume.countDown()) >> fiber.joinWithNever
      }(fiber => IO.blocking(resume.countDown()) >> fiber.cancel)

  private def buildStorage(root: Path) =
    val thumbnails = FileThumbnailStorage.make[IO](root)
    FilePhotoStorage.make[IO](thumbnails)

  private def buildSource(root: Path) = root.toString.refineUnsafe[PhotoSourceConstraint]

  private def buildLocation(root: Path, filename: String) =
    OriginalPhotoLocation(buildSource(root), filename.refineUnsafe[PhotoFilenameConstraint])

  private def buildJpeg(maybeDate: Option[String], orientation: Int, maybeFraction: Option[String]) =
    val output = new ByteArrayOutputStream()
    require(ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "jpeg", output))
    val bytes = output.toByteArray
    maybeDate.fold(bytes): date =>
      val exif     = ByteBuffer.allocate(104).order(ByteOrder.LITTLE_ENDIAN)
      val _        = exif.put("Exif\u0000\u0000".getBytes(StandardCharsets.US_ASCII))
      val _        = exif.putShort(0x4949.toShort).putShort(42.toShort).putInt(8)
      val _        = exif.putShort(2.toShort).putShort(0x0112.toShort).putShort(3.toShort).putInt(1).putShort(orientation.toShort).putShort(0.toShort)
      val _        = exif.putShort(0x8769.toShort).putShort(4.toShort).putInt(1).putInt(38).putInt(0)
      val _        = exif.putShort(2.toShort).putShort(0x9003.toShort).putShort(2.toShort).putInt(20).putInt(68)
      val _        = exif.putShort(0x9291.toShort).putShort(2.toShort).putInt(10).putInt(88).putInt(0)
      val _        = exif.put(date.getBytes(StandardCharsets.US_ASCII)).put(0.toByte)
      val fraction = maybeFraction.getOrElse("").padTo(9, '\u0000')
      val _        = exif.put(fraction.getBytes(StandardCharsets.US_ASCII)).put(0.toByte)
      val segment  = ByteBuffer.allocate(108).put(0xff.toByte).put(0xe1.toByte).putShort(106.toShort).put(exif.array()).array()
      bytes.take(2) ++ segment ++ bytes.drop(2)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildPausedReader(
      reached: CountDownLatch,
      resume: CountDownLatch,
      failRead: Boolean = false,
      failRelease: Boolean = false,
      failConstruction: Boolean = false,
      maybeSharedFailure: Option[CompletableFuture[Unit]] = None
  ) =
    Resource.make(IO.blocking:
      val registry  = IIORegistry.getDefaultInstance
      val delegates = registry.getServiceProviders(classOf[ImageReaderSpi], true).asScala.toVector
      val jpeg      = delegates.find(_.getFormatNames.exists(_.equalsIgnoreCase("jpeg"))).getOrElse(fail("JDK JPEG provider missing"))
      val provider  = new ImageReaderSpi(
        "photography",
        "1",
        Array("jpeg"),
        Array("jpg"),
        Array("image/jpeg"),
        classOf[PausedReader].getName,
        Array(classOf[ImageInputStream]),
        Array.empty[String],
        false,
        "",
        "",
        Array.empty[String],
        Array.empty[String],
        false,
        "",
        "",
        Array.empty[String],
        Array.empty[String]
      ):
        override def getDescription(locale: Locale): String               = "Synchronized real JPEG reader"
        override def canDecodeInput(input: Object): Boolean               = jpeg.canDecodeInput(input)
        override def createReaderInstance(extension: Object): ImageReader =
          if failConstruction then
            val _ = new DataInputStream(new ByteArrayInputStream(Array.emptyByteArray)).readByte()
          val delegate = Option(jpeg.createReaderInstance()).getOrElse(fail("JDK JPEG reader missing"))
          new PausedReader(this, delegate, reached, resume, failRead = failRead, failRelease = failRelease, maybeSharedFailure = maybeSharedFailure)
      registry.registerServiceProvider(provider)
      delegates.foreach { delegate =>
        val _ = registry.setOrdering(classOf[ImageReaderSpi], provider, delegate)
      }
      provider)(provider => IO.blocking(IIORegistry.getDefaultInstance.deregisterServiceProvider(provider)))
  // scalafix:on DisableSyntax.defaultArgs

  final private class PausedReader(
      provider: ImageReaderSpi,
      delegate: ImageReader,
      reached: CountDownLatch,
      resume: CountDownLatch,
      failRead: Boolean,
      failRelease: Boolean,
      maybeSharedFailure: Option[CompletableFuture[Unit]]
  ) extends ImageReader(provider):
    override def read(index: Int, parameters: ImageReadParam): BufferedImage =
      reached.countDown()
      resume.await()
      maybeSharedFailure.foreach(_.join())
      if failRead then
        val _ = new DataInputStream(new ByteArrayInputStream(Array.emptyByteArray)).readByte()
      delegate.setInput(getInput)
      delegate.read(index, parameters)
    override def getNumImages(allowSearch: Boolean): Int                     = 1
    override def getWidth(index: Int): Int                                   = 3
    override def getHeight(index: Int): Int                                  = 2
    override def getImageTypes(index: Int): JavaIterator[ImageTypeSpecifier] =
      JavaList.of(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB)).iterator()
    override def getStreamMetadata(): IIOMetadata          = delegate.getStreamMetadata()
    override def getImageMetadata(index: Int): IIOMetadata = delegate.getImageMetadata(index)
    override def dispose(): Unit                           =
      delegate.dispose()
      maybeSharedFailure.foreach(_.join())
      if failRelease then
        val _ = ByteBuffer.allocate(0).get()

  private def buildDirectory =
    Resource.make(IO.blocking(Files.createTempDirectory(Path.of("target"), "originals-").toAbsolutePath)): root =>
      Resource.fromAutoCloseable(IO.blocking(Files.walk(root))).use: paths =>
        paths.iterator().asScala.toVector.reverse.traverse_(path => IO.blocking(Files.deleteIfExists(path)).void)
