package photography.adapters.sqlite.photo

import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.typelevel.doobie.{ConnectionIO, Meta, Read}
import org.typelevel.doobie.free.connection
import org.typelevel.doobie.implicits.*
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.album.*
import photography.ports.photo.PhotoRepository
import photography.ports.photo.PhotoRepository.*
import java.time.LocalDateTime
import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.util.{Locale, UUID}

object SqlitePhotoRepository:
  def make: PhotoRepository[ConnectionIO] = new LiveSqlitePhotoRepository

  final private class LiveSqlitePhotoRepository extends PhotoRepository[ConnectionIO]:
    override def saveCaption(chapter: ChapterId, photo: PhotoId, text: String): ConnectionIO[SaveCaptionResult] =
      writeCaption(chapter, photo, maybeCaption = Option.when(text.nonEmpty)(text)).map:
        case Right(1)    => SaveCaptionResult.Saved
        case Right(_)    => SaveCaptionResult.PhotoNotFound
        case Left(cause) => SaveCaptionResult.Failed(cause)

    override def listChapter(chapter: ChapterId): ConnectionIO[ListResult] =
      sql"""
        SELECT
          p.id,
          p.chapter_id,
          c.source_folder,
          p.source,
          p.source_hash,
          p.captured_local,
          p.width,
          p.height,
          p.thumbnail,
          p.thumbnail_version,
          p.thumbnail_width,
          p.thumbnail_height,
          p.thumbnail_quality,
          p.thumbnail_bytes,
          p.caption
        FROM photo p
        JOIN chapter c ON c.id = p.chapter_id
        WHERE p.chapter_id = ${chapter.value}
        ORDER BY p.id
      """
        .query[Photo].to[Vector].map(photos => ListResult.Listed(photos = photos))

    override def save(photo: Photo): ConnectionIO[WriteResult] =
      sql"""
        INSERT INTO photo (
          id,
          chapter_id,
          source,
          source_hash,
          captured_local,
          width,
          height,
          thumbnail,
          thumbnail_version,
          thumbnail_width,
          thumbnail_height,
          thumbnail_quality,
          thumbnail_bytes,
          caption
        )
        VALUES (
          ${photo.id.value},
          ${photo.chapter.value},
          ${photo.original.filename},
          ${photo.metadata.hash},
          ${photo.metadata.maybeCapturedLocal},
          ${photo.metadata.dimensions.width},
          ${photo.metadata.dimensions.height},
          ${photo.thumbnail.location.value},
          ${photo.thumbnail.version},
          ${photo.thumbnail.dimensions.width},
          ${photo.thumbnail.dimensions.height},
          ${photo.thumbnail.quality},
          ${photo.thumbnail.bytes},
          ${photo.maybeCaption}
        )
        ON CONFLICT (id) DO UPDATE SET
          source = excluded.source,
          source_hash = excluded.source_hash,
          captured_local = excluded.captured_local,
          width = excluded.width,
          height = excluded.height,
          thumbnail = excluded.thumbnail,
          thumbnail_version = excluded.thumbnail_version,
          thumbnail_width = excluded.thumbnail_width,
          thumbnail_height = excluded.thumbnail_height,
          thumbnail_quality = excluded.thumbnail_quality,
          thumbnail_bytes = excluded.thumbnail_bytes
        WHERE photo.chapter_id = excluded.chapter_id
      """.update.run.map:
        case 1 => WriteResult.Applied
        case _ => WriteResult.Failed

    override def remove(photo: PhotoId): ConnectionIO[WriteResult] =
      sql"DELETE FROM photo WHERE id = ${photo.value}".update.run.as(WriteResult.Applied)

    override def recordWrite(intent: PhotoWriteIntent): ConnectionIO[RecordWriteResult] =
      val (kind, location, maybePrevious) = intent match
        case publication: PhotoWriteIntent.Publication => ("publication", publication.location.value, publication.maybePrevious.map(_.value))
        case retirement: PhotoWriteIntent.Retirement   => ("retirement", retirement.location.value, none[UUID])
      sql"""INSERT INTO photo_write_intent (id, chapter_id, photo_id, kind, location, previous)
        VALUES (${intent.id.value}, ${intent.chapter.value}, ${intent.photo.value}, $kind, $location, $maybePrevious)"""
        .update.run.as(RecordWriteResult.Recorded)

    override def completeWrite(id: PhotoWriteIntentId): ConnectionIO[CompleteWriteResult] =
      sql"DELETE FROM photo_write_intent WHERE id = ${id.value}".update.run.as(CompleteWriteResult.Completed)

    override def listUnfinishedWrites: ConnectionIO[ListUnfinishedWritesResult] =
      sql"SELECT id, chapter_id, photo_id, kind, location, previous FROM photo_write_intent ORDER BY id"
        .query[(UUID, UUID, UUID, String, UUID, Option[UUID])].to[Vector].flatMap(rows => rows.traverse(decodeIntent).liftTo[ConnectionIO])
        .map(intents => ListUnfinishedWritesResult.Listed(intents = intents))

    private def writeCaption(chapter: ChapterId, photo: PhotoId, maybeCaption: Option[String]) =
      val update = sql"UPDATE photo SET caption = $maybeCaption WHERE chapter_id = ${chapter.value} AND id = ${photo.value}".update.run
        .flatMap(rows => checkCaptionWrite(chapter, photo, rows = rows))
      sql"SAVEPOINT photo_caption".update.run >> update.attempt.flatTap:
        case Left(cause) =>
          val rollback = sql"ROLLBACK TO photo_caption".update.run >> sql"RELEASE photo_caption".update.run.void
          rollback.handleErrorWith(error => connection.delay(cause.addSuppressed(error)) >> cause.raiseError[ConnectionIO, Unit])
        case Right(_) => sql"RELEASE photo_caption".update.run.void

    private def checkCaptionWrite(chapter: ChapterId, photo: PhotoId, rows: Int) =
      if rows === 1 then rows.pure[ConnectionIO]
      else
        sql"SELECT EXISTS(SELECT 1 FROM photo WHERE chapter_id = ${chapter.value} AND id = ${photo.value})".query[Boolean].unique
          .ensure(new IllegalStateException("Caption update did not change the existing photo"))(!_).as(0)

    private def decodeIntent(row: (UUID, UUID, UUID, String, UUID, Option[UUID])) =
      val (id, chapter, photo, kind, location, maybePrevious) = row
      kind match
        case "publication" =>
          PhotoWriteIntent.Publication(
            PhotoWriteIntentId(id),
            ChapterId(chapter),
            PhotoId(photo),
            ThumbnailLocation(location),
            maybePrevious = maybePrevious.map(ThumbnailLocation(_))
          )
            .asRight[IllegalArgumentException]
        case "retirement" if maybePrevious.isEmpty =>
          PhotoWriteIntent.Retirement(PhotoWriteIntentId(id), ChapterId(chapter), PhotoId(photo), ThumbnailLocation(location))
            .asRight[IllegalArgumentException]
        case _ => new IllegalArgumentException("Invalid photo write evidence").asLeft[PhotoWriteIntent]

    private given refinedStringMeta[C](using RuntimeConstraint[String, C]): Meta[String :| C] =
      Meta[String].tiemap[String :| C](_.refineEither[C])(value => value: String)
    private given refinedIntMeta[C](using RuntimeConstraint[Int, C]): Meta[Int :| C] =
      Meta[Int].tiemap[Int :| C](_.refineEither[C])(value => value: Int)
    private val captureFormat         = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS", Locale.ROOT)
    private given Meta[LocalDateTime] = Meta[String].tiemap { value =>
      Either.catchOnly[DateTimeParseException](LocalDateTime.parse(value, captureFormat)).leftMap(_.getMessage).flatMap: parsed =>
        Either.cond(captureFormat.format(parsed) === value, parsed, "Noncanonical capture time")
    }(captureFormat.format)
    private given Meta[ThumbnailQuality] = Meta[String].tiemap {
      case "low"      => ThumbnailQuality.Low.asRight[String]
      case "standard" => ThumbnailQuality.Standard.asRight[String]
      case "high"     => ThumbnailQuality.High.asRight[String]
      case _          => "Invalid thumbnail quality".asLeft[ThumbnailQuality]
    } {
      case ThumbnailQuality.Low      => "low"
      case ThumbnailQuality.Standard => "standard"
      case ThumbnailQuality.High     => "high"
    }
    private given Read[PhotoDimensions]     = Read[(PixelCount, PixelCount)].map(PhotoDimensions.apply.tupled)
    private given Read[ThumbnailDimensions] = Read[(ThumbnailPixelCount, ThumbnailPixelCount)].map(ThumbnailDimensions.apply.tupled)
    private given Read[PhotoMetadata]       =
      Read[(String :| Not[Blank], Option[LocalDateTime], PhotoDimensions)].map(PhotoMetadata.apply.tupled)
    private given Read[Thumbnail] =
      Read[(UUID, String :| Not[Blank], ThumbnailDimensions, ThumbnailQuality, ThumbnailByteCount)].map:
        case (location, version, dimensions, quality, bytes) =>
          Thumbnail(ThumbnailLocation(location), version = version, dimensions, quality, bytes = bytes)
    private given Read[Photo] =
      Read[(UUID, UUID, PhotoSource, PhotoFilename, PhotoMetadata, Thumbnail, Option[String])].map:
        case (id, chapter, source, filename, metadata, thumbnail, maybeCaption) =>
          Photo(PhotoId(id), ChapterId(chapter), OriginalPhotoLocation(source, filename), metadata, thumbnail, maybeCaption = maybeCaption)
