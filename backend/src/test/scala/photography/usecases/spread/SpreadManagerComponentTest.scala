package photography.usecases.spread
import photography.domain.gear.CameraSelection

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.album.AlbumCompositionRepository
import photography.ports.spread.SpreadRepository
import photography.ports.photo.PhotoPlacementRepository
import photography.ports.album.AlbumCompositionRepository.Located
import photography.usecases.spread.SpreadManager.*
import photography.ports.spread.SpreadTemplateRepository
import photography.ports.spread.SpreadTemplateRepository.FindTemplateResult

import java.nio.file.Path
import java.time.{Instant, LocalDate}
import java.util.UUID

final class SpreadManagerComponentTest extends CatsEffectSuite:
  private given logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  private val box                             = SlotBox(x = 0.0, y = 0.0, width = 50.0, height = 100.0)
  private val wideBox                         = SlotBox(x = 50.0, y = 0.0, width = 50.0, height = 100.0)
  private val defaultLayout                   = SpreadTemplateId(buildUuid(index = 113))
  private val singleLayout                    = SpreadTemplateId(buildUuid(index = 114))
  private val chapterLayout                   = SpreadTemplateId(buildUuid(index = 5))
  private val altLayout                       = SpreadTemplateId(buildUuid(index = 6))
  private val albumLayout                     = SpreadTemplateId(buildUuid(index = 1))
  private val altAlbumLayout                  = SpreadTemplateId(buildUuid(index = 7))
  private val catalog: Vector[SpreadTemplate] = Vector(
    SpreadTemplate.PhotosOnly(defaultLayout, name = "4 x (L/L)", photoSlots = NonEmptyList.of(box, wideBox)),
    SpreadTemplate.PhotosOnly(singleLayout, name = "L", photoSlots = NonEmptyList.one(box)),
    SpreadTemplate.ChapterStart(chapterLayout, name = "Text + 2 x P + (L/L)", photoSlots = Vector(box, wideBox), textSlot = box),
    SpreadTemplate.ChapterStart(altLayout, name = "Text", photoSlots = Vector(box), textSlot = wideBox),
    SpreadTemplate.AlbumStart(albumLayout, name = "2 x (L/L) + Text", photoSlots = Vector(box), textSlot = wideBox),
    SpreadTemplate.AlbumStart(altAlbumLayout, name = "Synopsis", photoSlots = Vector(box, wideBox), textSlot = box)
  )
  private val untouched = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)
  private val newId     = SpreadId(buildUuid(index = 1000L))
  private val country   = Country(CountryId(buildUuid(index = 22L)), code = "JP", name = "Japan", AbsoluteLocation(Path.of("/flags/jp.svg")))

  private val albumOpening = Spread.AlbumStart(
    SpreadId(buildUuid(index = 2L)),
    albumLayout,
    photoSlots = Vector.empty[SpreadSlot.Photo],
    textSlot = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 3L)), wideBox),
    placements = Vector.empty[SpreadPlacement]
  )
  private val chapterOpening = Spread.ChapterStart(
    SpreadId(buildUuid(index = 30L)),
    chapterLayout,
    photoSlots = Vector(buildPhotoSlot(index = 32L, box)),
    textSlot = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 31L)), box),
    placements = Vector.empty[SpreadPlacement]
  )
  private val first         = buildPhotoSpread(index = 40L, placements = Vector.empty[SpreadPlacement])
  private val second        = buildPhotoSpread(index = 41L, placements = Vector.empty[SpreadPlacement])
  private val firstOccupied = buildPhotoSpread(
    index = 40L,
    placements = Vector(
      buildPlacement(index = 70L, slot = PhotoSpreadSlotId(buildUuid(index = 4000L))),
      buildPlacement(index = 71L, slot = PhotoSpreadSlotId(buildUuid(index = 4001L)))
    )
  )
  private val chapter = Chapter(
    ChapterId(buildUuid(index = 20L)),
    Destination(name = "Asian capitals", countries = NonEmptyList.one(country)),
    VisitPeriod(firstDay = LocalDate.of(2026, 5, 1), days = 2),
    description = "",
    gear = Vector.empty[CameraSelection],
    sourceFolder = "/photos/capitals",
    chapterOpening,
    spreads = Vector(first, second)
  )
  private val foreign      = buildPhotoSpread(index = 90L, placements = Vector.empty[SpreadPlacement])
  private val otherChapter = chapter.copy(
    id = ChapterId(buildUuid(index = 50L)),
    opening = chapterOpening.copy(id = SpreadId(buildUuid(index = 60L))),
    spreads = Vector(foreign)
  )
  private val album = Album(
    AlbumId(buildUuid(index = 1L)),
    year = 2026,
    title = "A year abroad",
    synopsis = "",
    authors = Author.all,
    Instant.EPOCH,
    albumOpening,
    chapters = Vector(chapter, otherChapter)
  )

  Vector(
    (chapterOpening.id, NonEmptyList.of(chapterOpening.id, newId, first.id, second.id)),
    (first.id, NonEmptyList.of(chapterOpening.id, first.id, newId, second.id))
  ).foreach { case (after, expectedOrder) =>
    test(s"should insert the default layout $defaultLayout after $after and return its identity"):
      val refs = Refs()

      for
        manager <- buildManager(refs)
        result  <- manager.add(album.id, chapter.id, after)
        inserts <- refs.inserts.get
        orders  <- refs.orders.get
      yield
        val expected = Vector((chapter.id, newId, defaultLayout, List(box, wideBox), Vector.empty[SpreadPlacement]))
        assertEquals(result, AddResult.Added(newId))
        assertEquals(describeInserts(values = inserts), expected)
        assertEquals(orders, Vector((chapter.id, expectedOrder)))
  }

  test("should reject adding after the album opening without changing the album"):
    val refs = Refs()

    for
      manager <- buildManager(refs)
      result  <- manager.add(album.id, chapter.id, after = albumOpening.id)
      inserts <- refs.inserts.get
      orders  <- refs.orders.get
    yield
      assertEquals(result, AddResult.Rejected(Conflict.AddTarget))
      assertEquals(inserts, Vector.empty[(ChapterId, Spread.PhotosOnly)])
      assertEquals(orders, Vector.empty[(ChapterId, NonEmptyList[SpreadId])])

  test(s"should enforce the ${InteriorSpreadCapacity.maximum} interior spread capacity"):
    Vector(
      (63, AddResult.Rejected(Conflict.AtCapacity), Vector.empty[ChapterId]),
      (62, AddResult.Added(newId), Vector(chapter.id))
    ).traverse_ { case (photos, expected, insertedChapters) =>
      val refs    = Refs()
      val current = album.copy(chapters =
        Vector(chapter.copy(spreads =
          Vector.tabulate(photos)(index => buildPhotoSpread(index = 200L + index.toLong, placements = Vector.empty[SpreadPlacement]))
        ))
      )

      for
        manager <- buildManager(refs, maybeAlbum = current.some)
        result  <- manager.add(current.id, chapter.id, after = chapterOpening.id)
        inserts <- refs.inserts.get
      yield
        assertEquals(result, expected)
        assertEquals(inserts.map(_.chapter), insertedChapters)
    }

  test("should report unknown album, chapter, or foreign and unknown spread targets when adding"):
    val refs = Refs()

    for
      missingAlbum   <- buildManager(refs, maybeAlbum = none[Album]).flatMap(_.add(album.id, chapter.id, after = chapterOpening.id))
      missingChapter <- buildManager(refs).flatMap(_.add(album.id, ChapterId(buildUuid(index = 999L)), after = chapterOpening.id))
      unknownSpread  <- buildManager(refs).flatMap(_.add(album.id, chapter.id, after = SpreadId(buildUuid(index = 999L))))
      foreignSpread  <- buildManager(refs).flatMap(_.add(album.id, chapter.id, after = foreign.id))
    yield
      assertEquals(missingAlbum, AddResult.AlbumNotFound)
      assertEquals(missingChapter, AddResult.ChapterNotFound)
      assertEquals(unknownSpread, AddResult.SpreadNotFound)
      assertEquals(foreignSpread, AddResult.SpreadNotFound)

  test("should fail adding when the default layout is unavailable or has the wrong context"):
    val refs      = Refs()
    val missing   = FindTemplateResult.NotFound
    val wrongKind = FindTemplateResult.Found(SpreadTemplate.ChapterStart(chapterLayout, name = "Text", photoSlots = Vector(box), textSlot = box))

    for
      unavailable <-
        buildManager(refs, maybeTemplateResult = missing.some).flatMap(_.add(album.id, chapter.id, after = chapterOpening.id))
      wrong <- buildManager(refs, maybeTemplateResult = wrongKind.some).flatMap(_.add(album.id, chapter.id, after = chapterOpening.id))
    yield
      assertEquals(unavailable, AddResult.Failed)
      assertEquals(wrong, AddResult.Failed)

  test("should change every caption corner without changing placement identity, photo, crop or transform"):
    CaptionCorner.values.toVector.traverse_ { corner =>
      val refs               = Refs()
      val occupied           = buildAlbumWithSpreads(spreads = Vector(firstOccupied, second))
      val selected           = firstOccupied.placements.headOption.getOrElse(fail("Expected occupied spread"))
      val expectedPlacements = firstOccupied.placements.map { value =>
        if value.id.value === selected.id.value then value.copy(captionCorner = corner) else value
      }
      val expected = firstOccupied.copy(placements = expectedPlacements)

      for
        manager      <- buildManager(refs, maybeAlbum = occupied.some)
        result       <- manager.editPlacements(album.id, first.id, Edit.SetCaptionCorner(selected.id, corner))
        replacements <- refs.placementWrites.get
        layouts      <- refs.layouts.get
      yield
        assertEquals(result, PlacementResult.Saved(expected))
        assertEquals(replacements, Vector(first.id -> expected.placements))
        assertEquals(layouts, Vector.empty[Spread])
    }

  test("should change an unoccupied photo spread to a same-context layout with fresh slots"):
    val refs = Refs()

    for
      manager      <- buildManager(refs)
      result       <- manager.changeTemplate(album.id, first.id, singleLayout, confirmPlacements = false)
      replacements <- refs.replacements.get
    yield
      assertEquals(result, ChangeTemplateResult.Changed(first.id, singleLayout))
      replacements match
        case Vector(replaced: Spread.PhotosOnly) =>
          assertEquals(replaced.template, singleLayout)
          assertEquals(replaced.photoSlots.map(_.box).toList, List(box))
          assertEquals(replaced.placements, Vector.empty[SpreadPlacement])
          assert(!replaced.photoSlots.toList.exists(slot => first.photoSlots.toList.exists(_.id.value === slot.id.value)))
        case other => fail(s"Expected one replaced photo spread, obtained $other")

  test("should warn with the placement count and change nothing until an occupied template change is confirmed"):
    val refs     = Refs()
    val occupied = buildAlbumWithSpreads(spreads = Vector(firstOccupied, second))

    for
      manager  <- buildManager(refs, maybeAlbum = occupied.some)
      result   <- manager.changeTemplate(occupied.id, first.id, singleLayout, confirmPlacements = false)
      replaced <- refs.replacements.get
    yield
      assertEquals(result, ChangeTemplateResult.Occupied(2))
      assertEquals(replaced, Vector.empty[Spread])

  test("should keep placements matched by photo-slot order, reset their transforms and release unmatched photos"):
    val refs     = Refs()
    val occupied = buildAlbumWithSpreads(spreads = Vector(firstOccupied, second))

    for
      manager  <- buildManager(refs, maybeAlbum = occupied.some)
      result   <- manager.changeTemplate(occupied.id, first.id, singleLayout, confirmPlacements = true)
      replaced <- refs.replacements.get
    yield
      assertEquals(result, ChangeTemplateResult.Changed(first.id, singleLayout))
      replaced match
        case Vector(replacement: Spread.PhotosOnly) =>
          val retained = replacement.placements.map(placed => (placed.id, placed.photo, placed.captionCorner, placed.transform))
          assertEquals(retained, Vector((SpreadPlacementId(buildUuid(70L)), PhotoId(buildUuid(570L)), CaptionCorner.TopRight, untouched)))
          assertEquals(replacement.placements.map(_.slot).toList, replacement.photoSlots.map(_.id).toList)
        case other => fail(s"Expected one replaced photo spread, obtained $other")

  test("should reject a template from another spread context without changing the spread"):
    Vector(
      first.id          -> chapterLayout,
      first.id          -> albumLayout,
      chapterOpening.id -> singleLayout,
      chapterOpening.id -> albumLayout,
      albumOpening.id   -> singleLayout,
      albumOpening.id   -> chapterLayout
    ).traverse_ { case (spread, template) =>
      val refs = Refs()

      for
        manager  <- buildManager(refs)
        result   <- manager.changeTemplate(album.id, spread, template, confirmPlacements = false)
        replaced <- refs.replacements.get
      yield
        assertEquals(result, ChangeTemplateResult.Rejected(Conflict.ContextMismatch))
        assertEquals(replaced, Vector.empty[Spread])
    }

  test("should reject unknown templates and unknown spreads when changing templates"):
    val refs            = Refs()
    val unknown         = SpreadId(buildUuid(index = 999L))
    val missingTemplate = buildManager(refs, maybeTemplateResult = FindTemplateResult.NotFound.some)
    val missingAlbum    = buildManager(refs, maybeAlbum = none[Album])

    for
      unknownTemplate <- missingTemplate.flatMap(_.changeTemplate(album.id, first.id, singleLayout, confirmPlacements = false))
      unknownSpread   <-
        buildManager(refs).flatMap(_.changeTemplate(album.id, unknown, singleLayout, confirmPlacements = false))
      absentAlbum <- missingAlbum.flatMap(_.changeTemplate(album.id, first.id, singleLayout, confirmPlacements = false))
    yield
      assertEquals(unknownTemplate, ChangeTemplateResult.Rejected(Conflict.UnknownTemplate))
      assertEquals(unknownSpread, ChangeTemplateResult.SpreadNotFound)
      assertEquals(absentAlbum, ChangeTemplateResult.AlbumNotFound)

  Vector(
    (chapterOpening.id, altLayout, Vector(box), wideBox),
    (albumOpening.id, altAlbumLayout, Vector(box, wideBox), box)
  ).foreach { case (openingId, template, expectedPhotos, expectedText) =>
    test(s"should replace opening $openingId photo and text slots using template $template"):
      val refs = Refs()

      for
        manager  <- buildManager(refs)
        result   <- manager.changeTemplate(album.id, openingId, template, confirmPlacements = false)
        replaced <- refs.replacements.get
      yield
        val actual = replaced.map:
          case opening: Spread.ChapterStart => (opening.id, opening.template, opening.photoSlots.map(_.box), opening.textSlot.box)
          case opening: Spread.AlbumStart   => (opening.id, opening.template, opening.photoSlots.map(_.box), opening.textSlot.box)
          case other                        => fail(s"Expected opening, obtained $other")
        val expected = Vector((openingId, template, expectedPhotos, expectedText))
        assertEquals(result, ChangeTemplateResult.Changed(openingId, template))
        assertEquals(actual, expected)
  }

  test("should report a failed template lookup as Failed without changing the spread"):
    val refs = Refs()

    for
      manager  <- buildManager(refs, maybeTemplateResult = FindTemplateResult.Failed(SpreadTemplateRepository.Failure.DatabaseFailure).some)
      result   <- manager.changeTemplate(album.id, first.id, singleLayout, confirmPlacements = false)
      replaced <- refs.replacements.get
    yield
      assertEquals(result, ChangeTemplateResult.Failed)
      assertEquals(replaced, Vector.empty[Spread])

  test("should delete an unoccupied photo spread and renumber the remaining chapter spreads"):
    val refs = Refs()

    for
      manager <- buildManager(refs)
      result  <- manager.delete(album.id, first.id, confirmPlacements = false)
      removed <- refs.removals.get
      orders  <- refs.orders.get
    yield
      assertEquals(result, DeleteResult.Deleted)
      assertEquals(removed, Vector(first.id))
      assertEquals(orders, Vector((chapter.id, NonEmptyList.of(chapterOpening.id, second.id))))

  test("should warn with the placement count and delete nothing until an occupied delete is confirmed"):
    val refs     = Refs()
    val occupied = buildAlbumWithSpreads(spreads = Vector(firstOccupied, second))

    for
      unconfirmed <- buildManager(
        refs,
        maybeAlbum = occupied.some
      ).flatMap(_.delete(occupied.id, first.id, confirmPlacements = false))
      removedBefore <- refs.removals.get
      confirmed     <- buildManager(
        refs,
        maybeAlbum = occupied.some
      ).flatMap(_.delete(occupied.id, first.id, confirmPlacements = true))
      removed <- refs.removals.get
    yield
      assertEquals(unconfirmed, DeleteResult.Occupied(2))
      assertEquals(removedBefore, Vector.empty[SpreadId])
      assertEquals(confirmed, DeleteResult.Deleted)
      assertEquals(removed, Vector(first.id))

  test("should reject deleting either opening and report unknown spreads when deleting"):
    val refs = Refs()

    for
      chapterOpeningDelete <-
        buildManager(refs).flatMap(_.delete(album.id, chapterOpening.id, confirmPlacements = false))
      albumOpeningDelete <-
        buildManager(refs).flatMap(_.delete(album.id, albumOpening.id, confirmPlacements = false))
      unknown <- buildManager(refs).flatMap(_.delete(
        album.id,
        SpreadId(buildUuid(index = 999L)),
        confirmPlacements = false
      ))
    yield
      assertEquals(chapterOpeningDelete, DeleteResult.Rejected(Conflict.OpeningDelete))
      assertEquals(albumOpeningDelete, DeleteResult.Rejected(Conflict.OpeningDelete))
      assertEquals(unknown, DeleteResult.SpreadNotFound)

  test("should report an unknown album when deleting"):
    val refs = Refs()

    for
      missing <- buildManager(
        refs,
        maybeAlbum = none[Album]
      ).flatMap(_.delete(album.id, first.id, confirmPlacements = false))
      removed <- refs.removals.get
    yield
      assertEquals(missing, DeleteResult.AlbumNotFound)
      assertEquals(removed, Vector.empty[SpreadId])

  test("should report album read and write failures as Failed"):
    val refs       = Refs()
    val failure    = new IllegalStateException("Spread store unavailable")
    val unreadable = buildManager(refs, maybeReadError = failure.some)
    val unwritable = buildManager(refs, maybeWriteError = failure.some)

    for
      readFailed  <- unreadable.flatMap(_.delete(album.id, first.id, confirmPlacements = false))
      writeFailed <- unwritable.flatMap(_.delete(album.id, first.id, confirmPlacements = false))
      removed     <- refs.removals.get
    yield
      assertEquals(readFailed, DeleteResult.Failed)
      assertEquals(writeFailed, DeleteResult.Failed)
      assertEquals(removed, Vector(first.id))

  test("should place, release and re-place photos with the initial transform in each interior context"):
    val contexts = Vector[Spread](albumOpening.copy(photoSlots = Vector(first.photoSlots.head)), chapterOpening, first)

    contexts.traverse_ { target =>
      val refs  = Refs()
      val photo = target match
        case _: Spread.AlbumStart => PhotoId(buildUuid(index = 590L))
        case _                    => PhotoId(buildUuid(index = 570L))
      val slots = target match
        case value: Spread.AlbumStart   => value.photoSlots
        case value: Spread.ChapterStart => value.photoSlots
        case value: Spread.PhotosOnly   => value.photoSlots.toList.toVector
      val slot    = slots.headOption.getOrElse(fail("Expected an editable photo slot")).id
      val current = target match
        case value: Spread.AlbumStart   => album.copy(opening = value)
        case value: Spread.ChapterStart => album.copy(chapters = Vector(chapter.copy(opening = value)))
        case value: Spread.PhotosOnly   => buildAlbumWithSpreads(spreads = Vector(value))
      val expected   = SpreadPlacement(SpreadPlacementId(buildUuid(index = 1000L)), slot, photo, untouched, CaptionCorner.BottomRight)
      val populated  = replaceTestPlacements(target, placements = Vector(expected))
      val savedReads = responses(Vector(target, populated, populated, target).map: spread =>
        AlbumCompositionRepository.SpreadResult.Found(spread match
          case value: Spread.AlbumStart   => Located.AlbumOpening(value)
          case value: Spread.ChapterStart => Located.ChapterOpening(chapter.id, value)
          case value: Spread.PhotosOnly   => Located.Photo(chapter.id, value)))

      for
        manager <- buildManager(refs, maybeAlbum = current.some, spreadReads = savedReads.some)
        placed  <- manager.editPlacements(album.id, target.id, Edit.Place(slot, photo))
        read    <- manager.readPlacements(album.id, target.id)
        removed <- manager.editPlacements(album.id, target.id, Edit.Remove(expected.id))
        fresh   <- manager.editPlacements(album.id, target.id, Edit.Place(slot, photo))
        writes  <- refs.placementWrites.get
      yield
        assertEquals(describePlacementResult(placed), Vector(expected))
        assertEquals(read, PlacementReadResult.Found(populated))
        assertEquals(removed, PlacementResult.Saved(target))
        assertEquals(describePlacementResult(fresh), Vector(expected.copy(id = SpreadPlacementId(buildUuid(index = 1001L)))))
        assertEquals(
          writes,
          Vector(
            target.id -> Vector(expected),
            target.id -> Vector.empty[SpreadPlacement],
            target.id -> Vector(expected.copy(id = SpreadPlacementId(buildUuid(index = 1001L))))
          )
        )
    }

  test("should adjust, move, swap and replace while retaining slot geometry and photo transforms"):
    val refs        = Refs()
    val current     = buildAlbumWithSpreads(spreads = Vector(firstOccupied))
    val source      = firstOccupied.placements.headOption.getOrElse(fail("Expected source placement"))
    val destination = firstOccupied.placements.lastOption.getOrElse(fail("Expected destination placement"))
    val replacement = PhotoId(buildUuid(index = 572L))
    val adjusted    = source.transform.copy(mirrored = false, zoom = 5.0)
    val changed     = firstOccupied.placements.map(value => if value.id.value === source.id.value then value.copy(transform = adjusted) else value)
    val movedSource = source.copy(transform = adjusted, slot = destination.slot)
    val swapped     = Vector(movedSource, destination.copy(slot = source.slot))
    val incoming    = SpreadPlacement(SpreadPlacementId(buildUuid(index = 1000L)), source.slot, replacement, untouched, CaptionCorner.BottomRight)
    val snapshots   = Vector(
      firstOccupied,
      firstOccupied.copy(placements = changed),
      firstOccupied.copy(placements = swapped),
      firstOccupied.copy(placements = swapped),
      firstOccupied.copy(placements = Vector(movedSource, incoming)),
      firstOccupied.copy(placements = Vector(movedSource))
    )
    val savedReads = responses(snapshots.map(spread => AlbumCompositionRepository.SpreadResult.Found(Located.Photo(chapter.id, spread))))

    for
      manager      <- buildManager(refs, maybeAlbum = current.some, spreadReads = savedReads.some)
      saved        <- manager.editPlacements(album.id, first.id, Edit.Adjust(source.id, adjusted))
      moved        <- manager.editPlacements(album.id, first.id, Edit.Move(source.id, destination.slot))
      same         <- manager.editPlacements(album.id, first.id, Edit.Move(source.id, destination.slot))
      replaced     <- manager.editPlacements(album.id, first.id, Edit.Place(source.slot, replacement))
      removed      <- manager.editPlacements(album.id, first.id, Edit.Remove(incoming.id))
      movedToEmpty <- manager.editPlacements(album.id, first.id, Edit.Move(source.id, source.slot))
      writes       <- refs.placementWrites.get
    yield
      assertEquals(saved, PlacementResult.Saved(firstOccupied.copy(placements = changed)))
      assertEquals(moved, PlacementResult.Saved(firstOccupied.copy(placements = swapped)))
      assertEquals(same, moved)
      assertEquals(replaced, PlacementResult.Saved(firstOccupied.copy(placements = Vector(movedSource, incoming))))
      assertEquals(removed, PlacementResult.Saved(firstOccupied.copy(placements = Vector(movedSource))))
      assertEquals(movedToEmpty, PlacementResult.Saved(firstOccupied.copy(placements = Vector(source.copy(transform = adjusted)))))
      assertEquals(
        writes,
        Vector(
          changed,
          swapped,
          swapped,
          Vector(movedSource, incoming),
          Vector(movedSource),
          Vector(source.copy(transform = adjusted))
        ).map(first.id -> _)
      )

  test("should reject stale placement targets and invalid zoom without replacing a spread"):
    val occupied  = buildAlbumWithSpreads(spreads = Vector(firstOccupied))
    val source    = firstOccupied.placements.headOption.getOrElse(fail("Expected source placement"))
    val stale     = SpreadPlacementId(buildUuid(index = 9999L))
    val staleSlot = PhotoSpreadSlotId(buildUuid(index = 9999L))
    val requests  = Vector(
      Edit.Place(staleSlot, PhotoId(buildUuid(index = 572L))) -> Failure.SlotNotFound,
      Edit.Remove(stale)                                      -> Failure.PlacementNotFound,
      Edit.SetCaptionCorner(stale, CaptionCorner.TopLeft)     -> Failure.PlacementNotFound,
      Edit.Move(stale, source.slot)                           -> Failure.PlacementNotFound,
      Edit.Move(source.id, staleSlot)                         -> Failure.SlotNotFound,
      Edit.Adjust(stale, untouched)                           -> Failure.PlacementNotFound,
      Edit.Adjust(source.id, untouched.copy(zoom = 0.5))      -> Failure.InvalidTransform,
      Edit.Adjust(source.id, untouched.copy(zoom = 6.0))      -> Failure.InvalidTransform
    )

    requests.traverse_ { case (request, failure) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, maybeAlbum = occupied.some)
        result  <- manager.editPlacements(album.id, first.id, request)
        writes  <- refs.placementWrites.get
      yield
        assertEquals(result, PlacementResult.Rejected(failure))
        assertEquals(writes, Vector.empty[(SpreadId, Vector[SpreadPlacement])])
    }

  test("should reject wrong-owner and used photos and inspect before each placement write"):
    val refs         = Refs()
    val photo        = PhotoId(buildUuid(index = 570L))
    val foreignPhoto = PhotoId(buildUuid(index = 590L))
    val missing      = PhotoId(buildUuid(index = 9999L))
    val inspections  = responses(Vector(
      PhotoPlacementRepository.InspectResult.Found(inCollection = false, placed = false),
      PhotoPlacementRepository.InspectResult.NotFound,
      PhotoPlacementRepository.InspectResult.Found(inCollection = true, placed = false),
      PhotoPlacementRepository.InspectResult.Found(inCollection = true, placed = true)
    ))

    for
      manager       <- buildManager(refs, inspection = inspections)
      secondManager <- buildManager(refs, inspection = PhotoPlacementRepository.InspectResult.Found(inCollection = true, placed = true).pure[IO])
      foreignResult <- manager.editPlacements(album.id, first.id, Edit.Place(first.photoSlots.head.id, foreignPhoto))
      missingPhoto  <-
        manager.editPlacements(album.id, first.id, Edit.Place(first.photoSlots.head.id, missing))
      outcomes <-
        first.photoSlots.toList.traverse(slot => manager.editPlacements(album.id, first.id, Edit.Place(slot.id, photo)))
      used    <- secondManager.editPlacements(album.id, second.id, Edit.Place(second.photoSlots.head.id, photo))
      effects <- refs.effects.get
    yield
      val saved       = outcomes.count { case PlacementResult.Saved(_) => true; case _ => false }
      val alreadyUsed = outcomes.count { case PlacementResult.Rejected(Failure.PhotoAlreadyUsed) => true; case _ => false }
      assertEquals(foreignResult, PlacementResult.Rejected(Failure.PhotoNotInCollection))
      assertEquals(missingPhoto, PlacementResult.Rejected(Failure.PhotoNotInCollection))
      assertEquals(saved, 1)
      assertEquals(alreadyUsed, 1)
      assertEquals(used, PlacementResult.Rejected(Failure.PhotoAlreadyUsed))
      assertEquals(
        effects,
        Vector(
          Effect.Inspect(album.id, chapter.id.some, foreignPhoto),
          Effect.Inspect(album.id, chapter.id.some, missing),
          Effect.Inspect(album.id, chapter.id.some, photo),
          Effect.Place(first.id),
          Effect.Inspect(album.id, chapter.id.some, photo),
          Effect.Inspect(album.id, chapter.id.some, photo)
        )
      )

  test("should distinguish missing placement targets and fail explicitly on reads, photo inspection and writes"):
    val refs    = Refs()
    val request = Edit.Place(first.photoSlots.head.id, PhotoId(buildUuid(index = 570L)))
    val absent  = SpreadId(buildUuid(index = 999L))
    val error   = new IllegalStateException("Placement unavailable")

    for
      manager          <- buildManager(refs)
      absentManager    <- buildManager(refs)
      missingAlbum     <- buildManager(refs, maybeAlbum = none[Album])
      broken           <- buildManager(refs, maybeReadError = error.some)
      uninspectable    <- buildManager(refs, maybeInspectionError = error.some)
      unwritable       <- buildManager(refs, maybeWriteError = error.some)
      absentRead       <- manager.readPlacements(album.id, absent)
      absentWrite      <- absentManager.editPlacements(album.id, absent, request)
      noAlbumRead      <- missingAlbum.readPlacements(album.id, first.id)
      noAlbumWrite     <- missingAlbum.editPlacements(album.id, first.id, request)
      failedRead       <- broken.readPlacements(album.id, first.id)
      failedEdit       <- broken.editPlacements(album.id, first.id, request)
      failedInspection <- uninspectable.editPlacements(album.id, first.id, request)
      failedWrite      <- unwritable.editPlacements(album.id, first.id, request)
    yield
      assertEquals(absentRead, PlacementReadResult.SpreadNotFound)
      assertEquals(absentWrite, PlacementResult.SpreadNotFound)
      assertEquals(noAlbumRead, PlacementReadResult.AlbumNotFound)
      assertEquals(noAlbumWrite, PlacementResult.AlbumNotFound)
      assertEquals(failedRead, PlacementReadResult.Failed)
      assertEquals(failedEdit, PlacementResult.Failed)
      assertEquals(failedInspection, PlacementResult.Failed)
      assertEquals(failedWrite, PlacementResult.Failed)

  test("should replace the observed chapter order and reject stale intent without replaying it"):
    val refs        = Refs()
    val observed    = NonEmptyList.of(chapterOpening.id, first.id, second.id)
    val proposed    = NonEmptyList.of(chapterOpening.id, second.id, first.id)
    val savedOrders = responses(Vector(observed.some, proposed.some))

    for
      manager <- buildManager(refs, orderReads = savedOrders.some)
      saved   <- manager.replaceChapterOrder(album.id, chapter.id, observed, proposed)
      stale   <- manager.replaceChapterOrder(album.id, chapter.id, observed, proposed)
      orders  <- refs.orders.get
    yield
      assertEquals(saved, ReplacementResult.Saved)
      assertEquals(stale, ReplacementResult.Stale)
      assertEquals(orders, Vector((chapter.id, proposed)))

  test("should preserve the opening and every observed spread exactly once before reading or writing"):
    val refs     = Refs()
    val observed = NonEmptyList.of(chapterOpening.id, first.id, second.id)
    val invalid  = Vector(
      NonEmptyList.of(first.id, chapterOpening.id, second.id),
      NonEmptyList.of(chapterOpening.id, first.id, first.id),
      NonEmptyList.of(chapterOpening.id, first.id),
      NonEmptyList.of(chapterOpening.id, first.id, foreign.id)
    )

    for
      manager <- buildManager(refs, maybeReadError = new AssertionError("Unexpected read").some)
      results <- invalid.traverse(order => manager.replaceChapterOrder(album.id, chapter.id, observed, order))
      orders  <- refs.orders.get
    yield
      assertEquals(results, Vector.fill(invalid.size)(ReplacementResult.Invalid))
      assertEquals(orders, Vector.empty[(ChapterId, NonEmptyList[SpreadId])])

  test("should report missing scoped chapter composition without writing"):
    val refs     = Refs()
    val observed = NonEmptyList.of(chapterOpening.id, first.id, second.id)

    for
      manager <- buildManager(refs, maybeAlbum = none[Album])
      result  <- manager.replaceChapterOrder(album.id, chapter.id, observed, observed)
      orders  <- refs.orders.get
    yield
      assertEquals(result, ReplacementResult.NotFound)
      assertEquals(orders, Vector.empty[(ChapterId, NonEmptyList[SpreadId])])

  test("should distinguish a failed pre-write read from an unconfirmed order write"):
    val refs        = Refs()
    val observed    = NonEmptyList.of(chapterOpening.id, first.id, second.id)
    val proposed    = NonEmptyList.of(chapterOpening.id, second.id, first.id)
    val unavailable = new IllegalStateException("Database unavailable")

    for
      unreadable <- buildManager(refs, maybeReadError = unavailable.some)
      failed     <- unreadable.replaceChapterOrder(album.id, chapter.id, observed, proposed)
      before     <- refs.orders.get
      unwritable <- buildManager(refs, maybeWriteError = unavailable.some)
      uncertain  <- unwritable.replaceChapterOrder(album.id, chapter.id, observed, proposed)
      after      <- refs.orders.get
    yield
      assertEquals(failed, ReplacementResult.Failed)
      assertEquals(before, Vector.empty[(ChapterId, NonEmptyList[SpreadId])])
      assertEquals(uncertain, ReplacementResult.Uncertain)
      assertEquals(after, Vector((chapter.id, proposed)))

  test("should commit spread bleed atomically while retaining geometry and placements and reject empty eligibility"):
    val refs     = Refs()
    val expected = firstOccupied.copy(photoSlots =
      NonEmptyList.of(
        buildPhotoSlot(index = 4000L, box).copy(bleed = Set(SpreadEdge.Top, SpreadEdge.Bottom, SpreadEdge.Left)),
        buildPhotoSlot(index = 4001L, wideBox).copy(bleed = Set(SpreadEdge.Top, SpreadEdge.Bottom, SpreadEdge.Right))
      )
    )
    val populated  = buildAlbumWithSpreads(Vector(firstOccupied, second))
    val savedReads = responses(Vector(
      AlbumCompositionRepository.SpreadResult.Found(Located.Photo(chapter.id, firstOccupied)),
      AlbumCompositionRepository.SpreadResult.Found(Located.Photo(chapter.id, expected)),
      AlbumCompositionRepository.SpreadResult.Found(Located.AlbumOpening(albumOpening))
    ))

    for
      manager      <- buildManager(refs, maybeAlbum = populated.some, spreadReads = savedReads.some)
      enabled      <- manager.setBleed(album.id, firstOccupied.id, enabled = true)
      disabled     <- manager.setBleed(album.id, firstOccupied.id, enabled = false)
      rejected     <- manager.setBleed(album.id, albumOpening.id, enabled = true)
      replacements <- refs.bleedWrites.get
      layouts      <- refs.layouts.get
    yield
      assertEquals(enabled, SetBleedResult.Saved(expected))
      assertEquals(disabled, SetBleedResult.Saved(firstOccupied))
      assertEquals(rejected, SetBleedResult.Rejected)
      assertEquals(
        replacements,
        Vector(
          firstOccupied.id -> expected.photoSlots.toList.map(slot => slot.id -> slot.bleed).toMap,
          firstOccupied.id -> firstOccupied.photoSlots.toList.map(slot => slot.id -> slot.bleed).toMap
        )
      )
      assertEquals(layouts, Vector.empty[Spread])

  test("should expose missing and failed bleed writes without changing another spread"):
    val refs        = Refs()
    val unavailable = new IllegalStateException("Unavailable")

    for
      manager      <- buildManager(refs)
      missingAlbum <- buildManager(refs, maybeAlbum = none[Album])
      broken       <- buildManager(refs, maybeReadError = unavailable.some)
      unwritable   <- buildManager(refs, maybeWriteError = unavailable.some)
      absent       <- manager.setBleed(album.id, newId, enabled = true)
      noAlbum      <- missingAlbum.setBleed(album.id, first.id, enabled = true)
      failedRead   <- broken.setBleed(album.id, first.id, enabled = true)
      failedWrite  <- unwritable.setBleed(album.id, first.id, enabled = true)
    yield
      assertEquals(absent, SetBleedResult.SpreadNotFound)
      assertEquals(noAlbum, SetBleedResult.AlbumNotFound)
      assertEquals(failedRead, SetBleedResult.Failed)
      assertEquals(failedWrite, SetBleedResult.Failed)

  private enum Effect:
    case Inspect(album: AlbumId, chapter: Option[ChapterId], photo: PhotoId)
    case Place(spread: SpreadId)

  final private case class Refs():
    val inserts: Ref[IO, Vector[(chapter: ChapterId, spread: Spread.PhotosOnly)]]         = Ref.unsafe(Vector.empty)
    val replacements: Ref[IO, Vector[Spread]]                                             = Ref.unsafe(Vector.empty)
    val layouts: Ref[IO, Vector[Spread]]                                                  = Ref.unsafe(Vector.empty)
    val orders: Ref[IO, Vector[(chapter: ChapterId, order: NonEmptyList[SpreadId])]]      = Ref.unsafe(Vector.empty)
    val removals: Ref[IO, Vector[SpreadId]]                                               = Ref.unsafe(Vector.empty)
    val placementWrites: Ref[IO, Vector[(SpreadId, Vector[SpreadPlacement])]]             = Ref.unsafe(Vector.empty)
    val bleedWrites: Ref[IO, Vector[(SpreadId, Map[PhotoSpreadSlotId, Set[SpreadEdge]])]] = Ref.unsafe(Vector.empty)
    val effects: Ref[IO, Vector[Effect]]                                                  = Ref.unsafe(Vector.empty)

  private def describeInserts(values: Vector[(chapter: ChapterId, spread: Spread.PhotosOnly)]) =
    values.map { case (chapterId, spread) => (chapterId, spread.id, spread.template, spread.photoSlots.map(_.box).toList, spread.placements) }

  private def buildAlbumWithSpreads(spreads: Vector[Spread.PhotosOnly]) =
    album.copy(chapters = Vector(chapter.copy(spreads = spreads), otherChapter))

  private def buildPhotoSlot(index: Long, slotBox: SlotBox) =
    SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index)), slotBox, bleed = Set.empty[SpreadEdge])

  private def buildPhotoSpread(index: Long, placements: Vector[SpreadPlacement]) =
    val slots = NonEmptyList.of(box, wideBox).zipWithIndex.map { case (slotBox, position) =>
      buildPhotoSlot(index = index * 100L + position.toLong, slotBox)
    }
    Spread.PhotosOnly(SpreadId(buildUuid(index)), defaultLayout, photoSlots = slots, placements = placements)

  private def buildPlacement(index: Long, slot: PhotoSpreadSlotId) =
    SpreadPlacement(
      SpreadPlacementId(buildUuid(index)),
      slot,
      PhotoId(buildUuid(index + 500L)),
      PhotoTransform(Shift(x = 3.0, y = 4.0), mirrored = true, zoom = 2.0),
      CaptionCorner.TopRight
    )

  private def replaceTestPlacements(spread: Spread, placements: Vector[SpreadPlacement]) = spread match
    case value: Spread.AlbumStart   => value.copy(placements = placements)
    case value: Spread.ChapterStart => value.copy(placements = placements)
    case value: Spread.PhotosOnly   => value.copy(placements = placements)

  private def describePlacementResult(result: PlacementResult) = result match
    case PlacementResult.Saved(spread) => spread.placements
    case other                         => fail(s"Expected saved placements, obtained $other")

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      maybeAlbum: Option[Album] = album.some,
      maybeReadError: Option[Throwable] = none,
      maybeWriteError: Option[Throwable] = none,
      maybeTemplateResult: Option[FindTemplateResult] = none,
      maybeInspectionError: Option[Throwable] = none,
      spreadReads: Option[IO[AlbumCompositionRepository.SpreadResult]] = none,
      orderReads: Option[IO[Option[NonEmptyList[SpreadId]]]] = none,
      inspection: IO[PhotoPlacementRepository.InspectResult] =
        PhotoPlacementRepository.InspectResult.Found(inCollection = true, placed = false).pure[IO]
  ) =
    val located = maybeAlbum.toList.flatMap: saved =>
      Vector[Located](Located.AlbumOpening(saved.opening)) ++ saved.chapters.flatMap: chapter =>
        Vector[Located](Located.ChapterOpening(chapter.id, chapter.opening)) ++ chapter.spreads.map(Located.Photo(chapter.id, _))
    given ids: IdGenerator[IO] with
      private val next                  = Ref.unsafe[IO, Long](1000L)
      override def generateId: IO[UUID] = next.getAndUpdate(_ + 1L).map(buildUuid)
    val templates = new SpreadTemplateRepository[IO]:
      override def listAlbumStartTemplates: IO[SpreadTemplateRepository.ListAlbumStartTemplatesResult] =
        new AssertionError("Unexpected album templates").raiseError[IO, SpreadTemplateRepository.ListAlbumStartTemplatesResult]
      override def listChapterStartTemplates: IO[SpreadTemplateRepository.ListChapterStartTemplatesResult] =
        new AssertionError("Unexpected chapter templates").raiseError[IO, SpreadTemplateRepository.ListChapterStartTemplatesResult]
      override def listPhotosOnlyTemplates: IO[SpreadTemplateRepository.ListPhotosOnlyTemplatesResult] =
        new AssertionError("Unexpected photo templates").raiseError[IO, SpreadTemplateRepository.ListPhotosOnlyTemplatesResult]
      override def findTemplate(id: SpreadTemplateId): IO[FindTemplateResult] = maybeTemplateResult.getOrElse(resolveCatalog(id)).pure[IO]
    val composition = new AlbumCompositionRepository[IO]:
      override def hasAlbum(value: AlbumId): IO[Boolean] =
        maybeReadError.fold(maybeAlbum.exists(_.id.value === value.value).pure[IO])(error => error.raiseError[IO, Boolean])
      override def countInteriorSpreads(value: AlbumId): IO[Int] =
        maybeAlbum.filter(_.id.value === value.value).fold(0)(_.collectSpreads.length).pure[IO]
      override def findChapterOrder(value: AlbumId, chapterId: ChapterId): IO[Option[NonEmptyList[SpreadId]]] =
        val saved = IO:
          maybeAlbum.filter(_.id.value === value.value).flatMap(_.chapters.find(_.id.value === chapterId.value)).map: chapter =>
            NonEmptyList(chapter.opening.id, tail = chapter.spreads.map(_.id).toList)
        maybeReadError match
          case Some(error) => error.raiseError[IO, Option[NonEmptyList[SpreadId]]]
          case None        => orderReads.getOrElse(saved)
      override def findSpread(value: AlbumId, id: SpreadId): IO[AlbumCompositionRepository.SpreadResult] =
        val saved = located.find(
          _.spread.id.value === id.value
        ).fold[AlbumCompositionRepository.SpreadResult](AlbumCompositionRepository.SpreadResult.NotFound)(
          AlbumCompositionRepository.SpreadResult.Found(_)
        )
        spreadReads.getOrElse(saved.pure[IO])
    val writes     = maybeWriteError.fold(IO.unit)(error => error.raiseError[IO, Unit])
    val repository = new SpreadRepository[IO]:
      override def insert(chapterId: ChapterId, spread: Spread.PhotosOnly): IO[Unit] =
        refs.inserts.update(_ :+ (chapterId, spread)) >> writes
      override def replace(spread: Spread): IO[Unit] =
        writes >> refs.layouts.update(_ :+ spread) >> refs.replacements.update(_ :+ spread)
      override def replacePlacements(spread: SpreadId, placements: Vector[SpreadPlacement]): IO[Unit] =
        IO.cede >> refs.effects.update(_ :+ Effect.Place(spread)) >> refs.placementWrites.update(_ :+ (spread, placements)) >> writes
      override def replaceBleed(spread: SpreadId, edges: Map[PhotoSpreadSlotId, Set[SpreadEdge]]): IO[Unit] =
        refs.bleedWrites.update(_ :+ (spread, edges)) >> writes
      override def reorder(chapterId: ChapterId, order: NonEmptyList[SpreadId]): IO[Unit] =
        refs.orders.update(_ :+ (chapterId, order)) >> writes
      override def remove(spread: SpreadId): IO[Unit] = refs.removals.update(_ :+ spread) >> writes
    val placements = new PhotoPlacementRepository[IO]:
      override def findPlacedPhotos(id: ChapterId): IO[PhotoPlacementRepository.FindResult] =
        new AssertionError("Unexpected inventory").raiseError[IO, PhotoPlacementRepository.FindResult]
      override def inspect(owner: AlbumId, maybeChapter: Option[ChapterId], photo: PhotoId): IO[PhotoPlacementRepository.InspectResult] =
        refs.effects.update(_ :+ Effect.Inspect(owner, maybeChapter, photo)) >>
          maybeInspectionError.fold(inspection)(_.raiseError[IO, PhotoPlacementRepository.InspectResult])
    given transaction: Transaction[IO, IO] with
      override def run[A](program: IO[A]): IO[A]     = program
      override def resolve[A](program: IO[A]): IO[A] = program
    SpreadManager.make(templates, composition, repository, placements, defaultLayout).pure[IO]
  // scalafix:on DisableSyntax.defaultArgs

  private def resolveCatalog(id: SpreadTemplateId) =
    catalog.find(_.id.value === id.value).fold[FindTemplateResult](FindTemplateResult.NotFound)(FindTemplateResult.Found(_))

  private def buildUuid(index: Long) = new UUID(0L, index)

  private def responses[A](values: Vector[A]): IO[A] =
    val remaining = Ref.unsafe[IO, Vector[A]](values)
    val next      = remaining.modify:
      case head +: tail => (tail, head.some)
      case _            => (Vector.empty[A], none[A])
    next.flatMap(_.liftTo[IO](new AssertionError("Unarranged spread repository response")))
