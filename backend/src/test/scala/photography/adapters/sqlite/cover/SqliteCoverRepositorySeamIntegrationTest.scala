package photography.adapters.sqlite.cover

import cats.effect.{IO, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import io.circe.Json
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityCodec.*
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.sqlite.{FlywayMigrator, SqliteConnection}
import photography.adapters.sqlite.SqliteCodecs.given
import photography.app.config.SqliteConfig
import photography.capabilities.{IdGenerator, Transaction}
import photography.adapters.sqlite.photo.SqlitePhotoPlacementRepository
import photography.adapters.sqlite.album.SqliteAlbumCompositionRepository
import photography.adapters.sqlite.spread.{SqliteSpreadRepository, SqliteSpreadTemplateRepository}
import photography.adapters.http.PhotoPlacementApi
import photography.domain.album.{AlbumId, PhotoId}
import photography.domain.editing.*
import photography.domain.layout.*
import photography.ports.cover.CoverRepository.{Composition, ReadResult, ReplaceResult}
import photography.syntax.SyncSyntax.*
import photography.usecases.cover.CoverManager
import photography.usecases.editing.EditorSessionManager
import photography.usecases.editing.EditorSessionManager.*
import photography.usecases.spread.SpreadManager
import java.nio.file.{Files, Path}
import java.util.UUID

class SqliteCoverRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[ConnectionIO] = Slf4jLogger.getLogger[ConnectionIO]
  private val album                  = AlbumId(new UUID(0L, 1L))
  private val cover                  = Cover(
    album,
    SpineOwner.Back,
    CoverSlot(CoverSlotId(new UUID(0L, 2L))),
    CoverSlot(CoverSlotId(new UUID(0L, 3L)))
  )
  private val firstPhoto   = PhotoId(new UUID(0L, 4L))
  private val secondPhoto  = PhotoId(new UUID(0L, 5L))
  private val initial      = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)
  private val adjusted     = PhotoTransform(Shift(x = 12.5, y = -7.25), mirrored = true, zoom = 3.0)
  private val first        = CoverPlacement(CoverPlacementId(new UUID(0L, 6L)), cover.front.id, firstPhoto, adjusted)
  private val second       = CoverPlacement(CoverPlacementId(new UUID(0L, 7L)), cover.back.id, secondPhoto, initial)
  private val foreignAlbum = AlbumId(new UUID(0L, 8L))
  private val foreignCover = Cover(
    foreignAlbum,
    SpineOwner.Front,
    CoverSlot(CoverSlotId(new UUID(0L, 9L))),
    CoverSlot(CoverSlotId(new UUID(0L, 10L)))
  )
  private val opening      = SpreadId(new UUID(0L, 20L))
  private val openingSlot  = PhotoSpreadSlotId(new UUID(0L, 21L))
  private val connection   = EditorConnection(SessionId(new UUID(0L, 200L)), TabId(new UUID(0L, 201L)), ConnectionGeneration(new UUID(0L, 200L)))
  private val grant        = GrantId(new UUID(0L, 200L))
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given IdGenerator[IO] with
    override def generateId: IO[UUID] = new UUID(0L, 200L).pure[IO]

  test("should atomically save, swap and release cover placements while retaining cover structure"):
    val absent   = AlbumId(new UUID(0L, 99L))
    val database = Resource.make(Files.createTempFile(Path.of("target"), "cover-placement-", ".sqlite").blocking[IO]): path =>
      Files.deleteIfExists(path).blocking[IO].void
    val swapped      = Vector(first.copy(slot = cover.back.id), second.copy(slot = cover.front.id))
    val invalidSlots = Vector(CoverSlotId(new UUID(0L, 98L)), foreignCover.front.id)

    database.evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void).use: path =>
      SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor.use: xa =>
        val repository          = SqliteCoverRepository.make
        val invalidReplacements = invalidSlots.traverse: slot =>
          val replacement = Vector(first.copy(slot = slot))
          for
            result  <- repository.replace(album, placements = replacement).transact(xa).attempt
            current <- repository.read(album).transact(xa)
            foreign <- repository.read(foreignAlbum).transact(xa)
          yield (result.isLeft, current, foreign)
        val expectedForeign = ReadResult.Found(Composition(foreignCover, placements = Vector.empty[CoverPlacement]))
        val expectedMoved   = ReadResult.Found(Composition(cover, placements = swapped))

        for
          _                  <- seedComposition.transact(xa)
          empty              <- repository.read(album).transact(xa)
          missing            <- repository.read(absent).transact(xa)
          missingReplacement <- repository.replace(absent, placements = Vector.empty[CoverPlacement]).transact(xa)
          firstSave          <- repository.replace(album, placements = Vector(first, second)).transact(xa)
          saved              <- repository.read(album).transact(xa)
          swapSave           <- repository.replace(album, placements = swapped).transact(xa)
          moved              <- repository.read(album).transact(xa)
          failed             <- repository.replace(album, placements = Vector(first, second.copy(photo = firstPhoto))).transact(xa).attempt
          retained           <- repository.read(album).transact(xa)
          rejectedSlots      <- invalidReplacements
          release            <- repository.replace(album, placements = Vector.empty[CoverPlacement]).transact(xa)
          removed            <- repository.read(album).transact(xa)
          title              <- sql"SELECT title FROM album WHERE id = ${album.value}".query[String].unique.transact(xa)
        yield
          assertEquals(empty, ReadResult.Found(Composition(cover, placements = Vector.empty[CoverPlacement])))
          assertEquals(missing, ReadResult.NotFound)
          assertEquals(missingReplacement, ReplaceResult.NotFound)
          assertEquals(firstSave, ReplaceResult.Replaced)
          assertEquals(swapSave, ReplaceResult.Replaced)
          assertEquals(release, ReplaceResult.Replaced)
          assertEquals(saved, ReadResult.Found(Composition(cover, placements = Vector(first, second))))
          assertEquals(moved, expectedMoved)
          assert(failed.isLeft)
          assertEquals(retained, moved)
          assertEquals(rejectedSlots, Vector.fill(invalidSlots.size)((true, expectedMoved, expectedForeign)))
          assertEquals(removed, empty)
          assertEquals(title, "Album")

  test("should commit only one concurrent cover placement for the same photo"):
    val database = Resource.make(Files.createTempFile(Path.of("target"), "cover-concurrent-", ".sqlite").blocking[IO]): path =>
      Files.deleteIfExists(path).blocking[IO].void

    database.evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void).use: path =>
      SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor.use: xa =>
        Resource.eval(Mutex[IO]).use: mutex =>
          given Mutex[IO] = mutex
          val repository  = SqliteCoverRepository.make

          for
            _        <- seedComposition.transact(xa)
            manager  <- buildCoverManager(xa)
            outcomes <- Vector(cover.front.id, cover.back.id).parTraverse(slot =>
              manager.edit(album, CoverManager.Edit.Place(slot, firstPhoto), connection, grant)
            )
            current <- repository.read(album).transact(xa)
          yield
            val savedCount    = outcomes.count { case CoverManager.Result.Saved(_) => true; case _ => false }
            val rejectedCount = outcomes.count { case CoverManager.Result.Rejected(CoverManager.Failure.PhotoAlreadyUsed) => true; case _ => false }
            val currentPhotos = current match
              case ReadResult.Found(value) => value.placements.map(_.photo)
              case ReadResult.NotFound     => fail("Expected the saved cover")
            assertEquals(savedCount, 1)
            assertEquals(rejectedCount, 1)
            assertEquals(currentPhotos, Vector(firstPhoto))

  test("should commit only one concurrent placement across cover and interior contexts"):
    val database = Resource.make(Files.createTempFile(Path.of("target"), "cover-interior-concurrent-", ".sqlite").blocking[IO]): path =>
      Files.deleteIfExists(path).blocking[IO].void

    database.evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void).use: path =>
      SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor.use: xa =>
        Resource.eval(Mutex[IO]).use: mutex =>
          given Mutex[IO] = mutex
          given IdGenerator[IO] with
            override def generateId: IO[UUID] = IO(UUID.randomUUID())
          given Transaction[ConnectionIO, IO] with
            override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
            override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)

          for
            _               <- seedComposition.transact(xa)
            sessions        <- EditorSessionManager.make[IO](lease = 30L)
            coverOwner      <- bindBrowser(sessions, owner = 1L)
            spreadOwner     <- bindBrowser(sessions, owner = 2L)
            coverSelection  <- sessions.select(coverOwner, TargetRevision.initial, EditingTarget.Cover(album))
            spreadSelection <- sessions.select(spreadOwner, TargetRevision.initial, EditingTarget.Spread(opening))
            coverLock = coverSelection match
              case SelectResult.Owned(value, _) => value
              case _                            => fail("Expected Cover ownership")
            spreadLock = spreadSelection match
              case SelectResult.Owned(value, _) => value
              case _                            => fail("Expected spread ownership")
            covers    = CoverManager.make[IO, ConnectionIO](SqliteCoverRepository.make, SqlitePhotoPlacementRepository.make, sessions)
            interiors = buildSpreadManager(xa, sessions)
            outcomes <- (
              covers.edit(album, CoverManager.Edit.Place(cover.front.id, firstPhoto), coverOwner, coverLock.grant),
              interiors.editPlacements(album, opening, SpreadManager.Edit.Place(openingSlot, firstPhoto), spreadOwner, spreadLock.grant)
            ).parTupled
            placementCount <- sql"SELECT COUNT(*) FROM placement WHERE photo_id = ${firstPhoto.value}".query[Int].unique.transact(xa)
          yield
            outcomes match
              case (CoverManager.Result.Saved(_), SpreadManager.PlacementResult.Rejected(SpreadManager.Failure.PhotoAlreadyUsed)) => ()
              case (CoverManager.Result.Rejected(CoverManager.Failure.PhotoAlreadyUsed), SpreadManager.PlacementResult.Saved(_))  => ()
              case _ => fail("Expected one saved composition and one already-used rejection")
            assertEquals(placementCount, 1)

  test("should reject invalid persisted cover shape, identities and transforms and propagate unavailable reads"):
    val database = Resource.make(Files.createTempFile(Path.of("target"), "cover-validity-", ".sqlite").blocking[IO]): path =>
      Files.deleteIfExists(path).blocking[IO].void

    database.evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void).use: path =>
      SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor.use: xa =>
        val repository      = SqliteCoverRepository.make
        val missingSide     = sql"DELETE FROM cover_slot WHERE album_id = ${album.value} AND side = 'front'".update.run >> repository.read(album)
        val invalidIdentity = sql"UPDATE cover_slot SET id = 'invalid' WHERE album_id = ${album.value} AND side = 'front'".update.run >>
          repository.read(album)
        val invalidTransform = sql"UPDATE placement SET zoom = 0".update.run >> repository.read(album)
        val unavailable      = sql"ALTER TABLE placement RENAME TO inaccessible".update.run >> repository.read(album)

        for
          _         <- seedComposition.transact(xa)
          missing   <- missingSide.transact(xa).attempt
          identity  <- invalidIdentity.transact(xa).attempt
          _         <- repository.replace(album, placements = Vector(first)).transact(xa)
          transform <- invalidTransform.transact(xa).attempt
          failed    <- unavailable.transact(xa).attempt
          retained  <- repository.read(album).transact(xa)
        yield
          assert(missing.isLeft)
          assert(identity.isLeft)
          assert(transform.isLeft)
          assert(failed.isLeft)
          assertEquals(retained, ReadResult.Found(Composition(cover, placements = Vector(first))))

  test("should protect both Cover sides through real HTTP and SQLite and reconcile a discarded save acknowledgement"):
    val database = Resource.make(Files.createTempFile(Path.of("target"), "cover-http-admission-", ".sqlite").blocking[IO]): path =>
      Files.deleteIfExists(path).blocking[IO].void
    val base = s"/api/albums/${album.value}/cover"
    val body = Json.obj(
      "slotId"  -> Json.fromString(cover.front.id.value.toString),
      "photoId" -> Json.fromString(firstPhoto.value.toString)
    )

    database.evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void).use: path =>
      SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor.use: xa =>
        Mutex[IO].flatMap: mutex =>
          given Mutex[IO] = mutex
          given IdGenerator[IO] with
            override def generateId: IO[UUID] = IO(UUID.randomUUID())
          given Transaction[ConnectionIO, IO] with
            override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
            override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
          val repository = SqliteCoverRepository.make

          for
            _        <- seedComposition.transact(xa)
            sessions <- EditorSessionManager.make[IO](lease = 30L)
            owner    <- bindBrowser(sessions, owner = 1L)
            other    <- bindBrowser(sessions, owner = 2L)
            selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Cover(album))
            (lock, revision) = selected match
              case SelectResult.Owned(value, current) => (value, current)
              case _                                  => fail("Expected Cover ownership")
            otherSelection <- sessions.select(other, TargetRevision.initial, EditingTarget.Cover(foreignAlbum))
            otherLock = otherSelection match
              case SelectResult.Owned(value, _) => value
              case _                            => fail("Expected other Cover ownership")
            covers = CoverManager.make[IO, ConnectionIO](repository, SqlitePhotoPlacementRepository.make, sessions)
            api    = PhotoPlacementApi.make(buildSpreadManager(xa, sessions), covers).orNotFound
            acknowledged <- api.run(coverRequest(Method.POST, base + "/placements", owner, lock.grant).withEntity(body))
            saved        <- repository.read(album).transact(xa)
            reconciled   <- api.run(Request[IO](Method.GET, Uri.unsafeFromString(base)))
            savedBody    <- reconciled.as[Json]
            blocked      <- api.run(coverRequest(Method.POST, base + "/placements", other, otherLock.grant).withEntity(
              body.mapObject(_.add("slotId", Json.fromString(cover.back.id.value.toString)))
            ))
            blockedBody <- blocked.as[Json]
            released    <- sessions.release(owner, revision, lock.grant)
            nextRevision = released match
              case ReleaseResult.Released(value) => value
              case _                             => fail("Expected release")
            next <- sessions.select(owner, nextRevision, EditingTarget.Cover(album))
            successor = next match
              case SelectResult.Owned(value, _) => value
              case _                            => fail("Expected reacquisition")
            stale     <- api.run(coverRequest(Method.POST, base + "/placements", owner, lock.grant).withEntity(body))
            staleBody <- stale.as[Json]
            retained  <- repository.read(album).transact(xa)
          yield
            assertEquals(acknowledged.status, Status.Ok)
            assertEquals(reconciled.status, Status.Ok)
            saved match
              case ReadResult.Found(value) =>
                val placementJson = value.placements.map(value =>
                  Json.obj(
                    "id"        -> Json.fromString(value.id.value.toString),
                    "slotId"    -> Json.fromString(value.slot.value.toString),
                    "photoId"   -> Json.fromString(value.photo.value.toString),
                    "transform" -> Json.obj("x" -> Json.fromInt(0), "y" -> Json.fromInt(0), "zoom" -> Json.fromInt(1), "mirrored" -> Json.False)
                  )
                )
                val expected = Json.obj(
                  "frontSlotId" -> Json.fromString(cover.front.id.value.toString),
                  "backSlotId"  -> Json.fromString(cover.back.id.value.toString),
                  "spineOwner"  -> Json.fromString("back"),
                  "placements"  -> Json.arr(placementJson*)
                )
                assertEquals(value.cover, cover)
                assertEquals(value.placements.map(value => (value.slot, value.photo, value.transform)), Vector((cover.front.id, firstPhoto, initial)))
                assertEquals(savedBody, expected)
              case _ => fail("Expected saved Cover")
            assertEquals(blocked.status, Status.Conflict)
            assertEquals(
              blockedBody,
              Json.obj(
                "code"    -> Json.fromString("target_locked"),
                "ownerId" -> Json.fromString(new UUID(0L, 1L).toString),
                "alias"   -> Json.fromString("Browser")
              )
            )
            assertEquals(successor.grant.value =!= lock.grant.value, true)
            assertEquals(stale.status, Status.Conflict)
            assertEquals(staleBody, Json.obj("code" -> Json.fromString("stale_grant")))
            assertEquals(retained, saved)

  test("should protect interior captions through real HTTP and SQLite and reconcile a discarded acknowledgement without replay"):
    val database = Resource.make(Files.createTempFile(Path.of("target"), "spread-http-admission-", ".sqlite").blocking[IO]): path =>
      Files.deleteIfExists(path).blocking[IO].void
    val base = s"/api/albums/${album.value}/spreads/${opening.value}"

    database.evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void).use: path =>
      SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor.use: xa =>
        Mutex[IO].flatMap: mutex =>
          given Mutex[IO] = mutex
          given IdGenerator[IO] with
            override def generateId: IO[UUID] = IO(UUID.randomUUID())
          given Transaction[ConnectionIO, IO] with
            override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
            override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
          for
            _        <- seedComposition.transact(xa)
            sessions <- EditorSessionManager.make[IO](lease = 30L)
            owner    <- bindBrowser(sessions, owner = 1L)
            other    <- bindBrowser(sessions, owner = 2L)
            selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Spread(opening))
            (lock, revision) = selected match
              case SelectResult.Owned(value, current) => (value, current)
              case _                                  => fail("Expected spread ownership")
            otherSelection <- sessions.select(other, TargetRevision.initial, EditingTarget.Cover(album))
            otherLock = otherSelection match
              case SelectResult.Owned(value, _) => value
              case _                            => fail("Expected Cover ownership")
            covers  = CoverManager.make[IO, ConnectionIO](SqliteCoverRepository.make, SqlitePhotoPlacementRepository.make, sessions)
            spreads = buildSpreadManager(xa, sessions)
            api     = PhotoPlacementApi.make(spreads, covers).orNotFound
            placed <- spreads.editPlacements(album, opening, SpreadManager.Edit.Place(openingSlot, firstPhoto), owner, lock.grant)
            savedPlacement = placed match
              case SpreadManager.PlacementResult.Saved(value) => value.placements.headOption.getOrElse(fail("Expected placement"))
              case _                                          => fail("Expected saved placement")
            change = Json.obj("captionCorner" -> Json.fromString("top_left"))
            path   = s"$base/placements/${savedPlacement.id.value}"
            acknowledged <- api.run(coverRequest(Method.PATCH, path, owner, lock.grant).withEntity(change))
            reconciled   <- api.run(Request[IO](Method.GET, Uri.unsafeFromString(base)))
            body         <- reconciled.as[Json]
            blocked      <- api.run(coverRequest(Method.DELETE, path, other, otherLock.grant))
            blockedBody  <- blocked.as[Json]
            released     <- sessions.release(owner, revision, lock.grant)
            nextRevision = released match
              case ReleaseResult.Released(value) => value
              case _                             => fail("Expected release")
            successor <- sessions.select(owner, nextRevision, EditingTarget.Spread(opening))
            stale     <- api.run(coverRequest(Method.DELETE, path, owner, lock.grant))
            staleBody <- stale.as[Json]
            current   <- spreads.readPlacements(album, opening)
          yield
            val expected = Spread.AlbumStart(
              opening,
              SpreadTemplateId(new UUID(2L, 22L)),
              photoSlots =
                Vector(SpreadSlot.Photo(openingSlot, SlotBox(x = 0.0, y = 0.0, width = 50.0, height = 100.0), bleed = Set.empty[SpreadEdge])),
              textSlot = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 23L)), SlotBox(x = 50.0, y = 0.0, width = 50.0, height = 100.0)),
              placements = Vector(SpreadPlacement(savedPlacement.id, openingSlot, firstPhoto, initial, CaptionCorner.TopLeft))
            )
            val expectedBody = Json.obj(
              "id"         -> Json.fromString(opening.value.toString),
              "kind"       -> Json.fromString("album_start"),
              "templateId" -> Json.fromString(expected.template.value.toString),
              "slots"      -> Json.arr(
                Json.obj(
                  "id"     -> Json.fromString(openingSlot.value.toString),
                  "kind"   -> Json.fromString("photo"),
                  "x"      -> Json.fromInt(0),
                  "y"      -> Json.fromInt(0),
                  "width"  -> Json.fromInt(50),
                  "height" -> Json.fromInt(100)
                ),
                Json.obj(
                  "id"     -> Json.fromString(new UUID(0L, 23L).toString),
                  "kind"   -> Json.fromString("text"),
                  "x"      -> Json.fromInt(50),
                  "y"      -> Json.fromInt(0),
                  "width"  -> Json.fromInt(50),
                  "height" -> Json.fromInt(100)
                )
              ),
              "placements" -> Json.arr(Json.obj(
                "id"            -> Json.fromString(savedPlacement.id.value.toString),
                "slotId"        -> Json.fromString(openingSlot.value.toString),
                "photoId"       -> Json.fromString(firstPhoto.value.toString),
                "captionCorner" -> Json.fromString("top_left"),
                "transform"     -> Json.obj("x" -> Json.fromInt(0), "y" -> Json.fromInt(0), "zoom" -> Json.fromInt(1), "mirrored" -> Json.False)
              ))
            )
            assertEquals(acknowledged.status, Status.Ok)
            assertEquals(reconciled.status, Status.Ok)
            assertEquals(body, expectedBody)
            assertEquals(blocked.status, Status.Conflict)
            assertEquals(
              blockedBody,
              Json.obj(
                "code"    -> Json.fromString("target_locked"),
                "ownerId" -> Json.fromString(new UUID(0L, 1L).toString),
                "alias"   -> Json.fromString("Browser")
              )
            )
            successor match
              case SelectResult.Owned(value, _) => assertEquals(value.grant.value =!= lock.grant.value, true)
              case _                            => fail("Expected reacquisition")
            assertEquals(stale.status, Status.Conflict)
            assertEquals(staleBody, Json.obj("code" -> Json.fromString("stale_grant")))
            assertEquals(current, SpreadManager.PlacementReadResult.Found(expected))

  private def bindBrowser(sessions: EditorSessionManager[IO], owner: Long) =
    for
      registered <- sessions.register(EditorId(new UUID(0L, owner)), "Browser", maybeSession = None)
      session = registered match
        case RegisterResult.Ready(value) => value
        case _                           => fail("Expected registration")
      bound <- sessions.bind(session.id, TabId(new UUID(1L, owner)))
    yield bound match
      case BindResult.Active(value) => value
      case _                        => fail("Expected bound tab")

  private def coverRequest(method: Method, path: String, connection: EditorConnection, grant: GrantId) =
    Request[IO](method, Uri.unsafeFromString(path)).putHeaders(
      "X-Editor-Session"    -> connection.session.value.toString,
      "X-Editor-Tab"        -> connection.tab.value.toString,
      "X-Editor-Generation" -> connection.generation.value.toString,
      "X-Editor-Grant"      -> grant.value.toString
    )

  private def seedComposition =
    val template = new UUID(2L, 22L)
    val textSlot = new UUID(0L, 23L)
    sql"""INSERT INTO album (id, year, title, synopsis, created_at_ms)
          VALUES (${album.value}, 2026, 'Album', 'Synopsis', 0), (${foreignAlbum.value}, 2027, 'Other', '', 0)""".update.run >>
      sql"INSERT INTO destination VALUES ('d', 'Trip')".update.run >>
      sql"INSERT INTO chapter VALUES ('c', ${album.value}, 'd', '', 0, 1, 0, '/photos')".update.run >>
      sql"INSERT INTO cover VALUES (${album.value}, 'back'), (${foreignAlbum.value}, 'front')".update.run >>
      sql"""INSERT INTO cover_slot VALUES
            (${cover.front.id.value}, ${album.value}, 'front'), (${cover.back.id.value}, ${album.value}, 'back'),
            (${foreignCover.front.id.value}, ${foreignAlbum.value}, 'front'), (${foreignCover.back.id.value}, ${foreignAlbum.value}, 'back')""".update.run >>
      Vector(firstPhoto, secondPhoto).traverse_ { photo =>
        sql"""INSERT INTO photo
          (id, chapter_id, source, source_hash, width, height, thumbnail, thumbnail_version,
           thumbnail_width, thumbnail_height, thumbnail_quality, thumbnail_bytes)
          VALUES (${photo.value}, 'c', ${photo.value.toString}, 'hash', 1, 1, 'key', 'version', 1, 1, 'low', 1)""".update.run.void
      } >>
      sql"INSERT INTO spread_template VALUES ($template, 'Opening', 'album_start')".update.run >>
      sql"""INSERT INTO spread_template_slot (template_id, kind, x, y, width, height)
            VALUES ($template, 'photo', 0, 0, 50, 100), ($template, 'text', 50, 0, 50, 100)""".update.run >>
      sql"INSERT INTO spread VALUES (${opening.value}, $template, 'album_start', ${album.value}, NULL, 0)".update.run >>
      sql"INSERT INTO spread_photo_slot VALUES (${openingSlot.value}, ${opening.value}, 0, 0, 50, 100)".update.run >>
      sql"INSERT INTO spread_text_slot VALUES ($textSlot, ${opening.value}, 50, 0, 50, 100)".update.run

  private def buildCoverManager(xa: Transactor[IO])(using Mutex[IO]) =
    given Transaction[ConnectionIO, IO] with
      override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
      override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
    val repository = SqliteCoverRepository.make
    val photos     = SqlitePhotoPlacementRepository.make
    for
      sessions <- EditorSessionManager.make[IO](lease = 30L)
      _        <- sessions.register(EditorId(new UUID(0L, 202L)), "Owner", maybeSession = None)
      _        <- sessions.bind(connection.session, connection.tab)
      _        <- sessions.select(connection, TargetRevision.initial, EditingTarget.Cover(album))
    yield CoverManager.make[IO, ConnectionIO](repository, photos, sessions)

  private def buildSpreadManager(xa: Transactor[IO], sessions: EditorSessionManager[IO])(using Mutex[IO]) =
    given Transaction[ConnectionIO, IO] with
      override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
      override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
    val photos             = SqlitePhotoPlacementRepository.make
    val composition        = SqliteAlbumCompositionRepository.make
    val spreads            = SqliteSpreadRepository.make
    val templateRepository = SqliteSpreadTemplateRepository.make[IO](xa)
    val templates          = templateRepository
    val defaultLayout      = SpreadTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000113"))
    SpreadManager.make[IO, ConnectionIO](templates, composition, spreads, photos, defaultLayout, sessions)
