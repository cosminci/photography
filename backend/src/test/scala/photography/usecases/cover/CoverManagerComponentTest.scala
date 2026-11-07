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
import photography.domain.album.{AlbumId, ChapterId, PhotoId, InteriorSpreadCount}
import photography.domain.layout.*
import photography.ports.cover.CoverRepository
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

  test("should select cover dimensions from the current interior count and saved sizing catalog"):
    val expanded = CoverSpecification.from(pageLimit = 36, width = 10100, height = 3000, spine = 250).fold(fail(_), identity)
    val catalog  = NonEmptyVector.of(expanded, specification)
    val cases    = Vector[InteriorSpreadCount](0, 15, 16, 65)

    cases.traverse_ { count =>
      val refs = Refs()

      for
        manager <- buildManager(refs, read = buildRead(empty, interiorSpreads = count, specifications = catalog))
        result  <- manager.read(cover.album)
      yield
        val selected = if count <= 15 then specification else expanded
        val expected = empty.copy(dimensions = CoverDimensions.forSpreads(count, NonEmptyVector.one(selected)))
        assertEquals(result, ReadResult.Found(expected))
    }

  test("should change either spine owner without changing either photograph or its framing"):
    val occupied = empty.copy(placements = Vector(first, second))
    val refs     = Refs()

    for
      manager <- buildManager(refs, read = buildRead(occupied))
      front   <- manager.setSpineOwner(cover.album, SpineOwner.Front)
      back    <- manager.setSpineOwner(cover.album, SpineOwner.Back)
      owners  <- refs.owners.get
    yield
      assertEquals(front, SetSpineOwnerResult.Saved(occupied.copy(cover = cover.copy(spineOwner = SpineOwner.Front))))
      assertEquals(back, SetSpineOwnerResult.Saved(occupied))
      assertEquals(owners, Vector((album = cover.album, owner = SpineOwner.Front), (album = cover.album, owner = SpineOwner.Back)))

  test("should initialize, replace, release and re-place photos without changing cover structure"):
    val placed   = empty.copy(placements = Vector(first.copy(transform = initial)))
    val replaced = empty.copy(placements = Vector(first.copy(photo = otherPhoto, transform = initial)))
    val refs     = Refs()

    for
      manager     <- buildManager(refs, read = buildReads(List(empty, placed, placed, replaced, empty)))
      saved       <- manager.edit(cover.album, Edit.Place(cover.front.id, photo))
      read        <- manager.read(cover.album)
      changed     <- manager.edit(cover.album, Edit.Place(cover.front.id, otherPhoto))
      removed     <- manager.edit(cover.album, Edit.Remove(fresh))
      freshResult <- manager.edit(cover.album, Edit.Place(cover.front.id, photo))
      writes      <- refs.replacements.get
      inspections <- refs.inspections.get
    yield
      assertEquals(saved, Result.Saved(placed))
      assertEquals(read, ReadResult.Found(placed))
      assertEquals(changed, Result.Saved(replaced))
      assertEquals(removed, Result.Saved(empty))
      assertEquals(freshResult, Result.Saved(placed))
      assertEquals(writes, Vector(placed, replaced, empty, placed).map(value => (album = cover.album, placements = value.placements)))
      assertEquals(inspections, Vector(photo, otherPhoto, photo).map(value => (album = cover.album, chapter = none[ChapterId], photo = value)))

  test("should retain transforms when moving or swapping and save completed adjustments"):
    val occupied = empty.copy(placements = Vector(first, second))
    val swapped  = occupied.copy(placements = Vector(first.copy(slot = cover.back.id), second.copy(slot = cover.front.id)))
    val changed  = swapped.copy(placements = Vector(first.copy(slot = cover.back.id), second.copy(slot = cover.front.id, transform = adjusted)))
    val released = changed.copy(placements = Vector(first.copy(slot = cover.back.id)))
    val refs     = Refs()

    for
      manager    <- buildManager(refs, read = buildReads(List(occupied, swapped, swapped, changed, released)))
      swap       <- manager.edit(cover.album, Edit.Move(fresh, cover.back.id))
      same       <- manager.edit(cover.album, Edit.Move(fresh, cover.back.id))
      adjustment <- manager.edit(cover.album, Edit.Adjust(otherId, adjusted))
      _          <- manager.edit(cover.album, Edit.Remove(otherId))
      move       <- manager.edit(cover.album, Edit.Move(fresh, cover.front.id))
      writes     <- refs.replacements.get
    yield
      assertEquals(swap, Result.Saved(swapped))
      assertEquals(same, swap)
      assertEquals(adjustment, Result.Saved(changed))
      assertEquals(move, Result.Saved(empty.copy(placements = Vector(first))))
      assertEquals(writes.map(_.placements), Vector(swapped.placements, swapped.placements, changed.placements, released.placements, Vector(first)))

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
      val refs = Refs()

      for
        manager <- buildManager(refs, read = buildRead(occupied))
        result  <- manager.edit(cover.album, request)
        writes  <- refs.replacements.get
      yield
        assertEquals(result, Result.Rejected(failure))
        assertEquals(writes, Vector.empty)
    }

  test("should reject wrong-owner, missing and already-used photos before persistence"):
    val responses = Vector(
      PhotoPlacementRepository.InspectResult.Found(inCollection = false, placed = false) -> Failure.PhotoNotInCollection,
      PhotoPlacementRepository.InspectResult.NotFound                                    -> Failure.PhotoNotInCollection,
      PhotoPlacementRepository.InspectResult.Found(inCollection = true, placed = true)   -> Failure.PhotoAlreadyUsed
    )
    responses.traverse_ { case (response, failure) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, inspected = response.pure[IO])
        result  <- manager.edit(cover.album, Edit.Place(cover.front.id, foreign))
        calls   <- refs.inspections.get
        writes  <- refs.replacements.get
      yield
        assertEquals(result, Result.Rejected(failure))
        assertEquals(calls, Vector((album = cover.album, chapter = none[ChapterId], photo = foreign)))
        assertEquals(writes, Vector.empty)
    }

  test("should serialize current cover reads, photo inspection and placement writes"):
    val refs      = Refs()
    val responses = Ref.unsafe[IO, List[PhotoPlacementRepository.InspectResult]](List(
      PhotoPlacementRepository.InspectResult.Found(inCollection = true, placed = false),
      PhotoPlacementRepository.InspectResult.Found(inCollection = true, placed = true)
    ))
    val inspected = responses.getAndUpdate(_.drop(1)).flatMap(_.headOption.liftTo[IO](new AssertionError("Unexpected inspection")))

    for
      manager  <- buildManager(refs, inspected = inspected)
      outcomes <- Vector(cover.front.id, cover.back.id).parTraverse(slot => manager.edit(cover.album, Edit.Place(slot, photo)))
      writes   <- refs.replacements.get
      events   <- refs.events.get
    yield
      assertEquals(outcomes.count { case Result.Saved(_) => true; case _ => false }, 1)
      assertEquals(outcomes.count { case Result.Rejected(Failure.PhotoAlreadyUsed) => true; case _ => false }, 1)
      assertEquals(writes.map(_.placements.map(_.photo)), Vector(Vector(photo)))
      assertEquals(events, Vector("read", "inspect", "replace", "read", "inspect"))

  test("should distinguish missing cover and read, inspection or write failures without empty success"):
    val absent       = AlbumId(new UUID(0L, 99L))
    val refs         = Refs()
    val readFailure  = IO.raiseError[CoverRepository.ReadResult](new IllegalStateException("read failed"))
    val writeFailure = new IllegalStateException("write failed")

    for
      manager       <- buildManager(refs, read = CoverRepository.ReadResult.NotFound.pure[IO])
      absentManager <- buildManager(refs, read = CoverRepository.ReadResult.NotFound.pure[IO])
      broken        <- buildManager(refs, read = readFailure)
      uninspectable <-
        buildManager(refs, inspected = IO.raiseError[PhotoPlacementRepository.InspectResult](new IllegalStateException("inspection failed")))
      unwritable <- buildManager(
        refs,
        replaced = IO.raiseError[CoverRepository.ReplaceResult](writeFailure),
        ownerWrite = IO.raiseError[CoverRepository.SetSpineOwnerResult](writeFailure)
      )
      vanished <- buildManager(
        refs,
        replaced = CoverRepository.ReplaceResult.NotFound.pure[IO],
        ownerWrite = CoverRepository.SetSpineOwnerResult.NotFound.pure[IO]
      )
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

  final private case class Refs():
    val events: Ref[IO, Vector[String]]                                                            = Ref.unsafe(Vector.empty)
    val reads: Ref[IO, Vector[AlbumId]]                                                            = Ref.unsafe(Vector.empty)
    val owners: Ref[IO, Vector[(album: AlbumId, owner: SpineOwner)]]                               = Ref.unsafe(Vector.empty)
    val replacements: Ref[IO, Vector[(album: AlbumId, placements: Vector[CoverPlacement])]]        = Ref.unsafe(Vector.empty)
    val inspections: Ref[IO, Vector[(album: AlbumId, chapter: Option[ChapterId], photo: PhotoId)]] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      read: IO[CoverRepository.ReadResult] = buildRead(empty),
      inspected: IO[PhotoPlacementRepository.InspectResult] =
        PhotoPlacementRepository.InspectResult.Found(inCollection = true, placed = false).pure[IO],
      replaced: IO[CoverRepository.ReplaceResult] = CoverRepository.ReplaceResult.Replaced.pure[IO],
      ownerWrite: IO[CoverRepository.SetSpineOwnerResult] = CoverRepository.SetSpineOwnerResult.Updated.pure[IO]
  ) =
    val reading    = read
    val repository = new CoverRepository[IO]:
      override def setSpineOwner(album: AlbumId, owner: SpineOwner): IO[CoverRepository.SetSpineOwnerResult] =
        refs.owners.update(_.appended((album = album, owner = owner))) >> ownerWrite
      override def read(album: AlbumId): IO[CoverRepository.ReadResult] =
        refs.reads.update(_ :+ album) >> refs.events.update(_ :+ "read") >> IO.cede >> reading
      override def replace(album: AlbumId, placements: Vector[CoverPlacement]): IO[CoverRepository.ReplaceResult] =
        refs.replacements.update(_.appended((album = album, placements = placements))) >> refs.events.update(_ :+ "replace") >> replaced
    val photos = new PhotoPlacementRepository[IO]:
      override def findPlacedPhotos(chapter: ChapterId): IO[PhotoPlacementRepository.FindResult] =
        IO.raiseError[PhotoPlacementRepository.FindResult](new AssertionError("Unexpected chapter inventory"))
      override def inspect(album: AlbumId, maybeChapter: Option[ChapterId], requested: PhotoId): IO[PhotoPlacementRepository.InspectResult] =
        refs.inspections.update(_.appended((album = album, chapter = maybeChapter, photo = requested))) >> refs.events.update(_ :+ "inspect") >>
          inspected
    given IdGenerator[IO] with
      override def generateId: IO[UUID] = fresh.value.pure[IO]
    Mutex[IO].map: mutex =>
      given Transaction[IO, IO] with
        override def run[A](program: IO[A]): IO[A]     = mutex.lock.surround(program)
        override def resolve[A](program: IO[A]): IO[A] = mutex.lock.surround(program)
      CoverManager.make[IO, IO](repository, photos)

  private def buildRead(
      composition: Composition,
      interiorSpreads: InteriorSpreadCount = 0,
      specifications: NonEmptyVector[CoverSpecification] = NonEmptyVector.one(specification)
  ) =
    CoverRepository.ReadResult.Found(CoverRepository.Stored(composition.cover, composition.placements, interiorSpreads, specifications)).pure[IO]
  // scalafix:on DisableSyntax.defaultArgs

  private def buildReads(compositions: List[Composition]) =
    val responses = Ref.unsafe[IO, List[Composition]](compositions)
    responses.getAndUpdate(_.drop(1)).flatMap(_.headOption.liftTo[IO](new AssertionError("Unexpected cover read"))).flatMap(buildRead(_))
