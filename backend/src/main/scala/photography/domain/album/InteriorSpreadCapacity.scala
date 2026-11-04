package photography.domain.album

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.GreaterEqual

type InteriorSpreadCount = Int :| GreaterEqual[0]

object InteriorSpreadCapacity:
  val maximum = 65

  def isReached(count: Int): Boolean = count >= maximum
