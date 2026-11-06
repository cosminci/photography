package photography.adapters.sqlite.spread

import cats.data.NonEmptyList
import cats.syntax.all.*
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.ConnectionIO
import photography.adapters.sqlite.SqliteCodecs.given
import io.github.iltotore.iron.doobie.given
import photography.domain.album.ChapterId
import photography.domain.layout.*
import photography.ports.spread.SpreadRepository
import java.util.UUID

object SqliteSpreadRepository:
  def make: SpreadRepository[ConnectionIO] = new LiveSqliteSpreadRepository

  final private class LiveSqliteSpreadRepository extends SpreadRepository[ConnectionIO]:
    override def insert(chapter: ChapterId, spread: Spread.PhotosOnly): ConnectionIO[Unit] =
      val insert = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
                        VALUES (${spread.id.value}, ${spread.template.value}, 'photos_only', ${chapter.value},
                        (SELECT coalesce(max(position), 0) + 1 FROM spread WHERE chapter_id = ${chapter.value}))"""
      val incompleteWrite = new IllegalStateException("Expected one persisted row")
      insert.update.run.ensure(incompleteWrite)(_ === 1) >> spread.photoSlots.traverse_(insertSlot(spread.id, _))

    override def replace(spread: Spread): ConnectionIO[Unit] =
      val id              = spread.id.value
      val update          = sql"UPDATE spread SET template_id = ${spread.template.value} WHERE id = $id"
      val incompleteWrite = new IllegalStateException("Expected one persisted row")
      update.update.run.ensure(incompleteWrite)(_ === 1) >>
        sql"DELETE FROM spread_photo_slot WHERE spread_id = $id".update.run >>
        sql"DELETE FROM spread_text_slot WHERE spread_id = $id".update.run >>
        collectSlots(spread).traverse_(insertSlot(spread.id, _)) >>
        spread.placements.traverse_(insertPlacement(spread.id, _))

    override def replacePlacements(spread: SpreadId, placements: Vector[SpreadPlacement]): ConnectionIO[Unit] =
      requireSpread(spread) >>
        sql"""DELETE FROM placement WHERE spread_photo_slot_id IN
          (SELECT id FROM spread_photo_slot WHERE spread_id = ${spread.value})""".update.run >>
        placements.traverse_(insertPlacement(spread, _))

    override def replaceBleed(spread: SpreadId, edges: Map[PhotoSpreadSlotId, Set[SpreadEdge]]): ConnectionIO[Unit] =
      val slots = sql"SELECT id FROM spread_photo_slot WHERE spread_id = ${spread.value}".query[UUID].to[Set]
      requireSpread(spread) >>
        slots.ensure(new IllegalArgumentException("Bleed must describe exactly this spread's photo slots"))(_ === edges.keySet.map(_.value)) >>
        sql"""DELETE FROM spread_photo_slot_bleed WHERE slot_id IN
          (SELECT id FROM spread_photo_slot WHERE spread_id = ${spread.value})""".update.run >>
        edges.toList.traverse_ { case (slot, directions) => insertEdges(slot, directions) }

    override def reorder(chapter: ChapterId, order: NonEmptyList[SpreadId]): ConnectionIO[Unit] =
      val supplied = order.toList.map(_.value)
      val shift    = sql"UPDATE spread SET position = position + 1000000 WHERE chapter_id = ${chapter.value} AND kind = 'photos_only'"
      sql"SELECT id FROM spread WHERE chapter_id = ${chapter.value}".query[UUID].to[List].flatMap: stored =>
        validateOrder(stored = stored, supplied = supplied).liftTo[ConnectionIO] >> shift.update.run >>
          supplied.zipWithIndex.traverse_ { case (id, position) => assignPosition(id, position = position) }

    override def remove(spread: SpreadId): ConnectionIO[Unit] =
      val missingSpread = new IllegalStateException("Expected one removed spread")
      sql"DELETE FROM spread WHERE id = ${spread.value}".update.run.ensure(missingSpread)(_ === 1).void

    private def assignPosition(id: UUID, position: Int) =
      val incompleteWrite = new IllegalStateException("Expected one persisted row")
      sql"UPDATE spread SET position = $position WHERE id = $id".update.run.ensure(incompleteWrite)(_ === 1).void

    private def validateOrder(stored: List[UUID], supplied: List[UUID]) =
      val containsEverySpread = stored.size === supplied.size && stored.toSet === supplied.toSet
      Either.cond(containsEverySpread, (), new IllegalArgumentException("Order must contain every spread of the chapter exactly once"))

    private def collectSlots(spread: Spread) = spread match
      case value: Spread.AlbumStart   => value.photoSlots.map(slot => slot: SpreadSlot) :+ value.textSlot
      case value: Spread.ChapterStart => value.photoSlots.map(slot => slot: SpreadSlot) :+ value.textSlot
      case value: Spread.PhotosOnly   => value.photoSlots.toList.toVector.map(slot => slot: SpreadSlot)

    private def requireSpread(spread: SpreadId) =
      sql"SELECT EXISTS(SELECT 1 FROM spread WHERE id = ${spread.value})".query[Boolean].unique
        .ensure(new IllegalStateException("Expected an existing spread"))(identity).void

    private def insertPlacement(spread: SpreadId, placement: SpreadPlacement) =
      val transform       = placement.transform
      val incompleteWrite = new IllegalStateException("Expected one persisted row")
      sql"""INSERT INTO placement (id, photo_id, kind, spread_photo_slot_id, cover_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner)
            SELECT ${placement.id.value}, ${placement.photo.value}, 'spread', id, NULL,
                    ${transform.shift.x}, ${transform.shift.y}, ${transform.mirrored}, ${transform.zoom}, ${placement.captionCorner}
            FROM spread_photo_slot WHERE id = ${placement.slot.value} AND spread_id = ${spread.value}""".update.run
        .ensure(incompleteWrite)(_ === 1).void

    private def insertSlot(spread: SpreadId, slot: SpreadSlot) =
      slot match
        case SpreadSlot.Photo(id, box, bleed) =>
          val photo = sql"INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height) VALUES (${id.value}, ${spread.value}, $box)"
          photo.update.run >> insertEdges(id, bleed)
        case SpreadSlot.Text(id, box) =>
          sql"INSERT INTO spread_text_slot (id, spread_id, x, y, width, height) VALUES (${id.value}, ${spread.value}, $box)".update.run.void

    private def insertEdges(slot: PhotoSpreadSlotId, edges: Set[SpreadEdge]) =
      edges.toList.traverse_(edge => sql"INSERT INTO spread_photo_slot_bleed (slot_id, edge) VALUES (${slot.value}, $edge)".update.run.void)
