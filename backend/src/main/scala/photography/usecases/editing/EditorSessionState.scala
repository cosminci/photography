package photography.usecases.editing

import photography.domain.editing.{EditingLock, EditorId, EditorSession}

final case class EditorSessionState(sessions: Map[EditorId, EditorSession], locks: Set[EditingLock])
