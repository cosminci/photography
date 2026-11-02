package photography.adapters.sqlite.spread

import photography.adapters.sqlite.album.SqliteAlbumCompositionRepository

import photography.adapters.sqlite.FlywayMigrator
import photography.adapters.sqlite.SqliteConnection
import photography.adapters.sqlite.album.SqliteAlbumRepository
import photography.adapters.sqlite.chapter.SqliteChapterRepository
import photography.adapters.sqlite.photo.SqlitePhotoPlacementRepository
import photography.adapters.sqlite.photo.SqlitePhotoRepository
import photography.adapters.http.{AlbumSpreadApi, ChapterCompositionApi, Routes}

import cats.data.NonEmptyList
import cats.effect.std.Mutex
import cats.effect.{Deferred, IO, Outcome, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import io.circe.Json
import org.http4s.{Header, Headers, HttpApp, Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityCodec.*
import org.typelevel.ci.CIString
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.sqlite.SqliteCodecs.given
import photography.app.config.SqliteConfig
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.domain.layout.*
import photography.domain.editing.*
import photography.usecases.editing.{EditingAccess, EditorSessionManager}
import photography.ports.album.AlbumRepository
import photography.ports.chapter.ChapterRepository
import photography.ports.spread.SpreadTemplateRepository
import photography.usecases.chapter.{ChapterMetadataManager, ChapterPlanning}
import photography.usecases.photo.{ChapterPhotoImport, PhotoInventory}
import photography.usecases.spread.SpreadManager
import photography.usecases.spread.SpreadManager.*

import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.duration.*

final class SqliteSpreadRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given logger: Logger[IO]   = Slf4jLogger.getLogger[IO]
  private given Logger[ConnectionIO] = Slf4jLogger.getLogger[ConnectionIO]

  private val albumId       = AlbumId(new UUID(1L, 1L))
  private val chapterId     = ChapterId(new UUID(1L, 2L))
  private val photoId       = PhotoId(new UUID(77L, 1L))
  private val placementId   = SpreadPlacementId(new UUID(78L, 1L))
  private val defaultLayout = SpreadTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000113"))
  private val singleLayout  = SpreadTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000144"))
  private val identity      = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)
  private val country       = Country(CountryId(new UUID(9L, 1L)), code = "XX", name = "Test country", AbsoluteLocation(Path.of("/flags/xx.svg")))
  private val spreads       = SqliteSpreadRepository.make
  private val composition   = SqliteAlbumCompositionRepository.make
  private val spreadRows    = sql"SELECT count(*) FROM spread".query[Long].unique
  private val photoRows     = sql"SELECT count(*) FROM photo".query[Long].unique
  private val placementRows = sql"SELECT count(*) FROM placement".query[Long].unique
  private val chapterOrder  = sql"SELECT position FROM spread WHERE chapter_id = ${chapterId.value} ORDER BY position".query[Int].to[List]
  private val connection    = EditorConnection(SessionId(new UUID(0L, 900L)), TabId(new UUID(0L, 900L)), ConnectionGeneration(new UUID(0L, 900L)))
  private val grant         = GrantId(new UUID(0L, 900L))
  private val credentials   = Headers(
    Header.Raw(CIString("X-Editor-Session"), connection.session.value.toString),
    Header.Raw(CIString("X-Editor-Tab"), connection.tab.value.toString),
    Header.Raw(CIString("X-Editor-Generation"), connection.generation.value.toString),
    Header.Raw(CIString("X-Editor-Grant"), grant.value.toString)
  )

  test("should persist insert, reorder, template replacement and removal with placement cascades"):
    buildTransactor.use: xa =>
      for
        opening    <- seedChapter(xa)
        photo      <- buildPhotoSpread(xa, index = 5L, layout = defaultLayout)
        _          <- (spreads.insert(chapterId, photo) >> spreads.reorder(chapterId, order = NonEmptyList.of(opening.id, photo.id))).transact(xa)
        _          <- placePhoto(xa, photo.photoSlots.head.id, index = 1L)
        before     <- readChapter(xa).map(_.spreads)
        replaced   <- retemplate(xa, photo)
        _          <- spreads.replace(replaced).transact(xa)
        after      <- readChapter(xa).map(_.spreads)
        _          <- spreads.remove(photo.id).transact(xa)
        removed    <- readChapter(xa).map(_.spreads)
        photos     <- photoRows.transact(xa)
        placements <- placementRows.transact(xa)
      yield
        val beforeSpreads = before
        val afterSpreads  = after
        assertEquals(beforeSpreads.map(_.placements.map(placed => (placed.id, placed.photo))), Vector(Vector((placementId, photoId))))
        assertEquals(afterSpreads.map(_.template), Vector(singleLayout))
        assertEquals(afterSpreads.flatMap(_.placements).map(_.transform), Vector(identity))
        assertEquals(afterSpreads.flatMap(_.placements).map(_.slot), afterSpreads.flatMap(_.photoSlots.toList).map(_.id))
        assertEquals(removed, Vector.empty[Spread.PhotosOnly])
        assertEquals(photos, 1L)
        assertEquals(placements, 0L)

  test("should roll back every spread write of a failed action and reject incomplete chapter orders"):
    buildTransactor.use: xa =>
      val failure = new IllegalStateException("Abort composed action")
      for
        opening    <- seedChapter(xa)
        committed  <- buildPhotoSpread(xa, index = 6L, layout = defaultLayout)
        second     <- buildPhotoSpread(xa, index = 7L, layout = defaultLayout)
        _          <- spreads.insert(chapterId, committed).transact(xa)
        failed     <- (spreads.insert(chapterId, second) >> failure.raiseError[ConnectionIO, Unit]).transact(xa).attempt
        rows       <- spreadRows.transact(xa)
        incomplete <- spreads.reorder(chapterId, order = NonEmptyList.one(opening.id)).transact(xa).attempt
        positions  <- chapterOrder.transact(xa)
      yield
        assert(failed.isLeft)
        assertEquals(rows, 3L)
        assert(incomplete.isLeft)
        assertEquals(positions, List(0, 1))

  test("should keep one chapter's positions contiguous when concurrent additions commit through the real mutex"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given coordination: Mutex[IO] = mutex
        for
          api       <- buildApi(xa, target = SpreadId(new UUID(15L, 100L)))
          opening   <- seedChapter(xa)
          results   <- (1 to 5).toList.parTraverse(_ => api.run(addRequest(opening.id)))
          positions <- chapterOrder.transact(xa)
        yield
          assertEquals(results.map(_.status), List.fill(5)(Status.Created))
          assertEquals(positions, List(0, 1, 2, 3, 4, 5))

  test("should move a valid photo spread without validating an unrelated incomplete cover"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given coordination: Mutex[IO] = mutex
        for
          api     <- buildApi(xa, target = SpreadId(new UUID(15L, 100L)))
          opening <- seedChapter(xa)
          first   <- buildPhotoSpread(xa, index = 15L, layout = defaultLayout)
          second  <- buildPhotoSpread(xa, index = 16L, layout = defaultLayout)
          _       <- (spreads.insert(chapterId, first) >> spreads.insert(chapterId, second)).transact(xa)
          _       <- sql"DELETE FROM cover_slot WHERE album_id = ${albumId.value} AND side = 'back'".update.run.transact(xa)
          observed = NonEmptyList.of(opening.id, first.id, second.id)
          proposed = NonEmptyList.of(opening.id, second.id, first.id)
          result  <- api.run(orderRequest(observed, proposed))
          ordered <- sql"SELECT id FROM spread WHERE chapter_id = ${chapterId.value} ORDER BY position".query[UUID].to[List].transact(xa)
        yield
          assertEquals(result.status, Status.NoContent)
          assertEquals(ordered.drop(1), List(second.id.value, first.id.value))

  test("should coordinate complete order replacement with concurrent addition without losing membership"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        for
          api     <- buildApi(xa, target = SpreadId(new UUID(15L, 100L)))
          opening <- seedChapter(xa)
          first   <- buildPhotoSpread(xa, index = 15L, layout = defaultLayout)
          second  <- buildPhotoSpread(xa, index = 16L, layout = defaultLayout)
          _       <- (spreads.insert(chapterId, first) >> spreads.insert(chapterId, second)).transact(xa)
          observed = NonEmptyList.of(opening.id, first.id, second.id)
          proposed = NonEmptyList.of(opening.id, second.id, first.id)
          (replacement, addition) <- (
            api.run(orderRequest(observed, proposed)),
            api.run(addRequest(opening.id))
          ).parTupled
          replacementBody <- if replacement.status === Status.Conflict then replacement.as[Json].map(_.some) else none[Json].pure[IO]
          body            <- addition.as[Json]
          saved           <- composition.findChapterOrder(albumId, chapterId).transact(xa)
          positions       <- chapterOrder.transact(xa)
        yield
          val added = body.hcursor.get[UUID]("id").fold(error => fail(error.message), id => id)
          assertEquals(addition.status, Status.Created)
          assert(Set(Status.NoContent, Status.Conflict).contains(replacement.status))
          assertEquals(
            replacementBody,
            Option.when(replacement.status === Status.Conflict)(Json.obj("code" -> Json.fromString("composition_changed")))
          )
          assertEquals(saved.map(_.toList.map(_.value).toSet), Some(Set(opening.id.value, first.id.value, second.id.value, added)))
          assertEquals(saved.map(_.head.value), Some(opening.id.value))
          assertEquals(positions, List(0, 1, 2, 3))

  test("should commit exactly one concurrent replacement of the same observed order"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        for
          api     <- buildApi(xa, target = SpreadId(new UUID(15L, 100L)))
          opening <- seedChapter(xa)
          first   <- buildPhotoSpread(xa, index = 15L, layout = defaultLayout)
          second  <- buildPhotoSpread(xa, index = 16L, layout = defaultLayout)
          _       <- (spreads.insert(chapterId, first) >> spreads.insert(chapterId, second)).transact(xa)
          observed = NonEmptyList.of(opening.id, first.id, second.id)
          proposed = NonEmptyList.of(opening.id, second.id, first.id)
          replacements <- List.fill(2)(()).parTraverse(_ => api.run(orderRequest(observed, proposed)))
          rejected     <- replacements.filter(_.status === Status.Conflict).traverse(_.as[Json])
          saved        <- composition.findChapterOrder(albumId, chapterId).transact(xa)
        yield
          assertEquals(replacements.map(_.status).toSet, Set(Status.NoContent, Status.Conflict))
          assertEquals(rejected, List(Json.obj("code" -> Json.fromString("composition_changed"))))
          assertEquals(saved.map(_.toList.map(_.value)), Some(proposed.toList.map(_.value)))

  test("should cancel an order replacement while coordination is held without applying a write"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        (buildApi(xa, target = SpreadId(new UUID(15L, 100L))), seedChapter(xa), Deferred[IO, Unit]).tupled.flatMap {
          case (api, opening, started) =>
            val order             = NonEmptyList.one(opening.id)
            val replacement       = started.complete(()).void >> api.run(orderRequest(order, order))
            val cancelWhileLocked = mutex.lock.surround:
              for
                fiber  <- replacement.start
                _      <- started.get
                _      <- IO.cede
                _      <- fiber.cancel.timeout(5.seconds)
                result <- fiber.join
              yield result

            for
              outcome <- cancelWhileLocked
              saved   <- composition.findChapterOrder(albumId, chapterId).transact(xa)
            yield outcome match
              case Outcome.Canceled() => assertEquals(saved.map(_.toList.map(_.value)), Some(List(opening.id.value)))
              case other              => fail(s"Expected canceled replacement, obtained $other")
        }

  test("should coordinate complete order replacement with concurrent deletion without resurrecting a removed spread"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        for
          api     <- buildApi(xa, target = SpreadId(new UUID(15L, 100L)))
          opening <- seedChapter(xa)
          first   <- buildPhotoSpread(xa, index = 15L, layout = defaultLayout)
          second  <- buildPhotoSpread(xa, index = 16L, layout = defaultLayout)
          _       <- (spreads.insert(chapterId, first) >> spreads.insert(chapterId, second)).transact(xa)
          observed    = NonEmptyList.of(opening.id, first.id, second.id)
          proposed    = NonEmptyList.of(opening.id, second.id, first.id)
          replacement = api.run(orderRequest(observed, proposed))
          deletion    = api.run(deleteRequest(first.id, confirm = true))
          results         <- (replacement, deletion).parTupled
          replacementBody <- results match
            case (response, _) =>
              if response.status === Status.Conflict then response.as[Json].map(_.some) else none[Json].pure[IO]
          saved     <- composition.findChapterOrder(albumId, chapterId).transact(xa)
          positions <- chapterOrder.transact(xa)
        yield
          val (replacement, deletion) = results
          assert(Set(Status.NoContent, Status.Conflict).contains(replacement.status))
          assertEquals(
            replacementBody,
            Option.when(replacement.status === Status.Conflict)(Json.obj("code" -> Json.fromString("composition_changed")))
          )
          assertEquals(deletion.status, Status.NoContent)
          assertEquals(saved.map(_.toList.map(_.value)), Some(List(opening.id.value, second.id.value)))
          assertEquals(positions, List(0, 1))

  test("should reconcile an HTTP order acknowledgement loss without replay or disturbing another browser's locked content"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO]         = mutex
        given IdGenerator[IO]   = IdGenerator.make[IO]
        val lostAcknowledgement = xa.strategy.after >> new IllegalStateException("Committed but acknowledgement lost").raiseError[ConnectionIO, Unit]
        val mutationXa          = Transactor.after.set(xa, lostAcknowledgement)
        given Transaction[ConnectionIO, IO] with
          override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(mutationXa)
          override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
        for
          sessions   <- EditorSessionManager.make[IO](lease = 30L)
          registered <- sessions.register(EditorId(new UUID(0L, 900L)), "Owner", maybeSession = None)
          session = registered match
            case EditorSessionManager.RegisterResult.Ready(value) => value
            case _                                                => fail("Expected registration")
          bound <- sessions.bind(session.id, connection.tab)
          active = bound match
            case EditorSessionManager.BindResult.Active(value) => value
            case _                                             => fail("Expected binding")
          manager = SpreadManager.make[IO, ConnectionIO](
            SqliteSpreadTemplateRepository.make[IO](xa),
            composition,
            spreads,
            SqlitePhotoPlacementRepository.make,
            defaultLayout
          )
          opening <- seedChapter(xa)
          first   <- buildPhotoSpread(xa, index = 15L, layout = defaultLayout)
          second  <- buildPhotoSpread(xa, index = 16L, layout = defaultLayout)
          _       <- (spreads.insert(chapterId, first) >> spreads.insert(chapterId, second)).transact(xa)
          _       <- placePhoto(xa, first.photoSlots.head.id, index = 1L)
          before  <- readChapter(xa)
          owner   <- sessions.register(EditorId(new UUID(0L, 901L)), "Other browser", maybeSession = None)
          ownerSession = owner match
            case EditorSessionManager.RegisterResult.Ready(value) => value
            case _                                                => fail("Expected owner")
          ownerBound <- sessions.bind(ownerSession.id, TabId(new UUID(0L, 901L)))
          ownerConnection = ownerBound match
            case EditorSessionManager.BindResult.Active(value) => value
            case _                                             => fail("Expected owner binding")
          selected <- sessions.select(ownerConnection, TargetRevision.initial, EditingTarget.Spread(first.id))
          observed = NonEmptyList.of(opening.id, first.id, second.id)
          proposed = NonEmptyList.of(opening.id, second.id, first.id)
          request  = Request[IO](Method.PUT, Uri.unsafeFromString(s"/api/albums/${albumId.value}/chapters/${chapterId.value}/composition"))
            .putHeaders(
              "X-Editor-Session"    -> active.session.value.toString,
              "X-Editor-Tab"        -> active.tab.value.toString,
              "X-Editor-Generation" -> active.generation.value.toString
            ).withEntity(Json.obj(
              "expectedSpreadIds" -> Json.arr(observed.toList.map(id => Json.fromString(id.value.toString))*),
              "spreadIds"         -> Json.arr(proposed.toList.map(id => Json.fromString(id.value.toString))*)
            ))
          routes = Routes.make(EditingAccess.make(sessions, SqliteAlbumCompositionRepository.makeMembershipRead[IO](xa)))(
            ChapterCompositionApi.make(manager)
          ).orNotFound
          uncertain <- routes.run(request)
          failure   <- uncertain.as[Json]
          recovered <- readChapter(xa)
          stale     <- routes.run(request)
          conflict  <- stale.as[Json]
          retained  <- readChapter(xa)
          ownership <- sessions.readTarget(ownerConnection)
        yield
          val expected = before.copy(spreads = before.spreads.reverse)
          assertEquals(uncertain.status, Status.InternalServerError)
          assertEquals(failure, Json.obj("code" -> Json.fromString("write_uncertain")))
          assertEquals(recovered, expected)
          assertEquals(stale.status, Status.Conflict)
          assertEquals(conflict, Json.obj("code" -> Json.fromString("composition_changed")))
          assertEquals(retained, recovered)
          selected match
            case EditorSessionManager.SelectResult.Owned(lock, revision) =>
              assertEquals(ownership, EditorSessionManager.ReadTargetResult.Current(revision, Some(lock)))
            case _ => fail("Expected retained ownership")

  test("should decide concurrent occupied confirmations from the state committed by the preceding mutation"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given coordination: Mutex[IO] = mutex
        for
          api         <- buildApi(xa, target = SpreadId(new UUID(20L, 100L)))
          _           <- seedChapter(xa)
          photo       <- buildPhotoSpread(xa, index = 20L, layout = defaultLayout)
          _           <- spreads.insert(chapterId, photo).transact(xa)
          _           <- placePhoto(xa, photo.photoSlots.head.id, index = 1L)
          maybeBefore <- composition.findSpread(albumId, photo.id).transact(xa)
          path           = s"/api/albums/${albumId.value}/spreads/${photo.id.value}"
          warningRequest = Request[IO](Method.DELETE, Uri.unsafeFromString(path), headers = credentials)
          changeRequest  = Request[IO](Method.PUT, Uri.unsafeFromString(s"$path/template"), headers = credentials)
            .withEntity(Json.obj("templateId" -> Json.fromString(singleLayout.value.toString)))
          (deleteWarning, changeWarning) <- (api.run(warningRequest), api.run(changeRequest)).parTupled
          bodies                         <- (deleteWarning.as[Json], changeWarning.as[Json]).tupled
          maybeUnchanged                 <- composition.findSpread(albumId, photo.id).transact(xa)
          confirmed                      <-
            List.fill(2)(()).parTraverse(_ => api.run(deleteRequest(photo.id, confirm = true)))
          rejected  <- confirmed.filter(_.status === Status.NotFound).traverse(_.as[Json])
          remaining <- placementRows.transact(xa)
        yield
          val occupied = Json.obj("code" -> Json.fromString("spread_occupied"), "placements" -> Json.fromInt(1))
          assertEquals((deleteWarning.status, changeWarning.status), (Status.Conflict, Status.Conflict))
          assertEquals(bodies, (occupied, occupied))
          assertEquals(maybeUnchanged.map(_.spread), maybeBefore.map(_.spread))
          assertEquals(confirmed.map(_.status).toSet, Set(Status.NoContent, Status.NotFound))
          assertEquals(rejected, List(Json.obj("code" -> Json.fromString("spread_not_found"))))
          assertEquals(remaining, 0L)

  test("should reconcile a lost HTTP deletion acknowledgement from SQLite without replaying the destructive request"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        for
          manager <- buildManager(xa)
          api     <- buildApi(xa, target = SpreadId(new UUID(15L, 100L)))
          opening <- seedChapter(xa)
          photo   <- buildPhotoSpread(xa, index = 15L, layout = defaultLayout)
          _       <- spreads.insert(chapterId, photo).transact(xa)
          _       <- placePhoto(xa, photo.photoSlots.head.id, index = 1L)
          path    = s"/api/albums/${albumId.value}/spreads/${photo.id.value}"
          request = Request[IO](Method.DELETE, Uri.unsafeFromString(s"$path?confirmPlacements=true"), headers = credentials)
          _          <- api.run(request).flatMap(_.body.compile.drain)
          saved      <- readChapter(xa)
          positions  <- chapterOrder.transact(xa)
          photos     <- photoRows.transact(xa)
          placements <- placementRows.transact(xa)
          recovered  <- manager.readPlacements(albumId, photo.id)
        yield
          assertEquals(saved.opening, opening)
          assertEquals(saved.spreads, Vector.empty[Spread.PhotosOnly])
          assertEquals(positions, List(0))
          assertEquals(photos, 1L)
          assertEquals(placements, 0L)
          assertEquals(recovered, PlacementReadResult.SpreadNotFound)

  test("should roll back a destructive replacement when its final placement insert fails"):
    buildTransactor.use: xa =>
      for
        _           <- seedChapter(xa)
        photo       <- buildPhotoSpread(xa, index = 21L, layout = defaultLayout)
        _           <- spreads.insert(chapterId, photo).transact(xa)
        _           <- placePhoto(xa, photo.photoSlots.head.id, index = 1L)
        maybeBefore <- composition.findSpread(albumId, photo.id).transact(xa)
        replacement <- retemplate(xa, photo)
        broken = replacement.copy(placements = replacement.placements.map(_.copy(photo = PhotoId(new UUID(99L, 99L)))))
        failed     <- spreads.replace(broken).transact(xa).attempt
        maybeAfter <- composition.findSpread(albumId, photo.id).transact(xa)
      yield
        assert(failed.isLeft)
        assertEquals(maybeAfter.map(_.spread), maybeBefore.map(_.spread))

  test("should distinguish absent chapter ownership from malformed persisted chapter ordering"):
    buildTransactor.use: xa =>
      val foreignAlbum = AlbumId(new UUID(12L, 1L))

      for
        opening      <- seedChapter(xa)
        maybeForeign <- composition.findChapterOrder(foreignAlbum, chapterId).transact(xa)
        _            <- sql"DELETE FROM spread WHERE id = ${opening.id.value}".update.run.transact(xa)
        malformed    <- composition.findChapterOrder(albumId, chapterId).transact(xa).attempt
      yield
        assertEquals(maybeForeign, none[NonEmptyList[SpreadId]])
        assert(malformed.isLeft)

  test("should reject stale and foreign album targets without changing either album"):
    buildTransactor.use: xa =>
      val otherAlbum  = AlbumId(new UUID(11L, 1L))
      val insertOther = sql"INSERT INTO album (id, year, title, synopsis, created_at_ms) VALUES (${otherAlbum.value}, 2025, 'Other', '', 0)"

      for
        manager <- buildManager(xa)
        opening <- seedChapter(xa)
        _       <- insertOther.update.run.transact(xa)
        photo   <- buildPhotoSpread(xa, index = 22L, layout = defaultLayout)
        _       <- spreads.insert(chapterId, photo).transact(xa)
        observed = NonEmptyList.of(opening.id, photo.id)
        foreignMove     <- manager.replaceChapterOrder(otherAlbum, chapterId, expected = observed, order = observed)
        foreignTemplate <- manager.changeTemplate(otherAlbum, photo.id, singleLayout, confirmPlacements = true)
        foreignDelete   <- manager.delete(otherAlbum, photo.id, confirmPlacements = true)
        foreignAdd      <- manager.add(otherAlbum, chapterId, opening.id)
        removed         <- manager.delete(albumId, photo.id, confirmPlacements = true)
        staleMove       <- manager.replaceChapterOrder(albumId, chapterId, expected = observed, order = observed)
        staleTemplate   <- manager.changeTemplate(albumId, photo.id, singleLayout, confirmPlacements = true)
        staleDelete     <- manager.delete(albumId, photo.id, confirmPlacements = true)
        staleAdd        <- manager.add(albumId, chapterId, photo.id)
        rows            <- spreadRows.transact(xa)
      yield
        assertEquals(foreignMove, ReplacementResult.NotFound)
        assertEquals(foreignTemplate, ChangeTemplateResult.SpreadNotFound)
        assertEquals(foreignDelete, DeleteResult.SpreadNotFound)
        assertEquals(foreignAdd, AddResult.ChapterNotFound)
        assertEquals(removed, DeleteResult.Deleted)
        assertEquals(staleMove, ReplacementResult.Stale)
        assertEquals(staleTemplate, ChangeTemplateResult.SpreadNotFound)
        assertEquals(staleDelete, DeleteResult.SpreadNotFound)
        assertEquals(staleAdd, AddResult.SpreadNotFound)
        assertEquals(rows, 2L)

  test("should retain placements by template slot order despite gaps and reversed placement identities, then expose released inventory"):
    buildTransactor.use: xa =>
      val inventory = buildInventory(xa)

      for
        manager      <- buildManager(xa)
        _            <- seedChapter(xa)
        photo        <- buildPhotoSpread(xa, index = 23L, layout = defaultLayout)
        _            <- spreads.insert(chapterId, photo).transact(xa)
        _            <- placeGappedPhotos(xa, slots = photo.photoSlots)
        three        <- findPhotoTemplate(xa, count = 3)
        before       <- inventory.listChapter(chapterId)
        changed      <- manager.changeTemplate(albumId, photo.id, three.id, confirmPlacements = true)
        maybeCurrent <- composition.findSpread(albumId, photo.id).transact(xa)
        after        <- inventory.listChapter(chapterId)
        deleted      <- manager.delete(albumId, photo.id, confirmPlacements = true)
        released     <- inventory.listChapter(chapterId)
      yield
        assertEquals(changed, ChangeTemplateResult.Changed(photo.id, three.id))
        maybeCurrent.map(_.spread) match
          case Some(value: Spread.PhotosOnly) =>
            val retained = value.placements.map(placed => (placed.id, placed.photo, placed.captionCorner, placed.transform))
            val expected = Vector(
              (buildPlacementId(index = 2L), buildPhotoId(index = 2L), CaptionCorner.TopRight, identity),
              (buildPlacementId(index = 3L), buildPhotoId(index = 3L), CaptionCorner.TopRight, identity)
            )
            assertEquals(retained, expected)
            val thirdSlot = value.photoSlots.toList.lift(2).getOrElse(fail("Missing third slot")).id
            assertEquals(value.placements.map(_.slot), Vector(thirdSlot, value.photoSlots.head.id))
          case other => fail(s"Expected photo spread, obtained $other")
        assertEquals(describeInventory(before), (3, 0))
        assertEquals(describeInventory(after), (3, 1))
        assertEquals(deleted, DeleteResult.Deleted)
        assertEquals(describeInventory(released), (3, 3))

  test("should confirm occupied chapter opening template changes while retaining required text and ordered photos"):
    buildTransactor.use: xa =>
      for
        manager <- buildManager(xa)
        opening <- seedChapter(xa)
        _       <- placePhoto(xa, opening.photoSlots.lift(1).getOrElse(fail("Missing opening slot")).id, index = 1L)
        layout  <- loadChapterTemplate(xa)
        warning <- manager.changeTemplate(albumId, opening.id, layout.id, confirmPlacements = false)
        changed <- manager.changeTemplate(albumId, opening.id, layout.id, confirmPlacements = true)
        current <- readChapter(xa)
      yield
        val retained = current.opening
        val photos   = current.spreads
        assertEquals(warning, ChangeTemplateResult.Occupied(1))
        assertEquals(changed, ChangeTemplateResult.Changed(opening.id, layout.id))
        assertEquals(retained.textSlot.box, layout.textSlot)
        val secondSlot = retained.photoSlots.lift(1).getOrElse(fail("Missing second slot")).id
        assertEquals(retained.placements.map(_.slot), Vector(secondSlot))
        assertEquals(retained.placements.map(_.transform), Vector(identity))
        assertEquals(photos, Vector.empty[Spread.PhotosOnly])

  test(s"should coordinate chapter creation and spread addition at the shared ${InteriorSpreadCapacity.maximum} spread capacity"):
    buildTransactor.use: xa =>
      Mutex[IO].flatMap: mutex =>
        given coordination: Mutex[IO] = mutex
        val chapters                  = buildChapterMetadataManager(xa)
        val period                    = ChapterMetadataManager.Period.Dates(VisitPeriod(LocalDate.of(2026, 8, 1), days = 2L))
        val createChapter             =
          chapters.create(albumId, name = "Later trip", countries = NonEmptyList.one(country.id), period, sourceFolder = "/photos/later")

        for
          api        <- buildApi(xa, target = SpreadId(new UUID(15L, 100L)))
          opening    <- seedChapter(xa)
          photos     <- (1 to 62).toVector.traverse(index => buildPhotoSpread(xa, index = 100L + index.toLong, layout = defaultLayout))
          _          <- photos.traverse_(spreads.insert(chapterId, _)).transact(xa)
          results    <- (api.run(addRequest(opening.id)), createChapter).parTupled
          spreadBody <- results match
            case (response, _) => response.as[Json]
          count <- composition.countInteriorSpreads(albumId).transact(xa)
        yield
          val (spread, chapter) = results
          val spreadWon         = spread.status match
            case Status.Created    => true
            case Status.BadRequest =>
              assertEquals(spreadBody, Json.obj("message" -> Json.fromString("An album can contain at most 65 interior spreads")))
              false
            case other => fail(s"Unexpected spread result: $other")
          val chapterWon = chapter match
            case _: ChapterMetadataManager.CreateResult.Created                           => true
            case ChapterMetadataManager.CreateResult.Rejected(ChapterConflict.AtCapacity) => false
            case other                                                                    => fail(s"Unexpected chapter result: $other")
          assertEquals(spreadWon, !chapterWon)
          assertEquals(count, InteriorSpreadCapacity.maximum)

  private def readChapter(xa: Transactor[IO]) =
    SqliteAlbumRepository.make[IO](xa, composition, SqliteChapterRepository.make).get(albumId).map:
      case AlbumRepository.GetResult.Found(album) =>
        album.chapters.find(_.id.value === chapterId.value).getOrElse(fail("Missing persisted chapter"))
      case other => fail(s"Expected persisted album, obtained $other")

  private def seedChapter(xa: Transactor[IO]) =
    val insertCountry = sql"INSERT INTO country VALUES (${country.id.value}, 'XX', 'Test country', '/flags/xx.svg')".update.run.transact(xa)
    val repository    = SqliteAlbumRepository.make[IO](xa, composition, SqliteChapterRepository.make)
    val cover         = Cover(albumId, SpineOwner.Front, CoverSlot(CoverSlotId(new UUID(1L, 12L))), CoverSlot(CoverSlotId(new UUID(1L, 13L))))
    for
      _            <- insertCountry
      albumStart   <- loadAlbumTemplate(xa)
      chapterStart <- loadChapterTemplate(xa)
      created      <- repository.create(buildAlbum(albumStart), cover)
      _            <- IO(assertEquals(created, AlbumRepository.CreateResult.Created))
      opening    = buildOpening(chapterStart)
      newChapter = ChapterRepository.NewChapter(
        chapterId,
        DestinationId(new UUID(3L, 1L)),
        name = "Trip",
        countries = NonEmptyList.one(country.id),
        VisitPeriod(firstDay = LocalDate.of(2026, 6, 1), days = 3L),
        description = "Retained description",
        sourceFolder = "/photos/trip",
        opening
      )
      _ <- SqliteChapterRepository.make.create(albumId, newChapter, order = NonEmptyList.one(chapterId)).transact(xa)
    yield opening

  private def loadAlbumTemplate(xa: Transactor[IO]) =
    SqliteSpreadTemplateRepository.make[IO](xa).listAlbumStartTemplates.flatMap:
      case SpreadTemplateRepository.ListAlbumStartTemplatesResult.Listed(values) => values.head.pure[IO]
      case other => IO.raiseError[SpreadTemplate.AlbumStart](new IllegalStateException(s"Missing album templates: $other"))

  private def loadChapterTemplate(xa: Transactor[IO]) =
    SqliteSpreadTemplateRepository.make[IO](xa).listChapterStartTemplates.flatMap:
      case SpreadTemplateRepository.ListChapterStartTemplatesResult.Listed(values) => values.head.pure[IO]
      case other => IO.raiseError[SpreadTemplate.ChapterStart](new IllegalStateException(s"Missing chapter templates: $other"))

  private def buildAlbum(template: SpreadTemplate.AlbumStart) =
    val photos = template.photoSlots.zipWithIndex.map { case (box, index) =>
      SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(1L, 100L + index.toLong)), box, bleed = Set.empty[SpreadEdge])
    }
    val opening = Spread.AlbumStart(
      SpreadId(new UUID(1L, 10L)),
      template.id,
      photoSlots = photos,
      textSlot = SpreadSlot.Text(TextSpreadSlotId(new UUID(1L, 11L)), template.textSlot),
      placements = Vector.empty[SpreadPlacement]
    )
    Album(albumId, year = 2026, title = "Album", synopsis = "", authors = Author.all, Instant.EPOCH, opening, chapters = Vector.empty[Chapter])

  private def buildOpening(template: SpreadTemplate.ChapterStart) =
    val photos = template.photoSlots.zipWithIndex.map { case (box, index) =>
      SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(2L, 100L + index.toLong)), box, bleed = Set.empty[SpreadEdge])
    }
    Spread.ChapterStart(
      SpreadId(new UUID(2L, 10L)),
      template.id,
      photoSlots = photos,
      textSlot = SpreadSlot.Text(TextSpreadSlotId(new UUID(2L, 11L)), template.textSlot),
      placements = Vector.empty[SpreadPlacement]
    )

  private def buildPhotoSpread(xa: Transactor[IO], index: Long, layout: SpreadTemplateId) =
    SqliteSpreadTemplateRepository.make[IO](xa).listPhotosOnlyTemplates.flatMap:
      case SpreadTemplateRepository.ListPhotosOnlyTemplatesResult.Listed(values) =>
        values.find(_.id.value === layout.value) match
          case Some(template) =>
            val slots = template.photoSlots.zipWithIndex.map { case (box, position) =>
              SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(index, position.toLong)), box, bleed = Set.empty[SpreadEdge])
            }
            Spread.PhotosOnly(SpreadId(new UUID(index, 100L)), template.id, photoSlots = slots, placements = Vector.empty[SpreadPlacement]).pure[IO]
          case None => IO.raiseError[Spread.PhotosOnly](new IllegalStateException(s"Missing photo layout $layout"))
      case other => IO.raiseError[Spread.PhotosOnly](new IllegalStateException(s"Missing photo layouts: $other"))

  private def retemplate(xa: Transactor[IO], photo: Spread.PhotosOnly) =
    SqliteSpreadTemplateRepository.make[IO](xa).listPhotosOnlyTemplates.flatMap:
      case SpreadTemplateRepository.ListPhotosOnlyTemplatesResult.Listed(values) =>
        values.find(_.id.value === singleLayout.value) match
          case Some(template) =>
            val slot = SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(5L, 900L)), template.photoSlots.head, bleed = Set.empty[SpreadEdge])
            val kept = SpreadPlacement(placementId, slot.id, photoId, identity, CaptionCorner.TopRight)
            Spread.PhotosOnly(photo.id, singleLayout, photoSlots = NonEmptyList.one(slot), placements = Vector(kept)).pure[IO]
          case None => IO.raiseError[Spread.PhotosOnly](new IllegalStateException("Missing single layout"))
      case other => IO.raiseError[Spread.PhotosOnly](new IllegalStateException(s"Missing photo layouts: $other"))

  private def placePhoto(xa: Transactor[IO], slot: PhotoSpreadSlotId, index: Long) =
    val photo       = buildPhotoId(index).value
    val placement   = buildPlacementId(index).value
    val source      = s"$index.jpg"
    val thumbnail   = new UUID(88L, index).toString
    val insertPhoto = sql"""INSERT INTO photo (id, chapter_id, source, source_hash, thumbnail, thumbnail_version, thumbnail_width, thumbnail_height,
                           thumbnail_quality, thumbnail_bytes, width, height)
                           VALUES ($photo, ${chapterId.value}, $source, 'hash', $thumbnail, 'v1', 10, 10, 'standard', 10, 10, 10)"""
    val insertPlace =
      sql"""INSERT INTO placement (id, photo_id, kind, spread_photo_slot_id, cover_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner)
                           VALUES ($placement, $photo, 'spread', ${slot.value}, NULL, 3.0, 4.0, 1, 2.0, 'top_right')"""
    (insertPhoto.update.run >> insertPlace.update.run).transact(xa).void

  private def placeGappedPhotos(xa: Transactor[IO], slots: NonEmptyList[SpreadSlot.Photo]) =
    Vector((0, 3L), (2, 2L), (7, 1L)).traverse_ { case (position, index) =>
      val slot = slots.toList.lift(position).getOrElse(fail("Missing slot"))
      placePhoto(xa, slot.id, index = index)
    }

  private def buildPhotoId(index: Long)     = PhotoId(new UUID(77L, index))
  private def buildPlacementId(index: Long) = SpreadPlacementId(new UUID(78L, index))

  private def findPhotoTemplate(xa: Transactor[IO], count: Int) =
    SqliteSpreadTemplateRepository.make[IO](xa).listPhotosOnlyTemplates.flatMap:
      case SpreadTemplateRepository.ListPhotosOnlyTemplatesResult.Listed(values) =>
        values.find(_.photoSlots.length === count).liftTo[IO](new IllegalStateException(s"Missing $count photo layout"))
      case other => IO.raiseError[SpreadTemplate.PhotosOnly](new IllegalStateException(s"Missing catalog: $other"))

  private def describeInventory(result: PhotoInventory.ListResult) = result match
    case PhotoInventory.ListResult.Listed(value) => (value.total, value.countUnplaced)
    case other                                   => fail(s"Expected inventory, obtained $other")

  private def buildManager(xa: Transactor[IO]) =
    given ids: IdGenerator[IO] = IdGenerator.make[IO]
    given transaction: Transaction[ConnectionIO, IO] with
      override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
      override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
    val templates  = SqliteSpreadTemplateRepository.make[IO](xa)
    val placements = SqlitePhotoPlacementRepository.make
    SpreadManager.make(templates, composition, spreads, placements, defaultLayout).pure[IO]

  private def buildApi(xa: Transactor[IO], target: SpreadId)(using Mutex[IO]): IO[HttpApp[IO]] =
    val sessionIds: IdGenerator[IO] = new IdGenerator[IO]:
      override def generateId: IO[UUID] = new UUID(0L, 900L).pure[IO]
    for
      sessions <- EditorSessionManager.make[IO](lease = 30L)(using summon, sessionIds, summon)
      _        <- sessions.register(EditorId(new UUID(0L, 900L)), "Owner", maybeSession = None)
      _        <- sessions.bind(connection.session, connection.tab)
      _        <- sessions.select(connection, TargetRevision.initial, EditingTarget.Spread(target))
      manager  <- buildManager(xa)
      access = EditingAccess.make(sessions, SqliteAlbumCompositionRepository.makeMembershipRead[IO](xa))
    yield (Routes.make(access)(AlbumSpreadApi.make(manager)) <+> Routes.make(access)(ChapterCompositionApi.make(manager))).orNotFound

  private def addRequest(after: SpreadId) =
    Request[IO](Method.POST, Uri.unsafeFromString(s"/api/albums/${albumId.value}/chapters/${chapterId.value}/spreads"), headers = credentials)
      .withEntity(Json.obj("afterSpreadId" -> Json.fromString(after.value.toString)))

  private def orderRequest(expected: NonEmptyList[SpreadId], proposed: NonEmptyList[SpreadId]) =
    Request[IO](Method.PUT, Uri.unsafeFromString(s"/api/albums/${albumId.value}/chapters/${chapterId.value}/composition"), headers = credentials)
      .withEntity(Json.obj(
        "expectedSpreadIds" -> Json.arr(expected.toList.map(id => Json.fromString(id.value.toString))*),
        "spreadIds"         -> Json.arr(proposed.toList.map(id => Json.fromString(id.value.toString))*)
      ))

  private def deleteRequest(spread: SpreadId, confirm: Boolean) =
    Request[IO](
      Method.DELETE,
      Uri.unsafeFromString(s"/api/albums/${albumId.value}/spreads/${spread.value}?confirmPlacements=$confirm"),
      headers = credentials
    )

  private def buildInventory(xa: Transactor[IO]) =
    given transaction: Transaction[ConnectionIO, IO] with
      override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
      override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
    PhotoInventory.make[IO, ConnectionIO](SqlitePhotoRepository.make, SqlitePhotoPlacementRepository.make, SqliteChapterRepository.make)

  private def buildChapterMetadataManager(xa: Transactor[IO])(using Mutex[IO]) =
    given ids: IdGenerator[IO] = IdGenerator.make[IO]
    given transaction: Transaction[ConnectionIO, IO] with
      override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
      override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
    val templates = SqliteSpreadTemplateRepository.make[IO](xa)
    val albums    = SqliteAlbumRepository.make[IO](xa, composition, SqliteChapterRepository.make)
    val imports   = new ChapterPhotoImport[IO]:
      override def prepare(album: AlbumId, chapter: ChapterId, source: PhotoSource): IO[ChapterPhotoImport.Request] =
        ChapterPhotoImport.Request(album, chapter, source, generation = BigInt(0)).pure[IO]
      override def launch(request: ChapterPhotoImport.Request): IO[ChapterPhotoImport.Scheduling]     = ChapterPhotoImport.Scheduling.Queued.pure[IO]
      override def retire(album: AlbumId, retirements: Vector[PhotoWriteIntent.Retirement]): IO[Unit] = IO.unit
      override def suspend(chapter: ChapterId): IO[Unit]                                              = IO.unit
      override def status(chapter: ChapterId): IO[ChapterPhotoImport.ExecutionOutcome]                =
        IO.raiseError[ChapterPhotoImport.ExecutionOutcome](new AssertionError("Unexpected import status"))
    ChapterMetadataManager.make(albums, SqliteChapterRepository.make, ChapterPlanning.make(templates), imports, SqlitePhotoRepository.make)

  private def buildTransactor =
    val acquireDatabase = IO.blocking(Files.createTempFile(Path.of("target"), "spread-", ".sqlite"))
    for
      path <- Resource.make(acquireDatabase)(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa
