import { AlbumRequestError } from "../albums/model/Album";
import type { EditorSession } from "./EditorSession";

export function makeEditorRequests(fetcher: typeof fetch, session: Pick<EditorSession, "available" | "headers" | "interrupt">): typeof fetch {
  return async (input, init) => {
    const method = init?.method ?? "GET";
    const headers = new Headers(init?.headers);
    if (method !== "GET" && method !== "HEAD") {
      if (!session.available()) throw new AlbumRequestError(409, "Reconnect this browser before saving. Your draft is retained.");
      for (const [name, value] of Object.entries(session.headers())) headers.set(name, value);
    }
    let response: Response;
    try {
      response = await fetcher(input, { ...init, headers });
    } catch (failure) {
      if (init?.signal?.aborted !== true) session.interrupt("The backend is unreachable. Editing is blocked and unsaved drafts are retained.");
      throw failure;
    }
    if (response.status === 409) {
      const body: unknown = await response.clone().json();
      if (typeof body !== "object" || body === null || !("code" in body)) throw new Error("The mutation conflict response could not be decoded.");
      if (["stale_session", "obsolete_connection", "stale_grant", "stale_transition", "target_locked"].includes(String(body.code)))
        session.interrupt("Editing ownership was rejected. Reconnect, read saved content and review retained drafts before saving again.");
    }
    return response;
  };
}
