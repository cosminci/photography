import {
  EditorSessionError,
  type EditorConnection,
  type EditorSessionTransport,
  type RegisteredSession,
  type SessionEvent,
  type TargetGrant,
  type TargetSelection,
  type TargetState,
} from "./EditorSession";

export function makeHttpEditorSession(fetcher: typeof fetch): EditorSessionTransport {
  async function request(
    method: string,
    path: string,
    headers: Readonly<Record<string, string>>,
    maybeBody?: unknown,
    maybeSignal?: AbortSignal,
  ): Promise<Record<string, unknown>> {
    const response = await fetcher(`/api/editor-session${path}`, {
      method,
      headers: { Accept: "application/json", ...headers, ...(maybeBody === undefined ? {} : { "Content-Type": "application/json" }) },
      ...(maybeBody === undefined ? {} : { body: JSON.stringify(maybeBody) }),
      cache: "no-store",
      signal: maybeSignal ?? null,
    });
    const value = readObject(await response.json());
    if (!response.ok && value["code"] !== "target_locked") {
      const code = readString(value["code"]);
      throw new EditorSessionError(code, `Browser ownership could not be confirmed (${code}).`);
    }
    return value;
  }

  async function subscribe(sessionId: string, tabId: string, receive: (event: SessionEvent) => void, signal: AbortSignal) {
    const response = await fetcher("/api/editor-session/events", {
      headers: { Accept: "text/event-stream", "X-Editor-Session": sessionId, "X-Editor-Tab": tabId },
      cache: "no-store",
      signal,
    });
    if (!response.ok || response.headers.get("Content-Type")?.startsWith("text/event-stream") !== true || response.body === null)
      throw new Error("The browser tab connection could not be opened.");
    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8", { fatal: true });
    let buffer = "";
    const cancel = () => {
      void reader.cancel();
    };
    signal.addEventListener("abort", cancel, { once: true });
    try {
      while (!signal.aborted) {
        const chunk = await reader.read();
        if (chunk.done) break;
        buffer += decoder.decode(chunk.value, { stream: true });
        const normalized = buffer.replace(/\r\n/g, "\n");
        const frames = normalized.split("\n\n");
        buffer = frames.splice(-1).join("");
        for (const frame of frames) {
          if (isAborted()) break;
          const lines = frame.split("\n");
          const name = lines
            .find((line) => line.startsWith("event:"))
            ?.slice(6)
            .trim();
          const data = lines
            .filter((line) => line.startsWith("data:"))
            .map((line) => line.slice(5).trimStart())
            .join("\n");
          if (data !== "") receive(readEvent(name, JSON.parse(data)));
        }
        if (buffer.length > 65_536) throw new Error("The tab connection sent an oversized event.");
      }
      if (!signal.aborted && buffer.trim() !== "") throw new Error("The tab connection ended during an event.");
    } finally {
      signal.removeEventListener("abort", cancel);
      await reader.cancel();
      reader.releaseLock();
    }
    function isAborted() {
      return signal.aborted;
    }
  }

  return {
    unbind: async (connection) => {
      const response = await fetcher("/api/editor-session/binding", { method: "DELETE", headers: connectionHeaders(connection), keepalive: true });
      if (!response.ok) throw new Error("The previous tab connection could not be released.");
    },
    register: async (editorId, alias, maybeSessionId, maybeSignal) =>
      readSession(
        await request("POST", "", maybeSessionId === undefined ? {} : { "X-Editor-Session": maybeSessionId }, { editorId, alias }, maybeSignal),
      ),
    subscribe,
    heartbeat: async (connection, maybeSignal) =>
      readSession(await request("POST", "/heartbeat", connectionHeaders(connection), undefined, maybeSignal)),
    readTarget: async (connection, maybeSignal) =>
      readTargetState(await request("GET", "/target", connectionHeaders(connection), undefined, maybeSignal)),
    select: async (connection, revision, target): Promise<TargetSelection> => {
      const value = await request("PUT", "/target", connectionHeaders(connection), { revision, target });
      if (value["code"] === "target_locked")
        return {
          revision: readRevision(value["revision"]),
          alias: readString(value["alias"]),
          remainingMillis: readRemaining(value["remainingMillis"]),
        };
      return readTargetState(value);
    },
    renew: async (connection, grantId) =>
      readGrant(await request("POST", "/target", { ...connectionHeaders(connection), "X-Editor-Grant": grantId })),
    release: async (connection, revision, grantId) => {
      const value = await request("DELETE", "/target", { ...connectionHeaders(connection), "X-Editor-Grant": grantId }, { revision });
      return { revision: readRevision(value["revision"]) };
    },
  };
}

function connectionHeaders(connection: EditorConnection) {
  return {
    "X-Editor-Session": connection.sessionId,
    "X-Editor-Tab": connection.tabId,
    "X-Editor-Generation": connection.generation,
  };
}

function readObject(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new Error("Invalid browser session response.");
  return Object.fromEntries(Object.entries(value));
}

function readString(value: unknown): string {
  if (typeof value !== "string" || value.trim() === "") throw new Error("Invalid browser session text.");
  return value;
}

function readId(value: unknown) {
  const id = readString(value);
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) throw new Error("Invalid browser session identity.");
  return id;
}

function readRevision(value: unknown) {
  const revision = readString(value);
  if (!/^(0|[1-9][0-9]*)$/.test(revision)) throw new Error("Invalid editing transition revision.");
  return revision;
}

function readRemaining(value: unknown) {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0 || value > 30_000) throw new Error("Invalid editing lease.");
  return value;
}

function readGrant(value: unknown): TargetGrant {
  const fields = readObject(value);
  const target = readObject(fields["target"]);
  const kind = target["kind"];
  if (kind !== "spread" && kind !== "cover") throw new Error("Invalid editing target.");
  return {
    target: { kind, id: readId(target["id"]) },
    grantId: readId(fields["grantId"]),
    remainingMillis: readRemaining(fields["remainingMillis"]),
  };
}

function readTargetState(value: Record<string, unknown>): TargetState {
  return { revision: readRevision(value["revision"]), grant: value["grant"] === null ? null : readGrant(value["grant"]) };
}

function readSession(value: Record<string, unknown>): RegisteredSession {
  if (value["leaseSeconds"] !== 30 || value["heartbeatSeconds"] !== 10) throw new Error("Invalid browser session timing.");
  return {
    sessionId: readId(value["sessionId"]),
    ownerId: readId(value["ownerId"]),
    alias: readString(value["alias"]),
    leaseSeconds: 30,
    heartbeatSeconds: 10,
  };
}

function readEvent(name: string | undefined, value: unknown): SessionEvent {
  const fields = readObject(value);
  if (name === "bound")
    return { kind: "bound", sessionId: readId(fields["sessionId"]), tabId: readId(fields["tabId"]), generation: readId(fields["generation"]) };
  if (name === "tab_blocked") return { kind: "tab_blocked", alias: readString(fields["alias"]) };
  if (name === "session_lost") return { kind: "session_lost" };
  if (name === "ownership_changed") return { kind: "ownership_changed" };
  throw new Error("The browser tab connection sent an unknown event.");
}
