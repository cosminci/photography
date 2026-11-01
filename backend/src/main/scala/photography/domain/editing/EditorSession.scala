package photography.domain.editing

import photography.domain.album.AlbumId
import photography.domain.layout.SpreadId
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

opaque type EditorId = UUID
object EditorId:
  def apply(value: UUID): EditorId         = value
  extension (id: EditorId) def value: UUID = id

opaque type SessionId = UUID
object SessionId:
  def apply(value: UUID): SessionId         = value
  extension (id: SessionId) def value: UUID = id

type SessionLease          = Long :| Positive
type EditorAliasConstraint = Not[Blank]
type EditorAlias           = String :| EditorAliasConstraint

opaque type TabId = UUID
object TabId:
  def apply(value: UUID): TabId         = value
  extension (id: TabId) def value: UUID = id

opaque type ConnectionGeneration = UUID
object ConnectionGeneration:
  def apply(value: UUID): ConnectionGeneration                 = value
  extension (generation: ConnectionGeneration) def value: UUID = generation

final case class EditorConnection(session: SessionId, tab: TabId, generation: ConnectionGeneration)

final case class EditorSession(
    id: SessionId,
    owner: EditorId,
    alias: EditorAlias,
    lastHeartbeat: FiniteDuration,
    expiresAt: FiniteDuration,
    maybeConnection: Option[EditorConnection]
)

enum EditingTarget:
  case Spread(id: SpreadId)
  case Cover(album: AlbumId)

final case class EditingLock(target: EditingTarget, session: SessionId)
