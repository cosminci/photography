package photography.domain.layout

import cats.data.NonEmptyVector
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive
import photography.domain.album.{InteriorSpreadCount, PixelCount}

final case class CoverSpecification private (pageLimit: Int :| Positive, width: PixelCount, height: PixelCount, spine: PixelCount)

object CoverSpecification:
  def from(pageLimit: Int, width: Int, height: Int, spine: Int): Either[String, CoverSpecification] =
    for
      pages      <- pageLimit.refineEither[Positive]
      total      <- width.refineEither[Positive]
      vertical   <- height.refineEither[Positive]
      horizontal <- spine.refineEither[Positive]
      _          <- Either.cond(horizontal < total, (), "Cover spine must be narrower than the artwork")
    yield CoverSpecification(pages, total, vertical, horizontal)

final case class CoverPhotoFrame(x: Double, width: Double, height: Double)

final case class CoverDimensions private (pageCount: Long, width: Double, height: Double, spineStart: Double, spineWidth: Double):
  def frontFrame(owner: SpineOwner): CoverPhotoFrame =
    val start = owner match
      case SpineOwner.Front => spineStart
      case SpineOwner.Back  => spineStart + spineWidth
    CoverPhotoFrame(x = start, width = width - start, height = height)

  def backFrame(owner: SpineOwner): CoverPhotoFrame =
    val end = owner match
      case SpineOwner.Front => spineStart
      case SpineOwner.Back  => spineStart + spineWidth
    CoverPhotoFrame(x = 0.0, width = end, height = height)

object CoverDimensions:
  def forSpreads(spreads: InteriorSpreadCount, specifications: NonEmptyVector[CoverSpecification]): CoverDimensions =
    val ordered       = specifications.sortBy(_.pageLimit: Int)
    val specification = ordered.find(_.pageLimit >= 2L * spreads).getOrElse(ordered.last)
    val width         = specification.width * 72.0 / 300.0
    val height        = specification.height * 72.0 / 300.0
    val spine         = specification.spine * 72.0 / 300.0
    CoverDimensions(pageCount = 2L * spreads, width = width, height = height, spineStart = (width - spine) / 2, spineWidth = spine)
