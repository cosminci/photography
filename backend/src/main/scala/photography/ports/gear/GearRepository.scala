package photography.ports.gear

import photography.domain.gear.*

trait GearRepository[F[_]]:
  def createCamera(camera: Camera): F[GearRepository.CameraCreateResult]
  def updateCamera(camera: Camera): F[GearRepository.CameraUpdateResult]
  def deleteCamera(id: CameraId): F[GearRepository.CameraDeleteResult]
  def createLens(lens: Lens): F[GearRepository.LensCreateResult]
  def updateLens(lens: Lens): F[GearRepository.LensUpdateResult]
  def deleteLens(id: LensId): F[GearRepository.LensDeleteResult]
  def readCatalog: F[GearRepository.CatalogReadResult]

object GearRepository:
  enum CameraCreateResult:
    case Created, Conflict, Failed, Uncertain

  enum CameraUpdateResult:
    case Updated, NotFound, Conflict, Failed, Uncertain

  enum CameraDeleteResult:
    case Deleted, NotFound, Used, Failed, Uncertain

  enum LensCreateResult:
    case Created, Conflict, Failed, Uncertain

  enum LensUpdateResult:
    case Updated, NotFound, Conflict, Failed, Uncertain

  enum LensDeleteResult:
    case Deleted, NotFound, Used, Failed, Uncertain

  enum CatalogReadResult:
    case Found(catalog: GearCatalog)
    case Failed
