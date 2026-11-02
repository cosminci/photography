import { createSignal, onCleanup, untrack } from "solid-js";

export interface EditingTarget {
  readonly kind: "spread" | "cover";
  readonly id: string;
}

export interface EditorConnection {
  readonly sessionId: string;
  readonly tabId: string;
  readonly generation: string;
}

export interface TargetGrant {
  readonly target: EditingTarget;
  readonly grantId: string;
  readonly remainingMillis: number;
}

export interface TargetState {
  readonly revision: string;
  readonly grant: TargetGrant | null;
}

export interface RegisteredSession {
  readonly sessionId: string;
  readonly ownerId: string;
  readonly alias: string;
  readonly leaseSeconds: number;
  readonly heartbeatSeconds: number;
}

export type SessionEvent =
  ({ readonly kind: "bound" } & EditorConnection) | { readonly kind: "tab_blocked"; readonly alias: string } | { readonly kind: "session_lost" };

export type TargetSelection = TargetState | { readonly revision: string; readonly alias: string; readonly remainingMillis: number };

export interface EditorSessionTransport {
  readonly register: (editorId: string, alias: string, sessionId: string | undefined) => Promise<RegisteredSession>;
  readonly subscribe: (sessionId: string, tabId: string, receive: (event: SessionEvent) => void, signal: AbortSignal) => Promise<void>;
  readonly heartbeat: (connection: EditorConnection) => Promise<RegisteredSession>;
  readonly readTarget: (connection: EditorConnection) => Promise<TargetState>;
  readonly select: (connection: EditorConnection, revision: string, target: EditingTarget) => Promise<TargetSelection>;
  readonly renew: (connection: EditorConnection, grantId: string) => Promise<TargetGrant>;
  readonly release: (connection: EditorConnection, revision: string, grantId: string) => Promise<{ readonly revision: string }>;
}

export class EditorSessionError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

interface RememberedEditor {
  readonly editorId: string;
  readonly alias: string;
  readonly sessionId?: string;
}

const storageKey = "photography.editor";

export function createEditorSession(
  transport: EditorSessionTransport,
  storage: Pick<Storage, "getItem" | "setItem">,
  identity: () => string,
  editorId: string,
) {
  const [opened, setOpened] = createSignal(false);
  const [blocked, setBlocked] = createSignal(false);
  const [connection, setConnection] = createSignal<EditorConnection>();
  const [alias, setAlias] = createSignal("");
  const [message, setMessage] = createSignal<string>();
  const [identifying, setIdentifying] = createSignal(false);
  const [connecting, setConnecting] = createSignal(false);
  const [targetPending, setTargetPending] = createSignal(false);
  const [grant, setGrant] = createSignal<TargetGrant>();
  const [targetMessage, setTargetMessage] = createSignal<string | undefined>("Choose a spread to edit.");
  const [authority, setAuthority] = createSignal(0);
  let remembered: RememberedEditor | undefined;
  let tabId: string;
  let revision = "0";
  let sessionDeadline = 0;
  let targetDeadline = 0;
  const [active, setActive] = createSignal(true);
  let attempt = 0;
  let stream: AbortController | undefined;
  let heartbeatConnection: EditorConnection | undefined;
  let transition = Promise.resolve(true);
  let lastTarget: EditingTarget | null | undefined;
  let expiry: ReturnType<typeof setTimeout> | undefined;

  function forgetAuthority(reason: string) {
    transition = Promise.resolve(true);
    setTargetPending(false);
    setConnection(undefined);
    setGrant(undefined);
    setMessage(reason);
    setTargetMessage("Editing ownership is unconfirmed. Reconnect and review retained drafts before editing.");
    setAuthority((current) => current + 1);
    scheduleExpiry();
  }

  function loseGrant(reason: string) {
    setGrant(undefined);
    setTargetMessage(reason);
    setAuthority((current) => current + 1);
    scheduleExpiry();
  }

  function remember(editor: RememberedEditor) {
    storage.setItem(storageKey, JSON.stringify(editor));
    remembered = editor;
    setAlias(editor.alias);
  }

  function restore() {
    const stored = storage.getItem(storageKey);
    if (stored === null) return;
    const value: unknown = JSON.parse(stored);
    if (
      typeof value !== "object" ||
      value === null ||
      !("editorId" in value) ||
      typeof value.editorId !== "string" ||
      !("alias" in value) ||
      typeof value.alias !== "string" ||
      value.alias.trim() === "" ||
      ("sessionId" in value && typeof value.sessionId !== "string")
    )
      throw new Error("Stored browser identification is invalid.");
    remembered = {
      editorId: value.editorId,
      alias: value.alias,
      ...("sessionId" in value && typeof value.sessionId === "string" ? { sessionId: value.sessionId } : {}),
    };
    setAlias(remembered.alias);
  }

  async function bind(event: SessionEvent, current: number, registered: RegisteredSession) {
    if (!active() || current !== attempt) return;
    if (event.kind === "tab_blocked") {
      setBlocked(true);
      interrupt(`This browser profile is already open in another tab (${event.alias}). Close that tab, then retry.`);
      return;
    }
    if (event.kind === "session_lost") {
      interrupt("The backend session was lost. Reconnect to continue; unsaved drafts are retained.");
      return;
    }
    try {
      if (event.sessionId !== registered.sessionId || event.tabId !== tabId) throw new Error("Unexpected tab binding.");
      const currentConnection = { sessionId: event.sessionId, tabId: event.tabId, generation: event.generation };
      const observedAt = Date.now();
      await transport.heartbeat(currentConnection);
      if (!active() || current !== attempt) return;
      const saved = await transport.readTarget(currentConnection);
      if (!active() || current !== attempt) return;
      revision = saved.revision;
      sessionDeadline = observedAt + registered.leaseSeconds * 1000;
      if (Date.now() >= sessionDeadline) throw new Error("The session confirmation expired. Reconnect before editing.");
      setConnection(currentConnection);
      publishGrant(saved.grant, observedAt);
      setOpened(true);
      setBlocked(false);
      setMessage(undefined);
    } catch (failure) {
      if (active() && current === attempt) interrupt(describeSessionFailure(failure));
    } finally {
      if (current === attempt) setConnecting(false);
    }
  }

  async function reconnect() {
    if (!active() || connecting()) return;
    const current = ++attempt;
    stream?.abort();
    stream = undefined;
    forgetAuthority("Connecting to the backend. Editing is unavailable.");
    setConnecting(true);
    try {
      restore();
      if (remembered === undefined) {
        setIdentifying(true);
        setMessage("Identify this browser before opening the application.");
        return;
      }
      const registered = await transport.register(remembered.editorId, remembered.alias, remembered.sessionId);
      if (!active() || current !== attempt) return;
      remember({ editorId: remembered.editorId, alias: registered.alias, sessionId: registered.sessionId });
      stream = new AbortController();
      const signal = stream.signal;
      void transport
        .subscribe(
          registered.sessionId,
          tabId,
          (event) => {
            untrack(() => {
              void bind(event, current, registered);
            });
          },
          signal,
        )
        .then(
          () => {
            if (active() && current === attempt && !signal.aborted && (connection() !== undefined || connecting())) {
              interrupt("The tab connection closed. Reconnect before editing.");
            }
          },
          (failure: unknown) => {
            if (active() && current === attempt && !signal.aborted) {
              interrupt(describeSessionFailure(failure));
            }
          },
        );
    } catch (failure) {
      if (active() && current === attempt) {
        setMessage(describeSessionFailure(failure));
        setConnecting(false);
      }
    } finally {
      if (current === attempt && stream === undefined) setConnecting(false);
    }
  }

  async function identify(label: string) {
    if (label.trim() === "") {
      setMessage("Enter a nonblank identification alias.");
      return;
    }
    try {
      restore();
      if (remembered === undefined) remember({ editorId, alias: label.trim() });
      setIdentifying(false);
      await reconnect();
    } catch (failure) {
      setMessage(describeSessionFailure(failure));
    }
  }

  function publishGrant(observed: TargetGrant | null, observedAt: number) {
    const saved = observed !== null && observedAt + observed.remainingMillis > Date.now() ? observed : null;
    const previous = grant()?.grantId;
    setGrant(saved ?? undefined);
    targetDeadline = saved === null ? 0 : observedAt + saved.remainingMillis;
    setTargetMessage(saved === null ? "View only. Choose Edit spread to acquire ownership." : undefined);
    if (previous !== saved?.grantId) setAuthority((current) => current + 1);
    scheduleExpiry();
  }

  function available() {
    return active() && connection() !== undefined;
  }

  function headers(): Record<string, string> {
    const current = connection();
    if (!active() || current === undefined || Date.now() >= sessionDeadline) {
      forgetAuthority("The session is unconfirmed or expired. Reconnect before editing.");
      throw new EditorSessionError("stale_session", "Editing is unavailable until the browser reconnects.");
    }
    const saved = grant();
    if (saved !== undefined && Date.now() >= targetDeadline) loseGrant("Editing ownership expired. Review drafts and choose Edit spread.");
    const owned = grant();
    return {
      "X-Editor-Session": current.sessionId,
      "X-Editor-Tab": current.tabId,
      "X-Editor-Generation": current.generation,
      ...(owned === undefined ? {} : { "X-Editor-Grant": owned.grantId }),
    };
  }

  function targetReason(target: EditingTarget) {
    if (!available()) return targetMessage();
    if (targetPending()) return "Confirming editing ownership. Please wait.";
    const saved = grant();
    if (saved !== undefined && sameTarget(saved.target, target)) return undefined;
    return targetMessage() ?? "This spread is view only.";
  }

  async function changeTarget(target: EditingTarget | null, explicit: boolean) {
    const current = connection();
    if (!active() || current === undefined) return false;
    setTargetPending(true);
    const previousTarget = lastTarget;
    lastTarget = target;
    try {
      const observedAt = Date.now();
      const state = await transport.readTarget(current);
      if (!active() || current !== connection()) return false;
      revision = state.revision;
      publishGrant(state.grant, observedAt);
      if (target === null) {
        if (state.grant !== null) {
          const released = await transport.release(current, revision, state.grant.grantId);
          if (!active() || current !== connection()) return false;
          revision = released.revision;
        }
        publishGrant(null, observedAt);
      } else if (state.grant === null || !sameTarget(state.grant.target, target)) {
        if (!explicit && previousTarget !== undefined && previousTarget !== null && sameTarget(previousTarget, target)) return false;
        const selectedAt = Date.now();
        const selected = await transport.select(current, revision, target);
        if (!active() || current !== connection()) return false;
        revision = selected.revision;
        if ("grant" in selected) publishGrant(selected.grant, selectedAt);
        else {
          publishGrant(null, selectedAt);
          setTargetMessage(`View only. ${selected.alias} is editing this spread. Retry when they leave or their lease expires.`);
        }
      }
      return true;
    } catch (failure) {
      if (active() && current === connection()) {
        interrupt(`Editing ownership is uncertain. ${describeSessionFailure(failure)} Reconnect and read ownership before trying again.`);
      }
      return false;
    } finally {
      if (active() && current === connection()) setTargetPending(false);
    }
  }

  function select(target: EditingTarget | null, explicit = false) {
    transition = transition.then(() => changeTarget(target, explicit));
    return transition;
  }

  async function heartbeat() {
    const current = connection();
    if (current === undefined || current === heartbeatConnection || targetPending()) return;
    heartbeatConnection = current;
    const saved = grant();
    let renewingTarget = false;
    try {
      headers();
      const observedAt = Date.now();
      const session = await transport.heartbeat(current);
      if (!active() || current !== connection()) return;
      sessionDeadline = observedAt + session.leaseSeconds * 1000;
      scheduleExpiry();
      if (saved !== undefined) {
        renewingTarget = true;
        const renewed = await transport.renew(current, saved.grantId);
        if (active() && current === connection() && saved.grantId === grant()?.grantId) {
          targetDeadline = observedAt + renewed.remainingMillis;
          scheduleExpiry();
        }
      }
    } catch (failure) {
      if (active() && current === connection() && (!renewingTarget || saved?.grantId === grant()?.grantId))
        interrupt(describeSessionFailure(failure));
    } finally {
      if (heartbeatConnection === current) heartbeatConnection = undefined;
    }
  }

  function interrupt(reason: string) {
    attempt++;
    forgetAuthority(reason);
    stream?.abort();
    setConnecting(false);
  }

  const offline = () => {
    interrupt("The backend is unreachable. Unsaved drafts are retained. Reconnect before editing.");
  };
  const pagehide = () => {
    interrupt("The page connection was closed. Reconnect before editing; unsaved drafts are retained.");
  };
  const timer = window.setInterval(() => {
    void heartbeat();
  }, 10_000);
  function scheduleExpiry() {
    clearTimeout(expiry);
    if (connection() === undefined) return;
    const deadline = grant() === undefined ? sessionDeadline : Math.min(sessionDeadline, targetDeadline);
    expiry = setTimeout(
      () => {
        if (Date.now() >= sessionDeadline) interrupt("The session expired without an accepted heartbeat. Reconnect before editing.");
        else loseGrant("Editing ownership expired. Review drafts and choose Edit spread.");
      },
      Math.max(0, deadline - Date.now()),
    );
  }
  window.addEventListener("offline", offline);
  window.addEventListener("pagehide", pagehide);
  onCleanup(() => {
    setActive(false);
    attempt++;
    stream?.abort();
    window.clearInterval(timer);
    clearTimeout(expiry);
    window.removeEventListener("offline", offline);
    window.removeEventListener("pagehide", pagehide);
  });
  try {
    tabId = identity();
    restore();
    if (remembered === undefined) {
      setIdentifying(true);
      setMessage("Identify this browser before opening the application.");
    } else
      untrack(() => {
        void reconnect();
      });
  } catch (failure) {
    setMessage(describeSessionFailure(failure));
  }

  return {
    opened,
    blocked,
    available,
    alias,
    message,
    identifying,
    connecting,
    targetPending,
    authority,
    headers,
    targetReason,
    identify,
    reconnect,
    select,
    interrupt,
  };
}

export type EditorSession = ReturnType<typeof createEditorSession>;
export type WorkspaceEditingSession = Pick<EditorSession, "available" | "authority" | "targetReason" | "targetPending" | "select">;

export function sameTarget(left: EditingTarget, right: EditingTarget): boolean {
  return left.kind === right.kind && left.id === right.id;
}

function describeSessionFailure(failure: unknown) {
  return failure instanceof Error ? failure.message : "The backend session could not be confirmed.";
}
