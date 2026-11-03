import type { WorkspaceEditingSession } from "../../../src/features/editing/EditorSession";

export const editingSession: WorkspaceEditingSession = {
  available: () => true,
  authority: () => 1,
  targetReason: () => undefined,
  targetPending: () => false,
  select: () => Promise.resolve(true),
  reconnect: () => Promise.resolve(),
  alias: () => "Cosmin",
  rename: () => Promise.resolve(),
  owner: () => undefined,
  ownershipRevision: () => 0,
};
