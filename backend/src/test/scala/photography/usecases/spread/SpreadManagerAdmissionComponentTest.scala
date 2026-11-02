package photography.usecases.spread

import cats.data.NonEmptyList
import cats.effect.{Clock, Deferred, IO, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.{AlbumId, ChapterId, PhotoId}
import photography.domain.editing.*
import photography.domain.layout.*
import photography.ports.album.AlbumCompositionRepository
import photography.ports.photo.PhotoPlacementRepository
import photography.ports.spread.{SpreadRepository, SpreadTemplateRepository}
import photography.usecases.editing.EditorSessionManager
import photography.usecases.editing.EditorSessionManager.*
import java.util.UUID
import scala.concurrent.duration.*

class SpreadManagerAdmissionComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given IdGenerator[IO] with
    override def generateId: IO[UUID] = IO(UUID.randomUUID())
  private given Transaction[IO, IO] with
    override def run[A](program: IO[A]): IO[A]     = program
    override def resolve[A](program: IO[A]): IO[A] = program
  private val album   = AlbumId(new UUID(0L, 1L))
  private val chapter = ChapterId(new UUID(0L, 2L))
  private val slot    = SpreadSlot.Photo(
    PhotoSpreadSlotId(new UUID(0L, 3L)),
    SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0),
    bleed = Set.empty[SpreadEdge]
  )
  private val empty     = Spread.PhotosOnly(SpreadId(new UUID(0L, 4L)), SpreadTemplateId(new UUID(0L, 5L)), NonEmptyList.one(slot), Vector.empty)
  private val photo     = PhotoId(new UUID(0L, 6L))
  private val placement = SpreadPlacementId(new UUID(0L, 7L))
  private val initial   = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)
  private val place     = SpreadManager.Edit.Place(slot.id, photo)
  private val edits     = Vector(
    place,
    SpreadManager.Edit.Remove(placement),
    SpreadManager.Edit.Adjust(placement, initial),
    SpreadManager.Edit.Move(placement, slot.id),
    SpreadManager.Edit.SetCaptionCorner(placement, CaptionCorner.TopLeft)
  )

  test("should reject every spread placement and caption-corner edit without its exact grant and retain public reads"):
    Mutex[IO].flatMap: mutex =>
      given Mutex[IO] = mutex
      for
        sessions <- EditorSessionManager.make[IO](lease = 30L)
        owner    <- bind(sessions, owner = 1L)
        selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Spread(empty.id))
        lock = selected match
          case SelectResult.Owned(value, _) => value
          case _                            => fail("Expected spread ownership")
        other     <- bind(sessions, owner = 2L)
        elsewhere <- sessions.select(other, TargetRevision.initial, EditingTarget.Cover(album))
        otherLock = elsewhere match
          case SelectResult.Owned(value, _) => value
          case _                            => fail("Expected other target ownership")
        state <- Ref.of[IO, Spread](empty)
        manager = buildManager(state, sessions, IO.unit)
        blocked     <- edits.traverse(edit => manager.editPlacements(album, empty.id, edit, other, otherLock.grant))
        borrowed    <- manager.editPlacements(album, empty.id, place, other, lock.grant)
        wrongTarget <- manager.editPlacements(album, SpreadId(new UUID(0L, 99L)), place, owner, lock.grant)
        read        <- manager.readPlacements(album, empty.id)
        saved       <- state.get
      yield
        assertEquals(blocked, Vector.fill(edits.size)(SpreadManager.PlacementResult.Locked(EditorId(new UUID(0L, 1L)), "Owner")))
        assertEquals(borrowed, SpreadManager.PlacementResult.OwnershipRejected(Failure.StaleGrant))
        assertEquals(wrongTarget, SpreadManager.PlacementResult.OwnershipRejected(Failure.StaleGrant))
        assertEquals(read, SpreadManager.PlacementReadResult.Found(empty))
        assertEquals(saved, empty)

  test("should fence obsolete connections and released grants after reacquisition without replaying edits"):
    Mutex[IO].flatMap: mutex =>
      given Mutex[IO] = mutex
      for
        sessions <- EditorSessionManager.make[IO](lease = 30L)
        owner    <- bind(sessions, owner = 1L)
        selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Spread(empty.id))
        (lock, revision) = selected match
          case SelectResult.Owned(value, current) => (value, current)
          case _                                  => fail("Expected spread ownership")
        rebound <- sessions.bind(owner.session, owner.tab)
        current = rebound match
          case BindResult.Active(value) => value
          case _                        => fail("Expected reconnect")
        state <- Ref.of[IO, Spread](empty)
        manager = buildManager(state, sessions, IO.unit)
        obsolete <- manager.editPlacements(album, empty.id, place, owner, lock.grant)
        release  <- sessions.release(current, revision, lock.grant)
        releasedRevision = release match
          case ReleaseResult.Released(value) => value
          case _                             => fail("Expected release")
        next  <- sessions.select(current, releasedRevision, EditingTarget.Spread(empty.id))
        stale <- manager.editPlacements(album, empty.id, place, current, lock.grant)
        saved <- state.get
      yield
        assertEquals(obsolete, SpreadManager.PlacementResult.OwnershipRejected(Failure.ObsoleteConnection))
        next match
          case SelectResult.Owned(value, _) => assertEquals(value.grant.value =!= lock.grant.value, true)
          case _                            => fail("Expected reacquisition")
        assertEquals(stale, SpreadManager.PlacementResult.OwnershipRejected(Failure.StaleGrant))
        assertEquals(saved, empty)

  test("should finish a live spread write across expiry before a waiting acquisition observes the complete saved spread"):
    TestControl.executeEmbed:
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        for
          sessions <- EditorSessionManager.make[IO](lease = 30L)
          owner    <- bind(sessions, owner = 1L)
          selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Spread(empty.id))
          lock = selected match
            case SelectResult.Owned(value, _) => value
            case _                            => fail("Expected spread ownership")
          state      <- Ref.of[IO, Spread](empty)
          entered    <- Deferred[IO, Unit]
          finish     <- Deferred[IO, Unit]
          attempting <- Deferred[IO, Unit]
          completed  <- Deferred[IO, Unit]
          manager = buildManager(state, sessions, entered.complete(()).void >> finish.get)
          write     <- manager.editPlacements(album, empty.id, place, owner, lock.grant).start
          _         <- entered.get
          _         <- IO.sleep(31.seconds)
          contender <- (for
            _          <- attempting.complete(())
            connection <- bind(sessions, owner = 2L)
            acquiredAt <- Clock[IO].monotonic
            acquired   <- sessions.select(connection, TargetRevision.initial, EditingTarget.Spread(empty.id))
            ownership  <- sessions.readTarget(connection)
            read       <- manager.readPlacements(album, empty.id)
            _          <- completed.complete(())
          yield (connection, acquiredAt, acquired, ownership, read)).start
          _         <- attempting.get
          _         <- IO.sleep(1.second)
          waiting   <- completed.tryGet
          _         <- finish.complete(())
          committed <- write.joinWithNever
          observed  <- contender.joinWithNever
          rejected  <- manager.editPlacements(album, empty.id, place, owner, lock.grant)
          saved     <- state.get
        yield
          val id = saved.placements match
            case Vector(value) => value.id
            case _             => fail("Expected one placement")
          val expected = empty.copy(placements = Vector(SpreadPlacement(id, slot.id, photo, initial, CaptionCorner.BottomRight)))
          assertEquals(saved, expected)
          assertEquals(committed, SpreadManager.PlacementResult.Saved(expected))
          assertEquals(waiting, none[Unit])
          observed match
            case (connection, acquiredAt, SelectResult.Owned(value, revision), ownership, read) =>
              val expectedLock = EditingLock(EditingTarget.Spread(empty.id), connection.session, value.grant, acquiredAt + 30.seconds)
              assertEquals(value, expectedLock)
              assertEquals(value.grant.value =!= lock.grant.value, true)
              assertEquals(revision, TargetRevision.initial.next)
              assertEquals(ownership, ReadTargetResult.Current(TargetRevision.initial.next, Some(expectedLock)))
              assertEquals(read, SpreadManager.PlacementReadResult.Found(expected))
            case _ => fail("Expected successor acquisition")
          assertEquals(rejected, SpreadManager.PlacementResult.OwnershipRejected(Failure.StaleSession))

  test("should reject queued expiry and independently expired target admissions without touching saved data"):
    TestControl.executeEmbed:
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        for
          sessions <- EditorSessionManager.make[IO](lease = 30L)
          owner    <- bind(sessions, owner = 1L)
          selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Spread(empty.id))
          lock = selected match
            case SelectResult.Owned(value, _) => value
            case _                            => fail("Expected spread ownership")
          state <- Ref.of[IO, Spread](empty)
          manager = buildManager(state, sessions, IO.raiseError[Unit](new AssertionError("Expired admission reached persistence")))
          _         <- IO.sleep(20.seconds)
          heartbeat <- sessions.heartbeat(owner)
          _         <- IO.sleep(10.seconds)
          expired   <- manager.editPlacements(album, empty.id, place, owner, lock.grant)
          ownership <- sessions.readTarget(owner)
          currentRevision = ownership match
            case ReadTargetResult.Current(revision, None) => revision
            case _                                        => fail("Expected expired target")
          next <- sessions.select(owner, currentRevision, EditingTarget.Spread(empty.id))
          currentLock = next match
            case SelectResult.Owned(value, _) => value
            case _                            => fail("Expected fresh ownership")
          queued <- mutex.lock.use: _ =>
            for
              pending <- manager.editPlacements(album, empty.id, place, owner, currentLock.grant).start
              _       <- IO.sleep(20.seconds)
            yield pending
          outcome   <- queued.joinWithNever
          restarted <- EditorSessionManager.make[IO](lease = 30L)
          lost      <- buildManager(state, restarted, IO.unit).editPlacements(album, empty.id, place, owner, lock.grant)
          saved     <- state.get
        yield
          heartbeat match
            case HeartbeatResult.Alive(value) => assertEquals(value.id, owner.session)
            case _                            => fail("Expected accepted session heartbeat")
          assertEquals(expired, SpreadManager.PlacementResult.OwnershipRejected(Failure.StaleGrant))
          assertEquals(outcome, SpreadManager.PlacementResult.OwnershipRejected(Failure.StaleSession))
          assertEquals(lost, SpreadManager.PlacementResult.OwnershipRejected(Failure.StaleSession))
          assertEquals(saved, empty)

  private def bind(sessions: EditorSessionManager[IO], owner: Long) =
    for
      registered <- sessions.register(EditorId(new UUID(0L, owner)), "Owner", maybeSession = None)
      session = registered match
        case RegisterResult.Ready(value) => value
        case _                           => fail("Expected registration")
      bound <- sessions.bind(session.id, TabId(new UUID(1L, owner)))
    yield bound match
      case BindResult.Active(value) => value
      case _                        => fail("Expected active tab")

  private def buildManager(state: Ref[IO, Spread], sessions: EditorSessionManager[IO], beforeWrite: IO[Unit])(using Mutex[IO]) =
    val composition = new AlbumCompositionRepository[IO]:
      override def hasAlbum(id: AlbumId): IO[Boolean]         = (id.value === album.value).pure[IO]
      override def countInteriorSpreads(id: AlbumId): IO[Int] = IO.raiseError[Int](new AssertionError("Unexpected count"))
      override def findChapterOrder(id: AlbumId, chapter: ChapterId): IO[Option[NonEmptyList[SpreadId]]] =
        IO.raiseError[Option[NonEmptyList[SpreadId]]](new AssertionError("Unexpected order"))
      override def findSpread(id: AlbumId, spread: SpreadId): IO[Option[AlbumCompositionRepository.Located]] =
        state.get.map(value =>
          Some(AlbumCompositionRepository.Located.Photo(
            chapter,
            value match
              case photos: Spread.PhotosOnly => photos
              case _                         => fail("Expected photo spread")
          ))
        )
    val repository = new SpreadRepository[IO]:
      override def insert(chapter: ChapterId, spread: Spread.PhotosOnly): IO[Unit]      = IO.raiseError[Unit](new AssertionError("Unexpected insert"))
      override def replace(spread: Spread): IO[Unit]                                    = beforeWrite >> state.set(spread)
      override def reorder(chapter: ChapterId, order: NonEmptyList[SpreadId]): IO[Unit] =
        IO.raiseError[Unit](new AssertionError("Unexpected reorder"))
      override def remove(spread: SpreadId): IO[Unit] = IO.raiseError[Unit](new AssertionError("Unexpected removal"))
    val photos = new PhotoPlacementRepository[IO]:
      override def findPlacedPhotos(chapter: ChapterId): IO[PhotoPlacementRepository.FindResult] =
        IO.raiseError[PhotoPlacementRepository.FindResult](new AssertionError("Unexpected inventory"))
      override def inspect(album: AlbumId, maybeChapter: Option[ChapterId], photo: PhotoId): IO[PhotoPlacementRepository.InspectResult] =
        PhotoPlacementRepository.InspectResult.Found(inCollection = true, placed = false).pure[IO]
    val templates = new SpreadTemplateRepository[IO]:
      override def listAlbumStartTemplates: IO[SpreadTemplateRepository.ListAlbumStartTemplatesResult] =
        IO.raiseError[SpreadTemplateRepository.ListAlbumStartTemplatesResult](new AssertionError("Unexpected templates"))
      override def listChapterStartTemplates: IO[SpreadTemplateRepository.ListChapterStartTemplatesResult] =
        IO.raiseError[SpreadTemplateRepository.ListChapterStartTemplatesResult](new AssertionError("Unexpected templates"))
      override def listPhotosOnlyTemplates: IO[SpreadTemplateRepository.ListPhotosOnlyTemplatesResult] =
        IO.raiseError[SpreadTemplateRepository.ListPhotosOnlyTemplatesResult](new AssertionError("Unexpected templates"))
      override def findTemplate(id: SpreadTemplateId): IO[SpreadTemplateRepository.FindTemplateResult] =
        IO.raiseError[SpreadTemplateRepository.FindTemplateResult](new AssertionError("Unexpected template"))
    SpreadManager.make[IO, IO](templates, composition, repository, photos, empty.template, sessions)
