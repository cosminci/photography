import { createRoot } from "solid-js";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createEditorSession,
  EditorSessionError,
  sameTarget,
  type EditorSessionTransport,
  type SessionEvent,
} from "../../../src/features/editing/EditorSession";

const identity = "00000000-0000-4000-8000-000000000001";
const sessionId = "00000000-0000-4000-8000-000000000002";
const tabId = "00000000-0000-4000-8000-000000000003";
const generation = "00000000-0000-4000-8000-000000000004";
const target = { kind: "spread", id: identity } as const;
const grant = { target, grantId: "00000000-0000-4000-8000-000000000005", remainingMillis: 30_000 };
const registered = { sessionId, ownerId: identity, alias: "Cosmin", leaseSeconds: 30, heartbeatSeconds: 10 };
const storageKey = "photography.editor";
const disposals: (() => void)[] = [];

afterEach(() => {
  for (const dispose of disposals.splice(0)) dispose();
  localStorage.clear();
  vi.useRealTimers();
});

function buildSession(
  overrides: Partial<EditorSessionTransport> = {},
  storage: Pick<Storage, "getItem" | "setItem"> = localStorage,
  identityFactory: () => string = () => tabId,
) {
  let receive: (event: SessionEvent) => void = () => {
    throw new Error("Session stream was not started");
  };
  const transport: EditorSessionTransport = {
    register: vi.fn().mockResolvedValue(registered),
    subscribe: vi.fn<EditorSessionTransport["subscribe"]>((_session, _tab, listener, signal) => {
      receive = listener;
      return new Promise<void>((resolve) => {
        signal.addEventListener(
          "abort",
          () => {
            resolve();
          },
          { once: true },
        );
      });
    }),
    heartbeat: vi.fn().mockResolvedValue(registered),
    readTarget: vi.fn().mockResolvedValue({ revision: "0", grant: null }),
    select: vi.fn().mockResolvedValue({ revision: "1", grant }),
    renew: vi.fn().mockResolvedValue(grant),
    release: vi.fn().mockResolvedValue({ revision: "2" }),
    ...overrides,
  };
  const session = createRoot((dispose) => {
    disposals.push(dispose);
    return createEditorSession(transport, storage, identityFactory, identity);
  });
  return {
    session,
    transport,
    emit: (event: SessionEvent) => {
      receive(event);
    },
  };
}

async function connect() {
  localStorage.setItem(storageKey, JSON.stringify({ editorId: identity, alias: "Cosmin", sessionId }));
  const built = buildSession();
  await vi.waitFor(() => {
    expect(built.transport.subscribe).toHaveBeenCalled();
  });
  built.emit({ kind: "bound", sessionId, tabId, generation });
  await vi.waitFor(() => {
    expect(built.session.available()).toBe(true);
  });
  return built;
}

describe("browser editing session", () => {
  it.each([
    { stage: "registration", outcome: "success" },
    { stage: "registration", outcome: "failure" },
    { stage: "binding heartbeat", outcome: "success" },
    { stage: "binding heartbeat", outcome: "failure" },
    { stage: "binding read", outcome: "success" },
    { stage: "binding read", outcome: "failure" },
  ])("should supersede hung $stage and isolate its late $outcome", async ({ stage, outcome }) => {
    let finish!: () => void;
    const pending = new Promise<void>((resolve) => {
      finish = resolve;
    });
    const register = vi.fn<EditorSessionTransport["register"]>().mockResolvedValue(registered);
    const heartbeat = vi.fn<EditorSessionTransport["heartbeat"]>().mockResolvedValue(registered);
    const readTarget = vi.fn<EditorSessionTransport["readTarget"]>().mockResolvedValue({ revision: "0", grant: null });
    const completeOld = async () => {
      await pending;
      if (outcome === "failure") throw new Error("Old establishment failed");
    };
    if (stage === "registration")
      register.mockImplementationOnce(async () => {
        await completeOld();
        return registered;
      });
    if (stage === "binding heartbeat")
      heartbeat.mockImplementationOnce(async () => {
        await completeOld();
        return registered;
      });
    if (stage === "binding read")
      readTarget.mockImplementationOnce(async () => {
        await completeOld();
        return { revision: "0", grant: null };
      });
    localStorage.setItem(storageKey, JSON.stringify({ editorId: identity, alias: "Cosmin", sessionId }));
    const built = buildSession({ register, heartbeat, readTarget });
    if (stage !== "registration") {
      await vi.waitFor(() => {
        expect(built.transport.subscribe).toHaveBeenCalledOnce();
      });
      built.emit({ kind: "bound", sessionId, tabId, generation });
      await vi.waitFor(() => {
        expect(stage === "binding read" ? readTarget : heartbeat).toHaveBeenCalledOnce();
      });
    }

    expect(built.session.connecting()).toBe(true);
    const maybeOldSignal =
      stage === "registration"
        ? register.mock.calls[0]?.[3]
        : stage === "binding read"
          ? readTarget.mock.calls[0]?.[1]
          : heartbeat.mock.calls[0]?.[1];
    await built.session.reconnect();
    expect(maybeOldSignal?.aborted).toBe(true);
    await vi.waitFor(() => {
      expect(built.transport.subscribe).toHaveBeenCalledTimes(stage === "registration" ? 1 : 2);
    });
    built.emit({ kind: "bound", sessionId, tabId, generation: identity });
    await vi.waitFor(() => {
      expect(built.session.available()).toBe(true);
    });
    finish();
    await pending;
    await Promise.resolve();

    expect(built.session.headers()["X-Editor-Generation"]).toBe(identity);
    expect(built.session.message()).toBeUndefined();
    expect(built.session.connecting()).toBe(false);
  });

  it("should cancel request lifetimes on target-only expiry and session loss without confusing the two leases", async () => {
    vi.useFakeTimers();
    const { session, transport } = await connect();
    vi.mocked(transport.select).mockResolvedValue({ revision: "1", grant: { ...grant, remainingMillis: 1000 } });
    await session.select(target);
    const granted = session.requestSignal();
    await vi.advanceTimersByTimeAsync(1000);
    expect(granted.aborted).toBe(true);
    expect(session.available()).toBe(true);
    const live = session.requestSignal();
    expect(live.aborted).toBe(false);
    session.interrupt("offline");
    expect(live.aborted).toBe(true);
    expect(session.requestSignal().aborted).toBe(false);
    expect(session.available()).toBe(false);
  });

  it("should reuse identification already remembered by another first-run tab", async () => {
    const { session, transport } = buildSession();
    localStorage.setItem(storageKey, JSON.stringify({ editorId: identity, alias: "Cosmin", sessionId }));

    await session.identify("Mara");

    expect(transport.register).toHaveBeenCalledExactlyOnceWith(identity, "Cosmin", sessionId, expect.any(AbortSignal));
    expect(session.alias()).toBe("Cosmin");
  });

  it("should reject a binding whose authoritative confirmation arrived after its lease elapsed", async () => {
    vi.useFakeTimers();
    localStorage.setItem(storageKey, JSON.stringify({ editorId: identity, alias: "Cosmin", sessionId }));
    const built = buildSession({
      readTarget: vi.fn().mockImplementation(() => {
        vi.setSystemTime(Date.now() + 30_001);
        return Promise.resolve({ revision: "0", grant: null });
      }),
    });
    await vi.waitFor(() => {
      expect(built.transport.subscribe).toHaveBeenCalled();
    });
    built.emit({ kind: "bound", sessionId, tabId, generation });
    await vi.waitFor(() => {
      expect(built.session.connecting()).toBe(false);
    });
    expect(built.session.available()).toBe(false);
    expect(built.session.message()).toMatch(/confirmation expired/);
  });

  it.each(["accepted", "rejected"] as const)(
    "should keep replacement heartbeat progress independent of a late %s old connection",
    async (outcome) => {
      vi.useFakeTimers();
      const { session, transport, emit } = await connect();
      let finishOld!: () => void;
      const old = new Promise<void>((resolve) => {
        finishOld = resolve;
      });
      vi.mocked(transport.heartbeat)
        .mockClear()
        .mockImplementationOnce(async () => {
          await old;
          if (outcome === "rejected") throw new Error("old connection rejected");
          return registered;
        });
      await vi.advanceTimersByTimeAsync(10_000);
      session.interrupt("offline");
      await session.reconnect();
      emit({ kind: "bound", sessionId, tabId, generation: identity });
      await vi.waitFor(() => {
        expect(session.available()).toBe(true);
      });
      let finishCurrent!: () => void;
      const current = new Promise<void>((resolve) => {
        finishCurrent = resolve;
      });
      vi.mocked(transport.heartbeat).mockImplementationOnce(async () => {
        await current;
        return registered;
      });
      await vi.advanceTimersByTimeAsync(10_000);
      finishOld();
      await vi.advanceTimersByTimeAsync(10_000);
      expect(transport.heartbeat).toHaveBeenCalledTimes(3);
      finishCurrent();
      await vi.advanceTimersByTimeAsync(0);
      expect(session.available()).toBe(true);
      expect(session.headers()["X-Editor-Generation"]).toBe(identity);
    },
  );

  it("should recover a new connection without waiting for an obsolete target read", async () => {
    const { session, transport, emit } = await connect();
    let finish!: () => void;
    const pending = new Promise<void>((resolve) => {
      finish = resolve;
    });
    vi.mocked(transport.readTarget).mockImplementationOnce(async () => {
      await pending;
      return { revision: "0", grant: null };
    });
    const oldSelection = session.select(target);
    const obsoleteDestination = session.select({ kind: "cover", id: identity });
    const obsoleteRelease = session.select(null);
    await vi.waitFor(() => {
      expect(session.targetPending()).toBe(true);
    });
    session.interrupt("offline");
    await session.reconnect();
    emit({ kind: "bound", sessionId, tabId, generation: identity });
    await vi.waitFor(() => {
      expect(session.available()).toBe(true);
    });
    expect(await session.select(target, true)).toBe(true);
    finish();
    expect(await oldSelection).toBe(false);
    expect(await obsoleteDestination).toBe(false);
    expect(await obsoleteRelease).toBe(false);
    expect(transport.select).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ generation: identity }), "0", target);
    expect(transport.release).not.toHaveBeenCalled();
    expect(session.targetReason(target)).toBeUndefined();
  });

  it("should ignore a rejected stream after its document is disposed", async () => {
    let reject!: (failure: Error) => void;
    const pending = new Promise<void>((_resolve, fail) => {
      reject = fail;
    });
    localStorage.setItem(storageKey, JSON.stringify({ editorId: identity, alias: "Cosmin", sessionId }));
    const built = buildSession({ subscribe: vi.fn().mockReturnValue(pending) });
    await vi.waitFor(() => {
      expect(built.transport.subscribe).toHaveBeenCalled();
    });
    const dispose = disposals.pop();
    if (dispose === undefined) throw new Error("Missing scope");
    dispose();
    reject(new Error("late stream failure"));
    await Promise.resolve();
    expect(built.session.available()).toBe(false);
    expect(() => built.session.headers()).toThrow();
  });

  it.each([
    { destination: target, same: true },
    { destination: { kind: "spread" as const, id: tabId }, same: false },
    { destination: { kind: "cover" as const, id: identity }, same: false },
  ])("should compare target identity and kind for $destination", ({ destination, same }) => {
    expect(sameTarget(target, destination)).toBe(same);
  });

  it.each([
    null,
    {},
    { editorId: 1, alias: "Mara" },
    { editorId: identity, alias: 1 },
    { editorId: identity, alias: " " },
    { editorId: identity, alias: "Mara", sessionId: 1 },
  ])("should expose invalid remembered identity %s without opening the application", (value) => {
    localStorage.setItem(storageKey, JSON.stringify(value));
    const { session, transport } = buildSession();
    expect(session.opened()).toBe(false);
    expect(session.message()).toMatch(/invalid/);
    expect(transport.register).not.toHaveBeenCalled();
  });

  it("should preserve explicit identity, storage and registration failures and permit a later retry", async () => {
    const storage = {
      getItem: vi
        .fn()
        .mockImplementationOnce(() => {
          throw new Error("storage unavailable");
        })
        .mockReturnValue(null),
      setItem: vi.fn().mockImplementationOnce(() => {
        throw new Error("cannot remember identification");
      }),
    };
    const { session } = buildSession({}, storage);
    expect(session.message()).toBe("storage unavailable");
    await session.identify("Mara");
    expect(session.message()).toBe("cannot remember identification");
    await session.reconnect();
    expect(session.identifying()).toBe(true);
    expect(session.connecting()).toBe(false);
    const failed = buildSession({}, localStorage, () => {
      throw new Error("random source unavailable");
    });
    expect(failed.session.message()).toBe("random source unavailable");
  });

  it.each([new EditorSessionError("credential_required", "Credential required"), "network unavailable"])(
    "should retain registration failure %s without inventing a session",
    async (failure) => {
      localStorage.setItem(storageKey, JSON.stringify({ editorId: identity, alias: "Cosmin" }));
      const { session, transport } = buildSession({ register: vi.fn().mockRejectedValue(failure) });
      await vi.waitFor(() => {
        expect(session.connecting()).toBe(false);
      });
      expect(session.opened()).toBe(false);
      expect(session.message()).toMatch(/Credential|could not be confirmed/);
      expect(transport.subscribe).not.toHaveBeenCalled();
    },
  );

  it("should require a nonblank remembered alias and establish a tab binding before opening the application", async () => {
    const { session, transport, emit } = buildSession();

    expect(session.opened()).toBe(false);
    await session.identify("   ");
    expect(transport.register).not.toHaveBeenCalled();
    expect(session.message()).toMatch(/nonblank/i);
    await session.identify("Cosmin");
    expect(transport.register).toHaveBeenCalledWith(identity, "Cosmin", undefined, expect.any(AbortSignal));
    expect(session.opened()).toBe(false);
    emit({ kind: "bound", sessionId, tabId, generation });

    await vi.waitFor(() => {
      expect(session.opened()).toBe(true);
    });
    expect(JSON.parse(localStorage.getItem(storageKey) ?? "")).toEqual({ editorId: identity, alias: "Cosmin", sessionId });
    expect(session.headers()).toEqual({
      "X-Editor-Session": sessionId,
      "X-Editor-Tab": tabId,
      "X-Editor-Generation": generation,
    });
  });

  it("should block the entire application in a second tab without discarding the remembered identity", async () => {
    localStorage.setItem(storageKey, JSON.stringify({ editorId: identity, alias: "Cosmin", sessionId }));
    const { session, transport, emit } = buildSession();
    await vi.waitFor(() => {
      expect(transport.subscribe).toHaveBeenCalled();
    });

    emit({ kind: "tab_blocked", alias: "Cosmin" });

    expect(session.opened()).toBe(false);
    expect(session.available()).toBe(false);
    expect(session.message()).toMatch(/another tab/i);
    expect(() => session.headers()).toThrow();
    expect(JSON.parse(localStorage.getItem(storageKey) ?? "")).toEqual({ editorId: identity, alias: "Cosmin", sessionId });
  });

  it("should acquire one target, release it for Reader and retain the independent session", async () => {
    const { session, transport } = await connect();

    await session.select(target);

    expect(transport.select).toHaveBeenCalledWith(expect.anything(), "0", target);
    expect(session.targetReason(target)).toBeUndefined();
    expect(session.headers()["X-Editor-Grant"]).toBe(grant.grantId);
    vi.mocked(transport.readTarget).mockResolvedValue({ revision: "1", grant });
    await session.select(null);
    expect(transport.release).toHaveBeenCalledWith(expect.anything(), "1", grant.grantId);
    expect(session.available()).toBe(true);
    expect(session.headers()["X-Editor-Grant"]).toBeUndefined();
  });

  it("should renew the session and target independently every ten seconds and block immediately on connection loss", async () => {
    vi.useFakeTimers();
    const { session, transport, emit } = await connect();
    await session.select(target);
    vi.mocked(transport.heartbeat).mockClear();

    await vi.advanceTimersByTimeAsync(10_000);
    expect(transport.heartbeat).toHaveBeenCalledOnce();
    expect(transport.renew).toHaveBeenCalledWith(expect.anything(), grant.grantId);
    emit({ kind: "session_lost" });

    expect(session.available()).toBe(false);
    expect(session.opened()).toBe(true);
    expect(session.targetReason(target)).toMatch(/lost|unconfirmed/i);
    expect(() => session.headers()).toThrow();
  });

  it("should show contention, retry explicitly and reconcile a lost selection acknowledgement without replaying it", async () => {
    const { session, transport, emit } = await connect();
    vi.mocked(transport.select).mockResolvedValueOnce({ revision: "1", alias: "Mara", remainingMillis: 20_000 });
    await session.select(target);
    expect(session.targetReason(target)).toMatch(/Mara/);
    expect(session.headers()["X-Editor-Grant"]).toBeUndefined();
    await session.select(target);
    expect(transport.select).toHaveBeenCalledOnce();
    expect(session.targetReason(target)).toMatch(/Mara/);
    vi.mocked(transport.select).mockRejectedValueOnce(new Error("lost acknowledgement"));
    expect(await session.select(target, true)).toBe(false);
    expect(session.available()).toBe(false);
    expect(session.message()).toMatch(/uncertain/);
    vi.mocked(transport.readTarget).mockResolvedValue({ revision: "2", grant });
    await session.reconnect();
    emit({ kind: "bound", sessionId, tabId, generation });
    await vi.waitFor(() => {
      expect(session.available()).toBe(true);
    });
    await session.select(target, true);
    expect(session.targetReason(target)).toBeUndefined();
    expect(transport.select).toHaveBeenCalledTimes(2);
    expect(session.targetReason({ kind: "cover", id: identity })).toMatch(/view only/i);
  });

  it("should block an unconfirmed former grant without automatically reacquiring the same target", async () => {
    const { session } = await connect();
    await session.select(target);

    expect(await session.select(target)).toBe(false);
    expect(session.headers()["X-Editor-Grant"]).toBeUndefined();
    expect(session.targetReason(target)).toMatch(/Choose Edit spread/);
  });

  it("should reconcile lost release acknowledgements using current ownership rather than repeat an obsolete release", async () => {
    const { session, transport, emit } = await connect();
    await session.select(target);
    vi.mocked(transport.readTarget).mockResolvedValueOnce({ revision: "1", grant }).mockResolvedValue({ revision: "2", grant: null });
    vi.mocked(transport.release).mockRejectedValue(new Error("lost release acknowledgement"));

    expect(await session.select(null)).toBe(false);
    expect(session.available()).toBe(false);
    expect(session.message()).toMatch(/uncertain/);
    await session.reconnect();
    emit({ kind: "bound", sessionId, tabId, generation });
    await vi.waitFor(() => {
      expect(session.available()).toBe(true);
    });
    expect(await session.select(null)).toBe(true);
    expect(transport.release).toHaveBeenCalledOnce();
    expect(session.available()).toBe(true);
  });

  it("should keep session heartbeat independent when no target is held and expire a hung heartbeat without extending authority", async () => {
    vi.useFakeTimers();
    const { session, transport } = await connect();
    vi.mocked(transport.heartbeat)
      .mockClear()
      .mockImplementation(() => new Promise(() => undefined));
    await vi.advanceTimersByTimeAsync(10_000);
    expect(transport.heartbeat).toHaveBeenCalledOnce();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(transport.heartbeat).toHaveBeenCalledOnce();
    expect(transport.renew).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(session.available()).toBe(false);
    expect(session.message()).toMatch(/expired/);
  });

  it("should preserve a still-live session when its independent target deadline expires", async () => {
    vi.useFakeTimers();
    const { session, transport } = await connect();
    vi.mocked(transport.select).mockResolvedValue({ revision: "1", grant: { ...grant, remainingMillis: 1000 } });
    await session.select(target);
    await vi.advanceTimersByTimeAsync(1000);
    expect(session.available()).toBe(true);
    expect(session.targetReason(target)).toMatch(/expired/);
    expect(session.headers()["X-Editor-Grant"]).toBeUndefined();
    await session.select(target);
    expect(transport.select).toHaveBeenCalledOnce();
  });

  it.each(["session", "target"] as const)("should reject elapsed %s authority even before a throttled browser timer runs", async (lease) => {
    vi.useFakeTimers();
    const { session, transport } = await connect();
    if (lease === "target") vi.mocked(transport.select).mockResolvedValue({ revision: "1", grant: { ...grant, remainingMillis: 1000 } });
    await session.select(target);
    if (lease === "session") {
      vi.setSystemTime(Date.now() + 30_001);
      expect(() => session.headers()).toThrow(/reconnects/);
      expect(session.available()).toBe(false);
    } else {
      vi.setSystemTime(Date.now() + 1001);
      expect(session.headers()["X-Editor-Grant"]).toBeUndefined();
      expect(session.available()).toBe(true);
    }
  });

  it("should read and resume a still-live grant after reload without acquiring a new grant", async () => {
    localStorage.setItem(storageKey, JSON.stringify({ editorId: identity, alias: "Cosmin", sessionId }));
    const { session, transport, emit } = buildSession({ readTarget: vi.fn().mockResolvedValue({ revision: "5", grant }) });
    await vi.waitFor(() => {
      expect(transport.subscribe).toHaveBeenCalled();
    });
    emit({ kind: "bound", sessionId, tabId, generation });
    await vi.waitFor(() => {
      expect(session.available()).toBe(true);
    });

    await session.select(target);
    expect(transport.select).not.toHaveBeenCalled();
    expect(session.headers()["X-Editor-Grant"]).toBe(grant.grantId);
  });

  it("should fence a pending ownership read when the document disconnects and keep retries serialized", async () => {
    const { session, transport } = await connect();
    let finish!: () => void;
    const pending = new Promise<void>((resolve) => {
      finish = resolve;
    });
    vi.mocked(transport.readTarget).mockImplementationOnce(async () => {
      await pending;
      return { revision: "0", grant: null };
    });
    const selecting = session.select(target);
    await vi.waitFor(() => {
      expect(session.targetPending()).toBe(true);
    });
    expect(session.targetReason(target)).toMatch(/confirming/i);
    session.interrupt("offline");
    finish();

    expect(await selecting).toBe(false);
    expect(transport.select).not.toHaveBeenCalled();
    expect(session.message()).toBe("offline");
  });

  it.each(["accepted", "rejected"] as const)("should ignore a late %s old-grant renewal after another target is acquired", async (outcome) => {
    vi.useFakeTimers();
    const { session, transport } = await connect();
    await session.select(target);
    let finish!: () => void;
    const pending = new Promise<void>((resolve) => {
      finish = resolve;
    });
    vi.mocked(transport.renew).mockImplementationOnce(async () => {
      await pending;
      if (outcome === "rejected") throw new Error("old grant rejected");
      return grant;
    });
    await vi.advanceTimersByTimeAsync(10_000);
    const destination = { kind: "cover", id: identity } as const;
    const replacement = { ...grant, target: destination, grantId: generation };
    vi.mocked(transport.select).mockResolvedValue({ revision: "2", grant: replacement });
    vi.mocked(transport.readTarget).mockResolvedValue({ revision: "1", grant });
    await session.select(destination);
    finish();
    await vi.advanceTimersByTimeAsync(0);
    expect(session.headers()["X-Editor-Grant"]).toBe(generation);
    expect(session.available()).toBe(true);
  });

  it("should ignore terminal callbacks and late rejected mutations from a disposed stream", async () => {
    const { session, transport } = await connect();
    let reject!: (failure: Error) => void;
    const pending = new Promise<never>((_resolve, fail) => {
      reject = fail;
    });
    vi.mocked(transport.select).mockReturnValueOnce(pending);
    const selecting = session.select(target);
    await vi.waitFor(() => {
      expect(transport.select).toHaveBeenCalled();
    });
    const receive = vi.mocked(transport.subscribe).mock.calls[0]?.[2];
    if (receive === undefined) throw new Error("Missing subscription");
    const dispose = disposals.pop();
    if (dispose === undefined) throw new Error("Missing scope");
    dispose();
    receive({ kind: "session_lost" });
    reject(new Error("late rejection"));
    expect(await selecting).toBe(false);
    await session.reconnect();
    expect(transport.subscribe).toHaveBeenCalledOnce();
  });

  it("should keep an active stream unavailable after its HTTP body closes and abort it on page departure", async () => {
    let finish!: () => void;
    const pending = new Promise<void>((resolve) => {
      finish = resolve;
    });
    localStorage.setItem(storageKey, JSON.stringify({ editorId: identity, alias: "Cosmin", sessionId }));
    const { session, transport } = buildSession({ subscribe: vi.fn().mockReturnValue(pending) });
    await vi.waitFor(() => {
      expect(transport.subscribe).toHaveBeenCalled();
    });
    expect(transport.subscribe).toHaveBeenCalledOnce();
    const receive = vi.mocked(transport.subscribe).mock.calls[0]?.[2];
    if (receive === undefined) throw new Error("Missing subscription");
    receive({ kind: "bound", sessionId, tabId, generation });
    await vi.waitFor(() => {
      expect(session.available()).toBe(true);
    });
    finish();
    await vi.waitFor(() => {
      expect(session.available()).toBe(false);
    });
    expect(session.message()).toMatch(/closed/);
    window.dispatchEvent(new Event("pagehide"));
    expect(vi.mocked(transport.subscribe).mock.calls[0]?.[3].aborted).toBe(true);
  });

  it.each(["heartbeat", "renew"] as const)(
    "should immediately suspend editing after rejected %s and not renew expired authority",
    async (operation) => {
      vi.useFakeTimers();
      const { session, transport } = await connect();
      await session.select(target);
      vi.mocked(transport[operation]).mockRejectedValue(new Error("authority rejected"));
      await vi.advanceTimersByTimeAsync(10_000);
      expect(session.available()).toBe(false);
      expect(session.message()).toBe("authority rejected");
    },
  );

  it("should suspend on offline and fence delayed events and requests from a replaced stream generation", async () => {
    const { session, transport } = await connect();
    const oldReceive = vi.mocked(transport.subscribe).mock.calls[0]?.[2];
    if (oldReceive === undefined) throw new Error("Missing first subscription");
    window.dispatchEvent(new Event("offline"));
    expect(session.available()).toBe(false);
    expect(await session.select(target)).toBe(false);
    await session.reconnect();
    const receive = vi.mocked(transport.subscribe).mock.calls[1]?.[2];
    if (receive === undefined) throw new Error("Missing replacement subscription");
    oldReceive({ kind: "session_lost" });
    receive({ kind: "bound", sessionId, tabId, generation: identity });
    await vi.waitFor(() => {
      expect(session.available()).toBe(true);
    });
    oldReceive({ kind: "bound", sessionId, tabId, generation });
    expect(session.headers()["X-Editor-Generation"]).toBe(identity);
  });

  it.each(["registration", "registration failure", "binding", "binding read", "binding read failure", "selection", "release"] as const)(
    "should ignore late %s completion after its document is disposed",
    async (phase) => {
      let finish!: () => void;
      const pending = new Promise<void>((resolve) => {
        finish = resolve;
      });
      localStorage.setItem(storageKey, JSON.stringify({ editorId: identity, alias: "Cosmin", sessionId }));
      const built = buildSession(
        phase.startsWith("registration")
          ? {
              register: vi.fn().mockImplementation(async () => {
                await pending;
                if (phase === "registration failure") throw new Error("late registration failure");
                return registered;
              }),
            }
          : {},
      );
      if (!phase.startsWith("registration")) {
        await vi.waitFor(() => {
          expect(built.transport.subscribe).toHaveBeenCalled();
        });
        if (phase === "binding")
          vi.mocked(built.transport.heartbeat).mockImplementationOnce(async () => {
            await pending;
            return registered;
          });
        if (phase.startsWith("binding read"))
          vi.mocked(built.transport.readTarget).mockImplementationOnce(async () => {
            await pending;
            if (phase === "binding read failure") throw new Error("late binding read failure");
            return { revision: "0", grant: null };
          });
        built.emit({ kind: "bound", sessionId, tabId, generation });
        if (phase.startsWith("binding read"))
          await vi.waitFor(() => {
            expect(built.transport.readTarget).toHaveBeenCalled();
          });
        if (!phase.startsWith("binding"))
          await vi.waitFor(() => {
            expect(built.session.available()).toBe(true);
          });
      }
      if (phase === "selection")
        vi.mocked(built.transport.select).mockImplementationOnce(async () => {
          await pending;
          return { revision: "1", grant };
        });
      if (phase === "release") {
        vi.mocked(built.transport.readTarget).mockResolvedValue({ revision: "1", grant });
        vi.mocked(built.transport.release).mockImplementationOnce(async () => {
          await pending;
          return { revision: "2" };
        });
      }
      let changing: Promise<boolean> | undefined;
      if (phase === "selection" || phase === "release") {
        changing = built.session.select(phase === "selection" ? target : null);
        await vi.waitFor(() => {
          expect(phase === "selection" ? built.transport.select : built.transport.release).toHaveBeenCalled();
        });
      }
      const dispose = disposals.pop();
      if (dispose === undefined) throw new Error("Missing owned scope");
      dispose();
      finish();
      await changing;
      await Promise.resolve();
      expect(await built.session.select(target)).toBe(false);
    },
  );

  it.each(["closed", "failed", "wrong binding", "binding read failure"] as const)("should block editing when a tab stream is %s", async (failure) => {
    localStorage.setItem(storageKey, JSON.stringify({ editorId: identity, alias: "Cosmin", sessionId }));
    const subscribe =
      failure === "closed"
        ? vi.fn().mockResolvedValue(undefined)
        : failure === "failed"
          ? vi.fn().mockRejectedValue(new Error("stream failed"))
          : undefined;
    const built = buildSession(subscribe === undefined ? {} : { subscribe });
    await vi.waitFor(() => {
      expect(built.transport.subscribe).toHaveBeenCalled();
    });
    if (failure === "wrong binding") built.emit({ kind: "bound", sessionId: identity, tabId, generation });
    if (failure === "binding read failure") {
      vi.mocked(built.transport.readTarget).mockRejectedValue(new Error("read failed"));
      built.emit({ kind: "bound", sessionId, tabId, generation });
    }
    await vi.waitFor(() => {
      expect(built.session.connecting()).toBe(false);
    });
    expect(built.session.available()).toBe(false);
    expect(built.session.opened()).toBe(false);
    expect(built.session.message()).toMatch(/closed|failed|Unexpected/);
  });
});
