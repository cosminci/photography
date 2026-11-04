package photography.usecases.cover

import cats.effect.{IO, Ref}
import cats.effect.std.Mutex
import cats.data.NonEmptyVector
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.{AlbumId, ChapterId, PhotoId}
import photography.domain.layout.*
import photography.ports.cover.CoverRepository
import photography.ports.cover.CoverRepository.Composition
import photography.ports.photo.PhotoPlacementRepository
import photography.usecases.cover.CoverManager.*
import java.util.UUID

class CoverManagerComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val cover        = Cover(
    AlbumId(new UUID(0L, 1L)),
    SpineOwner.Back,
    CoverSlot(CoverSlotId(new UUID(0L, 2L))),
    CoverSlot(CoverSlotId(new UUID(0L, 3L)))
  )
  private val photo         = PhotoId(new UUID(0L, 4L))
  private val otherPhoto    = PhotoId(new UUID(0L, 5L))
  private val foreign       = PhotoId(new UUID(0L, 9L))
  private val fresh         = CoverPlacementId(new UUID(0L, 6L))
  private val otherId       = CoverPlacementId(new UUID(0L, 7L))
  private val initial       = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)
  private val adjusted      = PhotoTransform(Shift(x = 20.0, y = -10.0), mirrored = true, zoom = 4.0)
  private val first         = CoverPlacement(fresh, cover.front.id, photo, adjusted)
  private val second        = CoverPlacement(otherId, cover.back.id, otherPhoto, initial)
  private val specification = CoverSpecification.from(pageLimit = 30, width = 10000, height = 3000, spine = 200).fold(fail(_), identity)
  private val dimensions    = CoverDimensions.forSpreads(spreads = 0, NonEmptyVector.one(specification))
  private val empty         = Composition(cover, placements = Vector.empty[CoverPlacement], dimensions = dimensions)

  test("should change either spine owner without changing either photograph or its framing"):
    val occupied = empty.copy(placements = Vector(first, second))

    for
      state   <- Ref.of[IO, Composition](occupied)
      manager <- buildManager(state)
      front   <- manager.setSpineOwner(cover.album, SpineOwner.Front)
      back    <- manager.setSpineOwner(cover.album, SpineOwner.Back)
      saved   <- state.get
    yield
      assertEquals(front, SetSpineOwnerResult.Saved(occupied.copy(cover = cover.copy(spineOwner = SpineOwner.Front))))
      assertEquals(back, SetSpineOwnerResult.Saved(occupied))
      assertEquals(saved, occupied)

  test("should initialize, replace, release and re-place photos without changing cover structure"):
    val placed   = empty.copy(placements = Vector(first.copy(transform = initial)))
    val replaced = empty.copy(placements = Vector(first.copy(photo = otherPhoto, transform = initial)))

    for
      state       <- Ref.of[IO, Composition](empty)
      manager     <- buildManager(state)
      saved       <- manager.edit(cover.album, Edit.Place(cover.front.id, photo))
      read        <- manager.read(cover.album)
      changed     <- manager.edit(cover.album, Edit.Place(cover.front.id, otherPhoto))
      removed     <- manager.edit(cover.album, Edit.Remove(fresh))
      freshResult <- manager.edit(cover.album, Edit.Place(cover.front.id, photo))
    yield
      assertEquals(saved, Result.Saved(placed))
      assertEquals(read, ReadResult.Found(placed))
      assertEquals(changed, Result.Saved(replaced))
      assertEquals(removed, Result.Saved(empty))
      assertEquals(freshResult, Result.Saved(placed))

  test("should retain transforms when moving or swapping and save completed adjustments"):
    val occupied = empty.copy(placements = Vector(first, second))
    val swapped  = occupied.copy(placements = Vector(first.copy(slot = cover.back.id), second.copy(slot = cover.front.id)))
    val changed  = swapped.copy(placements = Vector(first.copy(slot = cover.back.id), second.copy(slot = cover.front.id, transform = adjusted)))

    for
      state      <- Ref.of[IO, Composition](occupied)
      manager    <- buildManager(state)
      swap       <- manager.edit(cover.album, Edit.Move(fresh, cover.back.id))
      same       <- manager.edit(cover.album, Edit.Move(fresh, cover.back.id))
      adjustment <- manager.edit(cover.album, Edit.Adjust(otherId, adjusted))
      _          <- manager.edit(cover.album, Edit.Remove(otherId))
      move       <- manager.edit(cover.album, Edit.Move(fresh, cover.front.id))
    yield
      assertEquals(swap, Result.Saved(swapped))
      assertEquals(same, swap)
      assertEquals(adjustment, Result.Saved(changed))
      assertEquals(move, Result.Saved(empty.copy(placements = Vector(first))))

  test("should reject stale slots, vanished sources and invalid zoom without changing saved state"):
    val occupied = empty.copy(placements = Vector(first))
    val stale    = CoverSlotId(new UUID(0L, 99L))
    val requests = Vector(
      Edit.Place(stale, otherPhoto)                -> Failure.SlotNotFound,
      Edit.Remove(otherId)                         -> Failure.PlacementNotFound,
      Edit.Move(otherId, cover.back.id)            -> Failure.PlacementNotFound,
      Edit.Move(fresh, stale)                      -> Failure.SlotNotFound,
      Edit.Adjust(otherId, initial)                -> Failure.PlacementNotFound,
      Edit.Adjust(fresh, initial.copy(zoom = 0.5)) -> Failure.InvalidTransform,
      Edit.Adjust(fresh, initial.copy(zoom = 6.0)) -> Failure.InvalidTransform
    )

    requests.traverse_ { case (request, failure) =>
      for
        state   <- Ref.of[IO, Composition](occupied)
        manager <- buildManager(state)
        result  <- manager.edit(cover.album, request)
        saved   <- state.get
      yield
        assertEquals(result, Result.Rejected(failure))
        assertEquals(saved, occupied)
    }

  test("should reject wrong-owner and used photos and serialize current cover placement decisions"):
    for
      state        <- Ref.of[IO, Composition](empty)
      manager      <- buildManager(state, usedElsewhere = Set(otherPhoto))
      wrongOwner   <- manager.edit(cover.album, Edit.Place(cover.front.id, foreign))
      missingPhoto <- manager.edit(cover.album, Edit.Place(cover.front.id, PhotoId(new UUID(0L, 999L))))
      used         <- manager.edit(cover.album, Edit.Place(cover.front.id, otherPhoto))
      outcomes     <- Vector(cover.front.id, cover.back.id).parTraverse(slot => manager.edit(cover.album, Edit.Place(slot, photo)))
      saved        <- state.get
    yield
      assertEquals(wrongOwner, Result.Rejected(Failure.PhotoNotInCollection))
      assertEquals(missingPhoto, Result.Rejected(Failure.PhotoNotInCollection))
      assertEquals(used, Result.Rejected(Failure.PhotoAlreadyUsed))
      assertEquals(outcomes.count { case Result.Saved(_) => true; case _ => false }, 1)
      assertEquals(outcomes.count { case Result.Rejected(Failure.PhotoAlreadyUsed) => true; case _ => false }, 1)
      assertEquals(saved.placements.map(_.photo), Vector(photo))

  test("should distinguish missing cover and read, inspection or write failures without empty success"):
    val absent = AlbumId(new UUID(0L, 99L))

    for
      state            <- Ref.of[IO, Composition](empty)
      manager          <- buildManager(state)
      absentManager    <- buildManager(state)
      broken           <- buildManager(state, readFails = true)
      uninspectable    <- buildManager(state, inspectionFails = true)
      unwritable       <- buildManager(state, writeFails = true)
      vanished         <- buildManager(state, replaceMissing = true)
      absentRead       <- manager.read(absent)
      absentEdit       <- absentManager.edit(absent, Edit.Place(cover.front.id, photo))
      failedRead       <- broken.read(cover.album)
      failedEdit       <- broken.edit(cover.album, Edit.Place(cover.front.id, photo))
      failedInspection <- uninspectable.edit(cover.album, Edit.Place(cover.front.id, photo))
      failedWrite      <- unwritable.edit(cover.album, Edit.Place(cover.front.id, photo))
      vanishedWrite    <- vanished.edit(cover.album, Edit.Place(cover.front.id, photo))
      absentOwner      <- manager.setSpineOwner(absent, SpineOwner.Front)
      failedOwnerRead  <- broken.setSpineOwner(cover.album, SpineOwner.Front)
      failedOwnerWrite <- unwritable.setSpineOwner(cover.album, SpineOwner.Front)
      vanishedOwner    <- vanished.setSpineOwner(cover.album, SpineOwner.Front)
      saved            <- state.get
    yield
      assertEquals(absentRead, ReadResult.CoverNotFound)
      assertEquals(absentEdit, Result.CoverNotFound)
      assertEquals(failedRead, ReadResult.Failed)
      assertEquals(failedEdit, Result.Failed)
      assertEquals(failedInspection, Result.Failed)
      assertEquals(failedWrite, Result.Failed)
      assertEquals(vanishedWrite, Result.CoverNotFound)
      assertEquals(absentOwner, SetSpineOwnerResult.CoverNotFound)
      assertEquals(failedOwnerRead, SetSpineOwnerResult.Failed)
      assertEquals(failedOwnerWrite, SetSpineOwnerResult.Failed)
      assertEquals(vanishedOwner, SetSpineOwnerResult.CoverNotFound)
      assertEquals(saved, empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      state: Ref[IO, Composition],
      usedElsewhere: Set[PhotoId] = Set.empty[PhotoId],
      readFails: Boolean = false,
      inspectionFails: Boolean = false,
      writeFails: Boolean = false,
      replaceMissing: Boolean = false
  ) =
    val repository = new CoverRepository[IO]:
      override def setSpineOwner(album: AlbumId, owner: SpineOwner): IO[CoverRepository.SetSpineOwnerResult] =
        if writeFails then IO.raiseError[CoverRepository.SetSpineOwnerResult](new IllegalStateException("write failed"))
        else if replaceMissing then CoverRepository.SetSpineOwnerResult.NotFound.pure[IO]
        else state.update(value => value.copy(cover = value.cover.copy(spineOwner = owner))).as(CoverRepository.SetSpineOwnerResult.Updated)
      override def read(album: AlbumId): IO[CoverRepository.ReadResult] =
        if readFails then IO.raiseError[CoverRepository.ReadResult](new IllegalStateException("read failed"))
        else
          state.get.map: value =>
            if album.value === value.cover.album.value then CoverRepository.ReadResult.Found(value)
            else CoverRepository.ReadResult.NotFound
      override def replace(album: AlbumId, placements: Vector[CoverPlacement]): IO[CoverRepository.ReplaceResult] =
        if writeFails then IO.raiseError[CoverRepository.ReplaceResult](new IllegalStateException("write failed"))
        else if replaceMissing then CoverRepository.ReplaceResult.NotFound.pure[IO]
        else state.update(_.copy(placements = placements)).as(CoverRepository.ReplaceResult.Replaced)
    val photos = new PhotoPlacementRepository[IO]:
      override def findPlacedPhotos(chapter: ChapterId): IO[PhotoPlacementRepository.FindResult] =
        IO.raiseError[PhotoPlacementRepository.FindResult](new AssertionError("Unexpected chapter inventory"))
      override def inspect(album: AlbumId, maybeChapter: Option[ChapterId], requested: PhotoId): IO[PhotoPlacementRepository.InspectResult] =
        if inspectionFails then IO.raiseError[PhotoPlacementRepository.InspectResult](new IllegalStateException("inspection failed"))
        else
          state.get.map: value =>
            assertEquals(album, cover.album)
            assertEquals(maybeChapter, none[ChapterId])
            val used = usedElsewhere.contains(requested) || value.placements.exists(_.photo.value === requested.value)
            if requested.value === new UUID(0L, 999L) then PhotoPlacementRepository.InspectResult.NotFound
            else PhotoPlacementRepository.InspectResult.Found(inCollection = requested.value =!= foreign.value, placed = used)
    given IdGenerator[IO] with
      override def generateId: IO[UUID] = fresh.value.pure[IO]
    Mutex[IO].map: mutex =>
      given Transaction[IO, IO] with
        override def run[A](program: IO[A]): IO[A]     = mutex.lock.surround(program)
        override def resolve[A](program: IO[A]): IO[A] = mutex.lock.surround(program)
      CoverManager.make[IO, IO](repository, photos)
  // scalafix:on DisableSyntax.defaultArgs
