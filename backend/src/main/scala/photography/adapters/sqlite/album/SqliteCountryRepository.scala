package photography.adapters.sqlite.album

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all.*
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.album.*
import photography.ports.album.CountryRepository
import photography.ports.album.CountryRepository.ListResult

import java.util.UUID

object SqliteCountryRepository:
  def make[F[_]: Async: Logger](transactor: Transactor[F]): CountryRepository[F] =
    new LiveSqliteCountryRepository(transactor)

  final private class LiveSqliteCountryRepository[F[_]: Async: Logger](transactor: Transactor[F]) extends CountryRepository[F]:
    override def list: F[ListResult] =
      loadCountries.transact(transactor).attempt.flatMap:
        case Right(countries) => ListResult.Listed(countries).pure[F]
        case Left(error)      => Logger[F].error(error)("Could not list country catalog").as(ListResult.Failed)

    private def loadCountries =
      val select = sql"SELECT id, code, name, flag FROM country ORDER BY name, id"
      select.query[(UUID, CountryCode, CountryName, AbsoluteLocation)].to[List].flatMap: rows =>
        val countries = rows.map { case (id, code, name, flag) => Country(CountryId(id), code = code, name = name, flag) }
        NonEmptyList.fromList(countries).toRight(new IllegalArgumentException("Country catalog must not be empty")).liftTo[ConnectionIO]
