import { createParser } from "eventsource-parser";
import { check, literal, maxValue, minValue, nullable, number, object, parse, picklist, pipe, regex, safeInteger, string } from "valibot";
import { jsonObject } from "../../shared/Json";
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
    const value = parse(jsonObject, await response.json());
    if (!response.ok && value["code"] !== "target_locked") {
      const code = parse(text, value["code"]);
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
    const parser = createParser({
      onEvent: ({ event, data }) => {
        if (!signal.aborted) receive(readEvent(event, JSON.parse(data)));
      },
    });
    const cancel = () => {
      void reader.cancel();
    };
    const isAborted = () => signal.aborted;
    signal.addEventListener("abort", cancel, { once: true });
    try {
      while (!isAborted()) {
        const chunk = await reader.read();
        if (isAborted()) break;
        const decoded = chunk.done ? decoder.decode() : decoder.decode(chunk.value, { stream: true });
        const frames = (buffer + decoded).split(/(?:\r\n|\r(?!\n)|(?<!\r)\n){2}/);
        if (frames.some((frame) => frame.length > 65_536)) throw new Error("The tab connection sent an oversized event.");
        buffer = frames.slice(-1).join("");
        parser.feed(decoded);
        if (chunk.done) break;
      }
      if (!signal.aborted && buffer.trim() !== "") throw new Error("The tab connection ended during an event.");
    } finally {
      signal.removeEventListener("abort", cancel);
      await reader.cancel();
      reader.releaseLock();
    }
  }

  return {
    rename: async (connection, alias, signal) => readSession(await request("PATCH", "", connectionHeaders(connection), { alias }, signal)),
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
    select: async (connection, currentRevision, target): Promise<TargetSelection> => {
      const value = await request("PUT", "/target", connectionHeaders(connection), { revision: currentRevision, target });
      if (value["code"] === "target_locked")
        return {
          revision: parse(revision, value["revision"]),
          alias: parse(text, value["alias"]),
          remainingMillis: parse(remaining, value["remainingMillis"]),
        };
      return readTargetState(value);
    },
    renew: async (connection, grantId) =>
      readGrant(await request("POST", "/target", { ...connectionHeaders(connection), "X-Editor-Grant": grantId })),
    release: async (connection, currentRevision, grantId) => {
      const value = await request(
        "DELETE",
        "/target",
        { ...connectionHeaders(connection), "X-Editor-Grant": grantId },
        { revision: currentRevision },
      );
      return parse(object({ revision }), value);
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

const text = pipe(
  string(),
  check((value) => value.trim() !== ""),
);
const id = pipe(string(), regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/));
const revision = pipe(string(), regex(/^(0|[1-9][0-9]*)$/));
const remaining = pipe(number(), safeInteger(), minValue(0), maxValue(30_000));
const grant = object({ target: object({ kind: picklist(["spread", "cover"]), id }), grantId: id, remainingMillis: remaining });
const session = object({ sessionId: id, ownerId: id, alias: text, leaseSeconds: literal(30), heartbeatSeconds: literal(10) });
const readGrant = (value: unknown): TargetGrant => parse(grant, value);
const readTargetState = (value: unknown): TargetState => parse(object({ revision, grant: nullable(grant) }), value);
const readSession = (value: unknown): RegisteredSession => parse(session, value);

function readEvent(name: string | undefined, value: unknown): SessionEvent {
  const fields = parse(jsonObject, value);
  if (name === "bound") return { kind: "bound", ...parse(object({ sessionId: id, tabId: id, generation: id }), fields) };
  if (name === "tab_blocked") return { kind: "tab_blocked", ...parse(object({ alias: text }), fields) };
  if (name === "session_lost") return { kind: "session_lost" };
  if (name === "ownership_changed") return { kind: "ownership_changed" };
  throw new Error("The browser tab connection sent an unknown event.");
}
