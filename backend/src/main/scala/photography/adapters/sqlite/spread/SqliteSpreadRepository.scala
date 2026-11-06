package photography.adapters.sqlite.spread

import cats.data.NonEmptyList
import cats.syntax.all.*
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.ConnectionIO
import photography.adapters.sqlite.SqliteCodecs.given
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
      insert.update.run.ensure(
        // A single-row VALUES insert either applies once or fails; no application IGNORE triggers exist.
        // $COVERAGE-OFF$
        new IllegalStateException("Expected one persisted row")
        // $COVERAGE-ON$
      )(_ === 1) >>
        spread.photoSlots.traverse_(insertSlot(spread.id, _))

    override def replace(spread: Spread): ConnectionIO[Unit] =
      val id               = spread.id.value
      val update           = sql"UPDATE spread SET template_id = ${spread.template.value} WHERE id = $id"
      val deletePlacements = sql"DELETE FROM placement WHERE spread_photo_slot_id IN (SELECT id FROM spread_photo_slot WHERE spread_id = $id)"
      for
        _ <- update.update.run.ensure(
          // SpreadManager acquires the target under mutation coordination before replacement.
          // $COVERAGE-OFF$
          new IllegalStateException("Expected one persisted row")
          // $COVERAGE-ON$
        )(_ === 1)
        _ <- deletePlacements.update.run
        _ <- sql"DELETE FROM spread_photo_slot WHERE spread_id = $id".update.run
        _ <- sql"DELETE FROM spread_text_slot WHERE spread_id = $id".update.run
        _ <- collectSlots(spread).traverse_(insertSlot(spread.id, _))
        _ <- spread.placements.traverse_(insertPlacement)
      // Scala's trailing-Unit map is eliminated; scoverage retains a synthetic yield branch.
      // $COVERAGE-OFF$
      yield ()
      // $COVERAGE-ON$

    override def reorder(chapter: ChapterId, order: NonEmptyList[SpreadId]): ConnectionIO[Unit] =
      val supplied = order.toList.map(_.value)
      val shift    = sql"UPDATE spread SET position = position + 1000000 WHERE chapter_id = ${chapter.value} AND kind = 'photos_only'"
      for
        stored <- sql"SELECT id FROM spread WHERE chapter_id = ${chapter.value}".query[UUID].to[List]
        _      <- validateOrder(stored = stored, supplied = supplied).liftTo[ConnectionIO]
        _      <- shift.update.run
        _      <- supplied.zipWithIndex.traverse_ { case (id, position) => assignPosition(id, position = position) }
      // Scala's trailing-Unit map is eliminated; scoverage retains a synthetic yield branch.
      // $COVERAGE-OFF$
      yield ()
      // $COVERAGE-ON$

    override def remove(spread: SpreadId): ConnectionIO[Unit] =
      sql"DELETE FROM spread WHERE id = ${spread.value}".update.run.ensure(
        // SpreadManager acquires the target under mutation coordination before removal.
        // $COVERAGE-OFF$
        new IllegalStateException("Expected one removed spread")
        // $COVERAGE-ON$
      )(_ === 1).void

    private def assignPosition(id: UUID, position: Int) =
      sql"UPDATE spread SET position = $position WHERE id = $id".update.run.ensure(
        // validateOrder proves every supplied identity exists in this transaction.
        // $COVERAGE-OFF$
        new IllegalStateException("Expected one persisted row")
        // $COVERAGE-ON$
      )(_ === 1).void

    private def validateOrder(stored: List[UUID], supplied: List[UUID]) =
      val containsEverySpread = stored.size === supplied.size && stored.toSet === supplied.toSet
      Either.cond(containsEverySpread, (), new IllegalArgumentException("Order must contain every spread of the chapter exactly once"))

    private def collectSlots(spread: Spread) = spread match
      case value: Spread.AlbumStart   => value.photoSlots.map(slot => slot: SpreadSlot) :+ value.textSlot
      case value: Spread.ChapterStart => value.photoSlots.map(slot => slot: SpreadSlot) :+ value.textSlot
      case value: Spread.PhotosOnly   => value.photoSlots.toList.toVector.map(slot => slot: SpreadSlot)

    private def insertPlacement(placement: SpreadPlacement) =
      val transform = placement.transform
      sql"""INSERT INTO placement (id, photo_id, kind, spread_photo_slot_id, cover_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner)
            VALUES (${placement.id.value}, ${placement.photo.value}, 'spread', ${placement.slot.value}, NULL,
                    ${transform.shift.x}, ${transform.shift.y}, ${transform.mirrored}, ${transform.zoom}, ${placement.captionCorner})""".update.run
        .ensure(
          // A single-row VALUES insert either applies once or fails; no application IGNORE triggers exist.
          // $COVERAGE-OFF$
          new IllegalStateException("Expected one persisted row")
          // $COVERAGE-ON$
        )(_ === 1).void

    private def insertSlot(spread: SpreadId, slot: SpreadSlot) =
      slot match
        case SpreadSlot.Photo(id, box, bleed) =>
          for
            _ <- sql"INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height) VALUES (${id.value}, ${spread.value}, $box)".update.run
            _ <- bleed.toList.traverse_(edge => sql"INSERT INTO spread_photo_slot_bleed (slot_id, edge) VALUES (${id.value}, $edge)".update.run.void)
          // Scala's trailing-Unit map is eliminated; scoverage retains a synthetic yield branch.
          // $COVERAGE-OFF$
          yield ()
          // $COVERAGE-ON$
        case SpreadSlot.Text(id, box) =>
          sql"INSERT INTO spread_text_slot (id, spread_id, x, y, width, height) VALUES (${id.value}, ${spread.value}, $box)".update.run.void
