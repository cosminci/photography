package photography.domain.album

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import cats.syntax.all.*
import cats.data.NonEmptyList

type DestinationName = String :| (Not[Empty] & Not[Blank])

final case class Destination(name: DestinationName, countries: NonEmptyList[Country]):
  def isHome: Boolean = (name: String) === "Home"
