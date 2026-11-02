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
import photography.usecases.editing.EditorSessionManager.{Failure as SessionFailure, *}
import photography.usecases.spread.SpreadManager.*
import java.util.UUID
import scala.concurrent.duration.*

class SpreadManagerStructuralAdmissionComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given IdGenerator[IO] with
    override def generateId: IO[UUID] = IO(UUID.randomUUID())
  private given Transaction[IO, IO] with
    override def run[A](program: IO[A]): IO[A]     = program
    override def resolve[A](program: IO[A]): IO[A] = program
  private val album   = AlbumId(new UUID(0L, 1L))
  private val chapter = ChapterId(new UUID(0L, 2L))
  private val opening = SpreadId(new UUID(0L, 3L))
  private val box     = SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0)
  private val slot    = SpreadSlot.Photo(PhotoSpreadSlotId(new UUID(0L, 4L)), box, bleed = Set.empty[SpreadEdge])
  private val spread  = Spread.PhotosOnly(SpreadId(new UUID(0L, 5L)), SpreadTemplateId(new UUID(0L, 6L)), NonEmptyList.one(slot), Vector.empty)
  private val layout  = SpreadTemplate.PhotosOnly(SpreadTemplateId(new UUID(0L, 7L)), name = "Replacement", NonEmptyList.one(box))

  test("should insert and reorder without a target grant despite another browser's lock, but reject obsolete and expired connections"):
    TestControl.executeEmbed:
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        val order       = NonEmptyList.of(opening, spread.id)
        for
          sessions <- EditorSessionManager.make[IO](lease = 30L)
          owner    <- bind(sessions, owner = 1L)
          selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Spread(spread.id))
          editor   <- bind(sessions, owner = 2L)
          state    <- Ref.of[IO, Option[Spread]](Some(spread))
          writes   <- Ref.of[IO, Int](0)
          manager = buildManager(state, sessions, writes.update(_ + 1))
          added <- manager.add(album, chapter, spread.id, editor)
          inserted = added match
            case AddResult.Added(value) => value
            case other                  => fail(s"Expected insertion, obtained $other")
          currentOrder = NonEmptyList.of(opening, spread.id, inserted)
          reordered <- manager.replaceChapterOrder(album, chapter, currentOrder, currentOrder, editor)
          unchanged <- sessions.readTarget(owner)
          rebound   <- sessions.bind(editor.session, editor.tab)
          current = rebound match
            case BindResult.Active(value) => value
            case _                        => fail("Expected rebound")
          obsoleteAdd   <- manager.add(album, chapter, spread.id, editor)
          obsoleteOrder <- manager.replaceChapterOrder(album, chapter, order, order, editor)
          restarted     <- EditorSessionManager.make[IO](lease = 30L)
          staleAdd      <- buildManager(state, restarted, IO.unit).add(album, chapter, spread.id, current)
          staleOrder    <- buildManager(state, restarted, IO.unit).replaceChapterOrder(album, chapter, order, order, current)
          _             <- IO.sleep(30.seconds)
          expiredAdd    <- manager.add(album, chapter, spread.id, current)
          expiredOrder  <- manager.replaceChapterOrder(album, chapter, order, order, current)
          saved         <- state.get
          committed     <- writes.get
        yield
          assertEquals(reordered, ReplacementResult.Saved)
          selected match
            case SelectResult.Owned(lock, revision) => assertEquals(unchanged, ReadTargetResult.Current(revision, Some(lock)))
            case _                                  => fail("Expected unchanged ownership")
          assertEquals(obsoleteAdd, AddResult.OwnershipRejected(SessionFailure.ObsoleteConnection))
          assertEquals(obsoleteOrder, ReplacementResult.OwnershipRejected(SessionFailure.ObsoleteConnection))
          assertEquals(staleAdd, AddResult.OwnershipRejected(SessionFailure.StaleSession))
          assertEquals(staleOrder, ReplacementResult.OwnershipRejected(SessionFailure.StaleSession))
          assertEquals(expiredAdd, AddResult.OwnershipRejected(SessionFailure.StaleSession))
          assertEquals(expiredOrder, ReplacementResult.OwnershipRejected(SessionFailure.StaleSession))
          assertEquals(saved, Some(spread))
          assertEquals(committed, 3)

  test("should finish an admitted insertion across expiry while queued order admission expires and acquisition waits"):
    TestControl.executeEmbed:
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        val order       = NonEmptyList.of(opening, spread.id)
        for
          sessions  <- EditorSessionManager.make[IO](lease = 30L)
          editor    <- bind(sessions, owner = 1L)
          _         <- IO.sleep(20.seconds)
          next      <- bind(sessions, owner = 2L)
          state     <- Ref.of[IO, Option[Spread]](Some(spread))
          entered   <- Deferred[IO, Unit]
          finish    <- Deferred[IO, Unit]
          attempted <- Deferred[IO, Unit]
          completed <- Deferred[IO, SelectResult]
          queued    <- Deferred[IO, Unit]
          manager = buildManager(state, sessions, entered.complete(()).void >> finish.get)
          insertion   <- manager.add(album, chapter, spread.id, editor).start
          _           <- entered.get
          reordering  <- (queued.complete(()) >> manager.replaceChapterOrder(album, chapter, order, order, editor)).start
          _           <- queued.get
          _           <- IO.sleep(11.seconds)
          acquisition <-
          (attempted.complete(()) >> sessions.select(next, TargetRevision.initial, EditingTarget.Spread(spread.id))
            .flatTap(result => completed.complete(result).void)).start
          _        <- attempted.get
          _        <- IO.sleep(1.second)
          waiting  <- completed.tryGet
          _        <- finish.complete(())
          added    <- insertion.joinWithNever
          rejected <- reordering.joinWithNever
          selected <- acquisition.joinWithNever
          saved    <- state.get
          observed <- manager.readPlacements(album, spread.id)
        yield
          added match
            case AddResult.Added(_) => ()
            case other              => fail(s"Expected committed insertion, obtained $other")
          assertEquals(rejected, ReplacementResult.OwnershipRejected(SessionFailure.StaleSession))
          assertEquals(waiting, None)
          selected match
            case SelectResult.Owned(lock, revision) =>
              assertEquals(lock, EditingLock(EditingTarget.Spread(spread.id), next.session, lock.grant, 62.seconds))
              assertEquals(revision, TargetRevision.initial.next)
            case _ => fail("Expected successor")
          assertEquals(saved, Some(spread))
          assertEquals(observed, PlacementReadResult.Found(spread))

  test("should reject structural contention despite confirmation and require proof of an affected own grant"):
    Mutex[IO].flatMap: mutex =>
      given Mutex[IO] = mutex
      for
        sessions <- EditorSessionManager.make[IO](lease = 30L)
        owner    <- bind(sessions, owner = 1L)
        selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Spread(spread.id))
        lock = selected match
          case SelectResult.Owned(value, _) => value
          case _                            => fail("Expected ownership")
        other     <- bind(sessions, owner = 2L)
        elsewhere <- sessions.select(other, TargetRevision.initial, EditingTarget.Cover(album))
        otherLock = elsewhere match
          case SelectResult.Owned(value, _) => value
          case _                            => fail("Expected Cover ownership")
        state <- Ref.of[IO, Option[Spread]](Some(spread))
        manager = buildManager(state, sessions, IO.raiseError[Unit](new AssertionError("Rejected admission wrote")))
        blockedTemplate <- manager.changeTemplate(album, spread.id, layout.id, confirmPlacements = true, other, otherLock.grant)
        blockedDelete   <- manager.delete(album, spread.id, confirmPlacements = true, other, maybeGrant = None)
        borrowed        <- manager.changeTemplate(album, spread.id, layout.id, confirmPlacements = true, other, lock.grant)
        unproved        <- manager.delete(album, spread.id, confirmPlacements = true, owner, maybeGrant = None)
        wrongTarget     <- manager.changeTemplate(album, opening, layout.id, confirmPlacements = true, owner, lock.grant)
        rebound         <- sessions.bind(owner.session, owner.tab)
        obsolete        <- manager.delete(album, spread.id, confirmPlacements = true, owner, maybeGrant = Some(lock.grant))
        saved           <- state.get
      yield
        assertEquals(blockedTemplate, ChangeTemplateResult.Locked(EditorId(new UUID(0L, 1L)), "Owner"))
        assertEquals(blockedDelete, DeleteResult.Locked(EditorId(new UUID(0L, 1L)), "Owner"))
        assertEquals(borrowed, ChangeTemplateResult.OwnershipRejected(SessionFailure.StaleGrant))
        assertEquals(unproved, DeleteResult.OwnershipRejected(SessionFailure.StaleGrant))
        assertEquals(wrongTarget, ChangeTemplateResult.OwnershipRejected(SessionFailure.StaleGrant))
        rebound match
          case BindResult.Active(current) => assert(current.generation.value =!= owner.generation.value)
          case _                          => fail("Expected rebound connection")
        assertEquals(obsolete, DeleteResult.OwnershipRejected(SessionFailure.ObsoleteConnection))
        assertEquals(saved, Some(spread))

  test("should finish a live template replacement before a waiting successor acquires and deletes the saved target"):
    TestControl.executeEmbed:
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        for
          sessions <- EditorSessionManager.make[IO](lease = 30L)
          owner    <- bind(sessions, owner = 1L)
          selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Spread(spread.id))
          lock = selected match
            case SelectResult.Owned(value, _) => value
            case _                            => fail("Expected ownership")
          _         <- IO.sleep(20.seconds)
          next      <- bind(sessions, owner = 2L)
          state     <- Ref.of[IO, Option[Spread]](Some(spread))
          entered   <- Deferred[IO, Unit]
          finish    <- Deferred[IO, Unit]
          attempted <- Deferred[IO, Unit]
          completed <- Deferred[IO, SelectResult]
          manager = buildManager(state, sessions, entered.complete(()).void >> finish.get)
          write     <- manager.changeTemplate(album, spread.id, layout.id, confirmPlacements = true, owner, lock.grant).start
          _         <- entered.get
          _         <- IO.sleep(11.seconds)
          contender <- (for
            _          <- attempted.complete(())
            acquired   <- sessions.select(next, TargetRevision.initial, EditingTarget.Spread(spread.id))
            acquiredAt <- Clock[IO].monotonic
            _          <- completed.complete(acquired)
            read       <- manager.readPlacements(album, spread.id)
          yield (acquiredAt, acquired, read)).start
          _        <- attempted.get
          _        <- IO.sleep(1.second)
          waiting  <- completed.tryGet
          _        <- finish.complete(())
          changed  <- write.joinWithNever
          observed <- contender.joinWithNever
          current  <- state.get
          (acquiredAt, acquired, read) = observed
          nextLock                     = acquired match
            case SelectResult.Owned(value, revision) =>
              assertEquals(revision, TargetRevision.initial.next)
              value
            case _ => fail("Expected successor")
          stale    <- manager.changeTemplate(album, spread.id, layout.id, confirmPlacements = true, owner, lock.grant)
          released <- sessions.release(next, TargetRevision.initial.next, nextLock.grant)
          releasedRevision = released match
            case ReleaseResult.Released(value) => value
            case _                             => fail("Expected release")
          reacquired <- sessions.select(next, releasedRevision, EditingTarget.Spread(spread.id))
          currentLock = reacquired match
            case SelectResult.Owned(value, _) => value
            case _                            => fail("Expected reacquisition")
          staleTemplate <- manager.changeTemplate(album, spread.id, layout.id, confirmPlacements = true, next, nextLock.grant)
          staleDelete   <- manager.delete(album, spread.id, confirmPlacements = true, next, maybeGrant = Some(nextLock.grant))
          retained      <- state.get
          removed       <- manager.delete(album, spread.id, confirmPlacements = true, next, maybeGrant = Some(currentLock.grant))
          saved         <- state.get
        yield
          val freshSlot = current match
            case Some(value: Spread.PhotosOnly) => value.photoSlots.head.id
            case _                              => fail("Expected replacement")
          val expected = spread.copy(template = layout.id, photoSlots = NonEmptyList.one(slot.copy(id = freshSlot)))
          assertEquals(current, Some(expected))
          assertEquals(read, PlacementReadResult.Found(expected))
          assertEquals(changed, ChangeTemplateResult.Changed(spread.id, layout.id))
          assertEquals(waiting, None)
          assertEquals(nextLock, EditingLock(EditingTarget.Spread(spread.id), next.session, nextLock.grant, acquiredAt + 30.seconds))
          assert(nextLock.grant.value =!= lock.grant.value)
          assertEquals(stale, ChangeTemplateResult.OwnershipRejected(SessionFailure.StaleSession))
          assertEquals(staleTemplate, ChangeTemplateResult.OwnershipRejected(SessionFailure.StaleGrant))
          assertEquals(staleDelete, DeleteResult.OwnershipRejected(SessionFailure.StaleGrant))
          assertEquals(retained, Some(expected))
          assertEquals(removed, DeleteResult.Deleted)
          assertEquals(saved, None)

  test("should allow an unlocked deletion but reject expired and restarted credentials before persistence"):
    TestControl.executeEmbed:
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        for
          sessions <- EditorSessionManager.make[IO](lease = 30L)
          owner    <- bind(sessions, owner = 1L)
          selected <- sessions.select(owner, TargetRevision.initial, EditingTarget.Spread(spread.id))
          lock = selected match
            case SelectResult.Owned(value, _) => value
            case _                            => fail("Expected ownership")
          state <- Ref.of[IO, Option[Spread]](Some(spread))
          manager = buildManager(state, sessions, IO.unit)
          _               <- IO.sleep(20.seconds)
          _               <- sessions.heartbeat(owner)
          _               <- IO.sleep(10.seconds)
          expiredTemplate <- manager.changeTemplate(album, spread.id, layout.id, confirmPlacements = true, owner, lock.grant)
          expiredDelete   <- manager.delete(album, spread.id, confirmPlacements = true, owner, maybeGrant = Some(lock.grant))
          restarted       <- EditorSessionManager.make[IO](lease = 30L)
          rejected        <- buildManager(state, restarted, IO.unit).delete(album, spread.id, confirmPlacements = true, owner, maybeGrant = None)
          before          <- state.get
          removed         <- manager.delete(album, spread.id, confirmPlacements = true, owner, maybeGrant = None)
          after           <- state.get
        yield
          assertEquals(expiredTemplate, ChangeTemplateResult.OwnershipRejected(SessionFailure.StaleGrant))
          assertEquals(expiredDelete, DeleteResult.OwnershipRejected(SessionFailure.StaleGrant))
          assertEquals(rejected, DeleteResult.OwnershipRejected(SessionFailure.StaleSession))
          assertEquals(before, Some(spread))
          assertEquals(removed, DeleteResult.Deleted)
          assertEquals(after, None)

  private def bind(sessions: EditorSessionManager[IO], owner: Long) =
    for
      registered <- sessions.register(EditorId(new UUID(0L, owner)), "Owner", maybeSession = None)
      session = registered match
        case RegisterResult.Ready(value) => value
        case _                           => fail("Expected registration")
      bound <- sessions.bind(session.id, TabId(new UUID(1L, owner)))
    yield bound match
      case BindResult.Active(value) => value
      case _                        => fail("Expected tab")

  private def buildManager(state: Ref[IO, Option[Spread]], sessions: EditorSessionManager[IO], beforeWrite: IO[Unit])(using Mutex[IO]) =
    val inserted    = Ref.unsafe[IO, Option[Spread.PhotosOnly]](None)
    val composition = new AlbumCompositionRepository[IO]:
      override def hasAlbum(id: AlbumId): IO[Boolean]                                                    = (id.value === album.value).pure[IO]
      override def countInteriorSpreads(id: AlbumId): IO[Int]                                            = 2.pure[IO]
      override def findChapterOrder(id: AlbumId, chapter: ChapterId): IO[Option[NonEmptyList[SpreadId]]] =
        inserted.get.map(maybeInserted => Some(NonEmptyList(opening, spread.id :: maybeInserted.toList.map(_.id))))
      override def findSpread(id: AlbumId, target: SpreadId): IO[Option[AlbumCompositionRepository.Located]] =
        state.get.map(_.map {
          case value: Spread.PhotosOnly => AlbumCompositionRepository.Located.Photo(chapter, value)
          case _                        => fail("Expected photo spread")
        })
    val repository = new SpreadRepository[IO]:
      override def insert(chapter: ChapterId, value: Spread.PhotosOnly): IO[Unit] =
        val expected = Spread.PhotosOnly(value.id, layout.id, NonEmptyList.one(slot.copy(id = value.photoSlots.head.id)), Vector.empty)
        IO(assertEquals(value, expected)) >> beforeWrite >> inserted.set(Some(value))
      override def replace(value: Spread): IO[Unit]                                     = beforeWrite >> state.set(Some(value))
      override def remove(target: SpreadId): IO[Unit]                                   = beforeWrite >> state.set(None)
      override def reorder(chapter: ChapterId, order: NonEmptyList[SpreadId]): IO[Unit] =
        (state.get, inserted.get).tupled.flatMap { case (maybeCurrent, maybeInserted) =>
          val expected = NonEmptyList(opening, maybeCurrent.toList.map(_.id) ++ maybeInserted.toList.map(_.id))
          IO(assertEquals(order, expected)) >> beforeWrite
        }
    val photos = new PhotoPlacementRepository[IO]:
      override def findPlacedPhotos(chapter: ChapterId): IO[PhotoPlacementRepository.FindResult] =
        IO.raiseError[PhotoPlacementRepository.FindResult](new AssertionError("Unexpected inventory"))
      override def inspect(album: AlbumId, maybeChapter: Option[ChapterId], photo: PhotoId): IO[PhotoPlacementRepository.InspectResult] =
        IO.raiseError[PhotoPlacementRepository.InspectResult](new AssertionError("Unexpected photo read"))
    val templates = new SpreadTemplateRepository[IO]:
      override def listAlbumStartTemplates: IO[SpreadTemplateRepository.ListAlbumStartTemplatesResult] =
        IO.raiseError[SpreadTemplateRepository.ListAlbumStartTemplatesResult](new AssertionError("Unexpected templates"))
      override def listChapterStartTemplates: IO[SpreadTemplateRepository.ListChapterStartTemplatesResult] =
        IO.raiseError[SpreadTemplateRepository.ListChapterStartTemplatesResult](new AssertionError("Unexpected templates"))
      override def listPhotosOnlyTemplates: IO[SpreadTemplateRepository.ListPhotosOnlyTemplatesResult] =
        IO.raiseError[SpreadTemplateRepository.ListPhotosOnlyTemplatesResult](new AssertionError("Unexpected templates"))
      override def findTemplate(id: SpreadTemplateId): IO[SpreadTemplateRepository.FindTemplateResult] =
        SpreadTemplateRepository.FindTemplateResult.Found(layout).pure[IO]
    SpreadManager.make[IO, IO](templates, composition, repository, photos, layout.id, sessions)
