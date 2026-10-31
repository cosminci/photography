package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Encoder
import org.http4s.{CacheDirective, HttpRoutes}
import org.http4s.headers.`Cache-Control`
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.layout.{SlotBox, SpreadTemplate}
import photography.ports.spread.SpreadTemplateRepository
import photography.ports.spread.SpreadTemplateRepository.{ListAlbumStartTemplatesResult, ListChapterStartTemplatesResult}
import photography.ports.spread.SpreadTemplateRepository.ListPhotosOnlyTemplatesResult

trait SpreadTemplateApi[F[_]]:
  def routes: HttpRoutes[F]

object SpreadTemplateApi:
  def make[F[_]: Async](templates: SpreadTemplateRepository[F]): SpreadTemplateApi[F] =
    new LiveSpreadTemplateApi(templates)

  final private class LiveSpreadTemplateApi[F[_]: Async](templates: SpreadTemplateRepository[F])
      extends SpreadTemplateApi[F] with Http4sDsl[F]:
    override def routes: HttpRoutes[F] = HttpRoutes.of[F]:
      case GET -> Root / "api" / "templates" / "album-start" =>
        templates.listAlbumStartTemplates.flatMap:
          case listed: ListAlbumStartTemplatesResult.Listed => Ok(listed).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-store`)))
          case ListAlbumStartTemplatesResult.Failed(_)      => InternalServerError(Map("code" -> "service_failure"))
      case GET -> Root / "api" / "templates" / "chapter-start" =>
        templates.listChapterStartTemplates.flatMap:
          case listed: ListChapterStartTemplatesResult.Listed => Ok(listed).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-store`)))
          case ListChapterStartTemplatesResult.Failed(_)      => InternalServerError(Map("code" -> "service_failure"))
      case GET -> Root / "api" / "templates" / "photos-only" =>
        templates.listPhotosOnlyTemplates.flatMap:
          case listed: ListPhotosOnlyTemplatesResult.Listed => Ok(listed).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-store`)))
          case ListPhotosOnlyTemplatesResult.Failed(_)      => InternalServerError(Map("code" -> "service_failure"))

    private given Encoder[(String, SlotBox)] = Encoder.forProduct5("kind", "x", "y", "width", "height"): slot =>
      val (kind, box) = slot
      (kind, box.x: Double, box.y: Double, box.width: Double, box.height: Double)

    private given Encoder[SpreadTemplate.AlbumStart] = Encoder.forProduct3("id", "name", "slots"): template =>
      val slots = template.photoSlots.map(box => "photo" -> box) :+ ("text" -> template.textSlot)
      (template.id.value.toString, template.name: String, slots)

    private given Encoder[SpreadTemplate.ChapterStart] = Encoder.forProduct3("id", "name", "slots"): template =>
      val slots = template.photoSlots.map(box => "photo" -> box) :+ ("text" -> template.textSlot)
      (template.id.value.toString, template.name: String, slots)

    private given Encoder[SpreadTemplate.PhotosOnly] = Encoder.forProduct3("id", "name", "slots"): template =>
      (template.id.value.toString, template.name: String, template.photoSlots.toList.toVector.map(box => "photo" -> box))

    private given albumStartListing: Encoder[ListAlbumStartTemplatesResult.Listed] = Encoder.forProduct1("templates")(_.templates.toList)

    private given chapterStartListing: Encoder[ListChapterStartTemplatesResult.Listed] = Encoder.forProduct1("templates")(_.templates.toList)

    private given photosOnlyListing: Encoder[ListPhotosOnlyTemplatesResult.Listed] = Encoder.forProduct1("templates")(_.templates.toList)
