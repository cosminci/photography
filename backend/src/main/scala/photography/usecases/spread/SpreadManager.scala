package photography.usecases.spread

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import org.typelevel.log4cats.Logger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.{AlbumId, ChapterId, PhotoId, InteriorSpreadCapacity}
import photography.domain.layout.*
import photography.ports.album.AlbumCompositionRepository
import photography.ports.spread.SpreadRepository
import photography.ports.photo.PhotoPlacementRepository
import photography.ports.album.AlbumCompositionRepository.Located
import photography.syntax.EitherSyntax.*
import photography.syntax.EitherSyntax.Pure.*
import photography.usecases.spread.SpreadManager.*
import photography.ports.spread.SpreadTemplateRepository
import photography.ports.spread.SpreadTemplateRepository.FindTemplateResult

trait SpreadManager[F[_]]:
  def replaceChapterOrder(album: AlbumId, chapter: ChapterId, expected: NonEmptyList[SpreadId], order: NonEmptyList[SpreadId]): F[ReplacementResult]
  def add(album: AlbumId, chapter: ChapterId, after: SpreadId): F[AddResult]
  def changeTemplate(album: AlbumId, spread: SpreadId, template: SpreadTemplateId, confirmPlacements: Boolean): F[ChangeTemplateResult]
  def delete(album: AlbumId, spread: SpreadId, confirmPlacements: Boolean): F[DeleteResult]
  def readPlacements(album: AlbumId, spread: SpreadId): F[PlacementReadResult]
  def editPlacements(album: AlbumId, spread: SpreadId, request: Edit): F[PlacementResult]

object SpreadManager:
  def make[F[_]: MonadCancelThrow: IdGenerator: Mutex: Logger, Db[_]: MonadThrow](
      templates: SpreadTemplateRepository[F],
      composition: AlbumCompositionRepository[Db],
      spreads: SpreadRepository[Db],
      placements: PhotoPlacementRepository[Db],
      defaultPhotoLayout: SpreadTemplateId
  )(using Transaction[Db, F]): SpreadManager[F] =
    new LiveSpreadManager(templates, composition, spreads, placements, defaultPhotoLayout)

  final private class LiveSpreadManager[F[_]: MonadCancelThrow: IdGenerator: Mutex: Logger, Db[_]: MonadThrow](
      templates: SpreadTemplateRepository[F],
      composition: AlbumCompositionRepository[Db],
      spreads: SpreadRepository[Db],
      placements: PhotoPlacementRepository[Db],
      defaultPhotoLayout: SpreadTemplateId
  )(using Transaction[Db, F]) extends SpreadManager[F]:
    override def replaceChapterOrder(
        album: AlbumId,
        chapter: ChapterId,
        expected: NonEmptyList[SpreadId],
        order: NonEmptyList[SpreadId]
    ): F[ReplacementResult] =
      if !isReplacement(expected, order) then ReplacementResult.Invalid.pure[F]
      else summon[Mutex[F]].lock.surround(MonadCancelThrow[F].uncancelable(_ => replaceCurrent(album, chapter, expected, order)))

    override def add(album: AlbumId, chapter: ChapterId, after: SpreadId): F[AddResult] =
      act[AddResult](AddResult.Failed):
        templates.findTemplate(defaultPhotoLayout).flatMap[AddResult]:
          case FindTemplateResult.Found(layout: SpreadTemplate.PhotosOnly) =>
            buildPhotoSpread(layout).flatMap: fresh =>
              val mutation =
                for
                  _        <- composition.hasAlbum(album).map(Either.cond(_, (), AddResult.AlbumNotFound: AddResult)).eitherT
                  order    <- composition.findChapterOrder(album, chapter).orFail(ifEmpty = AddResult.ChapterNotFound: AddResult)
                  target   <- composition.findSpread(album, after).orFail(ifEmpty = AddResult.SpreadNotFound: AddResult)
                  _        <- (!isAlbumOpening(target)).ensure[Db, AddResult](ifFalse = AddResult.Rejected(Conflict.AddTarget))
                  inserted <- insertAfter(order = order, after, fresh.id).orFail[Db, AddResult](ifEmpty = AddResult.SpreadNotFound)
                  count    <- composition.countInteriorSpreads(album).lift[AddResult]
                  _        <- (!InteriorSpreadCapacity.isReached(count)).ensure[Db, AddResult](ifFalse = AddResult.Rejected(Conflict.AtCapacity))
                  _        <- (spreads.insert(chapter, fresh) >> spreads.reorder(chapter, order = inserted)).lift[AddResult]
                yield AddResult.Added(fresh.id): AddResult
              Transaction[Db, F].run(mutation.merge)
          case _ => AddResult.Failed.pure[F]

    override def changeTemplate(
        album: AlbumId,
        spread: SpreadId,
        template: SpreadTemplateId,
        confirmPlacements: Boolean
    ): F[ChangeTemplateResult] =
      act[ChangeTemplateResult](ChangeTemplateResult.Failed):
        templates.findTemplate(template).flatMap[ChangeTemplateResult]:
          case FindTemplateResult.Found(layout) =>
            buildReplacement(layout).flatMap: replace =>
              val mutation =
                for
                  _      <- composition.hasAlbum(album).map(Either.cond(_, (), ChangeTemplateResult.AlbumNotFound: ChangeTemplateResult)).eitherT
                  target <- composition.findSpread(album, spread).orFail(ifEmpty = ChangeTemplateResult.SpreadNotFound: ChangeTemplateResult)
                  fresh  <- replace(target.spread).orFail[Db, ChangeTemplateResult](ifEmpty = ChangeTemplateResult.Rejected(Conflict.ContextMismatch))
                  count = target.spread.placements.size
                  _ <- checkConfirmation(count = count, confirmed = confirmPlacements).leftMap(ChangeTemplateResult.Occupied(_)).eitherT[Db]
                  _ <- spreads.replace(fresh).lift[ChangeTemplateResult]
                yield ChangeTemplateResult.Changed(spread, template): ChangeTemplateResult
              Transaction[Db, F].run(mutation.merge)
          case FindTemplateResult.NotFound  => (ChangeTemplateResult.Rejected(Conflict.UnknownTemplate): ChangeTemplateResult).pure[F]
          case FindTemplateResult.Failed(_) => (ChangeTemplateResult.Failed: ChangeTemplateResult).pure[F]

    override def delete(album: AlbumId, spread: SpreadId, confirmPlacements: Boolean): F[DeleteResult] =
      val mutation =
        for
          _      <- composition.hasAlbum(album).map(Either.cond(_, (), DeleteResult.AlbumNotFound: DeleteResult)).eitherT
          target <- composition.findSpread(album, spread).orFail(ifEmpty = DeleteResult.SpreadNotFound: DeleteResult)
          photo  <- findPhoto(target).orFail[Db, DeleteResult](ifEmpty = DeleteResult.Rejected(Conflict.OpeningDelete))
          count = photo.value.placements.size
          _     <- checkConfirmation(count = count, confirmed = confirmPlacements).leftMap(DeleteResult.Occupied(_): DeleteResult).eitherT[Db]
          order <- composition.findChapterOrder(album, photo.chapter).orFail(ifEmpty = DeleteResult.SpreadNotFound: DeleteResult)
          remaining = removeFromOrder(order = order, spread)
          _ <- (spreads.remove(spread) >> spreads.reorder(photo.chapter, order = remaining)).lift[DeleteResult]
        yield DeleteResult.Deleted: DeleteResult
      act[DeleteResult](DeleteResult.Failed)(Transaction[Db, F].run(mutation.merge))

    override def readPlacements(album: AlbumId, spread: SpreadId): F[PlacementReadResult] =
      val read = composition.hasAlbum(album).flatMap:
        case false => PlacementReadResult.AlbumNotFound.pure[Db]
        case true  =>
          composition.findSpread(album, spread).map(_.fold[PlacementReadResult](PlacementReadResult.SpreadNotFound)(value =>
            PlacementReadResult.Found(value.spread)
          ))
      act(PlacementReadResult.Failed)(Transaction[Db, F].run(read))

    override def editPlacements(album: AlbumId, spread: SpreadId, request: Edit): F[PlacementResult] =
      act(PlacementResult.Failed):
        preparePlacementEdit(request).flatMap: change =>
          val mutation = composition.hasAlbum(album).flatMap:
            case false => PlacementResult.AlbumNotFound.pure[Db]
            case true  =>
              composition.findSpread(album, spread).flatMap:
                case None         => PlacementResult.SpreadNotFound.pure[Db]
                case Some(target) =>
                  checkPlacementPhoto(album, maybeChapter = findPlacementChapter(target), request).flatMap: permission =>
                    permission.flatMap(_ => change(target.spread)) match
                      case Left(reason)   => PlacementResult.Rejected(reason).pure[Db]
                      case Right(updated) => spreads.replace(updated).as(PlacementResult.Saved(updated): PlacementResult)
          Transaction[Db, F].run(mutation)

    private def isReplacement(expected: NonEmptyList[SpreadId], order: NonEmptyList[SpreadId]) =
      val observed          = expected.toList.map(_.value)
      val proposed          = order.toList.map(_.value)
      val retainsOpening    = expected.head.value === order.head.value
      val retainsMembership = proposed.toSet === observed.toSet && proposed.distinct.size === proposed.size
      retainsOpening && retainsMembership

    private def replaceCurrent(album: AlbumId, chapter: ChapterId, expected: NonEmptyList[SpreadId], order: NonEmptyList[SpreadId]) =
      Transaction[Db, F].resolve(composition.findChapterOrder(album, chapter)).attempt.flatMap:
        case Left(error) => Logger[F].error(error)("Chapter composition read failed").as(ReplacementResult.Failed)
        case Right(None) => ReplacementResult.NotFound.pure[F]
        case Right(Some(current)) if current.toList.map(_.value) =!= expected.toList.map(_.value) => ReplacementResult.Stale.pure[F]
        case Right(Some(_))                                                                       =>
          Transaction[Db, F].run(spreads.reorder(chapter, order)).as(ReplacementResult.Saved)
            .handleErrorWith(error => Logger[F].error(error)("Composition write acknowledgement failed").as(ReplacementResult.Uncertain))

    private def act[A](failed: A)(body: F[A]) =
      MonadCancelThrow[F].uncancelable: _ =>
        summon[Mutex[F]].lock.surround(body).handleErrorWith(error => Logger[F].error(error)("Spread action failed").as(failed))

    private type PlacementChange = Spread => Either[Failure, Spread]

    private def preparePlacementEdit(request: Edit) = request match
      case Edit.Place(slot, photo) =>
        IdGenerator[F].generateId.map: id =>
          val change: PlacementChange = spread =>
            requirePlacementSlot(slots = collectPlacementSlots(spread), slot).map: _ =>
              val fresh = SpreadPlacement(SpreadPlacementId(id), slot, photo, untouched, CaptionCorner.BottomRight)
              replacePlacements(spread, values = spread.placements.filterNot(_.slot.value === slot.value) :+ fresh)
          change
      case Edit.Remove(id) =>
        val change: PlacementChange = spread =>
          findPlacement(values = spread.placements, id).map(_ =>
            replacePlacements(spread, values = spread.placements.filterNot(_.id.value === id.value))
          )
        change.pure[F]
      case Edit.Adjust(id, transform) =>
        val change: PlacementChange = spread =>
          adjustPlacement(values = spread.placements, id, transform).map(values => replacePlacements(spread, values = values))
        change.pure[F]
      case Edit.SetCaptionCorner(id, corner) =>
        val change: PlacementChange = spread =>
          findPlacement(values = spread.placements, id).map: _ =>
            val values = spread.placements.map(value => if value.id.value === id.value then value.copy(captionCorner = corner) else value)
            replacePlacements(spread, values = values)
        change.pure[F]
      case Edit.Move(source, destination) =>
        val change: PlacementChange = spread =>
          movePlacement(slots = collectPlacementSlots(spread), values = spread.placements, source, destination)
            .map(values => replacePlacements(spread, values = values))
        change.pure[F]

    private def checkPlacementPhoto(album: AlbumId, maybeChapter: Option[ChapterId], request: Edit) = request match
      case Edit.Place(_, photo) =>
        placements.inspect(album, maybeChapter = maybeChapter, photo).map:
          case PhotoPlacementRepository.InspectResult.Found(inCollection, placed) =>
            if !inCollection then Failure.PhotoNotInCollection.asLeft[Unit]
            else if placed then Failure.PhotoAlreadyUsed.asLeft[Unit]
            else ().asRight[Failure]
          case PhotoPlacementRepository.InspectResult.NotFound => Failure.PhotoNotInCollection.asLeft[Unit]
      case _ => ().asRight[Failure].pure[Db]

    private def findPlacementChapter(target: Located) = target match
      case Located.AlbumOpening(_)            => none[ChapterId]
      case Located.ChapterOpening(chapter, _) => chapter.some
      case Located.Photo(chapter, _)          => chapter.some

    private def collectPlacementSlots(spread: Spread) = spread match
      case value: Spread.AlbumStart   => value.photoSlots.map(_.id).toSet
      case value: Spread.ChapterStart => value.photoSlots.map(_.id).toSet
      case value: Spread.PhotosOnly   => value.photoSlots.toList.map(_.id).toSet

    private def adjustPlacement(values: Vector[SpreadPlacement], id: SpreadPlacementId, transform: PhotoTransform) =
      for
        _ <- findPlacement(values = values, id)
        _ <- Either.cond(transform.zoom >= 1.0 && transform.zoom <= 5.0, (), Failure.InvalidTransform)
      yield values.map(value => if value.id.value === id.value then value.copy(transform = transform) else value)

    private def movePlacement(
        slots: Set[PhotoSpreadSlotId],
        values: Vector[SpreadPlacement],
        source: SpreadPlacementId,
        destination: PhotoSpreadSlotId
    ) =
      for
        placed <- findPlacement(values = values, source)
        _      <- requirePlacementSlot(slots = slots, destination)
      yield values.map: value =>
        if value.id.value === source.value then value.copy(slot = destination)
        else if value.slot.value === destination.value then value.copy(slot = placed.slot)
        else value

    private def requirePlacementSlot(slots: Set[PhotoSpreadSlotId], slot: PhotoSpreadSlotId) =
      Either.cond(slots.contains(slot), (), Failure.SlotNotFound)

    private def findPlacement(values: Vector[SpreadPlacement], id: SpreadPlacementId) =
      values.find(_.id.value === id.value).toRight(Failure.PlacementNotFound)

    private def replacePlacements(spread: Spread, values: Vector[SpreadPlacement]) = spread match
      case value: Spread.AlbumStart   => value.copy(placements = values)
      case value: Spread.ChapterStart => value.copy(placements = values)
      case value: Spread.PhotosOnly   => value.copy(placements = values)

    private def isAlbumOpening(target: Located) = target match
      case Located.AlbumOpening(_) => true
      case _                       => false

    private def findPhoto(target: Located) = target match
      case photo: Located.Photo => photo.some
      case _                    => none[Located.Photo]

    private def insertAfter(order: NonEmptyList[SpreadId], after: SpreadId, fresh: SpreadId) =
      val index = order.toList.indexWhere(_.value === after.value)
      Option.when(index >= 0)(NonEmptyList(order.head, tail = order.tail.patch(index, List(fresh), 0)))

    private def removeFromOrder(order: NonEmptyList[SpreadId], spread: SpreadId) =
      NonEmptyList(order.head, tail = order.tail.filterNot(_.value === spread.value))

    private def buildReplacement(layout: SpreadTemplate) = layout match
      case template: SpreadTemplate.AlbumStart   => allocateAlbumLayout(template)
      case template: SpreadTemplate.ChapterStart => allocateChapterLayout(template)
      case template: SpreadTemplate.PhotosOnly   => allocatePhotoLayout(template)

    private def allocateAlbumLayout(template: SpreadTemplate.AlbumStart) =
      (template.photoSlots.traverse(buildPhotoSlot), buildTextSlot(template.textSlot)).mapN: (photos, text) =>
        val replace: Spread => Option[Spread] =
          case opening: Spread.AlbumStart =>
            val retained = retainPlacements(previous = opening.photoSlots, placements = opening.placements, slots = photos)
            Spread.AlbumStart(opening.id, template.id, photoSlots = photos, text, placements = retained).some
          case _ => none[Spread]
        replace

    private def allocateChapterLayout(template: SpreadTemplate.ChapterStart) =
      (template.photoSlots.traverse(buildPhotoSlot), buildTextSlot(template.textSlot)).mapN: (photos, text) =>
        val replace: Spread => Option[Spread] =
          case opening: Spread.ChapterStart =>
            val retained = retainPlacements(previous = opening.photoSlots, placements = opening.placements, slots = photos)
            Spread.ChapterStart(opening.id, template.id, photoSlots = photos, text, placements = retained).some
          case _ => none[Spread]
        replace

    private def allocatePhotoLayout(template: SpreadTemplate.PhotosOnly) =
      template.photoSlots.traverse(buildPhotoSlot).map: photos =>
        val replace: Spread => Option[Spread] =
          case previous: Spread.PhotosOnly =>
            val retained =
              retainPlacements(previous = previous.photoSlots.toList.toVector, placements = previous.placements, slots = photos.toList.toVector)
            Spread.PhotosOnly(previous.id, template.id, photoSlots = photos, placements = retained).some
          case _ => none[Spread]
        replace

    private def retainPlacements(previous: Vector[SpreadSlot.Photo], placements: Vector[SpreadPlacement], slots: Vector[SpreadSlot.Photo]) =
      placements.flatMap: placement =>
        val position = previous.indexWhere(_.id.value === placement.slot.value)
        slots.lift(position).map(slot => placement.copy(slot = slot.id, transform = untouched)).toList

    private def checkConfirmation(count: Int, confirmed: Boolean) =
      Either.cond(confirmed || count === 0, (), count)

    private def buildPhotoSpread(layout: SpreadTemplate.PhotosOnly) =
      (IdGenerator[F].generateId, layout.photoSlots.traverse(buildPhotoSlot)).mapN: (id, slots) =>
        Spread.PhotosOnly(SpreadId(id), layout.id, photoSlots = slots, placements = Vector.empty[SpreadPlacement])

    private def buildPhotoSlot(box: SlotBox) =
      IdGenerator[F].generateId.map(id => SpreadSlot.Photo(PhotoSpreadSlotId(id), box, bleed = Set.empty[SpreadEdge]))

    private def buildTextSlot(box: SlotBox) =
      IdGenerator[F].generateId.map(id => SpreadSlot.Text(TextSpreadSlotId(id), box))

    private val untouched = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)

  enum Edit:
    case Place(slot: PhotoSpreadSlotId, photo: PhotoId)
    case Remove(id: SpreadPlacementId)
    case Adjust(id: SpreadPlacementId, transform: PhotoTransform)
    case SetCaptionCorner(id: SpreadPlacementId, corner: CaptionCorner)
    case Move(source: SpreadPlacementId, destination: PhotoSpreadSlotId)

  enum ReplacementResult derives CanEqual:
    case Saved, Invalid, NotFound, Stale, Failed, Uncertain

  enum PlacementReadResult:
    case Found(spread: Spread)
    case AlbumNotFound, SpreadNotFound, Failed

  enum PlacementResult:
    case Saved(spread: Spread)
    case Rejected(reason: Failure)
    case AlbumNotFound, SpreadNotFound, Failed

  enum Failure derives CanEqual:
    case PhotoNotInCollection, PhotoAlreadyUsed, SlotNotFound, PlacementNotFound, InvalidTransform

  enum Conflict derives CanEqual:
    case AtCapacity, AddTarget, ContextMismatch, OpeningDelete, UnknownTemplate

  enum AddResult derives CanEqual:
    case Added(id: SpreadId)
    case Rejected(conflict: Conflict)
    case AlbumNotFound, ChapterNotFound, SpreadNotFound, Failed

  enum ChangeTemplateResult derives CanEqual:
    case Changed(id: SpreadId, template: SpreadTemplateId)
    case Occupied(placements: Int)
    case Rejected(conflict: Conflict)
    case AlbumNotFound, SpreadNotFound, Failed

  enum DeleteResult derives CanEqual:
    case Deleted
    case Occupied(placements: Int)
    case Rejected(conflict: Conflict)
    case AlbumNotFound, SpreadNotFound, Failed
