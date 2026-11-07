package photography.usecases.chapter
import photography.usecases.photo.ChapterPhotoImport
import photography.usecases.photo.PhotoWriteRecovery

import cats.data.NonEmptyList
import cats.{Eq, Id}
import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.album.AlbumRepository
import photography.ports.album.AlbumRepository.{ChapterMetadata, PlanningSnapshot}
import photography.ports.chapter.ChapterRepository
import photography.ports.photo.PhotoRepository
import photography.usecases.chapter.ChapterMetadataManager.{CreateResult, Period, RefreshResult, SaveDescriptionResult, UpdateResult}
import photography.usecases.photo.ChapterPhotoImport.{ExecutionOutcome, Request, Scheduling, Status}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

final class ChapterMetadataManagerComponentTest extends CatsEffectSuite:
  private given Eq[ChapterMetadata] = Eq.fromUniversalEquals
  private given Eq[Period]          = Eq.fromUniversalEquals
  private val logger                = Slf4jLogger.getLogger[IO]
  private val countries             = NonEmptyList.of(
    Country(CountryId(buildUuid(index = 21)), code = "JP", name = "Japan", AbsoluteLocation(Path.of("/flags/jp.svg"))),
    Country(CountryId(buildUuid(index = 22)), code = "KR", name = "South Korea", AbsoluteLocation(Path.of("/flags/kr.svg"))),
    Country(CountryId(buildUuid(index = 23)), code = "TW", name = "Taiwan", AbsoluteLocation(Path.of("/flags/tw.svg"))),
    Country(CountryId(buildUuid(index = 24)), code = "SG", name = "Singapore", AbsoluteLocation(Path.of("/flags/sg.svg")))
  )
  private val box     = SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0)
  private val albumId = AlbumId(buildUuid(index = 1))
  private val album   = PlanningSnapshot(year = 2026, chapters = Vector.empty[ChapterMetadata], spreadCount = 1)
  private val chapter =
    ChapterMetadata(
      ChapterId(buildUuid(index = 20)),
      Destination(name = "Asian capitals", countries = countries),
      VisitPeriod(firstDay = LocalDate.of(2026, 5, 1), days = 2),
      sourceFolder = "/photos/capitals",
      description = ""
    )
  private val newChapter =
    val opening = Spread.ChapterStart(
      SpreadId(buildUuid(index = 11)),
      SpreadTemplateId(buildUuid(index = 4)),
      photoSlots = Vector.empty[SpreadSlot.Photo],
      SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 13)), box),
      placements = Vector.empty[SpreadPlacement]
    )
    ChapterRepository.NewChapter(
      ChapterId(buildUuid(index = 10)),
      name = "Kyoto",
      countries.map(_.id),
      chapter.period,
      description = "",
      sourceFolder = "/photos/new",
      opening
    )
  private val updatePlan = ChapterPlanning.UpdatePlan(chapter.sourceFolder, chapter.period, order = NonEmptyList.one(chapter.id))
  private val photo      =
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
        result     <- manager.update(albumId, chapter.id, name, countries = countryIds, visit, source)
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
    Vector("/photos/changed": PhotoSource, chapter.sourceFolder).traverse_ { source =>
      Vector("committed", "rolled-back", "unreadable", "missing", "unknown", "absent-chapter", "exception").traverse_ { resolution =>
        val refs    = Refs()
        val current = album.copy(chapters = Vector(chapter))
        val error   = new IllegalStateException("Lost commit acknowledgement")
        val state   = Ref.unsafe[IO, IO[AlbumRepository.PlanningResult]](AlbumRepository.PlanningResult.Found(current).pure[IO])
        val update  = (_: ChapterRepository.Update) =>
          val saved    = chapter.copy(destination = chapter.destination.copy(name = "Renamed"), sourceFolder = source)
          val response = resolution match
            case "committed"      => AlbumRepository.PlanningResult.Found(current.copy(chapters = Vector(saved))).pure[IO]
            case "rolled-back"    => AlbumRepository.PlanningResult.Found(current).pure[IO]
            case "unreadable"     => AlbumRepository.PlanningResult.Failed(AlbumRepository.Failure.DatabaseFailure).pure[IO]
            case "missing"        => AlbumRepository.PlanningResult.NotFound.pure[IO]
            case "absent-chapter" =>
              AlbumRepository.PlanningResult.Found(current.copy(chapters = Vector.empty[ChapterMetadata])).pure[IO]
            case "unknown" =>
              val unexpected = chapter.copy(sourceFolder = "/photos/elsewhere")
              AlbumRepository.PlanningResult.Found(current.copy(chapters = Vector(unexpected))).pure[IO]
            case _ => IO.raiseError[AlbumRepository.PlanningResult](error)
          state.set(response) >> IO.raiseError[Unit](error)
        val sourceChanged         = (source: String) =!= chapter.sourceFolder
        val scheduling            = if sourceChanged then Scheduling.Queued else Scheduling.NotRequested
        val expected              = if resolution === "committed" then UpdateResult.Updated(scheduling) else UpdateResult.Failed
        val suspends              = sourceChanged && resolution =!= "committed" && resolution =!= "rolled-back"
        val name: DestinationName = "Renamed"
        val countryIds            = countries.map(_.id)
        val visit                 = Period.Dates(chapter.period)

        for
          manager   <- buildManager(refs, maybeRead = state.get.flatten.some, update = update)
          result    <- manager.update(albumId, chapter.id, name, countries = countryIds, visit, source)
          suspended <- refs.suspensions.get
          prepared  <- refs.preparations.get
        yield
          assertEquals(result, expected)
          assertEquals(suspended, if suspends then Vector(chapter.id) else Vector.empty[ChapterId])
          assertEquals(prepared.size, if resolution === "committed" && sourceChanged then 1 else 0)
      }
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
        created <- manager.create(albumId, name = "Kyoto", countries = countryIds, visit, sourceFolder = "/photos/new")
        updated <- manager.update(albumId, chapter.id, name, countries = countryIds, visit, changedSource)
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
        val update                     = (_: ChapterRepository.Update) => if cancelAtCommit then handoff else IO.unit
        val scheduling                 = (if cancelAtCommit then IO.unit else handoff).as(Scheduling.Queued)
        val program                    =
          for
            manager      <- buildManager(refs, current = current, listed = photoListing, update = update, scheduling = scheduling)
            worker       <- manager.update(albumId, chapter.id, name, countries = countryIds, visit, changedSource).flatTap(_ => IO.cede).start
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

  test("should pass saved facts and requested metadata to planning, commit its complete plan, then launch the import"):
    val refs       = Refs()
    val order      = NonEmptyList.of(newChapter.id, chapter.id)
    val current    = album.copy(chapters = Vector(chapter))
    val countryIds = countries.map(_.id)
    val period     = Period.Dates(chapter.period)
    val planning   = buildPlanning(creation = (saved, name, ids, visit, folder) =>
      val matches = (name: String) === newChapter.name && ids.map(_.value) === countryIds.map(_.value) &&
        visit === period && (folder: String) === newChapter.sourceFolder
      val result = if matches then ChapterPlanning.CreationResult.Planned(newChapter, order) else ChapterPlanning.CreationResult.Failed
      refs.plans.update(_ :+ saved).as(result))
    val save = (_: ChapterRepository.NewChapter) => refs.preparations.get.flatMap(refs.preparedBeforeCommit.set)

    for
      manager  <- buildManager(refs, current = current, planning = planning, save = save)
      result   <- manager.create(albumId, newChapter.name, countries = countryIds, period, newChapter.sourceFolder)
      plans    <- refs.plans.get
      writes   <- refs.creations.get
      before   <- refs.preparedBeforeCommit.get
      launched <- refs.launches.get
    yield
      assertEquals(result, CreateResult.Created(newChapter.id, Scheduling.Queued))
      assertEquals(plans, Vector(current))
      assertEquals(writes, Vector((albumId, newChapter, order)))
      assertEquals(before, Vector.empty)
      assertEquals(launched, Vector(Request(albumId, newChapter.id, newChapter.sourceFolder, generation = BigInt(0))))

  test("should persist the planner's resolved edit and order with the requested countries and source"):
    val refs       = Refs()
    val current    = album.copy(chapters = Vector(chapter.copy(id = newChapter.id, sourceFolder = newChapter.sourceFolder), chapter))
    val countryIds = NonEmptyList.one(countries.head.id)
    val resolved   = VisitPeriod(firstDay = LocalDate.of(2026, 1, 1), days = 365)
    val order      = NonEmptyList.of(chapter.id, newChapter.id)
    val plan       = updatePlan.copy(period = resolved, order = order)
    val planning   = buildPlanning(update = (year, chapters, id, name, period, folder) =>
      val matches = (year: Int) === current.year && chapters === current.chapters && id.value === chapter.id.value &&
        (name: String) === chapter.destination.name && period === Period.AlbumYear &&
        (folder: String) === chapter.sourceFolder
      if matches then ChapterPlanning.UpdateResult.Planned(plan)
      else ChapterPlanning.UpdateResult.ChapterNotFound)
    val expected = ChapterRepository.Update(albumId, chapter.id, chapter.destination.name, countryIds, resolved, chapter.sourceFolder, order)

    for
      manager <- buildManager(refs, current = current, planning = planning)
      result  <- manager.update(albumId, chapter.id, chapter.destination.name, countryIds, Period.AlbumYear, chapter.sourceFolder)
      writes  <- refs.updates.get
    yield
      assertEquals(result, UpdateResult.Updated(Scheduling.NotRequested))
      assertEquals(writes, Vector(expected))

  test("should stop rejected and unavailable plans before photo reads, persistence or import preparation"):
    val conflict  = ChapterConflict.FolderAssigned
    val scenarios = Vector(
      (
        creation = ChapterPlanning.CreationResult.Rejected(conflict),
        update = ChapterPlanning.UpdateResult.Rejected(conflict),
        expected = (created = CreateResult.Rejected(conflict), updated = UpdateResult.Rejected(conflict))
      ),
      (
        creation = ChapterPlanning.CreationResult.Failed,
        update = ChapterPlanning.UpdateResult.ChapterNotFound,
        expected = (created = CreateResult.Failed, updated = UpdateResult.ChapterNotFound)
      )
    )
    scenarios.traverse_ { case (creation, update, expected) =>
      val refs     = Refs()
      val planning = buildPlanning(
        creation = (_, _, _, _, _) => creation.pure[IO],
        update = (_, _, _, _, _, _) => update
      )

      for
        manager  <- buildManager(refs, planning = planning)
        created  <- manager.create(albumId, chapter.destination.name, countries.map(_.id), Period.AlbumYear, chapter.sourceFolder)
        updated  <- manager.update(albumId, chapter.id, chapter.destination.name, countries.map(_.id), Period.AlbumYear, chapter.sourceFolder)
        creates  <- refs.creations.get
        updates  <- refs.updates.get
        reads    <- refs.photoReads.get
        prepared <- refs.preparations.get
      yield
        assertEquals((created = created, updated = updated), expected)
        assertEquals(creates, Vector.empty)
        assertEquals(updates, Vector.empty)
        assertEquals(reads, Vector.empty)
        assertEquals(prepared, Vector.empty)
    }

  test("should serialize concurrent creations through acquisition, planning and commit"):
    val refs      = Refs()
    val committed = album.copy(chapters = Vector(chapter.copy(id = newChapter.id, sourceFolder = newChapter.sourceFolder)), spreadCount = 2)
    val state     = Ref.unsafe[IO, PlanningSnapshot](album)
    val read      = state.get.flatMap(saved => IO.cede.as(AlbumRepository.PlanningResult.Found(saved)))
    val responses = Map(
      album     -> ChapterPlanning.CreationResult.Planned(newChapter, NonEmptyList.one(newChapter.id)),
      committed -> ChapterPlanning.CreationResult.Rejected(ChapterConflict.FolderAssigned)
    )
    val planning = buildPlanning(creation =
      (saved, _, _, _, _) =>
        refs.plans.update(_ :+ saved).as(responses.getOrElse(saved, ChapterPlanning.CreationResult.Failed))
    )

    for
      manager <- buildManager(refs, maybeRead = read.some, planning = planning, save = _ => state.set(committed))
      results <- Vector.fill(2)(()).parTraverse(_ =>
        manager.create(albumId, newChapter.name, countries.map(_.id), Period.Dates(chapter.period), newChapter.sourceFolder)
      )
      plans  <- refs.plans.get
      writes <- refs.creations.get
    yield
      assertEquals(results.toSet, Set(CreateResult.Created(newChapter.id, Scheduling.Queued), CreateResult.Rejected(ChapterConflict.FolderAssigned)))
      assertEquals(plans, Vector(album, committed))
      assertEquals(writes, Vector((albumId, newChapter, NonEmptyList.one(newChapter.id))))

  test("should report missing metadata and rejected writes without preparing or launching imports"):
    val current          = album.copy(chapters = Vector(chapter))
    val period           = Period.Dates(chapter.period)
    val found            = AlbumRepository.PlanningResult.Found(current)
    val saved            = (creation = ChapterRepository.CreateResult.Saved, update = ChapterRepository.UpdateResult.Saved)
    val missingAlbum     = (created = CreateResult.AlbumNotFound, updated = UpdateResult.AlbumNotFound)
    val failed           = (created = CreateResult.Failed, updated = UpdateResult.Failed)
    val absentWrites     = (creation = ChapterRepository.CreateResult.AlbumNotFound, update = ChapterRepository.UpdateResult.AlbumNotFound)
    val failedWrites     = (creation = ChapterRepository.CreateResult.Failed, update = ChapterRepository.UpdateResult.Failed)
    val unknownCountries = (creation = ChapterRepository.CreateResult.UnknownCountries, update = ChapterRepository.UpdateResult.UnknownCountries)
    val rejected         =
      (created = CreateResult.Rejected(ChapterConflict.UnknownCountries), updated = UpdateResult.Rejected(ChapterConflict.UnknownCountries))
    val scenarios = Vector(
      (read = AlbumRepository.PlanningResult.NotFound, writes = saved, expected = missingAlbum),
      (read = AlbumRepository.PlanningResult.Failed(AlbumRepository.Failure.DatabaseFailure), writes = saved, expected = failed),
      (read = found, writes = absentWrites, expected = missingAlbum),
      (read = found, writes = failedWrites, expected = failed),
      (
        read = found,
        writes = (creation = absentWrites.creation, update = ChapterRepository.UpdateResult.ChapterNotFound),
        expected = (created = CreateResult.AlbumNotFound, updated = UpdateResult.ChapterNotFound)
      ),
      (read = found, writes = unknownCountries, expected = rejected)
    )
    scenarios.traverse_ { case (read, writes, expected) =>
      val observations = Refs()

      for
        manager  <- buildManager(observations, maybeRead = read.pure[IO].some, createWrite = writes.creation, updateWrite = writes.update)
        created  <- manager.create(albumId, name = "Kyoto", countries = countries.map(_.id), period, sourceFolder = "/photos/new")
        updated  <- manager.update(albumId, chapter.id, name = "Kyoto", countries = countries.map(_.id), period, sourceFolder = "/photos/new")
        prepared <- observations.preparations.get
        launched <- observations.launches.get
      yield
        assertEquals((created = created, updated = updated), expected)
        assertEquals(prepared, Vector.empty)
        assertEquals(launched, Vector.empty)
    }

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
        created   <- service.create(albumId, name = "Kyoto", countries = countryIds, period, sourceFolder = "/photos/new")
        refreshed <- service.refresh(chapter.id)
        updated   <- service.update(albumId, chapter.id, name, countries = countryIds, period, sourceFolder = "/photos/changed")
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

  test("should report missing and unavailable saved sources without preparing an import"):
    Vector(
      (source = ChapterRepository.SourceResult.ChapterNotFound.pure[IO], expected = RefreshResult.ChapterNotFound),
      (source = ChapterRepository.SourceResult.Failed.pure[IO], expected = RefreshResult.Failed),
      (source = IO.raiseError[ChapterRepository.SourceResult](new IllegalStateException("Source lookup failed")), expected = RefreshResult.Failed)
    ).traverse_ { case (source, expected) =>
      val refs = Refs()

      for
        manager  <- buildManager(refs, maybeSource = source.some)
        result   <- manager.refresh(chapter.id)
        prepared <- refs.preparations.get
      yield
        assertEquals(result, expected)
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
        saved        <- manager.saveDescription(albumId, chapter.id, text = "日本\nVacanță")
        cleared      <- manager.saveDescription(albumId, chapter.id, text = "")
        writes       <- refs.descriptions.get
        updates      <- refs.updates.get
        reads        <- refs.photoReads.get
        preparations <- refs.preparations.get
        launches     <- refs.launches.get
        errors       <- refs.errors.get
      yield
        assertEquals(Vector(saved, cleared), Vector(expected, expected))
        assertEquals(writes, Vector((albumId, chapter.id, "日本\nVacanță"), (albumId, chapter.id, "")))
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
      result  <- manager.saveDescription(albumId, chapter.id, text = "Saved?")
      writes  <- refs.descriptions.get
    yield
      assertEquals(result, SaveDescriptionResult.Uncertain)
      assertEquals(writes, Vector((albumId, chapter.id, "Saved?")))

  test("should save descriptions within authoritative mutation coordination without reacquiring its mutex"):
    TestControl.executeEmbed:
      val refs = Refs()

      for
        mutex   <- Mutex[IO]
        manager <- buildManager(refs, coordination = mutex.pure[IO])
        result  <- mutex.lock.surround(manager.saveDescription(albumId, chapter.id, text = "Protected description"))
        writes  <- refs.descriptions.get
      yield
        assertEquals(result, SaveDescriptionResult.Saved)
        assertEquals(writes, Vector((albumId, chapter.id, "Protected description")))

  test("should finish the description transaction handoff before honoring cancellation"):
    TestControl.executeEmbed:
      val refs           = Refs()
      val handoffSignals = (Deferred[IO, Unit], Deferred[IO, Unit], Deferred[IO, Unit], Deferred[IO, Unit]).tupled

      handoffSignals.flatMap { case (entered, release, committed, cancelStarted) =>
        val committing = entered.complete(()).void >> release.get >> committed.complete(()).as(ChapterRepository.SaveDescriptionResult.Saved)

        for
          manager      <- buildManager(refs, descriptionWrite = committing)
          worker       <- manager.saveDescription(albumId, chapter.id, text = "Description").start
          _            <- entered.get
          cancellation <- (cancelStarted.complete(()).void >> worker.cancel).start
          _            <- cancelStarted.get >> IO.cede
          _            <- release.complete(())
          _            <- cancellation.join
          completed    <- committed.tryGet
        yield assertEquals(completed, ().some)
      }

  final private case class Refs():
    val plans: Ref[IO, Vector[PlanningSnapshot]]                                                                            = Ref.unsafe(Vector.empty)
    val preparedBeforeCommit: Ref[IO, Vector[(ChapterId, PhotoSource)]]                                                     = Ref.unsafe(Vector.empty)
    val errors: Ref[IO, Vector[Throwable]]                                                                                  = Ref.unsafe(Vector.empty)
    val descriptions: Ref[IO, Vector[(AlbumId, ChapterId, String)]]                                                         = Ref.unsafe(Vector.empty)
    val retirements: Ref[IO, Vector[PhotoWriteIntent.Retirement]]                                                           = Ref.unsafe(Vector.empty)
    val suspensions: Ref[IO, Vector[ChapterId]]                                                                             = Ref.unsafe(Vector.empty)
    val photoReads: Ref[IO, Vector[ChapterId]]                                                                              = Ref.unsafe(Vector.empty)
    val preparations: Ref[IO, Vector[(ChapterId, PhotoSource)]]                                                             = Ref.unsafe(Vector.empty)
    val launches: Ref[IO, Vector[Request]]                                                                                  = Ref.unsafe(Vector.empty)
    val creations: Ref[IO, Vector[(album: AlbumId, chapter: ChapterRepository.NewChapter, order: NonEmptyList[ChapterId])]] = Ref.unsafe(Vector.empty)

    val updates: Ref[IO, Vector[ChapterRepository.Update]] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      current: PlanningSnapshot = album,
      maybeRead: Option[IO[AlbumRepository.PlanningResult]] = None,
      maybeSource: Option[IO[ChapterRepository.SourceResult]] = None,
      createWrite: ChapterRepository.CreateResult = ChapterRepository.CreateResult.Saved,
      updateWrite: ChapterRepository.UpdateResult = ChapterRepository.UpdateResult.Saved,
      descriptionWrite: IO[ChapterRepository.SaveDescriptionResult] = ChapterRepository.SaveDescriptionResult.Saved.pure[IO],
      planning: ChapterPlanning[IO] = buildPlanning(),
      preparation: IO[Unit] = IO.unit,
      scheduling: IO[Scheduling] = Scheduling.Queued.pure[IO],
      retirement: IO[Unit] = IO.unit,
      save: ChapterRepository.NewChapter => IO[Unit] = _ => IO.unit,
      update: ChapterRepository.Update => IO[Unit] = _ => IO.unit,
      listed: IO[PhotoRepository.ListResult] = PhotoRepository.ListResult.Listed(photos = Vector.empty[Photo]).pure[IO],
      recording: IO[PhotoRepository.RecordWriteResult] = PhotoRepository.RecordWriteResult.Recorded.pure[IO],
      removal: IO[PhotoRepository.WriteResult] = PhotoRepository.WriteResult.Applied.pure[IO],
      coordination: IO[Mutex[IO]] = Mutex[IO]
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
      override def readPlanning(value: AlbumId): IO[AlbumRepository.PlanningResult] =
        if value.value === albumId.value then
          maybeRead.getOrElse((AlbumRepository.PlanningResult.Found(current): AlbumRepository.PlanningResult).pure[IO])
        else AlbumRepository.PlanningResult.NotFound.pure[IO]
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
      override def saveDescription(
          parent: AlbumId,
          id: ChapterId,
          text: String
      ): IO[ChapterRepository.SaveDescriptionResult] =
        refs.descriptions.update(_ :+ (parent, id, text)) >> descriptionWrite
      override def findSource(id: ChapterId): IO[ChapterRepository.SourceResult] =
        val saved =
          current.chapters.find(_.id.value === id.value).fold[ChapterRepository.SourceResult](ChapterRepository.SourceResult.ChapterNotFound):
            value => ChapterRepository.SourceResult.Found(albumId, value.sourceFolder)
        maybeSource.getOrElse(saved.pure[IO])
      override def create(
          parent: AlbumId,
          chapter: ChapterRepository.NewChapter,
          order: NonEmptyList[ChapterId]
      ): IO[ChapterRepository.CreateResult] =
        refs.creations.update(_ :+ (parent, chapter, order)) >> save(chapter).as(createWrite)
      override def update(command: ChapterRepository.Update): IO[ChapterRepository.UpdateResult] =
        refs.updates.update(_ :+ command) >> writeUpdate(command).as(updateWrite)
    coordination.map: mutex =>
      given Mutex[IO] = mutex
      given Transaction[IO, IO] with
        override def run[A](program: IO[A]): IO[A]     = program
        override def resolve[A](program: IO[A]): IO[A] = program
      val imports = new ChapterPhotoImport[IO]:
        override def prepare(parent: AlbumId, id: ChapterId, source: PhotoSource): IO[Request] =
          refs.preparations.update(_ :+ (id, source)) >>
            preparation.as(Request(parent, id, source, generation = BigInt(0)))
        override def launch(request: Request): IO[Scheduling] =
          mutex.lock.surround(refs.launches.update(_ :+ request) >> scheduling)
        override def retire(parent: AlbumId, retirements: Vector[PhotoWriteIntent.Retirement]): IO[Unit] =
          refs.retirements.update(_ ++ retirements) >> retirement
        override def suspend(id: ChapterId): IO[Unit]            = refs.suspensions.update(_ :+ id)
        override def status(id: ChapterId): IO[ExecutionOutcome] =
          ExecutionOutcome(Status.Idle, retirementIssues = Vector.empty[PhotoWriteRecovery.Issue]).pure[IO]
      val photoRepository = new PhotoRepository[IO]:
        override def find(photo: PhotoId): IO[PhotoRepository.FindResult] =
          IO.raiseError[PhotoRepository.FindResult](new AssertionError("Unexpected photo read"))
        override def saveCaption(
            chapter: ChapterId,
            id: PhotoId,
            text: String
        ): IO[PhotoRepository.SaveCaptionResult] =
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
      ChapterMetadataManager.make(albums, repository, planning, imports, photoRepository)

  private def buildPlanning(
      creation: (PlanningSnapshot, DestinationName, NonEmptyList[CountryId], Period, PhotoSource) => IO[ChapterPlanning.CreationResult] =
        (_, _, _, _, _) => ChapterPlanning.CreationResult.Planned(newChapter, NonEmptyList.one(newChapter.id)).pure[IO],
      update: (AlbumYear, Vector[ChapterMetadata], ChapterId, DestinationName, Period, PhotoSource) => ChapterPlanning.UpdateResult =
        (_, _, _, _, _, _) => ChapterPlanning.UpdateResult.Planned(updatePlan)
  ) =
    new ChapterPlanning[IO]:
      override def planCreation(
          saved: PlanningSnapshot,
          name: DestinationName,
          countries: NonEmptyList[CountryId],
          period: Period,
          folder: PhotoSource
      ): IO[ChapterPlanning.CreationResult] = creation(saved, name, countries, period, folder)
      override def planUpdate(
          year: AlbumYear,
          chapters: Vector[ChapterMetadata],
          id: ChapterId,
          name: DestinationName,
          period: Period,
          folder: PhotoSource
      ): ChapterPlanning.UpdateResult = update(year, chapters, id, name, period, folder)
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
