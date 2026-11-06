package photography.ports.cover

import photography.domain.album.{AlbumId, InteriorSpreadCount}
import cats.data.NonEmptyVector
import photography.domain.layout.{Cover, CoverPlacement, CoverSpecification, SpineOwner}

trait CoverRepository[Db[_]]:
  def read(album: AlbumId): Db[CoverRepository.ReadResult]

  def replace(album: AlbumId, placements: Vector[CoverPlacement]): Db[CoverRepository.ReplaceResult]
  def setSpineOwner(album: AlbumId, owner: SpineOwner): Db[CoverRepository.SetSpineOwnerResult]

object CoverRepository:
  final case class Stored(
      cover: Cover,
      placements: Vector[CoverPlacement],
      interiorSpreads: InteriorSpreadCount,
      specifications: NonEmptyVector[CoverSpecification]
  )

  enum ReadResult:
    case Found(stored: Stored)
    case NotFound

  enum ReplaceResult:
    case Replaced, NotFound

  enum SetSpineOwnerResult:
    case Updated, NotFound
