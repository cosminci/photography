package photography.domain.layout

import cats.data.NonEmptyList
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import java.util.UUID

type PositionOnSpread = Double :| (GreaterEqual[0.0] & Less[1.7976931348623157e308])

type DistanceWithinSpread = Double :| (Positive & Less[1.7976931348623157e308])

type SpreadTemplateName = String :| Not[Empty]

opaque type SpreadTemplateId = UUID
object SpreadTemplateId:
  def apply(value: UUID): SpreadTemplateId         = value
  extension (id: SpreadTemplateId) def value: UUID = id

final case class SlotBox(x: PositionOnSpread, y: PositionOnSpread, width: DistanceWithinSpread, height: DistanceWithinSpread) derives CanEqual:
  def contentBox: Either[String, SlotBox] =
    val left   = if touchesLeft then SlotBox.horizontalMargin else x: Double
    val top    = if touchesTop then SlotBox.verticalMargin else y: Double
    val right  = if touchesRight then 100.0 - SlotBox.horizontalMargin else x + width
    val bottom = if touchesBottom then 100.0 - SlotBox.verticalMargin else y + height
    SlotBox.fromEdges(left, top, right, bottom)

  def visibleBox(bleed: Set[SpreadEdge]): Either[String, SlotBox] =
    contentBox.flatMap: content =>
      val left   = if bleed.contains(SpreadEdge.Left) then 0.0 else content.x: Double
      val top    = if bleed.contains(SpreadEdge.Top) then 0.0 else content.y: Double
      val right  = if bleed.contains(SpreadEdge.Right) then 100.0 else content.x + content.width
      val bottom = if bleed.contains(SpreadEdge.Bottom) then 100.0 else content.y + content.height
      SlotBox.fromEdges(left, top, right, bottom)

  def eligibleBleed: Set[SpreadEdge] =
    val outward = Option.when(touchesLeft)(SpreadEdge.Left).toList.toSet ++ Option.when(touchesRight)(SpreadEdge.Right).toList.toSet
    if outward.nonEmpty && touchesTop && touchesBottom then outward ++ Set(SpreadEdge.Top, SpreadEdge.Bottom) else outward

  private def touchesLeft   = x <= SlotBox.horizontalMargin + SlotBox.tolerance
  private def touchesRight  = x + width >= 100.0 - SlotBox.horizontalMargin - SlotBox.tolerance
  private def touchesTop    = y <= SlotBox.verticalMargin + SlotBox.tolerance
  private def touchesBottom = y + height >= 100.0 - SlotBox.verticalMargin - SlotBox.tolerance

object SlotBox:
  private val horizontalMargin = 700.0 / InteriorDimensions.width
  private val verticalMargin   = 700.0 / InteriorDimensions.height
  private val tolerance        = 0.001

  private def fromEdges(left: Double, top: Double, right: Double, bottom: Double) =
    for
      x      <- left.refineEither[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
      y      <- top.refineEither[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
      width  <- (right - left).refineEither[Positive & Less[1.7976931348623157e308]]
      height <- (bottom - top).refineEither[Positive & Less[1.7976931348623157e308]]
    yield SlotBox(x, y, width, height)

sealed trait SpreadTemplate:
  def id: SpreadTemplateId
  def name: SpreadTemplateName

object SpreadTemplate:
  final case class AlbumStart(id: SpreadTemplateId, name: SpreadTemplateName, photoSlots: Vector[SlotBox], textSlot: SlotBox)   extends SpreadTemplate
  final case class ChapterStart(id: SpreadTemplateId, name: SpreadTemplateName, photoSlots: Vector[SlotBox], textSlot: SlotBox) extends SpreadTemplate
  final case class PhotosOnly(id: SpreadTemplateId, name: SpreadTemplateName, photoSlots: NonEmptyList[SlotBox])                extends SpreadTemplate
