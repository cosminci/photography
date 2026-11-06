package photography.adapters.http

import cats.syntax.all.*
import org.http4s.dsl.impl.UUIDVar
import java.util.UUID

private[http] object CanonicalId:
  def unapply(value: String): Option[UUID] = UUIDVar.unapply(value).filter(_.toString === value)
