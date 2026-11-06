package photography.adapters.sqlite

import cats.syntax.all.*
import io.github.iltotore.iron.doobie.given
import org.typelevel.doobie.{Meta, Read, Write}
import photography.domain.album.AbsoluteLocation
import photography.domain.layout.*

import java.nio.file.{InvalidPathException, Path}
import java.time.Instant
import java.util.UUID

private[sqlite] object SqliteCodecs:
  given Meta[UUID] = Meta[String].tiemap[UUID] { value =>
    Either.catchNonFatal(UUID.fromString(value)).leftMap(_.getMessage).flatMap: id =>
      Either.cond(id.toString === value, id, s"Noncanonical UUID: $value")
  }(_.toString)

  given Meta[AbsoluteLocation] = Meta[String].tiemap[AbsoluteLocation] { value =>
    Either.catchOnly[InvalidPathException](Path.of(value)).leftMap(_.getMessage).flatMap: path =>
      Either.cond(path.isAbsolute, AbsoluteLocation(path), "Expected absolute flag path")
  } {
    // $COVERAGE-OFF$
    _.value.toString
    // $COVERAGE-ON$
  }

  given Read[SlotBox] =
    Read[(PositionOnSpread, PositionOnSpread, DistanceWithinSpread, DistanceWithinSpread)].map(SlotBox.apply.tupled)

  given Write[SlotBox] =
    Write[(PositionOnSpread, PositionOnSpread, DistanceWithinSpread, DistanceWithinSpread)].contramap(box => (box.x, box.y, box.width, box.height))

  given Meta[Instant] = Meta[Long].timap[Instant](Instant.ofEpochMilli)(_.toEpochMilli)

  given Meta[CaptionCorner] = Meta[String].tiemap[CaptionCorner] {
    case "top_left"     => CaptionCorner.TopLeft.asRight[String]
    case "top_right"    => CaptionCorner.TopRight.asRight[String]
    case "bottom_left"  => CaptionCorner.BottomLeft.asRight[String]
    case "bottom_right" => CaptionCorner.BottomRight.asRight[String]
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
    // $COVERAGE-OFF$
    case other => s"Unknown bleed edge $other".asLeft[SpreadEdge]
    // $COVERAGE-ON$
  } {
    case SpreadEdge.Top    => "top"
    case SpreadEdge.Bottom => "bottom"
    case SpreadEdge.Left   => "left"
    case SpreadEdge.Right  => "right"
  }
