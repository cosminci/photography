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
    val upper      = left.copy(box = SlotBox(x = 0.0, y = 0.0, width = 20.0, height = 49.5))
    val lower      = right.copy(box = SlotBox(x = 0.0, y = 50.5, width = 30.0, height = 49.5))
    val inner      = left.copy(id = PhotoSpreadSlotId(new UUID(0L, 5L)), box = SlotBox(x = 31.0, y = 1.0, width = 20.0, height = 98.0))
    val rightUpper = right.copy(id = PhotoSpreadSlotId(new UUID(0L, 8L)), box = SlotBox(x = 80.0, y = 0.0, width = 20.0, height = 49.5))
    val rightLower = right.copy(id = PhotoSpreadSlotId(new UUID(0L, 9L)), box = SlotBox(x = 70.0, y = 50.5, width = 30.0, height = 49.5))
    val stacked    = spread.copy(photoSlots = NonEmptyList.of(upper, lower, inner, rightUpper, rightLower))

    assertEquals(
      stacked.withBleed(enabled = true).map(_.photos.map(_.bleed)),
      Vector(Set(SpreadEdge.Left), Set(SpreadEdge.Left), Set.empty[SpreadEdge], Set(SpreadEdge.Right), Set(SpreadEdge.Right)).asRight[String]
    )
    assertEquals(
      spread.copy(photoSlots = NonEmptyList.one(inner)).withBleed(enabled = true),
      "This spread has no eligible outer photo slots.".asLeft[Spread]
    )

  for (singleSide, single, outer) <- Vector((SpreadEdge.Left, left, right), (SpreadEdge.Right, right, left)) do
    test(s"should bleed only the full-height ${singleSide} photo opposite an outer stack"):
      val upper  = outer.copy(box = outer.box.copy(height = 49.5))
      val lower  = outer.copy(id = PhotoSpreadSlotId(new UUID(0L, 6L)), box = outer.box.copy(y = 50.5, height = 49.5))
      val inner  = left.copy(id = PhotoSpreadSlotId(new UUID(0L, 7L)), box = SlotBox(x = 31.0, y = 1.0, width = 18.0, height = 98.0))
      val layout = spread.copy(photoSlots = NonEmptyList.of(single, upper, lower, inner))
      val edges  = Set(SpreadEdge.Top, SpreadEdge.Bottom, singleSide)
      val saved  = layout.copy(photoSlots = NonEmptyList.of(single.copy(bleed = edges), upper, lower, inner))
      val mixed  = saved.copy(photoSlots = NonEmptyList.of(single.copy(bleed = edges), upper.copy(bleed = outer.box.eligibleBleed), lower, inner))

      assertEquals(saved.bleedState, Spread.BleedState.Enabled)
      assertEquals(mixed.bleedState, Spread.BleedState.Mixed)
      assertEquals(layout.withBleed(enabled = true), (saved: Spread).asRight[String])
      assertEquals(saved.withBleed(enabled = false), (layout: Spread).asRight[String])
      assertEquals(saved.withBleed(enabled = true), (saved: Spread).asRight[String])

  test("should preserve mixed saved directions until explicitly enabled and treat margin and expanded boxes identically"):
    val margin   = left.contentBox.fold(message => fail(message), identity)
    val imported = spread.copy(photoSlots = NonEmptyList.of(left.copy(bleed = Set(SpreadEdge.Left)), right))
    val inset    = spread.copy(photoSlots = NonEmptyList.of(left.copy(box = margin), right))

    assertEquals(imported.bleedState, Spread.BleedState.Mixed)
    assertEquals(spread.bleedState, Spread.BleedState.Disabled)
    assertEquals(inset.withBleed(enabled = true).map(_.photos.map(_.bleed)), spread.withBleed(enabled = true).map(_.photos.map(_.bleed)))
    assertEquals(imported.withBleed(enabled = true).map(_.bleedState), Spread.BleedState.Enabled.asRight[String])
