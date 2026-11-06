package photography.adapters.sqlite

import cats.syntax.all.*
import io.github.iltotore.iron.*
import org.typelevel.doobie.{Meta, Read, Write}
import photography.domain.album.{AbsoluteLocation, AlbumTitle, AlbumYear, CountryCode, CountryName, PhotoSource, InteriorSpreadCount}
import photography.domain.layout.*

import java.nio.file.{InvalidPathException, Path}
import java.time.Instant
import java.util.UUID

private[sqlite] object SqliteCodecs:
  given Meta[UUID] = Meta[String].tiemap[UUID] { value =>
    Either.catchNonFatal(UUID.fromString(value)).leftMap(_.getMessage).flatMap: id =>
      Either.cond(id.toString === value, id, s"Noncanonical UUID: $value")
  }(_.toString)

  given Meta[CountryCode]      = Meta[String].tiemap[CountryCode](_.refineEither)(value => value: String)
  given Meta[CountryName]      = Meta[String].tiemap[CountryName](_.refineEither)(value => value: String)
  given Meta[AbsoluteLocation] = Meta[String].tiemap[AbsoluteLocation] { value =>
    Either.catchOnly[InvalidPathException](Path.of(value)).leftMap(_.getMessage).flatMap: path =>
      Either.cond(path.isAbsolute, AbsoluteLocation(path), "Expected absolute flag path")
  }(
    // The country catalog is read-only; Meta requires the inverse representation even without a writer.
    // $COVERAGE-OFF$
    _.value.toString
      // $COVERAGE-ON$
  )

  private given Meta[PositionOnSpread] =
    Meta[Double].tiemap[PositionOnSpread](_.refineEither)(value => value: Double)

  private given Meta[DistanceWithinSpread] =
    Meta[Double].tiemap[DistanceWithinSpread](_.refineEither)(value => value: Double)

  given Read[SlotBox] =
    Read[(PositionOnSpread, PositionOnSpread, DistanceWithinSpread, DistanceWithinSpread)].map(SlotBox.apply.tupled)

  given Write[SlotBox] =
    Write[(PositionOnSpread, PositionOnSpread, DistanceWithinSpread, DistanceWithinSpread)].contramap(box => (box.x, box.y, box.width, box.height))

  given Meta[AlbumYear]           = Meta[Int].tiemap[AlbumYear](_.refineEither)(value => value: Int)
  given Meta[InteriorSpreadCount] = Meta[Int].tiemap[InteriorSpreadCount](_.refineEither)(value => value: Int)
  given Meta[AlbumTitle]          = Meta[String].tiemap[AlbumTitle](_.refineEither)(value => value: String)
  given Meta[PhotoSource]         = Meta[String].tiemap[PhotoSource](_.refineEither)(value => value: String)
  given Meta[Instant]             = Meta[Long].timap[Instant](Instant.ofEpochMilli)(_.toEpochMilli)
  given Meta[InDesignPoints]      = Meta[Double].tiemap[InDesignPoints](_.refineEither)(value => value: Double)
  given Meta[Zoom]                = Meta[Double].tiemap[Zoom](_.refineEither)(value => value: Double)

  given Meta[CaptionCorner] = Meta[String].tiemap[CaptionCorner] {
    case "top_left"     => CaptionCorner.TopLeft.asRight[String]
    case "top_right"    => CaptionCorner.TopRight.asRight[String]
    case "bottom_left"  => CaptionCorner.BottomLeft.asRight[String]
    case "bottom_right" => CaptionCorner.BottomRight.asRight[String]
    // placement.caption_corner is constrained to these four values.
    // $COVERAGE-OFF$
    case other => s"Unknown caption corner $other".asLeft[CaptionCorner]
    // $COVERAGE-ON$
  } {
    case CaptionCorner.TopLeft     => "top_left"
    case CaptionCorner.TopRight    => "top_right"
    case CaptionCorner.BottomLeft  => "bottom_left"
    case CaptionCorner.BottomRight => "bottom_right"
  }

  given Meta[SpreadEdge] = Meta[String].tiemap[SpreadEdge] {
    case "top"    => SpreadEdge.Top.asRight[String]
    case "bottom" => SpreadEdge.Bottom.asRight[String]
    case "left"   => SpreadEdge.Left.asRight[String]
    case "right"  => SpreadEdge.Right.asRight[String]
    // spread_photo_slot_bleed.edge is constrained to these four values.
    // $COVERAGE-OFF$
    case other => s"Unknown bleed edge $other".asLeft[SpreadEdge]
    // $COVERAGE-ON$
  } {
    case SpreadEdge.Top    => "top"
    case SpreadEdge.Bottom => "bottom"
    case SpreadEdge.Left   => "left"
    case SpreadEdge.Right  => "right"
  }
