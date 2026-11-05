package photography.usecases.album

import cats.effect.Async
import cats.syntax.all.*
import com.networknt.schema.{ExecutionConfig, InputFormat, SchemaLocation, SchemaRegistry, SpecificationVersion}
import io.circe.{Encoder, Json}
import org.typelevel.log4cats.Logger
import photography.domain.album.*
import photography.domain.layout.*
import photography.domain.gear.GearCatalog
import photography.capabilities.Transaction
import photography.ports.album.AlbumRepository
import photography.ports.cover.CoverRepository
import photography.ports.gear.GearRepository
import photography.ports.photo.{PhotoRepository, PhotoStorage}
import photography.syntax.EitherSyntax.*
import scala.jdk.CollectionConverters.*

trait BookExport[F[_]]:
  def exportBook(album: AlbumId): F[BookExport.Result]

object BookExport:
  def make[F[_]: Async: Logger, Db[_]](
      albums: AlbumRepository[F],
      covers: CoverRepository[Db],
      photos: PhotoRepository[Db],
      gear: GearRepository[F],
      originals: PhotoStorage[F]
  )(using Transaction[Db, F]): F[BookExport[F]] = Async[F].blocking(new LiveBookExport(albums, covers, photos, gear, originals))

  final private class LiveBookExport[F[_]: Async: Logger, Db[_]](
      albums: AlbumRepository[F],
      covers: CoverRepository[Db],
      photos: PhotoRepository[Db],
      gear: GearRepository[F],
      originals: PhotoStorage[F]
  )(using Transaction[Db, F]) extends BookExport[F]:
    private val schema =
      val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
      val loaded   = registry.getSchema(SchemaLocation.of("classpath:indesign-book.schema.json"))
      loaded.initializeValidators()
      loaded
    private val validationConfig = ExecutionConfig.builder().formatAssertionsEnabled(true).build()

    override def exportBook(album: AlbumId): F[Result] =
      readBook(album).value.flatMap {
        case Left(Result.Failed) =>
          Logger[F].error(s"Saved book acquisition failed for ${album.value}").as(Result.Failed)
        case Left(result) => result.pure[F]
        case Right(book)  => validate(book)
      }.handleErrorWith(error => Logger[F].error(error)(s"Book export failed for ${album.value}").as(Result.Failed))

    private def readBook(id: AlbumId) =
      for
        album <- albums.get(id).map {
          case AlbumRepository.GetResult.Found(saved) => saved.asRight[Result]
          case AlbumRepository.GetResult.NotFound     => (Result.NotFound: Result).asLeft[Album]
          case AlbumRepository.GetResult.Failed(_)    => (Result.Failed: Result).asLeft[Album]
        }.eitherT
        cover <- Transaction[Db, F].run(covers.read(id)).map {
          case CoverRepository.ReadResult.Found(composition) => composition.asRight[Result]
          case CoverRepository.ReadResult.NotFound           => (Result.Failed: Result).asLeft[CoverRepository.Composition]
        }.eitherT
        inventory <- album.chapters.traverse(chapter =>
          Transaction[Db, F].run(photos.listChapter(chapter.id)).map {
            case PhotoRepository.ListResult.Listed(values) => values.asRight[Result]
            case PhotoRepository.ListResult.Failed         => (Result.Failed: Result).asLeft[Vector[Photo]]
          }.eitherT
        )
        catalog <- gear.readCatalog.map {
          case GearRepository.CatalogReadResult.Found(value) => value.asRight[Result]
          case GearRepository.CatalogReadResult.Failed       => (Result.Failed: Result).asLeft[GearCatalog]
        }.eitherT
      yield
        val references = (album.collectSpreads.toList.flatMap(_.placements.map(_.photo)) ++ cover.placements.map(_.photo)).toSet
        SavedBook(album, cover, inventory.flatten.filter(photo => references.contains(photo.id)), catalog)

    private def validate(book: SavedBook) =
      val readiness  = (checkInterior(book), checkCover(book)).mapN(checkContent(book) ++ _ ++ _)
      val projection = readiness.flatMap(issues => BookExportJson.encode(book, warnings = issues).tupleLeft(issues))
      for
        (printIssues, json) <- projection.leftMap(new IllegalStateException(_)).liftTo[F]
        sourceIssues        <- book.photos.traverse(checkOriginal)
        issues = printIssues ++ sourceIssues.flatten
        result <-
          if issues.exists(_.severity === Severity.Blocking) then (Result.Blocked(issues): Result).pure[F]
          else validateArtifact(json, book.album.year)
      yield result

    private def validateArtifact(json: Json, year: Int) =
      for
        errors <-
          Async[F].blocking(schema.validate(json.noSpaces, InputFormat.JSON, context => context.setExecutionConfig(validationConfig)).asScala.toList)
        validated <- errors.toNel.toLeft(json).leftMap(errors =>
          new IllegalStateException(s"Book export schema validation failed: ${errors.toList.mkString("; ")}")
        ).liftTo[F]
      yield Result.Ready(validated, filename = s"album-$year-indesign.json"): Result

    private def checkContent(book: SavedBook) =
      val synopsis     = Option.when(book.album.synopsis.isBlank)(Issue(Code.EmptySynopsis, Severity.Blocking, Target.Album)).toList.toVector
      val descriptions = book.album.chapters.filter(_.description.isBlank)
        .map(chapter => Issue(Code.EmptyDescription, Severity.Blocking, Target.Chapter(chapter.id)))
      synopsis ++ descriptions

    private def checkInterior(book: SavedBook) =
      book.album.collectSpreads.toList.toVector.flatTraverse: spread =>
        spread.photos.flatTraverse: slot =>
          val target = Target.Slot(spread.id, slot.id)
          spread.placements.find(_.slot.value === slot.id.value) match
            case None            => Vector(Issue(Code.EmptyPhotoSlot, Severity.Blocking, target)).asRight[String]
            case Some(placement) =>
              for
                box   <- slot.visibleBox
                photo <- book.photos.find(_.id.value === placement.photo.value).toRight(s"Photo ${placement.photo.value} unavailable")
              yield
                val frame = InteriorDimensions.photoFrame(box, slot.bleed)
                resolutionWarning(photo, placement.transform, frame.width, frame.height, target)

    private def checkCover(book: SavedBook) =
      val cover      = book.cover.cover
      val dimensions = book.cover.dimensions
      val frames     = Vector(cover.front.id -> dimensions.frontFrame(cover.spineOwner), cover.back.id -> dimensions.backFrame(cover.spineOwner))
      frames.flatTraverse { case (slot, frame) =>
        val target = Target.CoverSlot(slot)
        book.cover.placements.find(_.slot.value === slot.value) match
          case None            => Vector(Issue(Code.EmptyPhotoSlot, Severity.Blocking, target)).asRight[String]
          case Some(placement) =>
            book.photos.find(_.id.value === placement.photo.value).toRight(s"Photo ${placement.photo.value} unavailable").map(photo =>
              resolutionWarning(photo, placement.transform, frame.width, frame.height, target)
            )
      }

    private def resolutionWarning(photo: Photo, transform: PhotoTransform, width: Double, height: Double, target: Target) =
      val scale = math.max(width / photo.metadata.dimensions.width, height / photo.metadata.dimensions.height) * transform.zoom
      Option.when(72.0 / scale < 300.0)(Issue(Code.LowResolution, Severity.Warning, target)).toList.toVector

    private def checkOriginal(photo: Photo) =
      originals.readOriginal(photo.original).map: result =>
        val maybeCode = result match
          case PhotoStorage.ReadResult.Read(_, metadata) =>
            Option.when(
              (metadata.hash: String) =!= (photo.metadata.hash: String) ||
                (photo.metadata.dimensions.width: Int) =!= (metadata.dimensions.width: Int) ||
                (photo.metadata.dimensions.height: Int) =!= (metadata.dimensions.height: Int)
            )(Code.OriginalChanged)
          case PhotoStorage.ReadResult.Undecodable => Code.OriginalUndecodable.some
          case PhotoStorage.ReadResult.Unstable    => Code.OriginalUnstable.some
          case PhotoStorage.ReadResult.Failed      => Code.OriginalUnavailable.some
        maybeCode.map(code => Issue(code, Severity.Blocking, Target.Photo(photo.id))).toList.toVector

  final private[album] case class SavedBook(album: Album, cover: CoverRepository.Composition, photos: Vector[Photo], gear: GearCatalog)

  enum Result:
    case Ready(json: Json, filename: String)
    case Blocked(issues: Vector[Issue])
    case NotFound, Failed

  enum Severity derives CanEqual:
    case Blocking, Warning

  object Severity:
    given cats.Eq[Severity] = cats.Eq.by(_.ordinal)

  enum Target:
    case Album
    case Chapter(chapter: ChapterId)
    case Slot(spread: SpreadId, slot: PhotoSpreadSlotId)
    case CoverSlot(slot: CoverSlotId)
    case Photo(photo: PhotoId)

  enum Code:
    case EmptySynopsis, EmptyDescription, EmptyPhotoSlot
    case LowResolution, OriginalChanged, OriginalUndecodable, OriginalUnstable, OriginalUnavailable

  final case class Issue(code: Code, severity: Severity, target: Target)

  object Issue:
    given Encoder[Issue] = Encoder.instance(BookExportJson.encodeIssue)
