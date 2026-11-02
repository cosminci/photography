package photography.adapters.sqlite.photo

import cats.effect.Async
import cats.syntax.all.*
import org.typelevel.doobie.{ConnectionIO, Fragment, Transactor}
import org.typelevel.doobie.implicits.*
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.album.{AlbumId, ChapterId, PhotoId}
import photography.domain.editing.EditingTarget
import photography.domain.layout.SpreadId
import org.typelevel.log4cats.Logger
import photography.ports.photo.PhotoPlacementRepository
import photography.ports.photo.PhotoPlacementRepository.{FindResult, InspectResult, TargetRead, TargetResult}
import java.util.UUID

object SqlitePhotoPlacementRepository:
  def make: PhotoPlacementRepository[ConnectionIO] = new LiveSqlitePhotoPlacementRepository

  def makeTargetRead[F[_]: Async: Logger](transactor: Transactor[F]): TargetRead[F] = new LiveTargetRead(transactor)

  final private class LiveTargetRead[F[_]: Async: Logger](transactor: Transactor[F]) extends TargetRead[F]:
    override def findTarget(chapter: ChapterId, photo: PhotoId): F[TargetResult] =
      val select = sql"""SELECT placed.kind, spread.spread_id, cover.album_id
        FROM photo p LEFT JOIN placement placed ON placed.photo_id = p.id
        LEFT JOIN spread_photo_slot spread ON spread.id = placed.spread_photo_slot_id
        LEFT JOIN cover_slot cover ON cover.id = placed.cover_slot_id
        WHERE p.id = ${photo.value} AND p.chapter_id = ${chapter.value}"""
      val read = select.query[(Option[String], Option[UUID], Option[UUID])].option.flatMap:
        case None                                       => (TargetResult.PhotoNotFound: TargetResult).pure[ConnectionIO]
        case Some((None, None, None))                   => (TargetResult.Found(maybeTarget = None): TargetResult).pure[ConnectionIO]
        case Some((Some("spread"), Some(spread), None)) =>
          (TargetResult.Found(EditingTarget.Spread(SpreadId(spread)).some): TargetResult).pure[ConnectionIO]
        case Some((Some("cover"), None, Some(album))) =>
          (TargetResult.Found(EditingTarget.Cover(AlbumId(album)).some): TargetResult).pure[ConnectionIO]
        case _ => new IllegalArgumentException("Invalid photo placement target").raiseError[ConnectionIO, TargetResult]
      read.transact(transactor).handleErrorWith: error =>
        Logger[F].error(error)("SQLite photo target read failed").as(TargetResult.Failed)

  final private class LiveSqlitePhotoPlacementRepository extends PhotoPlacementRepository[ConnectionIO]:
    override def findPlacedPhotos(chapter: ChapterId): ConnectionIO[FindResult] =
      sql"""SELECT DISTINCT p.id FROM photo p
        JOIN placement placed ON placed.photo_id = p.id
        WHERE p.chapter_id = ${chapter.value}"""
        .query[UUID].to[Vector].map(ids => FindResult.Found(photos = ids.map(PhotoId(_)).toSet))

    override def inspect(album: AlbumId, maybeChapter: Option[ChapterId], photo: PhotoId): ConnectionIO[InspectResult] =
      val chapterScope = maybeChapter.fold(Fragment.empty)(chapter => fr"AND c.id = ${chapter.value}")
      val membership   = fr"c.album_id = ${album.value}" ++ chapterScope
      val select       = fr"SELECT (" ++ membership ++
        fr"""), EXISTS(SELECT 1 FROM placement WHERE photo_id = p.id)
              FROM photo p JOIN chapter c ON c.id = p.chapter_id WHERE p.id = ${photo.value}"""
      select.query[(Boolean, Boolean)].option.map:
        _.fold[InspectResult](InspectResult.NotFound) { case (member, placed) => InspectResult.Found(inCollection = member, placed = placed) }
