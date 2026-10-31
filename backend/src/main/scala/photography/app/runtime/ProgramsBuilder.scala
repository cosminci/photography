package photography.app.runtime

import cats.effect.{Async, Resource}
import cats.effect.std.Mutex
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.sqlite.album.{SqliteAlbumRepository, SqliteCountryRepository}
import photography.adapters.sqlite.chapter.SqliteChapterRepository
import photography.adapters.sqlite.spread.SqliteSpreadTemplateRepository
import photography.adapters.sqlite.photo.SqlitePhotoPlacementRepository
import photography.adapters.sqlite.cover.SqliteCoverRepository
import photography.adapters.filesystem.FilePhotoStorage
import photography.capabilities.{Clock, IdGenerator, Transaction}
import photography.adapters.sqlite.album.SqliteAlbumCompositionRepository
import photography.adapters.sqlite.spread.SqliteSpreadRepository
import photography.domain.album.*
import photography.domain.layout.SpreadTemplateId
import java.util.UUID
import photography.ports.photo.{PhotoRepository, ThumbnailStorage}
import photography.usecases.album.{AlbumManager, AlbumPageSize}
import photography.usecases.photo.ChapterPhotoInventory
import photography.ports.album.{AlbumRepository, AlbumCompositionRepository, CountryRepository}
import photography.usecases.chapter.ChapterMetadataManager
import photography.usecases.photo.ChapterPhotoImport
import photography.usecases.photo.{AlbumPhotoEvents, PhotoInventory}
import photography.usecases.chapter.ChapterPlanning
import photography.ports.spread.SpreadTemplateRepository
import photography.usecases.spread.SpreadManager
import photography.usecases.cover.CoverManager
import photography.adapters.imageio.ImageIoPhotoThumbnailGenerator
import photography.adapters.sqlite.gear.SqliteGearRepository
import photography.usecases.gear.GearManager
import photography.usecases.chapter.ChapterManager

final case class Programs[F[_]](
    albums: AlbumManager[F],
    chapters: ChapterMetadataManager[F],
    gear: GearManager[F],
    chapterGear: ChapterManager[F],
    templates: SpreadTemplateRepository[F],
    inventories: ChapterPhotoInventory[F],
    metadata: AlbumRepository.MetadataRead[F],
    chapterMetadata: AlbumRepository.ChapterRead[F],
    membership: AlbumCompositionRepository.MembershipRead[F],
    countries: CountryRepository[F],
    photoEvents: AlbumPhotoEvents[F],
    spreads: SpreadManager[F],
    coverPlacements: CoverManager[F]
)

trait ProgramsBuilder[F[_]]:
  def build: Resource[F, Programs[F]]

object ProgramsBuilder:
  def make[F[_]: Async: Logger: Clock: IdGenerator](
      sqlite: Transactor[F],
      pageSize: AlbumPageSize,
      photoRepository: PhotoRepository[ConnectionIO],
      thumbnails: ThumbnailStorage[F],
      quality: ThumbnailQuality
  ): ProgramsBuilder[F] = new LiveProgramsBuilder(sqlite, pageSize, photoRepository, thumbnails, quality)

  final private class LiveProgramsBuilder[F[_]: Async: Logger: Clock: IdGenerator](
      sqlite: Transactor[F],
      pageSize: AlbumPageSize,
      photoRepository: PhotoRepository[ConnectionIO],
      thumbnails: ThumbnailStorage[F],
      quality: ThumbnailQuality
  ) extends ProgramsBuilder[F]:
    private given Logger[ConnectionIO] = Slf4jLogger.getLogger[ConnectionIO]
    private given Transaction[ConnectionIO, F] with
      override def run[A](program: ConnectionIO[A]): F[A]     = program.transact(sqlite)
      override def resolve[A](program: ConnectionIO[A]): F[A] = program.transact(sqlite)

    private val templates         = SqliteSpreadTemplateRepository.make[F](sqlite)
    private val albumComposition  = SqliteAlbumCompositionRepository.make
    private val chapterRepository = SqliteChapterRepository.make
    private val albumRepository   = SqliteAlbumRepository.make[F](sqlite, albumComposition, chapterRepository)
    private val placements        = SqlitePhotoPlacementRepository.make
    private val spreadRepository  = SqliteSpreadRepository.make
    private val coverRepository   = SqliteCoverRepository.make
    private val inventory         = PhotoInventory.make[F, ConnectionIO](photoRepository, placements, chapterRepository)
    private val planning          = ChapterPlanning.make(templates)
    private val gearRepository    = SqliteGearRepository.make[F](sqlite)

    override def build: Resource[F, Programs[F]] =
      for
        mutex   <- Resource.eval(Mutex[F])
        events  <- Resource.eval(AlbumPhotoEvents.make(albumRepository))
        imports <- buildImports(events)(using mutex)
      yield
        given Mutex[F] = mutex
        val gear       = GearManager.make(gearRepository, chapterRepository)
        Programs(
          albums = AlbumManager.make(albumRepository, templates, pageSize),
          chapters = ChapterMetadataManager.make(albumRepository, chapterRepository, planning, imports, photoRepository),
          gear = gear,
          chapterGear = ChapterManager.make(chapterRepository, gear),
          templates = templates,
          inventories = ChapterPhotoInventory.make(inventory, imports),
          metadata = albumRepository,
          chapterMetadata = albumRepository,
          membership = SqliteAlbumCompositionRepository.makeMembershipRead[F](sqlite),
          countries = SqliteCountryRepository.make[F](sqlite),
          photoEvents = events,
          spreads = SpreadManager.make(templates, albumComposition, spreadRepository, placements, defaultPhotoLayout),
          coverPlacements = CoverManager.make(coverRepository, placements)
        )

    private def buildImports(events: AlbumPhotoEvents[F])(using Mutex[F]) =
      val storage   = FilePhotoStorage.make(thumbnails)
      val generator = ImageIoPhotoThumbnailGenerator.make[F](quality)
      ChapterPhotoImport.make(storage, photoRepository, generator, events)

  private val defaultPhotoLayout = SpreadTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000113"))
