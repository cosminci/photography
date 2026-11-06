package photography.domain.album

import cats.data.NonEmptyList
import photography.domain.layout.Spread
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import java.time.Instant
import java.util.UUID

type AlbumYearConstraint = GreaterEqual[1900] & LessEqual[2100]
type AlbumYear           = Int :| AlbumYearConstraint
type AlbumTitle          = String :| (Not[Empty] & Not[Blank])

final case class Album(
    id: AlbumId,
    year: AlbumYear,
    title: AlbumTitle,
    synopsis: String,
    authors: AlbumAuthors,
    createdAt: Instant,
    opening: Spread.AlbumStart,
    chapters: Vector[Chapter]
):
  def collectSpreads: NonEmptyList[Spread] = NonEmptyList(opening, chapters.flatMap(chapter => chapter.opening +: chapter.spreads).toList)

opaque type AlbumId = UUID
object AlbumId:
  def apply(value: UUID): AlbumId         = value
  extension (id: AlbumId) def value: UUID = id
