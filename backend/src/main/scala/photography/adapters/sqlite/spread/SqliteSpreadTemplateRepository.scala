package photography.adapters.sqlite.spread

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all.*
import io.github.iltotore.iron.*
import org.typelevel.doobie.{ConnectionIO, Meta, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.layout.*
import photography.ports.spread.SpreadTemplateRepository
import photography.ports.spread.SpreadTemplateRepository.*

import java.util.UUID

object SqliteSpreadTemplateRepository:
  def make[F[_]: Async: Logger](transactor: Transactor[F]): SpreadTemplateRepository[F] =
    new LiveSqliteSpreadTemplateRepository(transactor)

  final private class LiveSqliteSpreadTemplateRepository[F[_]: Async: Logger](transactor: Transactor[F]) extends SpreadTemplateRepository[F]:
    override def listAlbumStartTemplates: F[ListAlbumStartTemplatesResult] =
      loadOpenings(kind = "album_start").transact(transactor).attempt.flatMap:
        case Right(rows) =>
          val templates = rows.map { case (id, name, photos, text) => SpreadTemplate.AlbumStart(id, name, photos, text) }
          ListAlbumStartTemplatesResult.Listed(templates).pure[F]
        case Left(error) =>
          Logger[F].error(error)("Could not list album opening templates").as(ListAlbumStartTemplatesResult.Failed(Failure.DatabaseFailure))

    override def listChapterStartTemplates: F[ListChapterStartTemplatesResult] =
      loadOpenings(kind = "chapter_start").transact(transactor).attempt.flatMap:
        case Right(rows) =>
          val templates = rows.map { case (id, name, photos, text) => SpreadTemplate.ChapterStart(id, name, photos, text) }
          ListChapterStartTemplatesResult.Listed(templates).pure[F]
        case Left(error) =>
          Logger[F].error(error)("Could not list chapter opening templates").as(ListChapterStartTemplatesResult.Failed(Failure.DatabaseFailure))

    override def listPhotosOnlyTemplates: F[SpreadTemplateRepository.ListPhotosOnlyTemplatesResult] =
      loadPhotoLayouts.transact(transactor).attempt.flatMap:
        case Right(templates) => ListPhotosOnlyTemplatesResult.Listed(templates).pure[F]
        case Left(error)      =>
          Logger[F].error(error)("Could not list photo spread templates").as(ListPhotosOnlyTemplatesResult.Failed(Failure.DatabaseFailure))

    override def findTemplate(id: SpreadTemplateId): F[SpreadTemplateRepository.FindTemplateResult] =
      loadTemplate(id.value).transact(transactor).attempt.flatMap:
        case Right(Some(template)) => FindTemplateResult.Found(template).pure[F]
        case Right(None)           => FindTemplateResult.NotFound.pure[F]
        case Left(error)           => Logger[F].error(error)("Could not find spread template").as(FindTemplateResult.Failed(Failure.DatabaseFailure))

    private def loadPhotoLayouts =
      val select = sql"SELECT id, name FROM spread_template WHERE kind = 'photos_only' ORDER BY id"
      for
        rows      <- select.query[(UUID, SpreadTemplateName)].to[List]
        templates <- rows.traverse { case (id, name) => loadPhotoTemplate(id, name) }
        nonEmpty  <- NonEmptyList.fromList(templates).toRight(new IllegalArgumentException("No photos_only templates")).liftTo[ConnectionIO]
      yield nonEmpty

    private def loadPhotoTemplate(id: UUID, name: SpreadTemplateName) =
      loadPhotoSlots(id).map(photos => SpreadTemplate.PhotosOnly(SpreadTemplateId(id), name, photoSlots = photos))

    private def loadTemplate(id: UUID) =
      sql"SELECT kind, name FROM spread_template WHERE id = $id".query[(String, SpreadTemplateName)].option.flatMap:
        case None                        => Option.empty[SpreadTemplate].pure[ConnectionIO]
        case Some(("photos_only", name)) =>
          loadPhotoSlots(id).map(photos => (SpreadTemplate.PhotosOnly(SpreadTemplateId(id), name, photoSlots = photos): SpreadTemplate).some)
        case Some(("album_start", name)) =>
          loadSlots(id).map { case (photos, text) =>
            (SpreadTemplate.AlbumStart(SpreadTemplateId(id), name, photoSlots = photos, text): SpreadTemplate).some
          }
        case Some(("chapter_start", name)) =>
          loadSlots(id).map { case (photos, text) =>
            (SpreadTemplate.ChapterStart(SpreadTemplateId(id), name, photoSlots = photos, text): SpreadTemplate).some
          }
        case Some((kind, _)) => new IllegalArgumentException(s"Unknown spread template kind $kind").raiseError[ConnectionIO, Option[SpreadTemplate]]

    private def loadPhotoSlots(id: UUID) =
      val select = sql"SELECT kind, x, y, width, height FROM spread_template_slot WHERE template_id = $id ORDER BY id"
      select.query[(String, SlotBox)].to[List].flatMap: rows =>
        val photos   = rows.collect { case ("photo", box) => box }
        val complete = photos.nonEmpty && photos.size === rows.size
        NonEmptyList.fromList(photos).filter(_ => complete)
          .toRight(new IllegalArgumentException(s"Invalid photo slots for template $id")).liftTo[ConnectionIO]

    private def loadOpenings(kind: String) =
      val select = sql"SELECT id, name FROM spread_template WHERE kind = $kind ORDER BY id"
      for
        rows      <- select.query[(UUID, SpreadTemplateName)].to[List]
        templates <- rows.traverse { case (id, name) => loadSlots(id).map { case (photos, text) => (SpreadTemplateId(id), name, photos, text) } }
        nonEmpty  <- NonEmptyList.fromList(templates).toRight(new IllegalArgumentException(s"No $kind templates")).liftTo[ConnectionIO]
      yield nonEmpty

    private def loadSlots(id: UUID) =
      val select = sql"SELECT kind, x, y, width, height FROM spread_template_slot WHERE template_id = $id ORDER BY id"
      select.query[(String, SlotBox)].to[List].flatMap: rows =>
        val photos = rows.collect { case ("photo", box) => box }.toVector
        val texts  = rows.collect { case ("text", box) => box }
        texts match
          case text :: Nil if photos.nonEmpty && photos.size + 1 === rows.size => (photos, text).pure[ConnectionIO]
          case _ => new IllegalArgumentException(s"Invalid opening slots for template $id").raiseError[ConnectionIO, (Vector[SlotBox], SlotBox)]

    private given Meta[SpreadTemplateName] =
      Meta[String].tiemap[SpreadTemplateName](_.refineEither)(value => value: String)
