package photography.adapters.filesystem

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import fs2.io.file.{Files as StreamFiles, Path as StreamPath}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.typelevel.log4cats.Logger
import photography.domain.album.*
import photography.ports.photo.ThumbnailStorage
import photography.ports.photo.ThumbnailStorage.*
import photography.syntax.SyncSyntax.*
import java.nio.channels.FileChannel
import java.nio.file.{Files, NoSuchFileException, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

object FileThumbnailStorage:
  def make[F[_]: Async: Logger](root: Path): ThumbnailStorage[F] = new LiveFileThumbnailStorage(root)

  final private class LiveFileThumbnailStorage[F[_]: Async: Logger](root: Path) extends ThumbnailStorage[F]:
    override def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): F[StoreResult] =
      val partial = root.resolve(s"${location.value}.tmp")
      val target  = root.resolve(s"${location.value}.jpg")
      val write   =
        for
          bytes  <- image.bytes.toArray.blocking[F]
          _      <- checkRoot().liftTo[F]
          _      <- Files.createDirectories(root).blocking[F]
          _      <- require(!Files.exists(target), "Thumbnail location already exists").blocking[F]
          _      <- Files.write(partial, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).blocking[F]
          _      <- force(partial, StandardOpenOption.WRITE)
          _      <- Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE).blocking[F]
          _      <- force(root, StandardOpenOption.READ)
          digest <- computeVersion(bytes).blocking[F]
        yield digest
      write.map: version =>
        val size = image.bytes.size.refineUnsafe[ThumbnailByteCountConstraint]
        StoreResult.Stored(Thumbnail(location, version = version, dimensions = image.dimensions, quality = image.quality, bytes = size))
      .handleError(error => StoreResult.Failed(error))

    override def readThumbnail(location: ThumbnailLocation, version: String :| Not[Blank]): F[ReadResult] =
      val read = checkRoot().liftTo[F] >> resolveThumbnail(location).flatMap:
        case None       => ReadResult.NotFound.pure[F]
        case Some(path) =>
          StreamFiles.forAsync[F].readAll(StreamPath.fromNioPath(path)).take(300001).compile.toVector.flatMap: bytes =>
            val read = bytes.refineEither[ThumbnailBytesConstraint].leftMap(new IllegalStateException(_))
            read.liftTo[F].flatMap: content =>
              computeVersion(content.toArray).blocking[F].map: actualVersion =>
                if (actualVersion: String) === (version: String) then ReadResult.Read(content) else ReadResult.NotFound
      read.recoverWith:
        case _: NoSuchFileException => ReadResult.NotFound.pure[F]
        case error                  => Logger[F].error(error)("Managed thumbnail read failed").as(ReadResult.Failed)

    override def removeThumbnail(location: ThumbnailLocation): F[RemoveResult] =
      val remove =
        for
          _      <- checkRoot().liftTo[F]
          _      <- Files.deleteIfExists(root.resolve(s"${location.value}.jpg")).blocking[F]
          _      <- Files.deleteIfExists(root.resolve(s"${location.value}.tmp")).blocking[F]
          exists <- Files.exists(root).blocking[F]
          _      <- if exists then force(root, StandardOpenOption.READ) else ().pure[F]
        yield RemoveResult.Removed
      remove.handleError(error => RemoveResult.Failed(error))

    private def force(path: Path, access: StandardOpenOption) =
      Resource.fromAutoCloseable(FileChannel.open(path, access).blocking[F])
        .use(channel => channel.force(true).blocking[F])

    private def resolveThumbnail(location: ThumbnailLocation) =
      Async[F].blocking:
        val directory = root.toRealPath()
        val file      = directory.resolve(s"${location.value}.jpg").toRealPath()
        val contained = file.startsWith(directory) && Files.readAttributes(file, classOf[BasicFileAttributes]).isRegularFile
        Option.when(contained)(file)

    private def computeVersion(bytes: Array[Byte]) =
      HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).refineUnsafe[Not[Blank]]

    private def checkRoot() =
      val valid = root.isAbsolute && root.normalize().toString === root.toString
      Either.cond(valid, (), new IllegalArgumentException("Expected an absolute normalized thumbnail root"))
