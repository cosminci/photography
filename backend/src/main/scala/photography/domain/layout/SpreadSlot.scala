package photography.domain.layout

import java.util.UUID

enum SpreadEdge derives CanEqual:
  case Top, Bottom, Left, Right

sealed trait SpreadSlot derives CanEqual

object SpreadSlot:
  final case class Photo(id: PhotoSpreadSlotId, box: SlotBox, bleed: Set[SpreadEdge]) extends SpreadSlot:
    def contentBox: Either[String, SlotBox] = box.contentBox
    def visibleBox: Either[String, SlotBox] = box.visibleBox(bleed)
  final case class Text(id: TextSpreadSlotId, box: SlotBox) extends SpreadSlot

opaque type PhotoSpreadSlotId = UUID
object PhotoSpreadSlotId:
  def apply(value: UUID): PhotoSpreadSlotId         = value
  extension (id: PhotoSpreadSlotId) def value: UUID = id

opaque type TextSpreadSlotId = UUID
object TextSpreadSlotId:
  def apply(value: UUID): TextSpreadSlotId         = value
  extension (id: TextSpreadSlotId) def value: UUID = id
