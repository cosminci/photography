package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, Encoder}
import io.github.iltotore.iron.*
import org.http4s.{CacheDirective, HttpRoutes, MessageBodyFailure, Query, QueryParamDecoder, QueryParameterValue, Request}
import org.http4s.headers.`Cache-Control`
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.{Album, AlbumId, AlbumTitle, AlbumYear, AlbumYearConstraint}
import io.github.iltotore.iron.constraint.all.*
import photography.usecases.album.AlbumManager
import photography.usecases.album.AlbumManager.*
import photography.ports.album.AlbumRepository
import java.util.UUID

trait AlbumApi[F[_]]:
  def routes: HttpRoutes[F]

object AlbumApi:
  def make[F[_]: Async](albums: AlbumManager[F], metadata: AlbumRepository.MetadataRead[F]): AlbumApi[F] =
    new LiveAlbumApi(albums, metadata)

  final private class LiveAlbumApi[F[_]: Async](albums: AlbumManager[F], metadata: AlbumRepository.MetadataRead[F])
      extends AlbumApi[F] with Http4sDsl[F]:
    override def routes: HttpRoutes[F] =
      HttpRoutes.of[F] {
        case request @ GET -> Root / "api" / "albums"                    => list(request.uri.query)
        case request @ POST -> Root / "api" / "albums"                   => create(request)
        case GET -> Root / "api" / "albums" / CanonicalId(id)            => read(AlbumId(id))
        case GET -> Root / "api" / "albums" / _                          => respondAlbumNotFound
        case request @ PATCH -> Root / "api" / "albums" / AlbumIdVar(id) => rename(id, request)
        case DELETE -> Root / "api" / "albums" / AlbumIdVar(id)          => delete(id)
        case (PATCH | DELETE) -> Root / "api" / "albums" / _             => respondAlbumNotFound
      }

    private def read(id: AlbumId) =
      metadata.metadata(id).flatMap:
        case AlbumRepository.MetadataResult.Found(value) => Ok(value).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-store`)))
        case AlbumRepository.MetadataResult.NotFound     => respondAlbumNotFound
        case AlbumRepository.MetadataResult.Failed(_)    => respondServiceFailure

    private def list(query: Query) = query match
      case Page(page) =>
        albums.list(page = page).flatMap:
          case ListAlbumsResult.Listed(selected) => Ok(selected).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-store`)))
          case ListAlbumsResult.Failed           => respondServiceFailure
      case _ => BadRequest(Map("message" -> "Supply page once as a 32-bit integer"))

    private def create(request: Request[F]) =
      request.as[(AlbumYear, AlbumTitle)].flatMap { case (year, title) => albums.create(year, title) }.flatMap {
        case CreateAlbumResult.Created(album)  => Created(album)
        case CreateAlbumResult.YearAlreadyUsed => BadRequest(Map("message" -> "An album already exists for that year"))
        case CreateAlbumResult.Failed          => respondServiceFailure
      }.recoverWith { case _: MessageBodyFailure => BadRequest(invalidYear) }

    private def rename(id: AlbumId, request: Request[F]) =
      request.as[AlbumTitle].flatMap(title => albums.rename(id, title)).flatMap {
        case RenameResult.Renamed(album) => Ok(album)
        case RenameResult.NotFound       => respondAlbumNotFound
        case RenameResult.Failed         => respondServiceFailure
      }.recoverWith { case _: MessageBodyFailure => BadRequest(invalidTitle) }

    private def delete(id: AlbumId) =
      albums.delete(id).flatMap:
        case DeleteAlbumResult.Deleted  => NoContent()
        case DeleteAlbumResult.NotFound => respondAlbumNotFound
        case DeleteAlbumResult.Failed   => respondServiceFailure

    private def respondAlbumNotFound  = NotFound(Map("code" -> "album_not_found"))
    private def respondServiceFailure = InternalServerError(Map("code" -> "service_failure"))
    private val invalidTitle          = Map("message" -> "Supply a non-blank album title")
    private val invalidYear           = Map("message" -> "Choose a year from 1900 to 2100")

    private object Page:
      def unapply(query: Query): Option[Int] =
        query.pairs.collect { case ("page", value) => value } match
          case Vector()            => 1.some
          case Vector(Some(value)) => QueryParamDecoder[Int].decode(QueryParameterValue(value)).toOption
          case _                   => None

    private object AlbumIdVar:
      def unapply(value: String): Option[AlbumId] =
        UUIDVar.unapply(value).filter(_.toString.equalsIgnoreCase(value)).map(AlbumId(_))

    private object CanonicalId:
      def unapply(value: String): Option[UUID] =
        Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value)

    private given Encoder[AlbumRepository.Metadata] = Encoder.forProduct5("id", "year", "title", "synopsis", "createdAt"): album =>
      (album.id.value.toString, album.year: Int, album.title: String, album.synopsis, album.createdAt.toString)

    private given Decoder[AlbumYear] = Decoder.decodeJsonNumber.emap(_.toInt.toRight("Expected an integer year"))
      .emap(_.refineEither[AlbumYearConstraint]).prepare(_.downField("year"))

    private given Decoder[AlbumTitle] = Decoder.decodeString.emap(_.trim.refineEither[Not[Empty] & Not[Blank]]).prepare(_.downField("title"))

    private given Decoder[(AlbumYear, AlbumTitle)] = Decoder[AlbumYear].emap: year =>
      s"Journey of the Shepherds $year".refineEither[Not[Empty] & Not[Blank]].map(title => (year, title))

    private given Encoder[Album] = Encoder.forProduct4("id", "year", "title", "createdAt"): album =>
      (album.id.value.toString, album.year: Int, album.title: String, album.createdAt.toString)

    private given Encoder[AlbumPage] = Encoder.forProduct5("albums", "page", "pageSize", "totalAlbums", "totalPages"): page =>
      (page.albums, page.page, page.pageSize, page.totalAlbums, page.totalPages)
