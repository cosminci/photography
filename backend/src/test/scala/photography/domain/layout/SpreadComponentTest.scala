package photography.domain.layout

import cats.data.NonEmptyList
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import java.util.UUID

class SpreadComponentTest extends FunSuite:
  private val left   = SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(0L, 1L)), SlotBox(x = 0.0, y = 0.0, width = 49.8, height = 100.0), Set.empty)
  private val right  = SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(0L, 2L)), SlotBox(x = 50.2, y = 0.0, width = 49.8, height = 100.0), Set.empty)
  private val spread = Spread.PhotosOnly(SpreadId(new UUID(0L, 3L)), SpreadTemplateId(new UUID(0L, 4L)), NonEmptyList.of(left, right), Vector.empty)

  test("should project all enabled edges and preserve a genuinely interior frame"):
    val full  = SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0)
    val inner = SlotBox(x = 10.0, y = 20.0, width = 30.0, height = 40.0)

    assertEquals(full.visibleBox(SpreadEdge.values.toSet), full.asRight[String])
    assertEquals(inner.contentBox, inner.asRight[String])
    assertEquals(inner.visibleBox(Set.empty), inner.asRight[String])
    assertEquals(left.copy(box = SlotBox(x = 0.0, y = 0.0, width = 0.1, height = 100.0)).contentBox.isLeft, true)

  test("should apply coordinated directions to both opening kinds and permit clearing an empty opening"):
    val text     = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 8L)), right.box)
    val openings = Vector[Spread](
      Spread.AlbumStart(spread.id, spread.template, Vector(left), text, Vector.empty),
      Spread.ChapterStart(spread.id, spread.template, Vector(left), text, Vector.empty)
    )

    openings.foreach: opening =>
      val enabled = opening.withBleed(enabled = true).fold(message => fail(message), identity)
      assertEquals(enabled.photos.map(_.bleed), Vector(Set(SpreadEdge.Top, SpreadEdge.Bottom, SpreadEdge.Left)))
      assertEquals(enabled.withBleed(enabled = false), opening.asRight[String])
    val empty = Spread.AlbumStart(spread.id, spread.template, Vector.empty, text, Vector.empty)
    assertEquals(empty.withBleed(enabled = false), (empty: Spread).asRight[String])
    assertEquals(empty.withBleed(enabled = true).isLeft, true)

  test("should enable complete outer directions and derive reversible frames without changing saved geometry"):
    val enabled = spread.withBleed(enabled = true)

    assertEquals(
      enabled.map(_.photos.map(_.bleed)),
      Vector(Set(SpreadEdge.Top, SpreadEdge.Bottom, SpreadEdge.Left), Set(SpreadEdge.Top, SpreadEdge.Bottom, SpreadEdge.Right)).asRight[String]
    )
    assertEquals(enabled.map(_.photos.map(_.box)), Vector(left.box, right.box).asRight[String])
    assertEquals(left.visibleBox, left.contentBox)
    assertEquals(enabled.flatMap(_.withBleed(enabled = false)), (spread: Spread).asRight[String])

  test("should enable outer stacks together while keeping inner slots ineligible"):
    val upper   = left.copy(box = SlotBox(x = 0.0, y = 0.0, width = 20.0, height = 49.5))
    val lower   = right.copy(box = SlotBox(x = 0.0, y = 50.5, width = 30.0, height = 49.5))
    val inner   = left.copy(id = PhotoSpreadSlotId(new UUID(0L, 5L)), box = SlotBox(x = 31.0, y = 1.0, width = 20.0, height = 98.0))
    val stacked = spread.copy(photoSlots = NonEmptyList.of(upper, lower, inner))

    assertEquals(
      stacked.withBleed(enabled = true).map(_.photos.map(_.bleed)),
      Vector(Set(SpreadEdge.Left), Set(SpreadEdge.Left), Set.empty[SpreadEdge]).asRight[String]
    )
    assertEquals(
      spread.copy(photoSlots = NonEmptyList.one(inner)).withBleed(enabled = true),
      "This spread has no eligible outer photo slots.".asLeft[Spread]
    )

  test("should preserve mixed saved directions until explicitly enabled and treat margin and expanded boxes identically"):
    val margin   = left.contentBox.fold(message => fail(message), identity)
    val imported = spread.copy(photoSlots = NonEmptyList.of(left.copy(bleed = Set(SpreadEdge.Left)), right))
    val inset    = spread.copy(photoSlots = NonEmptyList.of(left.copy(box = margin), right))

    assertEquals(imported.bleedState, Spread.BleedState.Mixed)
    assertEquals(spread.bleedState, Spread.BleedState.Disabled)
    assertEquals(inset.withBleed(enabled = true).map(_.photos.map(_.bleed)), spread.withBleed(enabled = true).map(_.photos.map(_.bleed)))
    assertEquals(imported.withBleed(enabled = true).map(_.bleedState), Spread.BleedState.Enabled.asRight[String])
