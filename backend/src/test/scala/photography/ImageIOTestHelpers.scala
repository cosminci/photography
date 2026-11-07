package photography

import cats.effect.{IO, Resource}
import java.util.Locale
import java.util.concurrent.Semaphore
import javax.imageio.{ImageReader, ImageTypeSpecifier, ImageWriter}
import javax.imageio.spi.{IIORegistry, ImageReaderSpi, ImageReaderWriterSpi, ImageWriterSpi}
import javax.imageio.stream.{ImageInputStream, ImageOutputStream}
import scala.jdk.CollectionConverters.*

private[photography] object ImageIOTestHelpers:
  private val registryLock = new Semaphore(1, true)

  def acquire: Resource[IO, Unit] =
    Resource.make(IO.interruptible(registryLock.acquire()))(_ => IO.blocking(registryLock.release()))

  def register[A <: ImageReaderWriterSpi](category: Class[A])(provider: => A): Resource[IO, A] =
    Resource.make {
      IO.blocking:
        val registry  = IIORegistry.getDefaultInstance
        val delegates = registry.getServiceProviders(category, true).asScala.toVector
        val installed = provider
        registry.registerServiceProvider(installed)
        delegates.foreach { delegate =>
          val _ = registry.setOrdering(category, installed, delegate)
        }
        installed
    }(installed => IO.blocking(IIORegistry.getDefaultInstance.deregisterServiceProvider(installed)))

  def reader(reader: Class[? <: ImageReader], description: String)(canDecode: Object => Boolean)(
      create: ImageReaderSpi => ImageReader
  ): ImageReaderSpi =
    new ImageReaderSpi(
      "photography",
      "1",
      Array("jpeg"),
      Array("jpg"),
      Array("image/jpeg"),
      reader.getName,
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
      override def getDescription(locale: Locale): String               = description
      override def canDecodeInput(input: Object): Boolean               = canDecode(input)
      override def createReaderInstance(extension: Object): ImageReader = create(this)

  def writer(writer: Class[? <: ImageWriter], description: String)(canEncode: ImageTypeSpecifier => Boolean)(
      create: ImageWriterSpi => ImageWriter
  ): ImageWriterSpi =
    new ImageWriterSpi(
      "photography",
      "1",
      Array("jpeg"),
      Array("jpg"),
      Array("image/jpeg"),
      writer.getName,
      Array(classOf[ImageOutputStream]),
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
      override def getDescription(locale: Locale): String               = description
      override def canEncodeImage(image: ImageTypeSpecifier): Boolean   = canEncode(image)
      override def createWriterInstance(extension: Object): ImageWriter = create(this)
