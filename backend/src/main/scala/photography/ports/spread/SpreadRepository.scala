package photography.ports.spread

import cats.data.NonEmptyList
import photography.domain.album.ChapterId
import photography.domain.layout.{Spread, SpreadId, SpreadPlacement, PhotoSpreadSlotId, SpreadEdge}

trait SpreadRepository[Db[_]]:
  def insert(chapter: ChapterId, spread: Spread.PhotosOnly): Db[Unit]
  def replace(spread: Spread): Db[Unit]
  def replacePlacements(spread: SpreadId, placements: Vector[SpreadPlacement]): Db[Unit]
  def replaceBleed(spread: SpreadId, edges: Map[PhotoSpreadSlotId, Set[SpreadEdge]]): Db[Unit]
  def reorder(chapter: ChapterId, order: NonEmptyList[SpreadId]): Db[Unit]
  def remove(spread: SpreadId): Db[Unit]
