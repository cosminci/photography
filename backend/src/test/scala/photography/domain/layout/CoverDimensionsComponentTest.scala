package photography.domain.layout

import cats.data.NonEmptyVector
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import photography.domain.album.InteriorSpreadCount

class CoverDimensionsComponentTest extends FunSuite:
  private val small          = CoverSpecification.from(pageLimit = 30, width = 10382, height = 3402, spine = 142).fold(fail(_), identity)
  private val large          = CoverSpecification.from(pageLimit = 130, width = 10557, height = 3402, spine = 366).fold(fail(_), identity)
  private val specifications = NonEmptyVector.of(large, small)

  final private case class Scenario(spreads: InteriorSpreadCount, width: Double, spine: Double)

  Vector(
    Scenario(spreads = 0, width = 2491.68, spine = 34.08),
    Scenario(spreads = 15, width = 2491.68, spine = 34.08),
    Scenario(spreads = 16, width = 2533.68, spine = 87.84),
    Scenario(spreads = 65, width = 2533.68, spine = 87.84),
    Scenario(spreads = 66, width = 2533.68, spine = 87.84)
  ).foreach: scenario =>
    test(s"should fit ${scenario.spreads} interior spreads using the smallest sufficient specification"):
      val dimensions = CoverDimensions.forSpreads(scenario.spreads, specifications)

      assertEquals(dimensions.pageCount, 2L * scenario.spreads)
      assertEqualsDouble(dimensions.width, scenario.width, 0.000001)
      assertEqualsDouble(dimensions.height, 816.48, 0.000001)
      assertEqualsDouble(dimensions.spineWidth, scenario.spine, 0.000001)
      assertEqualsDouble(dimensions.spineStart, (scenario.width - scenario.spine) / 2, 0.000001)

  SpineOwner.values.foreach: owner =>
    test(s"should extend only the $owner photograph across the spine without a gap or overlap"):
      val dimensions = CoverDimensions.forSpreads(spreads = 15, specifications)
      val front      = dimensions.frontFrame(owner)
      val back       = dimensions.backFrame(owner)
      val boundary   = owner match
        case SpineOwner.Front => 1228.8
        case SpineOwner.Back  => 1262.88

      assertEqualsDouble(front.x, boundary, 0.000001)
      assertEqualsDouble(back.width, boundary, 0.000001)
      assertEqualsDouble(back.x, 0.0, 0.000001)
      assertEqualsDouble(front.width + back.width, dimensions.width, 0.000001)
      assertEquals(front.height, dimensions.height)
      assertEquals(back.height, dimensions.height)

  final private case class InvalidSpecification(pageLimit: Int, width: Int, height: Int, spine: Int)

  Vector(
    InvalidSpecification(pageLimit = 0, width = 100, height = 50, spine = 10),
    InvalidSpecification(pageLimit = 30, width = 0, height = 50, spine = 10),
    InvalidSpecification(pageLimit = 30, width = 100, height = 0, spine = 10),
    InvalidSpecification(pageLimit = 30, width = 100, height = 50, spine = 0),
    InvalidSpecification(pageLimit = 30, width = 100, height = 50, spine = 100)
  ).foreach: scenario =>
    test(s"should reject invalid persisted cover specification $scenario"):
      assertEquals(CoverSpecification.from(scenario.pageLimit, scenario.width, scenario.height, scenario.spine).isLeft, true)
