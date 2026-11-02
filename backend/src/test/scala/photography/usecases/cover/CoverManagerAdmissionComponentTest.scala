package photography.usecases.cover

import cats.effect.{Deferred, IO, Ref}
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
import photography.ports.cover.CoverRepository
import photography.ports.cover.CoverRepository.Composition
import photography.ports.photo.PhotoPlacementRepository
import photography.usecases.editing.EditorSessionManager
import photography.usecases.editing.EditorSessionManager.*
import java.util.UUID
import scala.concurrent.duration.*

class CoverManagerAdmissionComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given IdGenerator[IO] with
    override def generateId: IO[UUID] = IO(UUID.randomUUID())
  private given Transaction[IO, IO] with
    override def run[A](program: IO[A]): IO[A]     = program
    override def resolve[A](program: IO[A]): IO[A] = program
  private val album = AlbumId(new UUID(0L, 1L))
  private val cover = Cover(album, SpineOwner.Back, CoverSlot(CoverSlotId(new UUID(0L, 2L))), CoverSlot(CoverSlotId(new UUID(0L, 3L))))
  private val empty = Composition(cover, placements = Vector.empty[CoverPlacement])
  private val photo = PhotoId(new UUID(0L, 4L))
  private val place = CoverManager.Edit.Place(cover.front.id, photo)
  private val edits = Vector(
    place,
    CoverManager.Edit.Place(cover.back.id, photo),
    CoverManager.Edit.Remove(CoverPlacementId(new UUID(0L, 5L))),
    CoverManager.Edit.Move(CoverPlacementId(new UUID(0L, 5L)), cover.back.id),
    CoverManager.Edit.Adjust(CoverPlacementId(new UUID(0L, 5L)), PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0))
  )

  test("should reject every full Cover edit without its exact grant and retain public reads"):
    for
      mutex  <- Mutex[IO]
      result <- {
        given Mutex[IO] = mutex
        for
          sessions <- EditorSessionManager.make[IO](lease = 30L)
          owner    <- bind(sessions, owner = 1L)
          other    <- bind(sessions, owner = 2L)
          selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Cover(album))
          lock = selected match
            case SelectResult.Owned(value, _) => value
            case _                            => fail("Expected Cover ownership")
          otherSelection <- sessions.select(other, TargetRevision.initial, EditingTarget.Cover(AlbumId(new UUID(0L, 99L))))
          otherLock = otherSelection match
            case SelectResult.Owned(value, _) => value
            case _                            => fail("Expected other target ownership")
          state <- Ref.of[IO, Composition](empty)
          manager = buildManager(state, sessions, IO.unit)
          blocked  <- edits.traverse(edit => manager.edit(album, edit, other, otherLock.grant))
          borrowed <- manager.edit(album, place, other, lock.grant)
          wrong    <- manager.edit(album, place, owner, GrantId(new UUID(0L, 99L)))
          read     <- manager.read(album)
          saved    <- state.get
        yield
          assertEquals(blocked, Vector.fill(edits.size)(CoverManager.Result.Locked(EditorId(new UUID(0L, 1L)), "Owner")))
          assertEquals(wrong, CoverManager.Result.OwnershipRejected(Failure.StaleGrant))
          assertEquals(borrowed, CoverManager.Result.OwnershipRejected(Failure.StaleGrant))
          assertEquals(read, CoverManager.ReadResult.Found(empty))
          assertEquals(saved, empty)
      }
    yield result

  test("should fence obsolete connections and old grants after release and reacquisition"):
    for
      mutex  <- Mutex[IO]
      result <- {
        given Mutex[IO] = mutex
        for
          sessions <- EditorSessionManager.make[IO](lease = 30L)
          owner    <- bind(sessions, owner = 1L)
          selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Cover(album))
          (lock, revision) = selected match
            case SelectResult.Owned(value, current) => (value, current)
            case _                                  => fail("Expected Cover ownership")
          rebound <- sessions.bind(owner.session, owner.tab)
          current = rebound match
            case BindResult.Active(value) => value
            case _                        => fail("Expected reconnect")
          state <- Ref.of[IO, Composition](empty)
          manager = buildManager(state, sessions, IO.unit)
          stale   <- manager.edit(album, place, owner, lock.grant)
          release <- sessions.release(current, revision, lock.grant)
          releasedRevision = release match
            case ReleaseResult.Released(value) => value
            case _                             => fail("Expected release")
          reacquired <- sessions.select(current, releasedRevision, EditingTarget.Cover(album))
          successor = reacquired match
            case SelectResult.Owned(value, _) => value
            case _                            => fail("Expected reacquisition")
          old   <- manager.edit(album, place, current, lock.grant)
          saved <- state.get
        yield
          assertEquals(stale, CoverManager.Result.OwnershipRejected(Failure.ObsoleteConnection))
          assertEquals(successor.grant.value =!= lock.grant.value, true)
          assertEquals(old, CoverManager.Result.OwnershipRejected(Failure.StaleGrant))
          assertEquals(saved, empty)
      }
    yield result

  test("should finish a live admission across expiry before a waiting acquisition sees saved state"):
    TestControl.executeEmbed:
      for
        mutex  <- Mutex[IO]
        result <- {
          given Mutex[IO] = mutex
          for
            sessions <- EditorSessionManager.make[IO](lease = 30L)
            owner    <- bind(sessions, owner = 1L)
            other    <- bind(sessions, owner = 2L)
            selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Cover(album))
            lock = selected match
              case SelectResult.Owned(value, _) => value
              case _                            => fail("Expected Cover ownership")
            state      <- Ref.of[IO, Composition](empty)
            entered    <- Deferred[IO, Unit]
            finish     <- Deferred[IO, Unit]
            attempting <- Deferred[IO, Unit]
            completed  <- Deferred[IO, Unit]
            manager = buildManager(state, sessions, entered.complete(()).void >> finish.get)
            write     <- manager.edit(album, place, owner, lock.grant).start
            _         <- entered.get
            _         <- IO.sleep(31.seconds)
            contender <- (for
              _          <- attempting.complete(())
              connection <- bind(sessions, owner = 2L)
              acquired   <- sessions.select(connection, TargetRevision.initial, EditingTarget.Cover(album))
              saved      <- manager.read(album)
              _          <- completed.complete(())
            yield (acquired, saved)).start
            _         <- attempting.get
            _         <- IO.sleep(1.second)
            waiting   <- completed.tryGet
            _         <- finish.complete(())
            committed <- write.joinWithNever
            observed  <- contender.joinWithNever
            rejected  <- manager.edit(album, place, owner, lock.grant)
            saved     <- state.get
          yield
            assertEquals(committed, CoverManager.Result.Saved(saved))
            assertEquals(waiting, none[Unit])
            assertEquals(saved.placements.map(value => (value.slot, value.photo)), Vector((cover.front.id, photo)))
            observed match
              case (SelectResult.Owned(value, revision), read) =>
                assertEquals(value.target, EditingTarget.Cover(album))
                assertEquals(value.session.value === other.session.value, false)
                assertEquals(revision, TargetRevision.initial.next)
                assertEquals(read, CoverManager.ReadResult.Found(saved))
              case _ => fail("Expected acquisition after committed write")
            assertEquals(rejected, CoverManager.Result.OwnershipRejected(Failure.StaleSession))
        }
      yield result

  test("should reject an admission queued until session expiry without touching saved data"):
    TestControl.executeEmbed:
      for
        mutex  <- Mutex[IO]
        result <- {
          given Mutex[IO] = mutex
          for
            sessions <- EditorSessionManager.make[IO](lease = 30L)
            owner    <- bind(sessions, owner = 1L)
            selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Cover(album))
            lock = selected match
              case SelectResult.Owned(value, _) => value
              case _                            => fail("Expected Cover ownership")
            state <- Ref.of[IO, Composition](empty)
            manager = buildManager(state, sessions, IO.raiseError[Unit](new AssertionError("Expired admission reached persistence")))
            queued <- mutex.lock.use: _ =>
              for
                pending <- manager.edit(album, place, owner, lock.grant).start
                _       <- IO.sleep(30.seconds)
              yield pending
            outcome <- queued.joinWithNever
            saved   <- state.get
          yield
            assertEquals(outcome, CoverManager.Result.OwnershipRejected(Failure.StaleSession))
            assertEquals(saved, empty)
        }
      yield result

  test("should reject an expired target despite accepted session heartbeat and reject pre-restart credentials"):
    TestControl.executeEmbed:
      for
        mutex  <- Mutex[IO]
        result <- {
          given Mutex[IO] = mutex
          for
            sessions <- EditorSessionManager.make[IO](lease = 30L)
            owner    <- bind(sessions, owner = 1L)
            selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Cover(album))
            lock = selected match
              case SelectResult.Owned(value, _) => value
              case _                            => fail("Expected Cover ownership")
            state <- Ref.of[IO, Composition](empty)
            manager = buildManager(state, sessions, IO.raiseError[Unit](new AssertionError("Lost authority reached persistence")))
            _         <- IO.sleep(20.seconds)
            heartbeat <- sessions.heartbeat(owner)
            _         <- IO.sleep(10.seconds)
            expired   <- manager.edit(album, place, owner, lock.grant)
            restarted <- EditorSessionManager.make[IO](lease = 30L)
            lost      <- buildManager(state, restarted, IO.unit).edit(album, place, owner, lock.grant)
            saved     <- state.get
          yield
            heartbeat match
              case HeartbeatResult.Alive(value) => assertEquals(value.id, owner.session)
              case _                            => fail("Expected accepted session heartbeat")
            assertEquals(expired, CoverManager.Result.OwnershipRejected(Failure.StaleGrant))
            assertEquals(lost, CoverManager.Result.OwnershipRejected(Failure.StaleSession))
            assertEquals(saved, empty)
        }
      yield result

  private def bind(sessions: EditorSessionManager[IO], owner: Long) =
    for
      registered <- sessions.register(EditorId(new UUID(0L, owner)), "Owner", maybeSession = None)
      session = registered match
        case RegisterResult.Ready(value) => value
        case _                           => fail("Expected registered session")
      bound <- sessions.bind(session.id, TabId(new UUID(1L, owner)))
    yield bound match
      case BindResult.Active(value) => value
      case _                        => fail("Expected active tab")

  private def buildManager(state: Ref[IO, Composition], sessions: EditorSessionManager[IO], beforeWrite: IO[Unit])(using Mutex[IO]) =
    val covers = new CoverRepository[IO]:
      override def read(album: AlbumId): IO[CoverRepository.ReadResult] =
        state.get.map(CoverRepository.ReadResult.Found(_))
      override def replace(album: AlbumId, placements: Vector[CoverPlacement]): IO[CoverRepository.ReplaceResult] =
        beforeWrite >> state.update(_.copy(placements = placements)).as(CoverRepository.ReplaceResult.Replaced)
    val photos = new PhotoPlacementRepository[IO]:
      override def findPlacedPhotos(chapter: ChapterId): IO[PhotoPlacementRepository.FindResult] =
        IO.raiseError[PhotoPlacementRepository.FindResult](new AssertionError("Unexpected inventory"))
      override def inspect(album: AlbumId, maybeChapter: Option[ChapterId], photo: PhotoId): IO[PhotoPlacementRepository.InspectResult] =
        PhotoPlacementRepository.InspectResult.Found(inCollection = true, placed = false).pure[IO]
    CoverManager.make[IO, IO](covers, photos, sessions)
