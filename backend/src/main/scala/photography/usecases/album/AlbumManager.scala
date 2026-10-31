package photography.usecases.album

import photography.ports.spread.SpreadTemplateRepository

import cats.Monad
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import photography.capabilities.{Clock, IdGenerator}
import photography.domain.album.{Album, AlbumAuthors, AlbumId, AlbumTitle, AlbumYear, Author, Chapter}
import org.typelevel.log4cats.Logger
import photography.domain.layout.*
import photography.ports.album.AlbumRepository
import photography.usecases.album.AlbumManager.*
import photography.ports.spread.SpreadTemplateRepository.ListAlbumStartTemplatesResult

import java.time.temporal.ChronoUnit

trait AlbumManager[F[_]]:
  def list(page: Int): F[ListAlbumsResult]
  def create(year: AlbumYear, title: AlbumTitle): F[CreateAlbumResult]
  def rename(id: AlbumId, title: AlbumTitle): F[RenameResult]
  def saveSynopsis(album: AlbumId, text: String): F[SaveSynopsisResult]
  def saveAuthors(album: AlbumId, authors: AlbumAuthors): F[SaveAuthorsResult]
  def delete(id: AlbumId): F[DeleteAlbumResult]

type AlbumPageSize = Int :| Positive

object AlbumManager:
  def make[F[_]: Monad: IdGenerator: Clock: Logger](
      repository: AlbumRepository[F],
      spreads: SpreadTemplateRepository[F],
      pageSize: AlbumPageSize
  ): AlbumManager[F] =
    new LiveAlbumManager(repository, spreads, pageSize)

  final private class LiveAlbumManager[F[_]: Monad: IdGenerator: Clock: Logger](
      repository: AlbumRepository[F],
      spreads: SpreadTemplateRepository[F],
      pageSize: AlbumPageSize
  ) extends AlbumManager[F]:

    override def list(page: Int): F[ListAlbumsResult] =
      repository.list(offset = 0, limit = pageSize).flatMap:
        case AlbumRepository.ListResult.Failed(_)         => ListAlbumsResult.Failed.pure[F]
        case AlbumRepository.ListResult.Listed(firstPage) =>
          val totalAlbums  = firstPage.totalAlbums
          val totalPages   = ((totalAlbums + pageSize - 1) / pageSize).toInt
          val selectedPage = if totalPages === 0 then 1 else page.max(1).min(totalPages)
          val offset       = (selectedPage - 1) * pageSize
          val selected     =
            if offset === 0 then AlbumRepository.ListResult.Listed(firstPage).pure[F] else repository.list(offset = offset, limit = pageSize)
          selected.map:
            case AlbumRepository.ListResult.Listed(slice) =>
              val result =
                AlbumPage(albums = slice.albums, page = selectedPage, pageSize = pageSize, totalAlbums = totalAlbums, totalPages = totalPages)
              ListAlbumsResult.Listed(result)
            case AlbumRepository.ListResult.Failed(_) => ListAlbumsResult.Failed

    override def create(year: AlbumYear, title: AlbumTitle): F[CreateAlbumResult] =
      spreads.listAlbumStartTemplates.flatMap:
        case ListAlbumStartTemplatesResult.Listed(templates) =>
          val template = templates.head
          buildInitialComposition(year, title, template.id, photoSlots = template.photoSlots, template.textSlot).flatMap { case (album, cover) =>
            repository.create(album, cover).map:
              case AlbumRepository.CreateResult.Created                                         => CreateAlbumResult.Created(album)
              case AlbumRepository.CreateResult.Failed(AlbumRepository.Failure.YearAlreadyUsed) => CreateAlbumResult.YearAlreadyUsed
              case AlbumRepository.CreateResult.Failed(_)                                       => CreateAlbumResult.Failed
          }
        case ListAlbumStartTemplatesResult.Failed(_) => CreateAlbumResult.Failed.pure[F]

    override def rename(id: AlbumId, title: AlbumTitle): F[RenameResult] =
      repository.rename(id, title = title).map:
        case AlbumRepository.RenameResult.Renamed(album) => RenameResult.Renamed(album)
        case AlbumRepository.RenameResult.NotFound       => RenameResult.NotFound
        case AlbumRepository.RenameResult.Failed(_)      => RenameResult.Failed

    override def delete(id: AlbumId): F[DeleteAlbumResult] =
      repository.delete(id).map:
        case AlbumRepository.DeleteResult.Deleted   => DeleteAlbumResult.Deleted
        case AlbumRepository.DeleteResult.NotFound  => DeleteAlbumResult.NotFound
        case AlbumRepository.DeleteResult.Failed(_) => DeleteAlbumResult.Failed

    override def saveSynopsis(album: AlbumId, text: String): F[SaveSynopsisResult] =
      repository.saveSynopsis(album, text = text).flatMap:
        case AlbumRepository.SaveSynopsisResult.Saved(updated) => SaveSynopsisResult.Saved(updated).pure[F]
        case AlbumRepository.SaveSynopsisResult.NotFound       => SaveSynopsisResult.NotFound.pure[F]
        case AlbumRepository.SaveSynopsisResult.Failed(reason) =>
          Logger[F].error(s"Album synopsis save failed for ${album.value}: $reason").as(SaveSynopsisResult.Failed)
        case AlbumRepository.SaveSynopsisResult.Uncertain =>
          Logger[F].error(s"Album synopsis save acknowledgement uncertain for ${album.value}").as(SaveSynopsisResult.Uncertain)

    override def saveAuthors(album: AlbumId, authors: AlbumAuthors): F[SaveAuthorsResult] =
      repository.saveAuthors(album, authors).flatMap:
        case AlbumRepository.SaveAuthorsResult.Saved(updated) => SaveAuthorsResult.Saved(updated).pure[F]
        case AlbumRepository.SaveAuthorsResult.NotFound       => SaveAuthorsResult.NotFound.pure[F]
        case AlbumRepository.SaveAuthorsResult.Failed(reason) =>
          Logger[F].error(s"Album authors save failed for ${album.value}: $reason").as(SaveAuthorsResult.Failed)
        case AlbumRepository.SaveAuthorsResult.Uncertain =>
          Logger[F].error(s"Album authors save acknowledgement uncertain for ${album.value}").as(SaveAuthorsResult.Uncertain)

    private def buildInitialComposition(
        year: AlbumYear,
        title: AlbumTitle,
        template: SpreadTemplateId,
        photoSlots: Vector[SlotBox],
        textSlot: SlotBox
    ) =
      val newPhotos = photoSlots.traverse(box => IdGenerator[F].generateId.map(id => SpreadSlot.Photo(PhotoSpreadSlotId(id), box, bleed = Set.empty)))
      for
        albumId   <- IdGenerator[F].generateId.map(AlbumId(_))
        spreadId  <- IdGenerator[F].generateId.map(SpreadId(_))
        photos    <- newPhotos
        text      <- IdGenerator[F].generateId.map(id => SpreadSlot.Text(TextSpreadSlotId(id), textSlot))
        frontId   <- IdGenerator[F].generateId.map(CoverSlotId(_))
        backId    <- IdGenerator[F].generateId.map(CoverSlotId(_))
        createdAt <- Clock[F].readInstant.map(_.truncatedTo(ChronoUnit.MILLIS))
      yield
        val start = Spread.AlbumStart(spreadId, template, photoSlots = photos, textSlot = text, placements = Vector.empty[SpreadPlacement])
        val album =
          Album(albumId, year = year, title = title, synopsis = "", Author.all, createdAt = createdAt, start, chapters = Vector.empty[Chapter])
        val cover = Cover(albumId, SpineOwner.Front, front = CoverSlot(frontId), back = CoverSlot(backId))
        (album, cover)

  final case class AlbumPage(albums: Vector[Album], page: Int, pageSize: Int, totalAlbums: Long, totalPages: Int)

  enum ListAlbumsResult derives CanEqual:
    case Listed(page: AlbumPage)
    case Failed

  enum CreateAlbumResult derives CanEqual:
    case Created(album: Album)
    case YearAlreadyUsed, Failed

  enum RenameResult derives CanEqual:
    case Renamed(album: Album)
    case NotFound, Failed

  enum DeleteAlbumResult derives CanEqual:
    case Deleted
    case NotFound
    case Failed

  enum SaveSynopsisResult derives CanEqual:
    case Saved(album: Album)
    case NotFound, Failed, Uncertain

  enum SaveAuthorsResult derives CanEqual:
    case Saved(album: Album)
    case NotFound, Failed, Uncertain
