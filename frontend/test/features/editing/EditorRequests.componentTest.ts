import { describe, expect, it, vi } from "vitest";
import { makeEditorRequests } from "../../../src/features/editing/EditorRequests";
import type { EditorSession } from "../../../src/features/editing/EditorSession";

function buildSession(available = true): Pick<EditorSession, "available" | "headers" | "interrupt"> {
  return {
    available: () => available,
    headers: () => ({ "X-Editor-Session": "private", "X-Editor-Grant": "exact-grant" }),
    interrupt: vi.fn(),
  };
}

describe("browser mutation credentials", () => {
  it.each(["POST", "PUT", "PATCH", "DELETE"])(
    "should attach current private ownership headers to %s without changing request content",
    async (method) => {
      const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 204 }));
      const session = buildSession();
      const headers = { "Content-Type": "application/json" };

      await makeEditorRequests(fetcher, session)("/api/albums", { method, headers, body: "{}" });

      expect(fetcher.mock.lastCall?.[0]).toBe("/api/albums");
      const init = fetcher.mock.lastCall?.[1];
      expect(init?.method).toBe(method);
      expect(init?.body).toBe("{}");
      expect(Object.fromEntries(new Headers(init?.headers))).toEqual({
        "content-type": "application/json",
        "x-editor-session": "private",
        "x-editor-grant": "exact-grant",
      });
      expect(headers).toEqual({ "Content-Type": "application/json" });
    },
  );

  it.each([undefined, "GET", "HEAD"])("should keep %s viewing requests public", async (method) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response("{}"));
    const init = method === undefined ? undefined : { method };

    await makeEditorRequests(fetcher, buildSession(false))("/api/albums", init);

    expect(Object.fromEntries(new Headers(fetcher.mock.lastCall?.[1]?.headers))).toEqual({});
  });

  it("should reject a write before dispatch when the current tab connection is unavailable", async () => {
    const fetcher = vi.fn<typeof fetch>();
    await expect(makeEditorRequests(fetcher, buildSession(false))("/api/albums", { method: "POST" })).rejects.toThrow(/reconnect/i);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it.each(["stale_session", "obsolete_connection", "stale_grant", "stale_transition", "target_locked", "composition_changed"])(
    "should distinguish ownership rejection %s from ordinary content conflicts",
    async (code) => {
      const session = buildSession();
      const response = new Response(JSON.stringify({ code }), { status: 409 });
      const request = makeEditorRequests(vi.fn<typeof fetch>().mockResolvedValue(response), session);

      expect(await request("/api/albums", { method: "PUT" })).toBe(response);
      expect(await response.json()).toEqual({ code });
      expect(session.interrupt).toHaveBeenCalledTimes(code === "composition_changed" ? 0 : 1);
    },
  );

  it.each([null, "conflict", {}, []])("should report malformed conflict representation %s", async (body) => {
    const request = makeEditorRequests(vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(body), { status: 409 })), buildSession());
    await expect(request("/api/albums", { method: "PUT" })).rejects.toThrow(/decoded/);
  });

  it.each([true, false])(
    "should preserve the transport failure and interrupt editing only for noncancelled requests, cancelled=%s",
    async (cancelled) => {
      const failure = new Error("offline");
      const session = buildSession();
      const controller = new AbortController();
      if (cancelled) controller.abort();
      const request = makeEditorRequests(vi.fn<typeof fetch>().mockRejectedValue(failure), session);

      await expect(request("/api/albums", { signal: controller.signal })).rejects.toBe(failure);
      expect(session.interrupt).toHaveBeenCalledTimes(cancelled ? 0 : 1);
    },
  );
});
