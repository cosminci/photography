package photography.adapters.imageio

import cats.Eq
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import com.drew.imaging.jpeg.{JpegMetadataReader, JpegProcessingException}
import com.drew.metadata.exif.{ExifDirectoryBase, ExifIFD0Directory}
import io.github.iltotore.iron.*
import com.twelvemonkeys.image.{ImageUtil, ResampleOp}
import photography.domain.album.*
import photography.ports.photo.PhotoThumbnailGenerator
import photography.ports.photo.PhotoThumbnailGenerator.GenerationResult
import photography.syntax.SyncSyntax.*
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import java.util.List as JavaList
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.{IIOException, IIOImage, ImageIO, ImageTypeSpecifier, ImageWriteParam}
import javax.imageio.stream.{MemoryCacheImageInputStream, MemoryCacheImageOutputStream}
import scala.jdk.CollectionConverters.*

object ImageIoPhotoThumbnailGenerator:
  def make[F[_]: Async](quality: ThumbnailQuality): PhotoThumbnailGenerator[F] = new LiveImageIoPhotoThumbnailGenerator(quality)

  final private class LiveImageIoPhotoThumbnailGenerator[F[_]: Async](override val quality: ThumbnailQuality)
      extends PhotoThumbnailGenerator[F]:
    override def generate(content: PhotoContent): F[GenerationResult] =
      renderThumbnail(content).flatMap:
        case Some(image) => (GenerationResult.Generated(image): GenerationResult).pure[F]
        case None        => (GenerationResult.Failed(new IIOException("Encoded thumbnail exceeds its preset")): GenerationResult).pure[F]
      .recover:
        case error if isExpectedFailure(error) => GenerationResult.Failed(error)

    private def renderThumbnail(content: PhotoContent) =
      for
        bytes       <- content.bytes.toArray.blocking[F]
        orientation <- readOrientation(bytes = bytes).blocking[F]
        decoded     <- decodeImage(bytes = bytes)
        encoded     <- fitThumbnail(decoded, orientation = orientation, longestEdge = quality.longestEdge)
      yield encoded

    private def fitThumbnail(original: BufferedImage, orientation: Int, longestEdge: Int) =
      Async[F].tailRecM(longestEdge): edge =>
        transformImage(original, orientation = orientation, longestEdge = edge).blocking[F].flatMap: image =>
          encodeAtQuality(image).map:
            case None if edge > 1 => math.max(1, edge * 4 / 5).asLeft[Option[EncodedThumbnail]]
            case result           => result.asRight[Int]

    private def encodeAtQuality(image: BufferedImage) =
      Async[F].tailRecM(85): compression =>
        encodeImage(image, compression = compression).map:
          case None if compression > 65 => (compression - 5).asLeft[Option[EncodedThumbnail]]
          case result                   => result.asRight[Int]

    private def readOrientation(bytes: Array[Byte]) =
      val metadata = JpegMetadataReader.readMetadata(new ByteArrayInputStream(bytes))
      Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
        .flatMap(directory => Option(directory.getInteger(ExifDirectoryBase.TAG_ORIENTATION)))
        .fold(1)(_.intValue)

    private def decodeImage(bytes: Array[Byte]) =
      val input = Resource.makeCase(new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)).blocking[F]):
        case (stream, exit) => releaseAfterProcessing(stream.close().blocking[F], exit)
      input.use: stream =>
        val acquire = ImageIO.getImageReaders(stream).asScala.nextOption().flatMap(Option(_))
          .toRight(new IIOException("JPEG decoder unavailable")).blocking[F].flatMap(_.liftTo[F])
        Resource.makeCase(acquire) { case (reader, exit) => releaseAfterProcessing(reader.dispose().blocking[F], exit) }.use: reader =>
          {
            val warnings = new AtomicBoolean(false)
            reader.addIIOReadWarningListener((_, _) => warnings.set(true))
            reader.setInput(stream)
            Option(reader.read(0)).filter(_ => !warnings.get()).toRight(new IIOException("Invalid JPEG image"))
          }.blocking[F].flatMap(_.liftTo[F])

    private def transformImage(image: BufferedImage, orientation: Int, longestEdge: Int) =
      orientImage(resizeImage(image, longestEdge = longestEdge), orientation = orientation)

    private def resizeImage(image: BufferedImage, longestEdge: Int) =
      val scale  = math.min(1.0, longestEdge.toDouble / math.max(image.getWidth, image.getHeight))
      val width  = math.max(1, math.round(image.getWidth * scale).toInt)
      val height = math.max(1, math.round(image.getHeight * scale).toInt)
      val output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
      new ResampleOp(width, height, ResampleOp.FILTER_LANCZOS).filter(image, output)

    private def orientImage(image: BufferedImage, orientation: Int) =
      orientation match
        case 2 => ImageUtil.createFlipped(image, ImageUtil.FLIP_VERTICAL)
        case 3 => ImageUtil.createRotated(image, ImageUtil.ROTATE_180)
        case 4 => ImageUtil.createFlipped(image, ImageUtil.FLIP_HORIZONTAL)
        case 5 => ImageUtil.createFlipped(ImageUtil.createRotated(image, ImageUtil.ROTATE_90_CW), ImageUtil.FLIP_VERTICAL)
        case 6 => ImageUtil.createRotated(image, ImageUtil.ROTATE_90_CW)
        case 7 => ImageUtil.createFlipped(ImageUtil.createRotated(image, ImageUtil.ROTATE_90_CCW), ImageUtil.FLIP_VERTICAL)
        case 8 => ImageUtil.createRotated(image, ImageUtil.ROTATE_90_CCW)
        case _ => image

    private def encodeImage(image: BufferedImage, compression: Int) =
      new ByteArrayOutputStream().blocking[F].flatMap: bytes =>
        val output = Resource.makeCase(new MemoryCacheImageOutputStream(bytes).blocking[F]):
          case (stream, exit) => releaseAfterProcessing(stream.close().blocking[F], exit)
        output.use(stream => writeJpeg(image, stream, compression = compression)) >>
          {
            val dimensions = ThumbnailDimensions(
              width = image.getWidth.refineUnsafe[ThumbnailPixelCountConstraint],
              height = image.getHeight.refineUnsafe[ThumbnailPixelCountConstraint]
            )
            validateThumbnail(bytes = bytes.toByteArray.toVector, dimensions)
          }.blocking[F]

    private def writeJpeg(image: BufferedImage, output: MemoryCacheImageOutputStream, compression: Int) =
      val acquire = ImageIO.getImageWritersByFormatName("jpeg").asScala.nextOption().flatMap(Option(_))
        .toRight(new IIOException("JPEG encoder unavailable")).blocking[F].flatMap(_.liftTo[F])
      Resource.makeCase(acquire) { case (writer, exit) => releaseAfterProcessing(writer.dispose().blocking[F], exit) }.use: writer =>
        {
          val parameters = writer.getDefaultWriteParam
          parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
          parameters.setCompressionQuality(compression / 100.0f)
          writer.setOutput(output)
          val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), parameters)
          val encoded  = new IIOImage(image, JavaList.of[BufferedImage](), metadata)
          writer.write(writer.getDefaultStreamMetadata(parameters), encoded, parameters)
        }.blocking[F]

    private def validateThumbnail(bytes: Vector[Byte], dimensions: ThumbnailDimensions) =
      Option.when(bytes.sizeIs <= quality.maximumBytes):
        EncodedThumbnail(bytes = bytes.refineUnsafe[ThumbnailBytesConstraint], dimensions, quality)

    private def isExpectedFailure(error: Throwable) =
      isImageFailure(error) && error.getSuppressed.forall(isImageFailure)

    private def isImageFailure(error: Throwable) =
      error match
        case _: IOException | _: JpegProcessingException => true
        case _                                           => false

    private given Eq[Object] = Eq.fromUniversalEquals

    private def releaseAfterProcessing(release: F[Unit], exit: Resource.ExitCase) =
      release.handleErrorWith: error =>
        exit match
          case Resource.ExitCase.Errored(primary) => Async[F].delay(if (primary: Object) =!= (error: Object) then primary.addSuppressed(error))
          case _                                  => Async[F].raiseError(error)
