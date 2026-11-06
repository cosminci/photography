package photography.adapters.http

import cats.effect.Concurrent
import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure}
import org.http4s.{InvalidMessageBodyFailure, Request}
import org.http4s.circe.CirceInstances

private[http] object JsonBody:
  val text: Decoder[String] = Decoder.decodeString.prepare(_.downField("text"))

  def decode[F[_]: Concurrent, A](request: Request[F], decoder: Decoder[A], malformed: String)(
      explain: DecodingFailure => String
  ): F[Either[String, A]] =
    val json = CirceInstances.builder.withJsonDecodeError((_, failures) => InvalidMessageBodyFailure(explain(failures.head))).build
    request.attemptAs[A](using json.jsonOf[F, A](using Concurrent[F], decoder)).value.map: result =>
      result.leftMap:
        case InvalidMessageBodyFailure(details, _) => details
        case _                                     => malformed
