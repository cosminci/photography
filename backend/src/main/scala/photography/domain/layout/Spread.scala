package photography.domain.layout

import cats.data.NonEmptyList
import cats.syntax.all.*

import java.util.UUID

opaque type SpreadId = UUID
object SpreadId:
  def apply(value: UUID): SpreadId         = value
  extension (id: SpreadId) def value: UUID = id

sealed trait Spread:
  def id: SpreadId
  def template: SpreadTemplateId
  def placements: Vector[SpreadPlacement]

  def photos: Vector[SpreadSlot.Photo] = this match
    case value: Spread.AlbumStart   => value.photoSlots
    case value: Spread.ChapterStart => value.photoSlots
    case value: Spread.PhotosOnly   => value.photoSlots.toList.toVector

  def bleedState: Spread.BleedState =
    if photos.forall(_.bleed.isEmpty) then Spread.BleedState.Disabled
    else if photos.forall(slot => slot.bleed === slot.box.eligibleBleed) then Spread.BleedState.Enabled
    else Spread.BleedState.Mixed

  def withBleed(enabled: Boolean): Either[String, Spread] =
    if enabled && photos.forall(_.box.eligibleBleed.isEmpty) then "This spread has no eligible outer photo slots.".asLeft[Spread]
    else
      val update: SpreadSlot.Photo => SpreadSlot.Photo = slot => slot.copy(bleed = if enabled then slot.box.eligibleBleed else Set.empty)
      val changed                                      = this match
        case value: Spread.AlbumStart   => value.copy(photoSlots = value.photoSlots.map(update))
        case value: Spread.ChapterStart => value.copy(photoSlots = value.photoSlots.map(update))
        case value: Spread.PhotosOnly   => value.copy(photoSlots = value.photoSlots.map(update))
      changed.asRight[String]

object Spread:
  enum BleedState derives CanEqual:
    case Disabled, Enabled, Mixed

  final case class AlbumStart(
      id: SpreadId,
      template: SpreadTemplateId,
      photoSlots: Vector[SpreadSlot.Photo],
      textSlot: SpreadSlot.Text,
      placements: Vector[SpreadPlacement]
  ) extends Spread
  final case class ChapterStart(
      id: SpreadId,
      template: SpreadTemplateId,
      photoSlots: Vector[SpreadSlot.Photo],
      textSlot: SpreadSlot.Text,
      placements: Vector[SpreadPlacement]
  ) extends Spread
  final case class PhotosOnly(
      id: SpreadId,
      template: SpreadTemplateId,
      photoSlots: NonEmptyList[SpreadSlot.Photo],
      placements: Vector[SpreadPlacement]
  ) extends Spread
