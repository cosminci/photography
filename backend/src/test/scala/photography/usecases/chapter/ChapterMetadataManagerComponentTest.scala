package photography.usecases.chapter
import photography.domain.gear.CameraSelection

import photography.usecases.photo.ChapterPhotoImport
import photography.usecases.photo.PhotoWriteRecovery
import photography.ports.spread.SpreadTemplateRepository

import cats.data.NonEmptyList
import cats.Id
import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.album.AlbumRepository
import photography.ports.chapter.ChapterRepository
import photography.ports.photo.PhotoRepository
import photography.usecases.chapter.ChapterMetadataManager.{CreateResult, Period, RefreshResult, SaveDescriptionResult, UpdateResult}
import photography.ports.spread.SpreadTemplateRepository.ListChapterStartTemplatesResult
import photography.usecases.photo.ChapterPhotoImport.{ExecutionOutcome, Request, Scheduling, Status}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.nio.file.Path
import java.time.{Instant, LocalDate}
import java.util.UUID

final class ChapterMetadataManagerComponentTest extends CatsEffectSuite:
  private val logger                    = Slf4jLogger.getLogger[IO]
  private val homeName: DestinationName = "Home"
  private val countries                 = NonEmptyList.of(
    Country(CountryId(buildUuid(index = 21)), code = "JP", name = "Japan", AbsoluteLocation(Path.of("/flags/jp.svg"))),
    Country(CountryId(buildUuid(index = 22)), code = "KR", name = "South Korea", AbsoluteLocation(Path.of("/flags/kr.svg"))),
    Country(CountryId(buildUuid(index = 23)), code = "TW", name = "Taiwan", AbsoluteLocation(Path.of("/flags/tw.svg"))),
    Country(CountryId(buildUuid(index = 24)), code = "SG", name = "Singapore", AbsoluteLocation(Path.of("/flags/sg.svg")))
  )
  private val box      = SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0)
  private val template =
    SpreadTemplate.ChapterStart(SpreadTemplateId(buildUuid(index = 4)), name = "Default", photoSlots = Vector(box), textSlot = box)
  private val album =
    val textSlot        = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 5)), box)
    val emptyPhotoSlots = Vector.empty[SpreadSlot.Photo]
    val emptyPlacements = Vector.empty[SpreadPlacement]
    val opening = Spread.AlbumStart(SpreadId(buildUuid(index = 3)), template.id, photoSlots = emptyPhotoSlots, textSlot, placements = emptyPlacements)
    val emptyChapters = Vector.empty[Chapter]
    Album(
      AlbumId(buildUuid(index = 1)),
      year = 2026,
      title = "A year abroad",
      synopsis = "",
      authors = Author.all,
      Instant.EPOCH,
      opening,
      chapters = emptyChapters
    )
  private val chapter =
    val destination     = Destination(DestinationId(buildUuid(index = 30)), name = "Asian capitals", countries = countries)
    val period          = VisitPeriod(firstDay = LocalDate.of(2026, 5, 1), days = 2)
    val textSlot        = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 41)), box)
    val emptyPhotoSlots = Vector.empty[SpreadSlot.Photo]
    val emptyPlacements = Vector.empty[SpreadPlacement]
    val opening         =
      Spread.ChapterStart(SpreadId(buildUuid(index = 40)), template.id, photoSlots = emptyPhotoSlots, textSlot, placements = emptyPlacements)
    val emptySpreads = Vector.empty[Spread.PhotosOnly]
    val id           = ChapterId(buildUuid(index = 20))
    Chapter(
      id,
      destination,
      period,
      description = "",
      gear = Vector.empty[CameraSelection],
      sourceFolder = "/photos/capitals",
      opening,
      spreads = emptySpreads
    )
  private val home =
    val destination = chapter.destination.copy(name = homeName)
    val fullYear    = VisitPeriod(firstDay = LocalDate.of(2026, 1, 1), days = 365)
    chapter.copy(id = ChapterId(buildUuid(index = 50)), destination = destination, period = fullYear, sourceFolder = "/photos/Home")
  private val atCapacity =
    val photoSlot       = SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index = 61)), box, bleed = Set.empty)
    val emptyPlacements = Vector.empty[SpreadPlacement]
    val photoSlots      = NonEmptyList.one(photoSlot)
    val photo           = Spread.PhotosOnly(SpreadId(buildUuid(index = 60)), template.id, photoSlots = photoSlots, placements = emptyPlacements)
    val spreads         = Vector.tabulate(63)(index => photo.copy(id = SpreadId(buildUuid(index = 100 + index.toLong))))
    album.copy(chapters = Vector(chapter.copy(spreads = spreads)))
  private val photo =
    val metadata  = PhotoMetadata(hash = "source", maybeCapturedLocal = None, PhotoDimensions(width = 10, height = 10))
    val thumbnail =
      Thumbnail(ThumbnailLocation(buildUuid(index = 90)), version = "v", ThumbnailDimensions(width = 5, height = 5), ThumbnailQuality.Low, bytes = 1)
    val photoId  = PhotoId(buildUuid(index = 91))
    val original = OriginalPhotoLocation(chapter.sourceFolder, filename = "one.jpg")
    Photo(photoId, chapter.id, original, metadata, thumbnail, maybeCaption = None)

  test("should journal and dispatch only committed source-change retirements"):
    val saved     = ChapterRepository.UpdateResult.Saved
    val recorded  = PhotoRepository.RecordWriteResult.Recorded
    val removed   = PhotoRepository.WriteResult.Applied
    val scenarios = Vector(
      (false, saved, recorded, removed, UpdateResult.Updated(Scheduling.NotRequested)),
      (true, saved, recorded, removed, UpdateResult.Updated(Scheduling.Queued)),
      (true, ChapterRepository.UpdateResult.UnknownCountries, recorded, removed, UpdateResult.Rejected(ChapterConflict.UnknownCountries)),
      (true, saved, PhotoRepository.RecordWriteResult.Failed, removed, UpdateResult.Failed),
      (true, saved, recorded, PhotoRepository.WriteResult.Failed, UpdateResult.Failed)
    )
    scenarios.traverse_ { case (changed, metadataResult, journalResult, removalResult, expected) =>
      val refs                = Refs()
      val source: PhotoSource = if changed then "/photos/changed" else chapter.sourceFolder
      val name                = chapter.destination.name
      val countryIds          = countries.map(_.id)
      val visit               = Period.Dates(chapter.period)
      val succeeds            = changed &&
        (expected match
          case _: UpdateResult.Updated => true
          case _                       => false)

      val createManager = buildManager(
        refs,
        current = album.copy(chapters = Vector(chapter)),
        updateWrite = metadataResult,
        listed = PhotoRepository.ListResult.Listed(photos = Vector(photo)).pure[IO],
        recording = journalResult.pure[IO],
        removal = removalResult.pure[IO]
      )
      for
        manager    <- createManager
        result     <- manager.update(album.id, chapter.id, name, countries = countryIds, visit, source)
        dispatched <- refs.retirements.get
        prepared   <- refs.preparations.get
        listed     <- refs.photoReads.get
      yield
        assertEquals(result, expected)
        assertEquals(dispatched.map(_.location), if succeeds then Vector(photo.thumbnail.location) else Vector.empty[ThumbnailLocation])
        assertEquals(prepared, if succeeds then Vector((chapter.id, source)) else Vector.empty[(ChapterId, PhotoSource)])
        assertEquals(listed, if changed then Vector(chapter.id) else Vector.empty[ChapterId])
    }

  test("should resolve lost update acknowledgements and suspend old writes when the saved state is uncertain"):
    Vector("committed", "rolled-back", "unreadable", "missing", "unknown", "absent-chapter", "exception").traverse_ { resolution =>
      val refs    = Refs()
      val current = album.copy(chapters = Vector(chapter))
      val error   = new IllegalStateException("Lost commit acknowledgement")
      val state   = Ref.unsafe[IO, IO[AlbumRepository.GetResult]](AlbumRepository.GetResult.Found(current).pure[IO])
      val update  = (destination: DestinationId) =>
        val saved    = chapter.copy(destination = chapter.destination.copy(id = destination), sourceFolder = "/photos/changed")
        val response = resolution match
          case "committed"      => AlbumRepository.GetResult.Found(current.copy(chapters = Vector(saved))).pure[IO]
          case "rolled-back"    => AlbumRepository.GetResult.Found(current).pure[IO]
          case "unreadable"     => AlbumRepository.GetResult.Failed(AlbumRepository.Failure.DatabaseFailure).pure[IO]
          case "missing"        => AlbumRepository.GetResult.NotFound.pure[IO]
          case "absent-chapter" => AlbumRepository.GetResult.Found(current.copy(chapters = Vector.empty[Chapter])).pure[IO]
          case "unknown"        =>
            val unexpected = chapter.copy(destination = chapter.destination.copy(id = DestinationId(buildUuid(index = 999))))
            AlbumRepository.GetResult.Found(current.copy(chapters = Vector(unexpected))).pure[IO]
          case _ => IO.raiseError[AlbumRepository.GetResult](error)
        state.set(response) >> IO.raiseError[Unit](error)
      val expected                   = if resolution === "committed" then UpdateResult.Updated(Scheduling.Queued) else UpdateResult.Failed
      val suspends                   = resolution =!= "committed" && resolution =!= "rolled-back"
      val name                       = chapter.destination.name
      val countryIds                 = countries.map(_.id)
      val visit                      = Period.Dates(chapter.period)
      val changedSource: PhotoSource = "/photos/changed"

      for
        manager   <- buildManager(refs, maybeRead = state.get.flatten.some, update = update)
        result    <- manager.update(album.id, chapter.id, name, countries = countryIds, visit, changedSource)
        suspended <- refs.suspensions.get
        prepared  <- refs.preparations.get
      yield
        assertEquals(result, expected)
        assertEquals(suspended, if suspends then Vector(chapter.id) else Vector.empty[ChapterId])
        assertEquals(prepared.size, if resolution === "committed" then 1 else 0)
    }

  test("should stop before mutation when photo listing fails and report failed chapter creation explicitly"):
    Vector(
      PhotoRepository.ListResult.Failed.pure[IO],
      IO.raiseError[PhotoRepository.ListResult](new IllegalStateException("Read failed"))
    ).traverse_ { listed =>
      val refs                       = Refs()
      val error                      = new IllegalStateException("Write failed")
      val name                       = chapter.destination.name
      val countryIds                 = countries.map(_.id)
      val visit                      = Period.Dates(chapter.period)
      val changedSource: PhotoSource = "/photos/changed"

      for
        manager <- buildManager(refs, current = album.copy(chapters = Vector(chapter)), listed = listed, save = _ => IO.raiseError[Unit](error))
        created <- manager.create(album.id, name = "Kyoto", countries = countryIds, visit, sourceFolder = "/photos/new")
        updated <- manager.update(album.id, chapter.id, name, countries = countryIds, visit, changedSource)
        writes  <- refs.updates.get
      yield
        assertEquals(created, CreateResult.Failed)
        assertEquals(updated, UpdateResult.Failed)
        assertEquals(writes, Vector.empty)
    }

  test("should hand committed source retirement to application execution before honoring request cancellation"):
    Vector(false, true).traverse_ { cancelAtCommit =>
      val refs = Refs()

      (Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap { case (entered, release) =>
        val current                    = album.copy(chapters = Vector(chapter))
        val photoListing               = PhotoRepository.ListResult.Listed(photos = Vector(photo)).pure[IO]
        val name                       = chapter.destination.name
        val countryIds                 = countries.map(_.id)
        val visit                      = Period.Dates(chapter.period)
        val changedSource: PhotoSource = "/photos/changed"
        val handoff                    = entered.complete(()).void >> release.get
        val update                     = (_: DestinationId) => if cancelAtCommit then handoff else IO.unit
        val scheduling                 = (if cancelAtCommit then IO.unit else handoff).as(Scheduling.Queued)
        val program                    =
          for
            manager      <- buildManager(refs, current = current, listed = photoListing, update = update, scheduling = scheduling)
            worker       <- manager.update(album.id, chapter.id, name, countries = countryIds, visit, changedSource).flatTap(_ => IO.cede).start
            _            <- entered.get
            cancellation <- worker.cancel.start
            _            <- cancellation.join
            outcome      <- worker.join
            prepared     <- refs.preparations.get
            dispatched   <- refs.retirements.get
          yield
            assert(outcome.isCanceled)
            assertEquals(prepared, Vector((chapter.id, "/photos/changed": PhotoSource)))
            assertEquals(dispatched.map(_.location), Vector(photo.thumbnail.location))

        for
          control <- TestControl.execute(program)
          _       <- control.tick
          waiting <- control.results
          _       <- release.complete(())
          _       <- control.tick
          result  <- control.results
        yield
          assertEquals(waiting, None)
          assertEquals(result, Outcome.succeeded[Id, Throwable, Unit](()).some)
      }
    }

  test("should create a multi-country chapter and its empty opening"):
    val refs                  = Refs()
    val checkNoPreparedImport = refs.preparations.get.map(actual => assertEquals(actual, Vector.empty))
    val countryIds            = countries.map(_.id)

    for
      manager  <- buildManager(refs, save = _ => checkNoPreparedImport)
      result   <- manager.create(album.id, chapter.destination.name, countries = countryIds, Period.Dates(chapter.period), chapter.sourceFolder)
      writes   <- refs.creations.get
      prepared <- refs.preparations.get
    yield
      val photoSlot       = SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index = 12)), box, bleed = Set.empty)
      val textSlot        = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 13)), box)
      val emptyPlacements = Vector.empty[SpreadPlacement]
      val photoSlots      = Vector(photoSlot)
      val opening = Spread.ChapterStart(SpreadId(buildUuid(index = 11)), template.id, photoSlots = photoSlots, textSlot, placements = emptyPlacements)
      val chapterId       = ChapterId(buildUuid(index = 10))
      val destinationId   = DestinationId(buildUuid(index = 14))
      val destinationName = chapter.destination.name
      val sourceFolder    = chapter.sourceFolder
      val expected        = ChapterRepository.NewChapter(
        chapterId,
        destinationId,
        destinationName,
        countries = countryIds,
        chapter.period,
        description = "",
        sourceFolder,
        opening
      )
      assertEquals(result, CreateResult.Created(expected.id, Scheduling.Queued))
      assertEquals(writes, Vector((album.id, expected, NonEmptyList.one(expected.id))))
      assertEquals(prepared, Vector((expected.id, sourceFolder)))

  test(s"should resolve the full year for $homeName including leap days"):
    Vector((2024: AlbumYear, 366: VisitDays), (2026: AlbumYear, 365: VisitDays)).traverse_ { case (year, days) =>
      val refs        = Refs()
      val period      = VisitPeriod(firstDay = LocalDate.of(year, 1, 1), days = days)
      val homeChapter = home.copy(period = period)
      val current     = album.copy(year = year, chapters = Vector(homeChapter))

      for
        manager  <- buildManager(refs, current = current)
        updated  <- manager.update(album.id, home.id, homeName, countries = countries.map(_.id), Period.AlbumYear, home.sourceFolder)
        updates  <- refs.updates.get
        prepared <- refs.preparations.get
      yield
        assertEquals(updated, UpdateResult.Updated(Scheduling.NotRequested))
        assertEquals(updates.map(_.period), Vector(period))
        assertEquals(prepared, Vector.empty)
    }

  test(s"should reject duplicate $homeName chapters, wrong full-year periods and reused folders before writing"):
    val scenarios = Vector(
      (album.copy(chapters = Vector(home)), homeName, Period.AlbumYear, "/photos/new": PhotoSource, ChapterConflict.HomeExists),
      (album, homeName, Period.Dates(chapter.period), "/photos/new": PhotoSource, ChapterConflict.HomePeriodMismatch),
      (
        album,
        homeName,
        Period.Dates(VisitPeriod(firstDay = LocalDate.of(2026, 1, 1), days = 366)),
        "/photos/new": PhotoSource,
        ChapterConflict.HomePeriodMismatch
      ),
      (
        album.copy(chapters = Vector(chapter)),
        chapter.destination.name,
        Period.Dates(chapter.period),
        chapter.sourceFolder,
        ChapterConflict.FolderAssigned
      )
    )
    scenarios.traverse_ { case (current, name, period, folder, expected) =>
      val refs        = Refs()
      val destination = chapter.destination.copy(name = name)
      val target      = chapter.copy(id = ChapterId(buildUuid(index = 70)), destination = destination, sourceFolder = "/photos/target")

      for
        creator   <- buildManager(refs, current = current)
        editor    <- buildManager(refs, current = current.copy(chapters = current.chapters :+ target))
        created   <- creator.create(album.id, name = name, countries = countries.map(_.id), period, sourceFolder = folder)
        updated   <- editor.update(album.id, target.id, name = name, countries = countries.map(_.id), period, sourceFolder = folder)
        creations <- refs.creations.get
        updates   <- refs.updates.get
      yield
        assertEquals(created, CreateResult.Rejected(expected))
        assertEquals(updated, UpdateResult.Rejected(expected))
        assertEquals(creations, Vector.empty)
        assertEquals(updates, Vector.empty)
    }

  test(s"should preserve chapter type when editing $homeName or an ordinary chapter"):
    Vector((home, chapter.destination.name), (chapter, homeName)).traverse_ { case (current, requestedName) =>
      val refs                       = Refs()
      val countryIds                 = countries.map(_.id)
      val changedSource: PhotoSource = "/photos/changed"

      for
        manager <- buildManager(refs, current = album.copy(chapters = Vector(current)))
        result  <- manager.update(album.id, current.id, name = requestedName, countries = countryIds, Period.AlbumYear, changedSource)
        writes  <- refs.updates.get
      yield
        assertEquals(result, UpdateResult.Rejected(ChapterConflict.TypeChange))
        assertEquals(writes, Vector.empty)
    }

  test("should admit the last opening, reject further creation and allow metadata changes at capacity"):
    val refs             = Refs()
    val current          = atCapacity
    val period           = Period.Dates(chapter.period)
    val updatedCountries = NonEmptyList.one(countries.head)
    val beforeLast       = current.copy(chapters = current.chapters.map(value => value.copy(spreads = value.spreads.drop(1))))

    for
      creator  <- buildManager(refs, current = beforeLast)
      full     <- buildManager(refs, current = current)
      admitted <- creator.create(album.id, name = "Kyoto", countries = countries.map(_.id), period, sourceFolder = "/photos/new")
      rejected <- full.create(album.id, name = "Kyoto", countries = countries.map(_.id), period, sourceFolder = "/photos/new")
      updated  <- full.update(album.id, chapter.id, name = "Kyoto", countries = updatedCountries.map(_.id), period, sourceFolder = "/photos/new")
      writes   <- refs.updates.get
    yield
      assertEquals(beforeLast.collectSpreads.length, 64)
      assertEquals(current.collectSpreads.length, 65)
      assertEquals(admitted, CreateResult.Created(ChapterId(buildUuid(index = 10)), Scheduling.Queued))
      assertEquals(rejected, CreateResult.Rejected(ChapterConflict.AtCapacity))
      assertEquals(updated, UpdateResult.Updated(Scheduling.Queued))
      val destinationName: DestinationName = "Kyoto"
      val sourceFolder: PhotoSource        = "/photos/new"
      val chapterOrder                     = NonEmptyList.one(chapter.id)
      val expectedWrites = Vector((album.id, chapter.id, destinationName, updatedCountries.map(_.id), chapter.period, sourceFolder, chapterOrder))
      assertEquals(writes, expectedWrites)

  test(s"should order chapters by dates and identity with $homeName last"):
    val refs        = Refs()
    val earlier     = chapter.copy(id = ChapterId(buildUuid(index = 70)), period = chapter.period.copy(days = 1), sourceFolder = "/photos/earlier")
    val tied        = earlier.copy(id = ChapterId(buildUuid(index = 71)), sourceFolder = "/photos/tied")
    val laterPeriod = VisitPeriod(firstDay = LocalDate.of(2026, 5, 2), days = 1)
    val later       = chapter.copy(id = ChapterId(buildUuid(index = 72)), period = laterPeriod, sourceFolder = "/photos/later")
    val current     = album.copy(chapters = Vector(earlier, tied, chapter, later, home))
    val countryIds  = countries.map(_.id)

    for
      manager   <- buildManager(refs, current = current)
      created   <- manager.create(album.id, name = "Kyoto", countries = countryIds, Period.Dates(chapter.period), sourceFolder = "/photos/new")
      updated   <- manager.update(album.id, later.id, name = "Kyoto", countries = countryIds, Period.Dates(earlier.period), later.sourceFolder)
      creations <- refs.creations.get
      updates   <- refs.updates.get
    yield
      val expectedCreations = Vector(NonEmptyList.of(earlier.id, tied.id, ChapterId(buildUuid(index = 10)), chapter.id, later.id, home.id))
      assertEquals(created, CreateResult.Created(ChapterId(buildUuid(index = 10)), Scheduling.Queued))
      assertEquals(updated, UpdateResult.Updated(Scheduling.NotRequested))
      assertEquals(creations.map(_.order), expectedCreations)
      assertEquals(updates.map(_.order), Vector(NonEmptyList.of(earlier.id, tied.id, later.id, chapter.id, home.id)))

  test("should decide concurrent creations from the latest saved composition"):
    val refs       = Refs()
    val beforeLast = atCapacity.copy(chapters = atCapacity.chapters.map(value => value.copy(spreads = value.spreads.drop(1))))
    val state      = Ref.unsafe[IO, Album](beforeLast)
    val read       = state.get.flatMap(value => IO.cede.as(AlbumRepository.GetResult.Found(value)))
    val countryIds = countries.map(_.id)
    val save       = (value: ChapterRepository.NewChapter) =>
      val destination  = Destination(value.destinationId, value.name, countries = countries)
      val emptySpreads = Vector.empty[Spread.PhotosOnly]
      val saved        = Chapter(
        value.id,
        destination,
        value.period,
        value.description,
        gear = Vector.empty[CameraSelection],
        value.sourceFolder,
        value.opening,
        spreads = emptySpreads
      )
      state.update(valueBefore => valueBefore.copy(chapters = valueBefore.chapters :+ saved))

    for
      manager <- buildManager(refs, maybeRead = read.some, save = save)
      createChapter = manager.create(album.id, name = "Kyoto", countries = countryIds, Period.Dates(chapter.period), sourceFolder = "/photos/new")
      results <- Vector.fill(2)(()).parTraverse(_ => createChapter)
      saved   <- state.get
    yield
      assertEquals(results.collect { case _: CreateResult.Created => () }.size, 1)
      assertEquals(results.collect { case CreateResult.Rejected(ChapterConflict.AtCapacity) => () }.size, 1)
      assertEquals(saved.collectSpreads.length, 65)

  test("should report missing metadata and rejected writes without preparing or launching imports"):
    val refs    = Refs()
    val current = album.copy(chapters = Vector(chapter))
    val period  = Period.Dates(chapter.period)
    val reads   = Vector(
      (AlbumRepository.GetResult.NotFound, (CreateResult.AlbumNotFound, UpdateResult.AlbumNotFound)),
      (
        AlbumRepository.GetResult.Failed(AlbumRepository.Failure.DatabaseFailure),
        (CreateResult.Failed, UpdateResult.Failed)
      )
    )
    val writes = Vector(
      (
        ChapterRepository.CreateResult.AlbumNotFound,
        ChapterRepository.UpdateResult.AlbumNotFound,
        (CreateResult.AlbumNotFound, UpdateResult.AlbumNotFound)
      ),
      (ChapterRepository.CreateResult.Failed, ChapterRepository.UpdateResult.Failed, (CreateResult.Failed, UpdateResult.Failed)),
      (
        ChapterRepository.CreateResult.AlbumNotFound,
        ChapterRepository.UpdateResult.ChapterNotFound,
        (CreateResult.AlbumNotFound, UpdateResult.ChapterNotFound)
      ),
      (
        ChapterRepository.CreateResult.UnknownCountries,
        ChapterRepository.UpdateResult.UnknownCountries,
        (CreateResult.Rejected(ChapterConflict.UnknownCountries), UpdateResult.Rejected(ChapterConflict.UnknownCountries))
      )
    )
    val unavailableReads = reads.traverse_ { case (read, expected) =>
      val observations = Refs()

      for
        manager  <- buildManager(observations, maybeRead = read.pure[IO].some)
        created  <- manager.create(album.id, name = "Kyoto", countries = countries.map(_.id), period, sourceFolder = "/photos/new")
        updated  <- manager.update(album.id, chapter.id, name = "Kyoto", countries = countries.map(_.id), period, sourceFolder = "/photos/new")
        prepared <- observations.preparations.get
        launched <- observations.launches.get
      yield
        assertEquals((created, updated), expected)
        assertEquals(prepared, Vector.empty)
        assertEquals(launched, Vector.empty)
    }
    val unavailableWrites = writes.traverse_ { case (creation, update, expected) =>
      val observations = Refs()

      for
        manager  <- buildManager(observations, current = current, createWrite = creation, updateWrite = update)
        created  <- manager.create(album.id, name = "Kyoto", countries = countries.map(_.id), period, sourceFolder = "/photos/new")
        updated  <- manager.update(album.id, chapter.id, name = "Kyoto", countries = countries.map(_.id), period, sourceFolder = "/photos/new")
        prepared <- observations.preparations.get
        launched <- observations.launches.get
      yield
        assertEquals((created, updated), expected)
        assertEquals(prepared, Vector.empty)
        assertEquals(launched, Vector.empty)
    }

    for
      missing     <- buildManager(refs)
      noTemplates <- buildManager(refs, templates = ListChapterStartTemplatesResult.Failed(SpreadTemplateRepository.Failure.DatabaseFailure))
      _           <- unavailableReads
      _           <- unavailableWrites
      absent      <- missing.update(album.id, chapter.id, name = "Kyoto", countries = countries.map(_.id), period, sourceFolder = "/photos/new")
      refreshed   <- missing.refresh(chapter.id)
      failed      <- noTemplates.create(album.id, name = "Kyoto", countries = countries.map(_.id), period, sourceFolder = "/photos/new")
      prepared    <- refs.preparations.get
      launched    <- refs.launches.get
    yield
      assertEquals(absent, UpdateResult.ChapterNotFound)
      assertEquals(failed, CreateResult.Failed)
      assertEquals(refreshed, RefreshResult.ChapterNotFound)
      assertEquals(prepared, Vector.empty)
      assertEquals(launched, Vector.empty)

  test("should preserve saved creation when launch fails and resolve Refresh from the saved source"):
    val error      = new RuntimeException("scheduling failed")
    val countryIds = countries.map(_.id)
    val name       = chapter.destination.name
    val period     = Period.Dates(chapter.period)
    val failures   = Vector(
      (IO.unit, Scheduling.Failed.pure[IO], IO.unit),
      (IO.unit, IO.raiseError[Scheduling](error), IO.unit),
      (IO.raiseError[Unit](error), Scheduling.Queued.pure[IO], IO.unit),
      (IO.unit, Scheduling.Queued.pure[IO], IO.raiseError[Unit](error))
    )

    failures.traverse_ { case (preparation, scheduling, retirement) =>
      val refs    = Refs()
      val listing = PhotoRepository.ListResult.Listed(photos = Vector(photo)).pure[IO]
      val manager = buildManager(
        refs,
        current = album.copy(chapters = Vector(chapter)),
        preparation = preparation,
        scheduling = scheduling,
        retirement = retirement,
        listed = listing
      )

      for
        service   <- manager
        created   <- service.create(album.id, name = "Kyoto", countries = countryIds, period, sourceFolder = "/photos/new")
        refreshed <- service.refresh(chapter.id)
        updated   <- service.update(album.id, chapter.id, name, countries = countryIds, period, sourceFolder = "/photos/changed")
        prepared  <- refs.preparations.get
        retired   <- refs.retirements.get
      yield
        assertEquals(created, CreateResult.Created(ChapterId(buildUuid(index = 10)), Scheduling.Failed))
        assertEquals(refreshed, RefreshResult.Scheduled(Scheduling.Failed))
        assertEquals(updated, UpdateResult.Updated(Scheduling.Failed))
        assertEquals(retired.map(_.location), Vector(photo.thumbnail.location))
        val expectedSources = Vector(
          (ChapterId(buildUuid(index = 10)), "/photos/new": PhotoSource),
          (chapter.id, chapter.sourceFolder),
          (chapter.id, "/photos/changed": PhotoSource)
        )
        assertEquals(prepared, expectedSources)
    }

  test("should fail Refresh explicitly when its authoritative saved source cannot be read"):
    Vector(
      ChapterRepository.SourceResult.Failed.pure[IO],
      IO.raiseError[ChapterRepository.SourceResult](new IllegalStateException("Source lookup failed"))
    ).traverse_ { source =>
      val refs = Refs()

      for
        manager  <- buildManager(refs, maybeSource = source.some)
        result   <- manager.refresh(chapter.id)
        prepared <- refs.preparations.get
      yield
        assertEquals(result, RefreshResult.Failed)
        assertEquals(prepared, Vector.empty[(ChapterId, PhotoSource)])
    }

  test("should save description alone with exact text and explicit outcomes without metadata or import work"):
    val failure = new IllegalStateException("Description write rejected")
    val cases   = Vector(
      ChapterRepository.SaveDescriptionResult.Saved           -> SaveDescriptionResult.Saved,
      ChapterRepository.SaveDescriptionResult.AlbumNotFound   -> SaveDescriptionResult.AlbumNotFound,
      ChapterRepository.SaveDescriptionResult.ChapterNotFound -> SaveDescriptionResult.ChapterNotFound,
      ChapterRepository.SaveDescriptionResult.Failed(failure) -> SaveDescriptionResult.Failed,
      ChapterRepository.SaveDescriptionResult.Uncertain       -> SaveDescriptionResult.Uncertain
    )

    cases.traverse_ { case (response, expected) =>
      val refs           = Refs()
      val expectedErrors = response match
        case ChapterRepository.SaveDescriptionResult.Failed(cause) => Vector(cause, cause)
        case _                                                     => Vector.empty[Throwable]

      for
        manager      <- buildManager(refs, descriptionWrite = response.pure[IO])
        saved        <- manager.saveDescription(album.id, chapter.id, text = "日本\nVacanță")
        cleared      <- manager.saveDescription(album.id, chapter.id, text = "")
        writes       <- refs.descriptions.get
        updates      <- refs.updates.get
        reads        <- refs.photoReads.get
        preparations <- refs.preparations.get
        launches     <- refs.launches.get
        errors       <- refs.errors.get
      yield
        assertEquals(Vector(saved, cleared), Vector(expected, expected))
        assertEquals(writes, Vector((album.id, chapter.id, "日本\nVacanță"), (album.id, chapter.id, "")))
        assertEquals(updates, Vector.empty)
        assertEquals(reads, Vector.empty)
        assertEquals(preparations, Vector.empty)
        assertEquals(launches, Vector.empty)
        assertEquals(errors, expectedErrors)
    }

  test("should report uncertain description acknowledgement without retrying the mutation"):
    val refs                = Refs()
    val lostAcknowledgement = IO.raiseError[ChapterRepository.SaveDescriptionResult](new IllegalStateException("Lost acknowledgement"))

    for
      manager <- buildManager(refs, descriptionWrite = lostAcknowledgement)
      result  <- manager.saveDescription(album.id, chapter.id, text = "Saved?")
      writes  <- refs.descriptions.get
    yield
      assertEquals(result, SaveDescriptionResult.Uncertain)
      assertEquals(writes, Vector((album.id, chapter.id, "Saved?")))

  test("should finish the description transaction handoff before honoring cancellation"):
    TestControl.executeEmbed:
      val refs           = Refs()
      val handoffSignals = (Deferred[IO, Unit], Deferred[IO, Unit], Deferred[IO, Unit], Deferred[IO, Unit]).tupled

      handoffSignals.flatMap { case (entered, release, committed, cancelStarted) =>
        val committing = entered.complete(()).void >> release.get >> committed.complete(()).as(ChapterRepository.SaveDescriptionResult.Saved)

        for
          manager      <- buildManager(refs, descriptionWrite = committing)
          worker       <- manager.saveDescription(album.id, chapter.id, text = "Description").start
          _            <- entered.get
          cancellation <- (cancelStarted.complete(()).void >> worker.cancel).start
          _            <- cancelStarted.get >> IO.cede
          _            <- release.complete(())
          _            <- cancellation.join
          completed    <- committed.tryGet
        yield assertEquals(completed, ().some)
      }

  final private case class Refs():
    val errors: Ref[IO, Vector[Throwable]]                                                                                  = Ref.unsafe(Vector.empty)
    val descriptions: Ref[IO, Vector[(AlbumId, ChapterId, String)]]                                                         = Ref.unsafe(Vector.empty)
    val retirements: Ref[IO, Vector[PhotoWriteIntent.Retirement]]                                                           = Ref.unsafe(Vector.empty)
    val suspensions: Ref[IO, Vector[ChapterId]]                                                                             = Ref.unsafe(Vector.empty)
    val photoReads: Ref[IO, Vector[ChapterId]]                                                                              = Ref.unsafe(Vector.empty)
    val preparations: Ref[IO, Vector[(ChapterId, PhotoSource)]]                                                             = Ref.unsafe(Vector.empty)
    val launches: Ref[IO, Vector[Request]]                                                                                  = Ref.unsafe(Vector.empty)
    val creations: Ref[IO, Vector[(album: AlbumId, chapter: ChapterRepository.NewChapter, order: NonEmptyList[ChapterId])]] = Ref.unsafe(Vector.empty)

    val updates: Ref[
      IO,
      Vector[(
          album: AlbumId,
          chapter: ChapterId,
          name: DestinationName,
          countries: NonEmptyList[CountryId],
          period: VisitPeriod,
          folder: PhotoSource,
          order: NonEmptyList[ChapterId]
      )]
    ] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      current: Album = album,
      maybeRead: Option[IO[AlbumRepository.GetResult]] = None,
      maybeSource: Option[IO[ChapterRepository.SourceResult]] = None,
      createWrite: ChapterRepository.CreateResult = ChapterRepository.CreateResult.Saved,
      updateWrite: ChapterRepository.UpdateResult = ChapterRepository.UpdateResult.Saved,
      descriptionWrite: IO[ChapterRepository.SaveDescriptionResult] = ChapterRepository.SaveDescriptionResult.Saved.pure[IO],
      templates: ListChapterStartTemplatesResult = ListChapterStartTemplatesResult.Listed(NonEmptyList.one(template)),
      preparation: IO[Unit] = IO.unit,
      scheduling: IO[Scheduling] = Scheduling.Queued.pure[IO],
      retirement: IO[Unit] = IO.unit,
      save: ChapterRepository.NewChapter => IO[Unit] = _ => IO.unit,
      update: DestinationId => IO[Unit] = _ => IO.unit,
      listed: IO[PhotoRepository.ListResult] = PhotoRepository.ListResult.Listed(photos = Vector.empty[Photo]).pure[IO],
      recording: IO[PhotoRepository.RecordWriteResult] = PhotoRepository.RecordWriteResult.Recorded.pure[IO],
      removal: IO[PhotoRepository.WriteResult] = PhotoRepository.WriteResult.Applied.pure[IO]
  ) =
    given Logger[IO] = buildLogger(refs)
    given IdGenerator[IO] with
      private val next                  = Ref.unsafe[IO, Long](10L)
      override def generateId: IO[UUID] = next.getAndUpdate(_ + 1L).map(buildUuid)
    val albums = new AlbumRepository[IO]:
      override def saveSynopsis(value: AlbumId, text: String): IO[AlbumRepository.SaveSynopsisResult] =
        IO.raiseError[AlbumRepository.SaveSynopsisResult](new AssertionError("Unexpected synopsis save"))
      override def saveAuthors(value: AlbumId, authors: AlbumAuthors): IO[AlbumRepository.SaveAuthorsResult] =
        IO.raiseError[AlbumRepository.SaveAuthorsResult](new AssertionError("Unexpected authors save"))
      override def get(value: AlbumId): IO[AlbumRepository.GetResult] =
        maybeRead.getOrElse((AlbumRepository.GetResult.Found(current): AlbumRepository.GetResult).pure[IO])
      override def list(offset: Int, limit: Int): IO[AlbumRepository.ListResult] =
        IO.raiseError[AlbumRepository.ListResult](new AssertionError("Unexpected list"))
      override def create(value: Album, cover: Cover): IO[AlbumRepository.CreateResult] =
        IO.raiseError[AlbumRepository.CreateResult](new AssertionError("Unexpected album creation"))
      override def rename(value: AlbumId, title: AlbumTitle): IO[AlbumRepository.RenameResult] =
        IO.raiseError[AlbumRepository.RenameResult](new AssertionError("Unexpected album rename"))
      override def delete(value: AlbumId): IO[AlbumRepository.DeleteResult] =
        IO.raiseError[AlbumRepository.DeleteResult](new AssertionError("Unexpected deletion"))
    val writeUpdate = update
    val repository  = new ChapterRepository.Metadata[IO]:
      override def saveDescription(parent: AlbumId, id: ChapterId, text: String): IO[ChapterRepository.SaveDescriptionResult] =
        refs.descriptions.update(_ :+ (parent, id, text)) >> descriptionWrite
      override def findSource(id: ChapterId): IO[ChapterRepository.SourceResult] =
        val saved =
          current.chapters.find(_.id.value === id.value).fold[ChapterRepository.SourceResult](ChapterRepository.SourceResult.ChapterNotFound):
            value => ChapterRepository.SourceResult.Found(current.id, value.sourceFolder)
        maybeSource.getOrElse(saved.pure[IO])
      override def create(
          parent: AlbumId,
          chapter: ChapterRepository.NewChapter,
          order: NonEmptyList[ChapterId]
      ): IO[ChapterRepository.CreateResult] =
        refs.creations.update(_ :+ (parent, chapter, order)) >> save(chapter).as(createWrite)
      override def update(command: ChapterRepository.Update): IO[ChapterRepository.UpdateResult] = command match
        case ChapterRepository.Update(parent, chapter, destination, name, countries, period, sourceFolder, order) =>
          refs.updates.update(_ :+ (parent, chapter, name, countries, period, sourceFolder, order)) >> writeUpdate(destination).as(updateWrite)
    val catalog = new SpreadTemplateRepository[IO]:
      override def listPhotosOnlyTemplates: IO[SpreadTemplateRepository.ListPhotosOnlyTemplatesResult] =
        IO.raiseError[SpreadTemplateRepository.ListPhotosOnlyTemplatesResult](new AssertionError("Unexpected photo templates"))
      override def findTemplate(id: SpreadTemplateId): IO[SpreadTemplateRepository.FindTemplateResult] =
        IO.raiseError[SpreadTemplateRepository.FindTemplateResult](new AssertionError("Unexpected template lookup"))
      override def listAlbumStartTemplates: IO[SpreadTemplateRepository.ListAlbumStartTemplatesResult] =
        IO.raiseError[SpreadTemplateRepository.ListAlbumStartTemplatesResult](new AssertionError("Unexpected album templates"))
      override def listChapterStartTemplates: IO[ListChapterStartTemplatesResult] = templates.pure[IO]
    Mutex[IO].map: mutex =>
      given Mutex[IO] = mutex
      given Transaction[IO, IO] with
        override def run[A](program: IO[A]): IO[A]     = program
        override def resolve[A](program: IO[A]): IO[A] = program
      val imports = new ChapterPhotoImport[IO]:
        override def prepare(parent: AlbumId, id: ChapterId, source: PhotoSource): IO[Request] =
          IO(assertEquals(parent, current.id)) >> refs.preparations.update(_ :+ (id, source)) >>
            preparation.as(Request(parent, id, source, generation = BigInt(0)))
        override def launch(request: Request): IO[Scheduling] =
          mutex.lock.surround(refs.launches.update(_ :+ request) >> scheduling)
        override def retire(parent: AlbumId, retirements: Vector[PhotoWriteIntent.Retirement]): IO[Unit] =
          refs.retirements.update(_ ++ retirements) >> retirement
        override def suspend(id: ChapterId): IO[Unit]            = refs.suspensions.update(_ :+ id)
        override def status(id: ChapterId): IO[ExecutionOutcome] =
          ExecutionOutcome(Status.Idle, retirementIssues = Vector.empty[PhotoWriteRecovery.Issue]).pure[IO]
      val photoRepository = new PhotoRepository[IO]:
        override def saveCaption(id: PhotoId, text: String): IO[PhotoRepository.SaveCaptionResult] =
          IO.raiseError[PhotoRepository.SaveCaptionResult](new AssertionError("Unexpected caption save"))
        override def listChapter(id: ChapterId): IO[PhotoRepository.ListResult] =
          refs.photoReads.update(_ :+ id) >> listed
        override def save(photo: Photo): IO[PhotoRepository.WriteResult] =
          IO.raiseError[PhotoRepository.WriteResult](new AssertionError("Unexpected photo save"))
        override def remove(id: PhotoId): IO[PhotoRepository.WriteResult]                         = removal
        override def recordWrite(intent: PhotoWriteIntent): IO[PhotoRepository.RecordWriteResult] =
          recording
        override def completeWrite(id: PhotoWriteIntentId): IO[PhotoRepository.CompleteWriteResult] =
          IO.raiseError[PhotoRepository.CompleteWriteResult](new AssertionError("Unexpected completion"))
        override def listUnfinishedWrites: IO[PhotoRepository.ListUnfinishedWritesResult] =
          IO.raiseError[PhotoRepository.ListUnfinishedWritesResult](new AssertionError("Unexpected global listing"))
      ChapterMetadataManager.make(albums, repository, ChapterPlanning.make(catalog), imports, photoRepository)
  // scalafix:on DisableSyntax.defaultArgs

  private def buildUuid(index: Long) = new UUID(0L, index)

  private def buildLogger(refs: Refs) =
    new Logger[IO]:
      override def error(cause: Throwable)(message: => String): IO[Unit] = refs.errors.update(_ :+ cause) >> logger.error(cause)(message)
      override def warn(cause: Throwable)(message: => String): IO[Unit]  = logger.warn(cause)(message)
      override def info(cause: Throwable)(message: => String): IO[Unit]  = logger.info(cause)(message)
      override def debug(cause: Throwable)(message: => String): IO[Unit] = logger.debug(cause)(message)
      override def trace(cause: Throwable)(message: => String): IO[Unit] = logger.trace(cause)(message)
      override def error(message: => String): IO[Unit]                   = logger.error(message)
      override def warn(message: => String): IO[Unit]                    = logger.warn(message)
      override def info(message: => String): IO[Unit]                    = logger.info(message)
      override def debug(message: => String): IO[Unit]                   = logger.debug(message)
      override def trace(message: => String): IO[Unit]                   = logger.trace(message)
