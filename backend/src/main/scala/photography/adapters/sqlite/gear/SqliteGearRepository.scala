package photography.adapters.sqlite.gear

import cats.effect.Async
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.{Not, Empty}
import org.typelevel.doobie.{ConnectionIO, Fragment, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.sqlite.{SQLiteErrorCode, SQLiteException}
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.gear.*
import photography.ports.gear.GearRepository
import photography.ports.gear.GearRepository.*

import java.util.UUID

object SqliteGearRepository:
  def make[F[_]: Async: Logger](transactor: Transactor[F]): GearRepository[F] =
    new LiveSqliteGearRepository(transactor)

  final private class LiveSqliteGearRepository[F[_]: Async: Logger](transactor: Transactor[F]) extends GearRepository[F]:
    override def createCamera(camera: Camera): F[CameraCreateResult] =
      val (kind, maybeMount, maybeSensor) = encodeKind(camera.kind)
      val insert                          = sql"""INSERT INTO camera (id, code, friendly_name, kind, mount, sensor_format)
        VALUES (${camera.id.value}, ${camera.code: String}, ${camera.friendlyName: String}, $kind, $maybeMount, $maybeSensor)"""
      executeWrite(requireApplied(insert).as(CameraCreateResult.Created)).map:
        case WriteResult.Applied(result) => result
        case WriteResult.Conflict        => CameraCreateResult.Conflict
        case WriteResult.Uncertain       => CameraCreateResult.Uncertain
        case _                           => CameraCreateResult.Failed

    override def updateCamera(camera: Camera): F[CameraUpdateResult] =
      val (kind, maybeMount, maybeSensor) = encodeKind(camera.kind)
      val update                          = sql"""UPDATE camera SET code = ${camera.code: String}, friendly_name = ${camera.friendlyName: String},
        kind = $kind, mount = $maybeMount, sensor_format = $maybeSensor WHERE id = ${camera.id.value}"""
      val exists = sql"SELECT EXISTS(SELECT 1 FROM camera WHERE id = ${camera.id.value})".query[Boolean].unique
      executeWrite(updateExisting(update, exists).map(if _ then CameraUpdateResult.Updated else CameraUpdateResult.NotFound)).map:
        case WriteResult.Applied(result) => result
        case WriteResult.Conflict        => CameraUpdateResult.Conflict
        case WriteResult.Uncertain       => CameraUpdateResult.Uncertain
        case _                           => CameraUpdateResult.Failed

    override def deleteCamera(id: CameraId): F[CameraDeleteResult] =
      val delete = sql"DELETE FROM camera WHERE id = ${id.value}"
      val exists = sql"SELECT EXISTS(SELECT 1 FROM camera WHERE id = ${id.value})".query[Boolean].unique
      executeWrite(updateExisting(delete, exists).map(if _ then CameraDeleteResult.Deleted else CameraDeleteResult.NotFound)).map:
        case WriteResult.Applied(result) => result
        case WriteResult.Used            => CameraDeleteResult.Used
        case WriteResult.Uncertain       => CameraDeleteResult.Uncertain
        case _                           => CameraDeleteResult.Failed

    override def createLens(lens: Lens): F[LensCreateResult] =
      val mount    = encodeMount(lens.mount)
      val coverage = encodeFormat(lens.coverage)
      val insert   = sql"""INSERT INTO lens (id, code, friendly_name, mount, coverage)
        VALUES (${lens.id.value}, ${lens.code: String}, ${lens.friendlyName: String}, $mount, $coverage)"""
      executeWrite(requireApplied(insert).as(LensCreateResult.Created)).map:
        case WriteResult.Applied(result) => result
        case WriteResult.Conflict        => LensCreateResult.Conflict
        case WriteResult.Uncertain       => LensCreateResult.Uncertain
        case _                           => LensCreateResult.Failed

    override def updateLens(lens: Lens): F[LensUpdateResult] =
      val mount    = encodeMount(lens.mount)
      val coverage = encodeFormat(lens.coverage)
      val update   = sql"""UPDATE lens SET code = ${lens.code: String}, friendly_name = ${lens.friendlyName: String},
        mount = $mount, coverage = $coverage WHERE id = ${lens.id.value}"""
      val exists = sql"SELECT EXISTS(SELECT 1 FROM lens WHERE id = ${lens.id.value})".query[Boolean].unique
      executeWrite(updateExisting(update, exists).map(if _ then LensUpdateResult.Updated else LensUpdateResult.NotFound)).map:
        case WriteResult.Applied(result) => result
        case WriteResult.Conflict        => LensUpdateResult.Conflict
        case WriteResult.Uncertain       => LensUpdateResult.Uncertain
        case _                           => LensUpdateResult.Failed

    override def deleteLens(id: LensId): F[LensDeleteResult] =
      val delete = sql"DELETE FROM lens WHERE id = ${id.value}"
      val exists = sql"SELECT EXISTS(SELECT 1 FROM lens WHERE id = ${id.value})".query[Boolean].unique
      executeWrite(updateExisting(delete, exists).map(if _ then LensDeleteResult.Deleted else LensDeleteResult.NotFound)).map:
        case WriteResult.Applied(result) => result
        case WriteResult.Used            => LensDeleteResult.Used
        case WriteResult.Uncertain       => LensDeleteResult.Uncertain
        case _                           => LensDeleteResult.Failed

    override def readCatalog: F[CatalogReadResult] =
      val readCameras = sql"SELECT id, code, friendly_name, kind, mount, sensor_format FROM camera ORDER BY rowid"
        .query[(UUID, String, String, String, Option[String], Option[String])].to[Vector]
      val readLenses =
        sql"SELECT id, code, friendly_name, mount, coverage FROM lens ORDER BY rowid".query[(UUID, String, String, String, String)].to[Vector]
      val query =
        for
          cameras <- readCameras
          lenses  <- readLenses
          catalog <- (cameras.traverse(decodeCamera), lenses.traverse(decodeLens)).mapN(GearCatalog.apply).liftTo[ConnectionIO]
        yield CatalogReadResult.Found(catalog): CatalogReadResult
      query.transact(transactor).handleErrorWith(error => Logger[F].error(error)("SQLite gear catalog read failed").as(CatalogReadResult.Failed))

    private def requireApplied(statement: Fragment) =
      statement.update.run.ensure(new IllegalStateException("Expected one applied gear catalog row"))(_ === 1).void

    private def updateExisting(statement: Fragment, exists: ConnectionIO[Boolean]) =
      exists.flatMap:
        case false => false.pure[ConnectionIO]
        case true  => requireApplied(statement).as(true)

    private def executeWrite[A](query: ConnectionIO[A]) =
      val commit = transactor.strategy.after.handleErrorWith(error => new UncertainCommit(error).raiseError[ConnectionIO, Unit])
      query.transact(Transactor.after.set(transactor, commit)).attempt.flatMap:
        case Right(value) => WriteResult.Applied(value).pure[F]
        case Left(error)  =>
          val failure = error match
            case _: UncertainCommit => WriteResult.Uncertain
            case sqlite: SQLiteException
                if sqlite.getResultCode.code === SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE.code ||
                  sqlite.getResultCode.code === SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY.code => WriteResult.Conflict
            case sqlite: SQLiteException if sqlite.getResultCode.code === SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY.code => WriteResult.Used
            case _                                                                                                          => WriteResult.Failed
          Logger[F].error(error)(s"SQLite gear catalog write failed: $failure").as(failure)

    private def decodeCamera(row: (UUID, String, String, String, Option[String], Option[String])) =
      val (id, code, name, kind, maybeMount, maybeSensor) = row
      val decoded                                         =
        for
          validCode <- code.refineEither[Not[Empty]]
          validName <- name.refineEither[Not[Empty]]
          validKind <- decodeKind(kind = kind, maybeMount = maybeMount, maybeSensor = maybeSensor)
        yield Camera(CameraId(id), validCode, validName, validKind)
      decoded.leftMap(new IllegalArgumentException(_))

    private def decodeLens(row: (UUID, String, String, String, String)) =
      val (id, code, name, mount, coverage) = row
      val decoded                           =
        for
          validCode     <- code.refineEither[Not[Empty]]
          validName     <- name.refineEither[Not[Empty]]
          validMount    <- decodeMount(value = mount)
          validCoverage <- decodeFormat(value = coverage)
        yield Lens(LensId(id), validCode, validName, validMount, validCoverage)
      decoded.leftMap(new IllegalArgumentException(_))

    private def decodeKind(kind: String, maybeMount: Option[String], maybeSensor: Option[String]) =
      (kind, maybeMount, maybeSensor) match
        case ("fixed_lens", None, None)                          => CameraKind.FixedLens.asRight[String]
        case ("interchangeable_lens", Some(mount), Some(sensor)) =>
          (decodeMount(value = mount), decodeFormat(value = sensor)).mapN(CameraKind.InterchangeableLens.apply)
        case _ => "Invalid stored camera optics".asLeft[CameraKind]

    private def encodeKind(kind: CameraKind) = kind match
      case CameraKind.FixedLens                          => ("fixed_lens", none[String], none[String])
      case CameraKind.InterchangeableLens(mount, sensor) => ("interchangeable_lens", encodeMount(mount).some, encodeFormat(sensor).some)

    private def decodeMount(value: String) = value match
      case "sony_e"  => LensMount.SonyE.asRight[String]
      case "nikon_z" => LensMount.NikonZ.asRight[String]
      case _         => s"Unknown stored lens mount $value".asLeft[LensMount]

    private def encodeMount(value: LensMount) = value match
      case LensMount.SonyE  => "sony_e"
      case LensMount.NikonZ => "nikon_z"

    private def decodeFormat(value: String) = value match
      case "aps_c"      => SensorFormat.ApsC.asRight[String]
      case "full_frame" => SensorFormat.FullFrame.asRight[String]
      case _            => s"Unknown stored sensor format $value".asLeft[SensorFormat]

    private def encodeFormat(value: SensorFormat) = value match
      case SensorFormat.ApsC      => "aps_c"
      case SensorFormat.FullFrame => "full_frame"

    final private class UncertainCommit(cause: Throwable) extends RuntimeException(cause)

    private enum WriteResult[+A]:
      case Applied(value: A)
      case Conflict, Used, Failed, Uncertain
