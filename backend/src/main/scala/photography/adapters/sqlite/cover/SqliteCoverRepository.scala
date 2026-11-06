package photography.adapters.sqlite.cover

import cats.data.NonEmptyVector
import cats.syntax.all.*
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits.*
import photography.adapters.sqlite.SqliteCodecs.given
import photography.adapters.sqlite.SqliteAlbumQueries
import photography.domain.album.{AlbumId, PhotoId, InteriorSpreadCount}
import photography.domain.layout.*
import photography.ports.cover.CoverRepository
import photography.ports.cover.CoverRepository.{Stored, ReadResult, ReplaceResult, SetSpineOwnerResult}
import java.util.UUID

object SqliteCoverRepository:
  def make: CoverRepository[ConnectionIO] = new LiveSqliteCoverRepository

  final private class LiveSqliteCoverRepository extends CoverRepository[ConnectionIO]:
    override def read(album: AlbumId): ConnectionIO[ReadResult] =
      sql"SELECT spine_owner FROM cover WHERE album_id = ${album.value}".query[String].option.flatMap:
        case None        => ReadResult.NotFound.pure[ConnectionIO]
        case Some(spine) =>
          for
            slots      <- sql"SELECT id, side FROM cover_slot WHERE album_id = ${album.value} ORDER BY side".query[(UUID, String)].to[Vector]
            cover      <- decodeCover(album, spine, slots = slots).liftTo[ConnectionIO]
            placements <- loadPlacements(album)
            inputs     <- loadSizing(album)
          yield
            val (spreads, specifications) = inputs
            ReadResult.Found(Stored(cover, placements, spreads, specifications))

    override def replace(album: AlbumId, placements: Vector[CoverPlacement]): ConnectionIO[ReplaceResult] =
      val exists = sql"SELECT EXISTS(SELECT 1 FROM cover WHERE album_id = ${album.value})".query[Boolean].unique
      val remove = sql"DELETE FROM placement WHERE cover_slot_id IN (SELECT id FROM cover_slot WHERE album_id = ${album.value})"
      exists.flatMap:
        case false => ReplaceResult.NotFound.pure[ConnectionIO]
        case true  => (remove.update.run >> placements.traverse_(insertPlacement(album, _))).as(ReplaceResult.Replaced)

    override def setSpineOwner(album: AlbumId, owner: SpineOwner): ConnectionIO[SetSpineOwnerResult] =
      val encoded = owner match
        case SpineOwner.Front => "front"
        case SpineOwner.Back  => "back"
      sql"UPDATE cover SET spine_owner = $encoded WHERE album_id = ${album.value}".update.run.map: changed =>
        if changed === 0 then SetSpineOwnerResult.NotFound else SetSpineOwnerResult.Updated

    private def loadSizing(album: AlbumId) =
      val count   = SqliteAlbumQueries.interiorSpreadCount(album).query[InteriorSpreadCount].unique
      val catalog = sql"SELECT page_limit, width_pixels, height_pixels, spine_pixels FROM cover_specification"
        .query[(Int, Int, Int, Int)].to[Vector]
      for
        spreads        <- count
        rows           <- catalog
        specifications <- rows.traverse(CoverSpecification.from.tupled).leftMap(
          // $COVERAGE-OFF$
          new IllegalArgumentException(_)
          // $COVERAGE-ON$
        ).liftTo[ConnectionIO]
        nonempty <- NonEmptyVector.fromVector(specifications).toRight(new IllegalStateException("Missing cover specifications")).liftTo[ConnectionIO]
      yield (spreads, nonempty)

    private def loadPlacements(album: AlbumId) =
      sql"""SELECT p.id, p.cover_slot_id, p.photo_id, p.shift_x, p.shift_y, p.mirrored, p.zoom
            FROM placement p JOIN cover_slot s ON s.id = p.cover_slot_id WHERE s.album_id = ${album.value}
            ORDER BY p.id""".query[(UUID, UUID, UUID, InDesignPoints, InDesignPoints, Boolean, Zoom)].to[Vector].map:
        _.map { case (id, slot, photo, x, y, mirrored, zoom) =>
          val transform = PhotoTransform(Shift(x = x, y = y), mirrored = mirrored, zoom = zoom)
          CoverPlacement(CoverPlacementId(id), CoverSlotId(slot), PhotoId(photo), transform)
        }

    private def insertPlacement(album: AlbumId, placement: CoverPlacement) =
      val transform = placement.transform
      sql"""INSERT INTO placement (id, photo_id, kind, spread_photo_slot_id, cover_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner)
            SELECT ${placement.id.value}, ${placement.photo.value}, 'cover', NULL, id,
                   ${transform.shift.x}, ${transform.shift.y}, ${transform.mirrored}, ${transform.zoom}, NULL
            FROM cover_slot WHERE id = ${placement.slot.value} AND album_id = ${album.value}""".update.run
        .ensure(new IllegalStateException("Cover slot no longer exists"))(_ === 1).void

    private def decodeCover(album: AlbumId, spine: String, slots: Vector[(UUID, String)]) =
      val maybeOwner = spine match
        case "front" => SpineOwner.Front.some
        case "back"  => SpineOwner.Back.some
        // $COVERAGE-OFF$
        case _ => none[SpineOwner]
        // $COVERAGE-ON$
      val maybeCover =
        for
          owner <- maybeOwner
          front <- slots.collectFirst { case (id, "front") => CoverSlot(CoverSlotId(id)) }
          back  <- slots.collectFirst { case (id, "back") => CoverSlot(CoverSlotId(id)) }
          if slots.size === 2
        yield Cover(album, owner, front, back)
      maybeCover.toRight(new IllegalArgumentException("Invalid persisted cover"))
