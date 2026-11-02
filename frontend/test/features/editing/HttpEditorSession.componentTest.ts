import { describe, expect, it, vi } from "vitest";
import { makeHttpEditorSession } from "../../../src/features/editing/HttpEditorSession";

const sessionId = "00000000-0000-4000-8000-000000000001";
const tabId = "00000000-0000-4000-8000-000000000002";
const generation = "00000000-0000-4000-8000-000000000003";
const connection = { sessionId, tabId, generation };
const target = { kind: "cover", id: sessionId } as const;
const grant = { target, grantId: tabId, remainingMillis: 30_000 };

describe("editor session HTTP translation", () => {
  it("should register, resume, heartbeat, read, renew and release through their existing HTTP contracts", async () => {
    const registered = { sessionId, ownerId: tabId, alias: "Mara", leaseSeconds: 30, heartbeatSeconds: 10 };
    const fetcher = vi.fn<typeof fetch>();
    const transport = makeHttpEditorSession(fetcher);
    fetcher.mockResolvedValue(new Response(JSON.stringify(registered)));
    expect(await transport.register(tabId, "Mara", undefined)).toEqual(registered);
    expect(fetcher.mock.lastCall?.[1]?.headers).toEqual({ Accept: "application/json", "Content-Type": "application/json" });
    fetcher.mockResolvedValue(new Response(JSON.stringify(registered)));
    expect(await transport.register(tabId, "Mara", sessionId)).toEqual(registered);
    expect(fetcher.mock.lastCall?.[1]?.headers).toEqual({
      Accept: "application/json",
      "Content-Type": "application/json",
      "X-Editor-Session": sessionId,
    });
    fetcher.mockResolvedValue(new Response(JSON.stringify(registered)));
    expect(await transport.heartbeat(connection)).toEqual(registered);
    fetcher.mockResolvedValue(new Response(JSON.stringify({ revision: "0", grant: null })));
    expect(await transport.readTarget(connection)).toEqual({ revision: "0", grant: null });
    fetcher.mockResolvedValue(new Response(JSON.stringify(grant)));
    expect(await transport.renew(connection, grant.grantId)).toEqual(grant);
    expect(fetcher.mock.lastCall?.[1]?.headers).toEqual(expect.objectContaining({ "X-Editor-Grant": grant.grantId }));
    fetcher.mockResolvedValue(new Response(JSON.stringify({ revision: "2" })));
    expect(await transport.release(connection, "1", grant.grantId)).toEqual({ revision: "2" });
    expect(fetcher.mock.lastCall).toEqual(["/api/editor-session/target", expect.objectContaining({ method: "DELETE", body: '{"revision":"1"}' })]);
  });

  it("should preserve a contended owner's display alias and the advanced revision without treating contention as ownership", async () => {
    const value = { code: "target_locked", revision: "2", alias: "Cosmin", remainingMillis: 10_000 };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(value), { status: 409 }));

    expect(await makeHttpEditorSession(fetcher).select(connection, "1", target)).toEqual({
      revision: "2",
      alias: "Cosmin",
      remainingMillis: 10_000,
    });
  });

  it.each([
    { response: null, operation: "heartbeat" },
    { response: [], operation: "heartbeat" },
    { response: { sessionId, ownerId: tabId, alias: "Mara", leaseSeconds: 31, heartbeatSeconds: 10 }, operation: "heartbeat" },
    { response: { sessionId: "invalid", ownerId: tabId, alias: "Mara", leaseSeconds: 30, heartbeatSeconds: 10 }, operation: "heartbeat" },
    { response: { sessionId, ownerId: tabId, alias: " ", leaseSeconds: 30, heartbeatSeconds: 10 }, operation: "heartbeat" },
    { response: { revision: "01", grant: null }, operation: "target" },
    { response: { revision: "1", grant: { ...grant, target: { kind: "album", id: sessionId } } }, operation: "target" },
    { response: { revision: "1", grant: { ...grant, remainingMillis: -1 } }, operation: "target" },
    { response: { revision: "1", grant: { ...grant, remainingMillis: 30_001 } }, operation: "target" },
    { response: { revision: "1", grant: { ...grant, remainingMillis: "30" } }, operation: "target" },
    { response: { revision: "1", grant: { ...grant, remainingMillis: 1.5 } }, operation: "target" },
    { response: { revision: null, grant: null }, operation: "target" },
  ])("should reject invalid $operation representation $response", async ({ response, operation }) => {
    const transport = makeHttpEditorSession(vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(response))));
    await expect(operation === "heartbeat" ? transport.heartbeat(connection) : transport.readTarget(connection)).rejects.toThrow();
  });

  it("should expose explicit backend rejection instead of manufacturing a session", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response('{"code":"credential_required"}', { status: 409 }));
    await expect(makeHttpEditorSession(fetcher).register(tabId, "Cosmin", undefined)).rejects.toMatchObject({ code: "credential_required" });
  });

  it("should send private credentials only in headers and preserve canonical target revisions", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ revision: "10000000000000000", grant })));
    const transport = makeHttpEditorSession(fetcher);

    expect(await transport.select(connection, "9999999999999999", target)).toEqual({ revision: "10000000000000000", grant });
    expect(fetcher).toHaveBeenCalledWith(
      "/api/editor-session/target",
      expect.objectContaining({
        method: "PUT",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          "X-Editor-Session": sessionId,
          "X-Editor-Tab": tabId,
          "X-Editor-Generation": generation,
        },
        body: JSON.stringify({ revision: "9999999999999999", target }),
      }),
    );
  });

  it("should decode fragmented UTF-8 SSE, ignore comments and release its reader on abort", async () => {
    const encoder = new TextEncoder();
    const payload = encoder.encode(`: heartbeat\r\n\r\nevent: tab_blocked\r\ndata: {"alias":"Măriuca","ownerId":"${sessionId}"}\r\n\r\n`);
    const cancelled = vi.fn();
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(
        new ReadableStream({
          start(controller) {
            for (const byte of payload) controller.enqueue(new Uint8Array([byte]));
          },
          cancel: cancelled,
        }),
        { headers: { "Content-Type": "text/event-stream" } },
      ),
    );
    const events = vi.fn();
    const controller = new AbortController();
    const listening = makeHttpEditorSession(fetcher).subscribe(sessionId, tabId, events, controller.signal);
    await vi.waitFor(() => {
      expect(events).toHaveBeenCalledWith({ kind: "tab_blocked", alias: "Măriuca" });
    });

    controller.abort();
    await listening;

    expect(cancelled).toHaveBeenCalledOnce();
    expect(fetcher).toHaveBeenCalledWith(
      "/api/editor-session/events",
      expect.objectContaining({
        headers: { Accept: "text/event-stream", "X-Editor-Session": sessionId, "X-Editor-Tab": tabId },
        signal: controller.signal,
      }),
    );
  });

  it("should decode a binding and reject unknown event data rather than enabling access", async () => {
    const data = `event: bound\ndata: ${JSON.stringify(connection)}\n\nevent: unknown\ndata: {}\n\n`;
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(data, { headers: { "Content-Type": "text/event-stream" } }));
    const events = vi.fn();

    await expect(makeHttpEditorSession(fetcher).subscribe(sessionId, tabId, events, new AbortController().signal)).rejects.toThrow();
    expect(events).toHaveBeenCalledExactlyOnceWith({ kind: "bound", ...connection });
  });

  it.each([
    { response: () => new Response("failed", { status: 503 }) },
    { response: () => new Response("not a stream") },
    { response: () => new Response(null, { headers: { "Content-Type": "text/event-stream" } }) },
    { response: () => new Response('event: bound\ndata: {"sessionId":"invalid"}\n\n', { headers: { "Content-Type": "text/event-stream" } }) },
    { response: () => new Response("event: bound\ndata: {", { headers: { "Content-Type": "text/event-stream" } }) },
    { response: () => new Response("x".repeat(65_537), { headers: { "Content-Type": "text/event-stream" } }) },
  ])("should reject unavailable, truncated or invalid tab streams %#", async ({ response }) => {
    const receive = vi.fn();
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response());

    await expect(makeHttpEditorSession(fetcher).subscribe(sessionId, tabId, receive, new AbortController().signal)).rejects.toThrow();
    expect(receive).not.toHaveBeenCalled();
  });

  it("should accept session loss, ignore comments and stop delivery when a listener aborts", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(': heartbeat\n\nevent: session_lost\ndata: {"code":"stale_session"}\n\nevent: bound\ndata: {}\n\n', {
        headers: { "Content-Type": "text/event-stream" },
      }),
    );
    const controller = new AbortController();
    const receive = vi.fn(() => {
      controller.abort();
    });

    await makeHttpEditorSession(fetcher).subscribe(sessionId, tabId, receive, controller.signal);
    expect(receive).toHaveBeenCalledExactlyOnceWith({ kind: "session_lost" });
  });

  it("should finish a complete stream without claiming that closure grants editing", async () => {
    const receive = vi.fn();
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(": heartbeat\n\n", { headers: { "Content-Type": "text/event-stream" } }));
    await makeHttpEditorSession(fetcher).subscribe(sessionId, tabId, receive, new AbortController().signal);
    expect(receive).not.toHaveBeenCalled();
  });

  it("should release an already cancelled stream without delivering buffered events", async () => {
    const receive = vi.fn();
    const controller = new AbortController();
    controller.abort();
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response("event: session_lost\ndata: {}\n\n", { headers: { "Content-Type": "text/event-stream" } }));
    await makeHttpEditorSession(fetcher).subscribe(sessionId, tabId, receive, controller.signal);
    expect(receive).not.toHaveBeenCalled();
  });
});
