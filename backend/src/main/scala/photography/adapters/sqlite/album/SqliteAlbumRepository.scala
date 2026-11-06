package photography.adapters.sqlite.album

import cats.effect.Async
import cats.MonadThrow
import cats.arrow.FunctionK
import cats.data.NonEmptyList
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.Positive
import io.github.iltotore.iron.constraint.collection.MinLength
import org.typelevel.doobie.{ConnectionIO, Fragment, Read, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.util.invariant.InvalidValue
import org.typelevel.log4cats.Logger
import org.sqlite.{SQLiteErrorCode, SQLiteException}
import photography.adapters.sqlite.SqliteCodecs.given
import photography.adapters.sqlite.SqliteAlbumQueries
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.album.AlbumRepository
import photography.ports.album.AlbumRepository.*

import java.time.{Instant, ZoneOffset}
import java.time.temporal.ChronoUnit
import java.util.UUID

object SqliteAlbumRepository:
  def make[F[_]: Async: Logger](transactor: Transactor[F]): AlbumRepository[F] & AlbumRepository.MetadataRead[F] & AlbumRepository.ChapterRead[F] =
    val resolve = new FunctionK[ConnectionIO, F]:
      override def apply[A](query: ConnectionIO[A]): F[A] = query.transact(transactor)
    val save = new FunctionK[ConnectionIO, F]:
      override def apply[A](query: ConnectionIO[A]): F[A] =
        val commit = transactor.strategy.after.handleErrorWith(error => new UncertainCommit(error).raiseError[ConnectionIO, Unit])
        query.transact(Transactor.after.set(transactor, commit))
    new LiveSqliteAlbumRepository(resolve, save)

  final private class LiveSqliteAlbumRepository[F[_]: MonadThrow: Logger](
      resolve: FunctionK[ConnectionIO, F],
      save: FunctionK[ConnectionIO, F]
  ) extends AlbumRepository[F] with AlbumRepository.MetadataRead[F] with AlbumRepository.ChapterRead[F]:
    private type MetadataRow = (UUID, AlbumYear, AlbumTitle, String, AlbumAuthors, Instant)
    private given Read[AlbumAuthors] = Read[Int].emap(decodeAuthors)
    private given Read[VisitPeriod]  = Read[(Long, Long)].emap { case (start, end) =>
      decodePeriod(start = Instant.ofEpochMilli(start), end = Instant.ofEpochMilli(end))
    }
    private val selectMetadata =
      fr"""SELECT id, year, title, synopsis, CASE WHEN typeof(authors) = 'integer' THEN authors ELSE 0 END, created_at_ms FROM album"""

    override def metadata(album: AlbumId): F[MetadataResult] =
      execute(load(album.value).map(_.fold[MetadataResult](MetadataResult.NotFound)(MetadataResult.Found(_))), MetadataResult.Failed(_))

    override def chapters(album: AlbumId): F[ChaptersResult] =
      val query = hasAlbum(album).flatMap:
        case false => (ChaptersResult.AlbumNotFound: ChaptersResult).pure[ConnectionIO]
        case true  => loadChapterMetadata(album.value).map[ChaptersResult](ChaptersResult.Found(_))
      execute(query, ChaptersResult.Failed(_))

    override def list(offset: Int, limit: Int): F[ListResult] =
      val query =
        for
          total <- sql"SELECT count(*) FROM album".query[Long].unique
          rows  <- (selectMetadata ++ sql"ORDER BY created_at_ms DESC, id DESC LIMIT $limit OFFSET $offset").query[MetadataRow].to[Vector]
        yield ListResult.Listed(AlbumPageSlice(totalAlbums = total, albums = rows.map(decodeMetadata))): ListResult
      execute(query, ListResult.Failed(_))

    override def create(album: Album, cover: Cover): F[CreateResult] =
      val opening   = album.opening
      val isInitial = album.chapters.isEmpty && album.opening.placements.isEmpty && cover.album.value === album.id.value
      val initial   = Either.cond(isInitial, (), new IllegalArgumentException("Expected an initial album and its cover"))
      val query     =
        for
          _ <- initial.liftTo[ConnectionIO]
          _ <- insertAlbum(album)
          _ <- insertInitialSpread(album.id, opening.id, opening.template, photos = opening.photoSlots, opening.textSlot)
          _ <- insertCover(cover)
          _ <- loadRequired(album.id.value)
        yield CreateResult.Created: CreateResult
      execute(query, CreateResult.Failed(_))

    override def readPlanning(id: AlbumId): F[PlanningResult] =
      val query = load(id.value).flatMap:
        case None           => (PlanningResult.NotFound: PlanningResult).pure[ConnectionIO]
        case Some(metadata) =>
          for
            chapters <- loadChapterMetadata(id.value)
            count    <- SqliteAlbumQueries.interiorSpreadCount(id).query[InteriorSpreadCount].unique
          yield PlanningResult.Found(PlanningSnapshot(metadata.year, chapters, count)): PlanningResult
      execute(query, PlanningResult.Failed(_))

    private def hasAlbum(album: AlbumId) =
      sql"SELECT EXISTS(SELECT 1 FROM album WHERE id = ${album.value})".query[Boolean].unique

    override def rename(id: AlbumId, title: AlbumTitle): F[RenameResult] =
      val update = sql"UPDATE album SET title = $title WHERE id = ${id.value}"
      saveField(id, update).map:
        case FieldSaveResult.Saved(updated) => RenameResult.Renamed(updated)
        case FieldSaveResult.NotFound       => RenameResult.NotFound
        case FieldSaveResult.Failed(reason) => RenameResult.Failed(reason)
        case FieldSaveResult.Uncertain      => RenameResult.Failed(Failure.DatabaseFailure)

    override def delete(id: AlbumId): F[DeleteResult] =
      val query = sql"DELETE FROM album WHERE id = ${id.value}".update.run.map: deleted =>
        if deleted === 0 then DeleteResult.NotFound else DeleteResult.Deleted
      execute(query, DeleteResult.Failed(_))

    override def saveSynopsis(album: AlbumId, text: String): F[SaveSynopsisResult] =
      val update = sql"UPDATE album SET synopsis = $text WHERE id = ${album.value}"
      saveField(album, update).map:
        case FieldSaveResult.Saved(updated) => SaveSynopsisResult.Saved(updated)
        case FieldSaveResult.NotFound       => SaveSynopsisResult.NotFound
        case FieldSaveResult.Failed(reason) => SaveSynopsisResult.Failed(reason)
        case FieldSaveResult.Uncertain      => SaveSynopsisResult.Uncertain

    override def saveAuthors(album: AlbumId, authors: AlbumAuthors): F[SaveAuthorsResult] =
      val encoded = encodeAuthors(authors)
      val update  = sql"UPDATE album SET authors = $encoded WHERE id = ${album.value}"
      saveField(album, update).map:
        case FieldSaveResult.Saved(updated) => SaveAuthorsResult.Saved(updated)
        case FieldSaveResult.NotFound       => SaveAuthorsResult.NotFound
        case FieldSaveResult.Failed(reason) => SaveAuthorsResult.Failed(reason)
        case FieldSaveResult.Uncertain      => SaveAuthorsResult.Uncertain

    private def saveField(album: AlbumId, update: Fragment) =
      val rejected = new IllegalArgumentException(s"Expected one applied album field update for ${album.value}")
      val query    = load(album.value).flatMap:
        case None    => FieldSaveResult.NotFound.pure[ConnectionIO]
        case Some(_) => update.update.run.ensure(rejected)(_ === 1) >> loadRequired(album.value).map(FieldSaveResult.Saved(_))
      save(query).attempt.flatMap:
        case Right(result) => result.pure[F]
        case Left(error)   =>
          val result = error match
            case sqlite: SQLiteException
                if (sqlite.getResultCode.code & 255) === SQLiteErrorCode.SQLITE_CONSTRAINT.code ||
                  sqlite.getResultCode.code === SQLiteErrorCode.SQLITE_ERROR.code => FieldSaveResult.Failed(Failure.DatabaseFailure)
            case _: IllegalArgumentException | _: InvalidValue[?, ?] => FieldSaveResult.Failed(Failure.DatabaseFailure)
            case _                                                   => FieldSaveResult.Uncertain
          Logger[F].error(error)(s"SQLite album field save failed for ${album.value}: $result").as(result)

    private enum FieldSaveResult:
      case Saved(album: Metadata)
      case NotFound, Uncertain
      case Failed(reason: Failure)

    private def insertAlbum(album: Album) =
      val authors = encodeAuthors(album.authors)
      insert(sql"""INSERT INTO album (id, year, title, synopsis, authors, created_at_ms)
            VALUES (${album.id.value}, ${album.year}, ${album.title}, ${album.synopsis}, $authors, ${album.createdAt})""")

    private def encodeAuthors(authors: AlbumAuthors) =
      authors.foldLeft(0)((encoded, author) => encoded | (1 << author.ordinal))

    private def decodeAuthors(encoded: Int) =
      Either.cond(encoded >= 1 && encoded <= 7, (), "Invalid album authors").flatMap: _ =>
        Author.all.filter(author => (encoded & (1 << author.ordinal)) =!= 0).refineEither[MinLength[1]]

    private def insertInitialSpread(
        album: AlbumId,
        spread: SpreadId,
        template: SpreadTemplateId,
        photos: Vector[SpreadSlot.Photo],
        text: SpreadSlot.Text
    ) =
      val row = sql"""INSERT INTO spread (id, template_id, kind, album_id, position)
                        VALUES (${spread.value}, ${template.value}, 'album_start', ${album.value}, 0)"""
      insert(row) >> photos.traverse_(slot => insertSlot(spread, slot)) >> insertSlot(spread, text)

    private def insertSlot(spread: SpreadId, slot: SpreadSlot) =
      slot match
        case SpreadSlot.Photo(id, box, bleed) =>
          val photo = sql"INSERT INTO spread_photo_slot (id, spread_id, x, y, width, height) VALUES (${id.value}, ${spread.value}, $box)"
          val edges = bleed.toList.traverse_(edge => insert(sql"INSERT INTO spread_photo_slot_bleed (slot_id, edge) VALUES (${id.value}, $edge)"))
          insert(photo) >> edges
        case SpreadSlot.Text(id, box) =>
          insert(sql"INSERT INTO spread_text_slot (id, spread_id, x, y, width, height) VALUES (${id.value}, ${spread.value}, $box)")

    private def load(id: UUID) = (selectMetadata ++ sql"WHERE id = $id").query[MetadataRow].option.map(_.map(decodeMetadata))

    private def decodeMetadata(row: MetadataRow) =
      val (id, year, title, synopsis, authors, createdAt) = row
      Metadata(AlbumId(id), year, title, synopsis, authors, createdAt)

    private def loadRequired(id: UUID) =
      val missingAlbum = new IllegalArgumentException(s"Missing required album $id")
      load(id).flatMap(_.toRight(missingAlbum).liftTo[ConnectionIO])

    private def loadChapterMetadata(albumId: UUID) =
      val select = sql"""SELECT id, name, description, start_ms, end_ms, source_folder
                        FROM chapter WHERE album_id = $albumId ORDER BY position"""
      validateChapters(albumId) >>
        select.query[(UUID, DestinationName, String, VisitPeriod, PhotoSource)].to[Vector].flatMap(_.traverse(decodeChapterMetadata))

    private def decodeChapterMetadata(row: (UUID, DestinationName, String, VisitPeriod, PhotoSource)) =
      val (id, name, description, period, folder) = row
      loadCountries(id).map(countries => ChapterMetadata(ChapterId(id), Destination(name, countries = countries), period, folder, description))

    private def decodePeriod(start: Instant, end: Instant) =
      val days    = ChronoUnit.DAYS.between(start, end)
      val aligned = start.compareTo(start.truncatedTo(ChronoUnit.DAYS)) === 0 && end.compareTo(end.truncatedTo(ChronoUnit.DAYS)) === 0
      Either.cond(aligned, (), "Expected UTC calendar days").flatMap(_ => days.refineEither[Positive])
        .map(length => VisitPeriod(start.atOffset(ZoneOffset.UTC).toLocalDate, days = length))

    private def loadCountries(chapter: UUID) =
      val select = sql"""SELECT k.id, k.code, k.name, k.flag FROM chapter_country c
                         LEFT JOIN country k ON k.id = c.country_id WHERE c.chapter_id = $chapter ORDER BY c.position"""
      select.query[(UUID, CountryCode, CountryName, AbsoluteLocation)].to[List].flatMap: rows =>
        val countries = rows.map { case (id, code, name, flag) => Country(CountryId(id), code = code, name = name, flag) }
        NonEmptyList.fromList(countries).toRight(new IllegalArgumentException("Destination must have countries")).liftTo[ConnectionIO]

    private def validateChapters(albumId: UUID) =
      sql"SELECT count(*) FROM chapter WHERE album_id = $albumId AND position < 0".query[Long].unique.flatMap: count =>
        Either.cond(count === 0L, (), new IllegalArgumentException("Invalid chapter composition")).liftTo[ConnectionIO]

    private def insertCover(cover: Cover) =
      val albumId = cover.album.value
      val spine   = cover.spineOwner match
        case SpineOwner.Front => "front"
        case SpineOwner.Back  => "back"
      val row   = sql"INSERT INTO cover (album_id, spine_owner) VALUES ($albumId, $spine)"
      val front = sql"INSERT INTO cover_slot (id, album_id, side) VALUES (${cover.front.id.value}, $albumId, 'front')"
      val back  = sql"INSERT INTO cover_slot (id, album_id, side) VALUES (${cover.back.id.value}, $albumId, 'back')"
      insert(row) >> insert(front) >> insert(back)

    private def insert(statement: Fragment) =
      val incompleteWrite = new IllegalStateException("Expected one inserted album-owned row")
      statement.update.run.ensure(incompleteWrite)(_ === 1).void

    private def execute[A](query: ConnectionIO[A], failed: Failure => A) =
      resolve(query).attempt.flatMap:
        case Right(result) => result.pure[F]
        case Left(error)   =>
          val reason = error match
            case sqlite: SQLiteException
                if sqlite.getResultCode.code === SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE.code &&
                  sqlite.getMessage.contains("album.year") => Failure.YearAlreadyUsed
            case _ => Failure.DatabaseFailure
          Logger[F].error(error)("SQLite album operation failed").as(failed(reason))

  final private class UncertainCommit(cause: Throwable) extends RuntimeException(cause)
