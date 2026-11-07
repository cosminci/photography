package photography.adapters.imageio

import cats.effect.{IO, Resource}
import cats.effect.unsafe.IORuntime
import cats.Eq
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import photography.ImageIOTestHelpers
import photography.domain.album.*
import photography.ports.photo.PhotoThumbnailGenerator.GenerationResult
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, EOFException, IOException}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.{Iterator as JavaIterator, List as JavaList, Random}
import java.util.concurrent.{CompletableFuture, CompletionException, CountDownLatch, Executors}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import javax.imageio.{IIOImage, ImageIO, ImageReadParam, ImageReader, ImageTypeSpecifier, ImageWriteParam, ImageWriter}
import javax.imageio.metadata.{IIOMetadata, IIOMetadataNode}
import javax.imageio.plugins.jpeg.JPEGQTable
import javax.imageio.spi.{IIORegistry, ImageReaderSpi, ImageWriterSpi}
import javax.imageio.stream.{ImageInputStream, ImageOutputStream, MemoryCacheImageInputStream, MemoryCacheImageOutputStream}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

final class ImageIoPhotoThumbnailGeneratorSeamIntegrationTest extends CatsEffectSuite:
  private val colors  = Vector(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
  private val imageIO = ResourceTestLocalFixture("image-io", ImageIOTestHelpers.acquire)

  override def munitFixtures = List(imageIO)

  test("should generate one uncropped proportional JPEG within each preset without upscaling or changing supplied content"):
    val sizes = Vector((2400, 1600), (1600, 2400), (96, 64), (64, 96), (1, 1), (4096, 1), (1, 4096))

    ThumbnailQuality.values.toVector.traverse_ { quality =>
      sizes.traverse_ { case (width, height) =>
        for
          content <- IO.blocking(buildJpeg(width = width, height = height, maybeOrientation = None, noisy = false))
          before  <- IO.blocking(content.bytes.toArray.toVector)
          result  <- ImageIoPhotoThumbnailGenerator.make[IO](quality).generate(content)
          decoded <- decodeJpeg(requireGenerated(result))
        yield
          val image          = requireGenerated(result)
          val scale          = math.min(1.0, quality.longestEdge.toDouble / math.max(width, height))
          val expectedWidth  = math.max(1, math.round(width * scale).toInt)
          val expectedHeight = math.max(1, math.round(height * scale).toInt)
          assertEquals(image.quality, quality)
          assertEquals((decoded.getWidth, decoded.getHeight), (expectedWidth, expectedHeight))
          assertEquals((image.dimensions.width: Int, image.dimensions.height: Int), (decoded.getWidth, decoded.getHeight))
          assert(image.bytes.sizeIs <= quality.maximumBytes)
          assertEquals(content.bytes: Vector[Byte], before)
          if width > 1 && height > 1 then assertQuadrants(decoded, order = Vector(0, 1, 2, 3))
      }
    }

  test("should bake every EXIF rotation and mirror into the JPEG pixels before discarding orientation metadata"):
    val sizes        = Vector((96, 64, 96, 64), (97, 65, 97, 65), (2400, 1600, 1536, 1024))
    val orientations = Vector(
      (1, Vector(0, 1, 2, 3)),
      (2, Vector(1, 0, 3, 2)),
      (3, Vector(3, 2, 1, 0)),
      (4, Vector(2, 3, 0, 1)),
      (5, Vector(0, 2, 1, 3)),
      (6, Vector(2, 0, 3, 1)),
      (7, Vector(3, 1, 2, 0)),
      (8, Vector(1, 3, 0, 2)),
      (0, Vector(0, 1, 2, 3))
    )

    orientations.traverse_ { case (orientation, order) =>
      sizes.traverse_ { case (width, height, scaledWidth, scaledHeight) =>
        for
          content <- IO.blocking(buildJpeg(width = width, height = height, maybeOrientation = orientation.some, noisy = false))
          result  <- ImageIoPhotoThumbnailGenerator.make[IO](ThumbnailQuality.Standard).generate(content)
          decoded <- decodeJpeg(requireGenerated(result))
        yield
          val expectedSize = if orientation >= 5 && orientation <= 8 then (scaledHeight, scaledWidth) else (scaledWidth, scaledHeight)
          assertEquals((decoded.getWidth, decoded.getHeight), expectedSize)
          assertQuadrants(decoded, order = order)
      }
    }

  test("should fit detailed JPEGs within every preset by adapting encoding without changing supplied originals"):
    ThumbnailQuality.values.toVector.traverse_ { quality =>
      for
        content <- IO.blocking(buildJpeg(width = quality.longestEdge, height = quality.longestEdge, maybeOrientation = None, noisy = true))
        result  <- ImageIoPhotoThumbnailGenerator.make[IO](quality).generate(content)
        decoded <- IO.blocking(ImageIO.read(new ByteArrayInputStream(requireGenerated(result).bytes.toArray)))
      yield
        val image = requireGenerated(result)
        assert(image.bytes.sizeIs <= quality.maximumBytes)
        assertEquals(image.quality, quality)
        assertEquals(decoded.getWidth, decoded.getHeight)
        assert(decoded.getWidth <= quality.longestEdge)
        assertEquals((image.dimensions.width: Int, image.dimensions.height: Int), (decoded.getWidth, decoded.getHeight))
    }

  test("should report malformed content as generation failure and keep valid subsequent generation available"):
    val generator = ImageIoPhotoThumbnailGenerator.make[IO](ThumbnailQuality.Low)

    IO.blocking(buildJpeg(width = 96, height = 64, maybeOrientation = None, noisy = false)).flatMap: valid =>
      val malformed = Vector(Vector(1.toByte, 2.toByte, 3.toByte), Vector(0xff.toByte, 0xd8.toByte, 0xff.toByte), valid.bytes.dropRight(2))

      for
        failed <- malformed.traverse(bytes => generator.generate(PhotoContent(bytes = bytes.refineUnsafe[MinLength[1]])))
        retry  <- generator.generate(valid)
      yield
        assert(failed.map(requireFailure).forall(_.getMessage.nonEmpty))
        assertEquals(requireGenerated(retry).quality, ThumbnailQuality.Low)

  test("should distinguish expected image IO failures from unexpected defects and preserve primary failures during reader release"):
    val generator = ImageIoPhotoThumbnailGenerator.make[IO](ThumbnailQuality.Low)
    val defect    = new CompletionException(new IllegalStateException("unexpected image provider defect"))
    val secondary = new CompletionException(new IllegalStateException("image provider release defect"))
    val cases     = Vector(
      (ReadAction.IoFailure, None, true),
      (ReadAction.ConstructionFailure, None, true),
      (ReadAction.Unexpected(new CompletionException(new IllegalStateException("unexpected decoder defect"))), None, false),
      (ReadAction.IoFailure, secondary.some, false),
      (ReadAction.Unexpected(new CompletionException(new IllegalStateException("primary decoder defect"))), secondary.some, false),
      (ReadAction.Unexpected(defect), defect.some, false),
      (ReadAction.Decode, secondary.some, false)
    )

    cases.traverse_ { case (action, maybeReleaseFailure, expectedFailure) =>
      val expectedClass = if action === ReadAction.ConstructionFailure then "javax.imageio.IIOException" else "java.io.EOFException"
      for
        content  <- IO.blocking(buildJpeg(width = 96, height = 64, maybeOrientation = None, noisy = false))
        refs     <- buildRefs
        result   <- buildObservedReader(refs, action, maybeReleaseFailure = maybeReleaseFailure).use(_ => generator.generate(content).attempt)
        disposed <- IO.delay(refs.disposed.get())
        memory   <- IO.delay(refs.memoryInput.get())
      yield
        if expectedFailure then assertEquals(result.map(requireFailure).map(_.getClass.getName), expectedClass.asRight[Throwable])
        else
          val expected = action match
            case ReadAction.Decode            => secondary
            case ReadAction.Unexpected(error) => error
            case ReadAction.IoFailure         =>
              val primary = result.swap.toOption.getOrElse(fail("Expected the primary IO failure to retain the release defect"))
              assertEquals(primary.getClass, classOf[EOFException])
              assertEquals(primary.getSuppressed.toVector, maybeReleaseFailure.toList.toVector)
              primary
            case ReadAction.ConstructionFailure | _: ReadAction.Paused => fail("Expected an unexpected provider failure")
          assertEquals(result, expected.asLeft[GenerationResult])
        action match
          case ReadAction.Unexpected(error) =>
            val expectedSuppressed = if maybeReleaseFailure.contains(error) then Vector.empty[Throwable] else maybeReleaseFailure.toList.toVector
            assertEquals(error.getSuppressed.toVector, expectedSuppressed)
          case ReadAction.Decode | ReadAction.IoFailure | ReadAction.ConstructionFailure | _: ReadAction.Paused => ()
        assertEquals(disposed, action =!= ReadAction.ConstructionFailure)
        assertEquals(memory, action =!= ReadAction.ConstructionFailure)
    }

  test("should leave unrelated single-compute-runtime fibers responsive while synchronous JPEG decoding is blocked"):
    val reached = new CountDownLatch(1)
    val resume  = new CountDownLatch(1)

    val resources =
      for
        content <- Resource.eval(IO.blocking(buildJpeg(width = 96, height = 64, maybeOrientation = None, noisy = false)))
        refs    <- Resource.eval(buildRefs)
        runtime <- buildRuntime
        _       <- buildObservedReader(refs, ReadAction.Paused(reached = reached, resume = resume), maybeReleaseFailure = None)
      yield (content, refs, runtime)

    resources.use { case (content, refs, runtime) =>
      val generation = ImageIoPhotoThumbnailGenerator.make[IO](ThumbnailQuality.Standard).generate(content)
      val compute    = IO.cede.as("compute progressed")
      val probe      = IO.fromCompletableFuture(IO.delay(compute.unsafeToCompletableFuture()(using runtime))).timeout(5.seconds)

      Resource.make(IO.delay(generation.unsafeToCompletableFuture()(using runtime))) { future =>
        IO.delay(resume.countDown()) >> IO.fromCompletableFuture(future.pure[IO]).void
      }.use: future =>
        for
          _        <- IO.interruptible(reached.await()).timeout(10.seconds)
          progress <- probe
          _        <- IO.delay(resume.countDown())
          result   <- IO.fromCompletableFuture(future.pure[IO])
        yield
          assertEquals(progress, "compute progressed")
          assertEquals(requireGenerated(result).quality, ThumbnailQuality.Standard)
          assert(refs.disposed.get())
    }

  test("should release the memory-backed JPEG encoder on write failures without hiding unexpected errors"):
    val generator = ImageIoPhotoThumbnailGenerator.make[IO](ThumbnailQuality.Standard)
    val defect    = new CompletionException(new IllegalStateException("unexpected JPEG encoder defect"))
    val cases     = Vector(WriteAction.IoFailure, WriteAction.Unexpected(defect), WriteAction.ConstructionFailure, WriteAction.Oversized)

    cases.traverse_ { action =>
      val expectedClass = if action === WriteAction.ConstructionFailure then "javax.imageio.IIOException" else "java.io.EOFException"
      for
        content <- IO.blocking(buildJpeg(width = 96, height = 64, maybeOrientation = None, noisy = false))
        refs    <- buildRefs
        result  <- buildObservedWriter(refs, action).use(_ => generator.generate(content).attempt)
        _       <- IO.blocking(refs.maybeOutput.get().foreach(output => intercept[IOException](output.getStreamPosition)))
      yield
        action match
          case WriteAction.Unexpected(error) => assertEquals(result, error.asLeft[GenerationResult])
          case WriteAction.Oversized         =>
            assertEquals(result.map(requireFailure).map(_.getMessage), "Encoded thumbnail exceeds its preset".asRight[Throwable])
          case _ => assertEquals(result.map(requireFailure).map(_.getClass.getName), expectedClass.asRight[Throwable])
        assertEquals(refs.writerDisposed.get(), action =!= WriteAction.ConstructionFailure)
        assertEquals(refs.memoryOutput.get(), action =!= WriteAction.ConstructionFailure)
    }

  final private case class Refs(
      disposed: AtomicBoolean,
      memoryInput: AtomicBoolean,
      writerDisposed: AtomicBoolean,
      memoryOutput: AtomicBoolean,
      maybeOutput: AtomicReference[Option[ImageOutputStream]]
  )

  private def requireGenerated(result: GenerationResult) =
    result match
      case GenerationResult.Generated(image) => image
      case GenerationResult.Failed(_)        => fail("Expected a complete generated thumbnail")

  private def requireFailure(result: GenerationResult) = result match
    case GenerationResult.Failed(cause) => cause
    case other                          => fail(s"Expected a generation failure, got $other")

  private def decodeJpeg(image: EncodedThumbnail) =
    val bytes = image.bytes: Vector[Byte]
    assertEquals(bytes.take(2), Vector(0xff.toByte, 0xd8.toByte))
    assertEquals(bytes.takeRight(2), Vector(0xff.toByte, 0xd9.toByte))
    Resource.fromAutoCloseable(IO.blocking(new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes.toArray)))).use: input =>
      val acquire = IO.blocking(ImageIO.getImageReaders(input).asScala.nextOption().getOrElse(fail("Generated JPEG cannot be decoded")))
      Resource.make(acquire)(reader => IO.blocking(reader.dispose())).use: reader =>
        IO.blocking:
          reader.setInput(input)
          val decoded           = reader.read(0)
          val expectedLuminance = JPEGQTable.K1Luminance.getScaledInstance(2.0f - 2.0f * 0.85f, true).getTable.toVector
          val actualLuminance   = readLuminanceTable(reader.getImageMetadata(0))
          assertEquals(actualLuminance, expectedLuminance)
          decoded

  private def readLuminanceTable(metadata: IIOMetadata) =
    metadata.getAsTree("javax_imageio_jpeg_image_1.0") match
      case root: IIOMetadataNode =>
        val tables = root.getElementsByTagName("dqtable")
        (0 until tables.getLength).iterator.map(tables.item).collectFirst:
          case table: IIOMetadataNode if table.getAttribute("qtableId") === "0" =>
            table.getUserObject match
              case value: JPEGQTable => value.getTable.toVector
              case _                 => fail("Missing JPEG quantization table")
        .getOrElse(fail("Missing luminance quantization table"))
      case _ => fail("Missing JPEG image metadata")

  private def assertQuadrants(image: BufferedImage, order: Vector[Int]): Unit =
    order.zipWithIndex.foreach { case (colorIndex, quadrant) =>
      val x        = image.getWidth * (if quadrant % 2 === 0 then 1 else 3) / 4
      val y        = image.getHeight * (if quadrant < 2 then 1 else 3) / 4
      val actual   = new Color(image.getRGB(x, y))
      val expected = colors.lift(colorIndex).getOrElse(fail("Unknown fixture color"))
      assert(math.abs(actual.getRed - expected.getRed) < 20)
      assert(math.abs(actual.getGreen - expected.getGreen) < 20)
      assert(math.abs(actual.getBlue - expected.getBlue) < 20)
    }

  private def buildJpeg(width: Int, height: Int, maybeOrientation: Option[Int], noisy: Boolean) =
    val image  = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val random = new Random(42L)
    (0 until height).foreach: y =>
      (0 until width).foreach: x =>
        val quadrant = (if y >= height / 2 then 2 else 0) + (if x >= width / 2 then 1 else 0)
        val rgb      = if noisy then random.nextInt() else colors.lift(quadrant).getOrElse(fail("Unknown fixture quadrant")).getRGB
        image.setRGB(x, y, rgb)
    val bytes  = new ByteArrayOutputStream()
    val output = new MemoryCacheImageOutputStream(bytes)
    try require(ImageIO.write(image, "jpeg", output))
    finally output.close()
    val jpeg    = bytes.toByteArray
    val marked  = jpeg.take(2) ++ Array(0xff.toByte, 0xef.toByte, 0.toByte, 6.toByte, 84.toByte, 72.toByte, 77.toByte, 66.toByte) ++ jpeg.drop(2)
    val encoded = maybeOrientation.fold(marked): value =>
      val exif = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
      val _    = exif.put(Array(69.toByte, 120.toByte, 105.toByte, 102.toByte, 0.toByte, 0.toByte))
      val _    = exif.putShort(0x4949.toShort).putShort(42.toShort).putInt(8)
      val _    = exif.putShort(1.toShort).putShort(0x0112.toShort).putShort(3.toShort).putInt(1)
      val _    = exif.putShort(value.toShort).putShort(0.toShort).putInt(0)
      val app1 = ByteBuffer.allocate(36).put(0xff.toByte).put(0xe1.toByte).putShort(34.toShort).put(exif.array()).array()
      marked.take(2) ++ app1 ++ marked.drop(2)
    PhotoContent(bytes = encoded.toVector.refineUnsafe[MinLength[1]])

  private def buildRefs =
    IO.delay:
      Refs(
        disposed = new AtomicBoolean(false),
        memoryInput = new AtomicBoolean(false),
        writerDisposed = new AtomicBoolean(false),
        memoryOutput = new AtomicBoolean(false),
        maybeOutput = new AtomicReference(None)
      )

  private enum ReadAction:
    case Decode, IoFailure, ConstructionFailure
    case Unexpected(error: CompletionException)
    case Paused(reached: CountDownLatch, resume: CountDownLatch)

  private enum WriteAction:
    case IoFailure, ConstructionFailure, Oversized
    case Unexpected(error: CompletionException)

  private given Eq[ReadAction]  = Eq.fromUniversalEquals
  private given Eq[WriteAction] = Eq.fromUniversalEquals

  private def buildObservedReader(refs: Refs, action: ReadAction, maybeReleaseFailure: Option[CompletionException]) =
    ImageIOTestHelpers.register(classOf[ImageReaderSpi]) {
      val registry  = IIORegistry.getDefaultInstance
      val delegates = registry.getServiceProviders(classOf[ImageReaderSpi], true).asScala.toVector
      val jpeg = delegates.find(provider => provider.getVendorName =!= "photography" && provider.getFormatNames.exists(_.equalsIgnoreCase("jpeg")))
        .getOrElse(fail("JDK JPEG provider missing"))
      ImageIOTestHelpers.reader(classOf[ObservedReader], description = "Isolated thumbnail fixture reader") { input =>
        input match
          case stream: ImageInputStream =>
            stream.mark()
            try stream.readInt() === 0xffd8ffef && stream.readInt() === 0x00065448 && stream.readShort() === 0x4d42
            finally stream.reset()
          case _ => false
      } { provider =>
        if action === ReadAction.ConstructionFailure then
          val _ = new DataInputStream(new ByteArrayInputStream(Array.emptyByteArray)).readByte()
        val delegate = Option(jpeg.createReaderInstance()).getOrElse(fail("JDK JPEG reader missing"))
        new ObservedReader(provider, delegate, refs, action, maybeReleaseFailure = maybeReleaseFailure)
      }
    }

  private def buildObservedWriter(refs: Refs, action: WriteAction) =
    ImageIOTestHelpers.register(classOf[ImageWriterSpi]) {
      val registry  = IIORegistry.getDefaultInstance
      val delegates = registry.getServiceProviders(classOf[ImageWriterSpi], true).asScala.toVector
      val jpeg = delegates.find(provider => provider.getVendorName =!= "photography" && provider.getFormatNames.exists(_.equalsIgnoreCase("jpeg")))
        .getOrElse(fail("JDK JPEG writer missing"))
      ImageIOTestHelpers.writer(classOf[ObservedWriter], description = "Isolated thumbnail fixture writer")(jpeg.canEncodeImage) { provider =>
        if action === WriteAction.ConstructionFailure then
          val _ = new DataInputStream(new ByteArrayInputStream(Array.emptyByteArray)).readByte()
        val delegate = Option(jpeg.createWriterInstance()).getOrElse(fail("JDK JPEG writer missing"))
        new ObservedWriter(provider, delegate, refs, action)
      }
    }

  final private class ObservedWriter(provider: ImageWriterSpi, delegate: ImageWriter, refs: Refs, action: WriteAction) extends ImageWriter(provider):
    private val observed = new AtomicBoolean(false)

    override def write(metadata: IIOMetadata, image: IIOImage, parameters: ImageWriteParam): Unit =
      val fixture = image.getRenderedImage.getWidth === 96 && image.getRenderedImage.getHeight === 64
      if fixture || action === WriteAction.Oversized then
        observed.set(true)
        val output = getOutput match
          case output: ImageOutputStream =>
            refs.maybeOutput.set(output.some)
            refs.memoryOutput.set(output.isCachedMemory && !output.isCachedFile)
            output
          case _ => fail("Expected a memory-backed JPEG output stream")
        action match
          case WriteAction.IoFailure           => val _ = new DataInputStream(new ByteArrayInputStream(Array.emptyByteArray)).readByte()
          case WriteAction.Unexpected(error)   => CompletableFuture.failedFuture[Unit](error).join()
          case WriteAction.ConstructionFailure => fail("A failed writer cannot encode")
          case WriteAction.Oversized           => output.write(new Array[Byte](300001))
      else
        delegate.setOutput(getOutput)
        delegate.write(metadata, image, parameters)
    override def getDefaultWriteParam: ImageWriteParam                              = delegate.getDefaultWriteParam
    override def getDefaultStreamMetadata(parameters: ImageWriteParam): IIOMetadata = delegate.getDefaultStreamMetadata(parameters)
    override def getDefaultImageMetadata(image: ImageTypeSpecifier, parameters: ImageWriteParam): IIOMetadata =
      delegate.getDefaultImageMetadata(image, parameters)
    override def convertStreamMetadata(metadata: IIOMetadata, parameters: ImageWriteParam): IIOMetadata =
      delegate.convertStreamMetadata(metadata, parameters)
    override def convertImageMetadata(metadata: IIOMetadata, image: ImageTypeSpecifier, parameters: ImageWriteParam): IIOMetadata =
      delegate.convertImageMetadata(metadata, image, parameters)
    override def dispose(): Unit =
      if observed.get() then refs.writerDisposed.set(true)
      delegate.dispose()

  final private class ObservedReader(
      provider: ImageReaderSpi,
      delegate: ImageReader,
      refs: Refs,
      action: ReadAction,
      maybeReleaseFailure: Option[CompletionException]
  ) extends ImageReader(provider):
    override def read(index: Int, parameters: ImageReadParam): BufferedImage =
      getInput match
        case stream: ImageInputStream => refs.memoryInput.set(stream.isCachedMemory && !stream.isCachedFile)
        case _                        => fail("Expected a memory-backed image stream")
      action match
        case ReadAction.Decode                  => ()
        case ReadAction.IoFailure               => val _ = new DataInputStream(new ByteArrayInputStream(Array.emptyByteArray)).readByte()
        case ReadAction.Unexpected(error)       => CompletableFuture.failedFuture[Unit](error).join()
        case ReadAction.ConstructionFailure     => fail("A failed reader cannot decode")
        case ReadAction.Paused(reached, resume) =>
          reached.countDown()
          resume.await()
      delegate.setInput(getInput)
      delegate.read(index, parameters)
    override def getNumImages(allowSearch: Boolean): Int                     = 1
    override def getWidth(index: Int): Int                                   = 96
    override def getHeight(index: Int): Int                                  = 64
    override def getImageTypes(index: Int): JavaIterator[ImageTypeSpecifier] =
      JavaList.of(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB)).iterator()
    override def getStreamMetadata(): IIOMetadata          = delegate.getStreamMetadata()
    override def getImageMetadata(index: Int): IIOMetadata = delegate.getImageMetadata(index)
    override def dispose(): Unit                           =
      refs.disposed.set(true)
      delegate.dispose()
      maybeReleaseFailure.foreach(error => CompletableFuture.failedFuture[Unit](error).join())

  private def buildRuntime =
    Resource.make(IO.delay:
      val executor = Executors.newSingleThreadExecutor()
      val compute  = ExecutionContext.fromExecutorService(executor)
      IORuntime.builder().setCompute(compute, () => executor.shutdown()).build())(runtime => IO.blocking(runtime.shutdown()))
