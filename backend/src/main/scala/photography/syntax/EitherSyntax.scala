package photography.syntax

import cats.data.EitherT
import cats.{Applicative, Functor}

object EitherSyntax:
  extension [F[_], E, A](value: F[Either[E, A]])
    def eitherT: EitherT[F, E, A] = EitherT(value)

  extension [F[_]: Functor, A](value: F[Option[A]])
    def orFail[E](ifEmpty: => E): EitherT[F, E, A] = EitherT.fromOptionF(value, ifEmpty)

  extension [F[_]: Functor, A](value: F[A])
    def lift[E]: EitherT[F, E, A] = EitherT.liftF(value)

  object Pure:
    extension [A](value: Option[A])
      def orFail[F[_]: Applicative, E](ifEmpty: => E): EitherT[F, E, A] = EitherT.fromOption[F](value, ifEmpty)

    extension (condition: Boolean)
      def ensure[F[_]: Applicative, E](ifFalse: => E): EitherT[F, E, Unit] = EitherT.cond[F](condition, (), ifFalse)
