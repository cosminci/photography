package photography.usecases.editing

import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.Stream
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.domain.album.{AlbumId, ChapterId, PhotoId}
import photography.domain.editing.*
import photography.domain.layout.SpreadId
import photography.ports.album.AlbumCompositionRepository
import photography.ports.album.AlbumCompositionRepository.{CompositionResult, OpeningResult}
import photography.ports.photo.PhotoPlacementRepository
import photography.ports.photo.PhotoPlacementRepository.TargetResult
import photography.usecases.editing.EditingAccess.{Protection, Result}
import photography.usecases.editing.EditorSessionManager.*

import java.util.UUID
import scala.concurrent.duration.*

final class EditingAccessComponentTest extends CatsEffectSuite:
  private val album            = AlbumId(new UUID(0L, 1L))
  private val opening          = SpreadId(new UUID(0L, 3L))
  private val target           = EditingTarget.Spread(opening)
  private val connection       = EditorConnection(SessionId(new UUID(0L, 7L)), TabId(new UUID(0L, 8L)), ConnectionGeneration(new UUID(0L, 9L)))
  private val grant            = GrantId(new UUID(0L, 10L))
  private val chapter          = ChapterId(new UUID(0L, 11L))
  private val photo            = PhotoId(new UUID(0L, 12L))
  private val found            = OpeningResult.Found(opening)
  private val unusedMembership = new AlbumCompositionRepository.MembershipRead[IO]:
    override def composition(album: AlbumId): IO[CompositionResult] =
      new AssertionError("Unexpected whole-album membership acquisition").raiseError[IO, CompositionResult]
    override def opening(album: AlbumId, maybeChapter: Option[ChapterId]): IO[OpeningResult] =
      new AssertionError("Unexpected opening acquisition").raiseError[IO, OpeningResult]
  private val unusedTargets = new PhotoPlacementRepository.TargetRead[IO]:
    override def findTarget(chapter: ChapterId, photo: PhotoId): IO[TargetResult] =
      new AssertionError("Unexpected photo target acquisition").raiseError[IO, TargetResult]

  Vector(target.some, EditingTarget.Cover(album).some, none[EditingTarget]).foreach { maybeTarget =>
    test(s"should resolve current caption target $maybeTarget and recheck authority before writing"):
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        val refs        = Refs()
        for
          connected  <- Ref.of[IO, ConnectionAuthorizationResult](ConnectionAuthorizationResult.Authorized)
          authorized <- Ref.of[IO, AuthorizationResult](AuthorizationResult.Authorized)
          current    <- Ref.of[IO, TargetResult](TargetResult.Found(maybeTarget))
          sessions = new AuthorizationStub(refs.calls, connected.get, authorized.get)
          targets  = new PhotoPlacementRepository.TargetRead[IO]:
            override def findTarget(chapter: ChapterId, photo: PhotoId): IO[TargetResult] =
              refs.calls.update(_ :+ Call.PhotoTarget(chapter, photo)) >> current.get
          access = EditingAccess.make(sessions, unusedMembership, targets)
          save   = access.run(connection, Protection.OwnPhotoSpread(chapter, photo), grant.some)(refs.calls.update(_ :+ Call.Written))
          saved    <- save
          _        <- current.set(TargetResult.Found(maybeTarget = None))
          unplaced <- access.run(connection, Protection.OwnPhotoSpread(chapter, photo), maybeGrant = None)(refs.calls.update(_ :+ Call.Written))
          _        <- current.set(TargetResult.Found(maybeTarget))
          _        <- authorized.set(AuthorizationResult.Rejected(Failure.StaleGrant))
          rejected <- save
          _        <- current.set(TargetResult.PhotoNotFound)
          missing  <- save
          _        <- current.set(TargetResult.Failed)
          failed   <- save
          _        <- connected.set(ConnectionAuthorizationResult.Rejected(Failure.ObsoleteConnection))
          obsolete <- save
          observed <- refs.calls.get
        yield
          val authorization = maybeTarget.fold[Call](Call.Affected(connection, Set.empty, grant.some))(Call.Target(connection, _, grant))
          val resolved      = Vector(Call.Connection(connection), Call.PhotoTarget(chapter, photo))
          val expected      =
            resolved ++ Vector(authorization, Call.Written) ++ resolved ++ Vector(Call.Affected(connection, Set.empty, None), Call.Written) ++
              resolved ++ Vector(authorization) ++ resolved ++ resolved ++ Vector(Call.Connection(connection))
          assertEquals(saved, Result.Completed(()))
          assertEquals(unplaced, Result.Completed(()))
          assertEquals(rejected, Result.Rejected[Unit](Failure.StaleGrant))
          assertEquals(missing, Result.PhotoNotFound[Unit]())
          assertEquals(failed, Result.Failed[Unit]())
          assertEquals(obsolete, Result.Rejected[Unit](Failure.ObsoleteConnection))
          assertEquals(observed, expected)
  }

  Vector(none[ChapterId], chapter.some).foreach { maybeChapter =>
    test(s"should resolve only opening $maybeChapter and preserve missing, failed, stale and obsolete outcomes"):
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO]     = mutex
        val protection      = maybeChapter.fold[Protection](Protection.OwnAlbumOpening(album))(Protection.OwnChapterOpening(album, _))
        val replacedOpening = SpreadId(new UUID(0L, 4L))
        val relationship    = Vector(Call.Connection(connection), Call.Opening(album, maybeChapter))
        val refs            = Refs()

        for
          connected  <- Ref.of[IO, ConnectionAuthorizationResult](ConnectionAuthorizationResult.Authorized)
          authorized <- Ref.of[IO, AuthorizationResult](AuthorizationResult.Authorized)
          current    <- Ref.of[IO, OpeningResult](found)
          sessions   = new AuthorizationStub(refs.calls, connected.get, authorized.get)
          membership = buildMembership(refs.calls, current.get)
          access     = EditingAccess.make(sessions, membership, unusedTargets)
          save       = access.run(connection, protection, grant.some)(refs.calls.update(_ :+ Call.Written))
          saved       <- save
          _           <- current.set(OpeningResult.Found(replacedOpening))
          _           <- authorized.set(AuthorizationResult.Rejected(Failure.StaleGrant))
          replaced    <- save
          _           <- current.set(maybeChapter.fold[OpeningResult](OpeningResult.AlbumNotFound)(_ => OpeningResult.ChapterNotFound))
          missing     <- save
          _           <- current.set(OpeningResult.AlbumNotFound)
          absentAlbum <- save
          _           <- current.set(OpeningResult.Failed(AlbumCompositionRepository.Failure.Unavailable))
          failed      <- save
          _           <- connected.set(ConnectionAuthorizationResult.Rejected(Failure.ObsoleteConnection))
          obsolete    <- save
          observed    <- refs.calls.get
        yield
          val initial  = relationship ++ Vector(Call.Target(connection, target, grant), Call.Written)
          val changed  = relationship :+ Call.Target(connection, EditingTarget.Spread(replacedOpening), grant)
          val expected = initial ++ changed ++ Vector.fill(3)(relationship).flatten :+ Call.Connection(connection)
          assertEquals(saved, Result.Completed(()))
          assertEquals(replaced, Result.Rejected[Unit](Failure.StaleGrant))
          assertEquals(missing, maybeChapter.fold[Result[Unit]](Result.AlbumNotFound())(_ => Result.ChapterNotFound()))
          assertEquals(absentAlbum, Result.AlbumNotFound[Unit]())
          assertEquals(failed, Result.Failed[Unit]())
          assertEquals(obsolete, Result.Rejected[Unit](Failure.ObsoleteConnection))
          assertEquals(observed, expected)
  }

  Vector(Protection.OwnAlbumOpening(album), Protection.OwnChapterOpening(album, chapter), Protection.OwnPhotoSpread(chapter, photo)).foreach {
    protection =>
      Vector(Failure.StaleSession, Failure.StaleGrant).foreach { failure =>
        test(s"should preserve $failure for $protection after a paused relationship read and keep coordination usable"):
          Mutex[IO].flatMap: mutex =>
            given Mutex[IO] = mutex
            val refs        = Refs()
            for
              authorization <- Ref.of[IO, AuthorizationResult](AuthorizationResult.Authorized)
              entered       <- Deferred[IO, Unit]
              finish        <- Deferred[IO, Unit]
              sessions   = new AuthorizationStub(refs.calls, ConnectionAuthorizationResult.Authorized.pure[IO], authorization.get)
              membership = buildMembership(refs.calls, entered.complete(()).void >> finish.get.as(found))
              targets    = new PhotoPlacementRepository.TargetRead[IO]:
                override def findTarget(chapter: ChapterId, photo: PhotoId): IO[TargetResult] =
                  refs.calls.update(_ :+ Call.PhotoTarget(chapter, photo)) >> entered.complete(()).void >>
                    finish.get.as(TargetResult.Found(target.some))
              access       = EditingAccess.make(sessions, membership, targets)
              relationship = protection match
                case Protection.OwnAlbumOpening(_)       => Call.Opening(album, maybeChapter = None)
                case Protection.OwnChapterOpening(_, id) => Call.Opening(album, maybeChapter = id.some)
                case _                                   => Call.PhotoTarget(chapter, photo)
              writing  <- access.run(connection, protection, grant.some)(refs.written.update(_ + 1)).start
              _        <- entered.get
              paused   <- refs.calls.get
              _        <- authorization.set(AuthorizationResult.Rejected(failure))
              _        <- finish.complete(())
              rejected <- writing.joinWithNever
              before   <- refs.written.get
              _        <- authorization.set(AuthorizationResult.Authorized)
              saved    <- access.run(connection, protection, grant.some)(refs.written.update(_ + 1))
              after    <- refs.written.get
              observed <- refs.calls.get
            yield
              val expected = Vector.fill(2)(Vector(Call.Connection(connection), relationship, Call.Target(connection, target, grant))).flatten
              assertEquals(paused, Vector(Call.Connection(connection), relationship))
              assertEquals(rejected, Result.Rejected[Unit](failure))
              assertEquals(before, 0)
              assertEquals(saved, Result.Completed(()))
              assertEquals(after, 1)
              assertEquals(observed, expected)
      }
  }

  test("should finish admitted writes across authority loss and cancellation while waiting coordination sees their result"):
    TestControl.executeEmbed:
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        val refs        = Refs()
        for
          authorization <- Ref.of[IO, AuthorizationResult](AuthorizationResult.Authorized)
          entered       <- Deferred[IO, Unit]
          finish        <- Deferred[IO, Unit]
          canceled      <- Deferred[IO, Unit]
          acquired      <- Deferred[IO, Int]
          sessions = new AuthorizationStub(refs.calls, ConnectionAuthorizationResult.Authorized.pure[IO], authorization.get)
          access   = EditingAccess.make(sessions, unusedMembership, unusedTargets)
          write    =
            access.run(connection, Protection.OwnSpread(target), grant.some)(entered.complete(()).void >> finish.get >> refs.written.update(_ + 1))
          writing       <- write.start
          _             <- entered.get
          waiter        <- write.start
          _             <- IO.sleep(1.second)
          _             <- waiter.cancel
          waiting       <- waiter.join
          queued        <- write.start
          canceling     <- (writing.cancel >> canceled.complete(())).start
          _             <- authorization.set(AuthorizationResult.Rejected(Failure.StaleSession))
          acquiring     <- mutex.lock.surround(refs.written.get.flatMap(count => acquired.complete(count).void)).start
          _             <- IO.sleep(1.second)
          maybeCanceled <- canceled.tryGet
          maybeAcquired <- acquired.tryGet
          _             <- finish.complete(())
          _             <- canceling.joinWithNever
          saved         <- writing.joinWithNever
          rejected      <- queued.joinWithNever
          _             <- acquiring.joinWithNever
          observed      <- acquired.get
          calls         <- refs.calls.get
        yield
          assertEquals(maybeCanceled, none[Unit])
          assertEquals(maybeAcquired, none[Int])
          assertEquals(saved, Result.Completed(()))
          assertEquals(rejected, Result.Rejected[Unit](Failure.StaleSession))
          waiting match
            case Outcome.Canceled() => ()
            case other              => fail(s"Expected canceled waiter, obtained $other")
          assertEquals(observed, 1)
          assertEquals(calls, Vector.fill(2)(Call.Target(connection, target, grant)))

  test("should keep relationship acquisition, admission and persistence in one interval before waiting coordination"):
    TestControl.executeEmbed:
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        val refs        = Refs()
        for
          entered  <- Deferred[IO, Unit]
          finish   <- Deferred[IO, Unit]
          acquired <- Deferred[IO, Int]
          sessions   = new AuthorizationStub(refs.calls, ConnectionAuthorizationResult.Authorized.pure[IO], AuthorizationResult.Authorized.pure[IO])
          membership = buildMembership(refs.calls, entered.complete(()).void >> finish.get.as(found))
          access     = EditingAccess.make(sessions, membership, unusedTargets)
          writing   <- access.run(connection, Protection.OwnAlbumOpening(album), grant.some)(refs.written.update(_ + 1)).start
          _         <- entered.get
          acquiring <- mutex.lock.surround(refs.written.get.flatMap(count => acquired.complete(count).void)).start
          _         <- IO.sleep(1.second)
          before    <- acquired.tryGet
          _         <- finish.complete(())
          saved     <- writing.joinWithNever
          _         <- acquiring.joinWithNever
          observed  <- acquired.get
          reads     <- refs.calls.get
        yield
          assertEquals(before, none[Int])
          assertEquals(reads, Vector(Call.Connection(connection), Call.Opening(album, maybeChapter = None), Call.Target(connection, target, grant)))
          assertEquals(saved, Result.Completed(()))
          assertEquals(observed, 1)

  Vector(target, EditingTarget.Cover(album)).foreach { destination =>
    test(s"should forward direct and shared $destination proof and preserve the authority's decisions without replay"):
      Mutex[IO].flatMap: mutex =>
        given Mutex[IO] = mutex
        val refs        = Refs()
        for
          authorization <- Ref.of[IO, AuthorizationResult](AuthorizationResult.Authorized)
          sessions = new AuthorizationStub(refs.calls, ConnectionAuthorizationResult.Authorized.pure[IO], authorization.get)
          access   = EditingAccess.make(sessions, unusedMembership, unusedTargets)
          mutation = refs.written.update(_ + 1)
          missing  <- access.run(connection, Protection.OwnSpread(destination), maybeGrant = None)(mutation)
          saved    <- access.run(connection, Protection.OwnSpread(destination), grant.some)(mutation)
          shared   <- access.run(connection, Protection.ChangeSpreads(Set(destination)), grant.some)(mutation)
          unlocked <- access.run(connection, Protection.ChangeSpreads(Set(destination)), maybeGrant = None)(mutation)
          _        <- authorization.set(AuthorizationResult.Rejected(Failure.StaleGrant))
          rejected <- access.run(connection, Protection.OwnSpread(destination), grant.some)(mutation)
          _        <- authorization.set(AuthorizationResult.Blocked(destination, EditorId(new UUID(0L, 6L)), "Other"))
          blocked  <- access.run(connection, Protection.ChangeSpreads(Set(destination)), maybeGrant = None)(mutation)
          count    <- refs.written.get
          observed <- refs.calls.get
        yield
          val expected = Vector(
            Call.Target(connection, destination, grant),
            Call.Affected(connection, Set(destination), grant.some),
            Call.Affected(connection, Set(destination), None),
            Call.Target(connection, destination, grant),
            Call.Affected(connection, Set(destination), None)
          )
          assertEquals(missing, Result.Rejected[Unit](Failure.StaleGrant))
          assertEquals(blocked, Result.Blocked[Unit](EditorId(new UUID(0L, 6L)), "Other"))
          assertEquals(saved, Result.Completed(()))
          assertEquals(shared, Result.Completed(()))
          assertEquals(unlocked, Result.Completed(()))
          assertEquals(rejected, Result.Rejected[Unit](Failure.StaleGrant))
          assertEquals(count, 3)
          assertEquals(observed, expected)
  }

  test("should allow independent shared writes and release coordination after application failure"):
    Mutex[IO].flatMap: mutex =>
      given Mutex[IO] = mutex
      val refs        = Refs()
      for
        connected <- Ref.of[IO, ConnectionAuthorizationResult](ConnectionAuthorizationResult.Authorized)
        sessions = new AuthorizationStub(refs.calls, connected.get, AuthorizationResult.Authorized.pure[IO])
        access   = EditingAccess.make(sessions, unusedMembership, unusedTargets)
        cause    = new IllegalStateException("Persistence failed")
        failed      <- access.run(connection, Protection.ChangeSpreads(Set(target)), maybeGrant = None)(cause.raiseError[IO, Unit]).attempt
        saved       <- access.run(connection, Protection.ChangeSpreads(Set(target)), maybeGrant = None)(refs.written.update(_ + 1))
        independent <- access.run(connection, Protection.ActiveTab, maybeGrant = None)(refs.written.update(_ + 1))
        _           <- connected.set(ConnectionAuthorizationResult.Rejected(Failure.ObsoleteConnection))
        obsolete    <- access.run(connection, Protection.ActiveTab, maybeGrant = None)(refs.written.update(_ + 1))
        count       <- refs.written.get
        observed    <- refs.calls.get
      yield
        val expected = Vector(
          Call.Affected(connection, Set(target), None),
          Call.Affected(connection, Set(target), None),
          Call.Connection(connection),
          Call.Connection(connection)
        )
        assertEquals(failed, cause.asLeft[Result[Unit]])
        assertEquals(saved, Result.Completed(()))
        assertEquals(independent, Result.Completed(()))
        assertEquals(obsolete, Result.Rejected[Unit](Failure.ObsoleteConnection))
        assertEquals(count, 2)
        assertEquals(observed, expected)

  private enum Call:
    case Connection(connection: EditorConnection)
    case Target(connection: EditorConnection, target: EditingTarget, grant: GrantId)
    case Affected(connection: EditorConnection, targets: Set[EditingTarget], maybeGrant: Option[GrantId])
    case Opening(album: AlbumId, maybeChapter: Option[ChapterId])
    case PhotoTarget(chapter: ChapterId, photo: PhotoId)
    case Written

  final private case class Refs():
    val calls: Ref[IO, Vector[Call]] = Ref.unsafe(Vector.empty)
    val written: Ref[IO, Int]        = Ref.unsafe(0)

  private def buildMembership(calls: Ref[IO, Vector[Call]], response: IO[OpeningResult]) =
    new AlbumCompositionRepository.MembershipRead[IO]:
      override def composition(album: AlbumId): IO[CompositionResult] =
        new AssertionError("Unexpected whole-album membership acquisition").raiseError[IO, CompositionResult]
      override def opening(album: AlbumId, maybeChapter: Option[ChapterId]): IO[OpeningResult] =
        calls.update(_ :+ Call.Opening(album, maybeChapter)) >> response

  private class AuthorizationStub(calls: Ref[IO, Vector[Call]], connected: IO[ConnectionAuthorizationResult], authorized: IO[AuthorizationResult])
      extends EditorSessionManager[IO]:
    override def authorizeConnection(connection: EditorConnection): IO[ConnectionAuthorizationResult] =
      calls.update(_ :+ Call.Connection(connection)) >> connected
    override def authorizeTarget(connection: EditorConnection, target: EditingTarget, grant: GrantId): IO[AuthorizationResult] =
      calls.update(_ :+ Call.Target(connection, target, grant)) >> authorized
    override def authorizeAffected(connection: EditorConnection, targets: Set[EditingTarget], maybeGrant: Option[GrantId]): IO[AuthorizationResult] =
      calls.update(_ :+ Call.Affected(connection, targets, maybeGrant)) >> authorized
    override def register(owner: EditorId, alias: EditorAlias, maybeSession: Option[SessionId]): IO[RegisterResult] =
      new AssertionError("Unexpected registration").raiseError[IO, RegisterResult]
    override def bind(session: SessionId, tab: TabId): IO[BindResult] =
      new AssertionError("Unexpected binding").raiseError[IO, BindResult]
    override def unbind(connection: EditorConnection): IO[UnbindResult] =
      new AssertionError("Unexpected release").raiseError[IO, UnbindResult]
    override def heartbeat(connection: EditorConnection): IO[HeartbeatResult] =
      new AssertionError("Unexpected heartbeat").raiseError[IO, HeartbeatResult]
    override def read(connection: EditorConnection): IO[ReadResult] =
      new AssertionError("Unexpected session read").raiseError[IO, ReadResult]
    override def rename(connection: EditorConnection, alias: EditorAlias): IO[RenameResult] =
      new AssertionError("Unexpected alias change").raiseError[IO, RenameResult]
    override def ownershipChanges(connection: EditorConnection): Stream[IO, OwnershipChangeResult] =
      Stream.raiseError[IO](new AssertionError("Unexpected ownership subscription"))
    override def select(connection: EditorConnection, revision: TargetRevision, target: EditingTarget): IO[SelectResult] =
      new AssertionError("Unexpected acquisition").raiseError[IO, SelectResult]
    override def release(connection: EditorConnection, revision: TargetRevision, grant: GrantId): IO[ReleaseResult] =
      new AssertionError("Unexpected target release").raiseError[IO, ReleaseResult]
    override def renewTarget(connection: EditorConnection, grant: GrantId): IO[RenewTargetResult] =
      new AssertionError("Unexpected renewal").raiseError[IO, RenewTargetResult]
    override def readTarget(connection: EditorConnection): IO[ReadTargetResult] =
      new AssertionError("Unexpected ownership read").raiseError[IO, ReadTargetResult]
