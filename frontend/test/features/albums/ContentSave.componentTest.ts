import { createRoot } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { AcknowledgedWriteError, AlbumRequestError } from "../../../src/features/albums/model/Album";
import { createContentSave } from "../../../src/features/albums/workspace/ContentSave";

describe("content save lifecycle", () => {
  it("should not start another write or reconciliation after its owning scope is disposed", async () => {
    const save = vi.fn();
    const read = vi.fn();
    const { session, dispose } = createRoot((release) => ({
      session: createContentSave<string>({ save, read, onSaved: vi.fn(), equal: (left, right) => left === right }),
      dispose: release,
    }));
    session.begin("Retained");
    dispose();
    await session.commit();
    await session.reconcile();
    expect(save).not.toHaveBeenCalled();
    expect(read).not.toHaveBeenCalled();
    expect(session.draft()).toEqual({ original: "Retained", value: "Retained" });
  });
  it.each(["acknowledged", "uncertain"] as const)("should retain %s publication failures and retry reads without replaying writes", async (phase) => {
    const save = phase === "acknowledged" ? vi.fn().mockResolvedValue("Saved") : vi.fn().mockRejectedValue(new Error("offline"));
    const read = vi.fn().mockResolvedValue("Saved");
    const onSaved = vi.fn().mockImplementationOnce(() => {
      throw new Error("Publication failed");
    });
    if (phase === "acknowledged")
      onSaved.mockImplementationOnce(() => {
        throw new Error("Publication still failed");
      });
    const session = createRoot(() => createContentSave<string>({ save, read, onSaved, equal: (left, right) => left === right }));
    session.begin("Saved");
    await session.commit();
    if (phase === "uncertain") await session.reconcile();

    expect(session.recovery()).toBe("acknowledged");
    expect(session.draft()).toEqual({ original: "Saved", value: "Saved" });
    await session.commit();

    expect(save).toHaveBeenCalledOnce();
    expect(session.draft()).toBeUndefined();
    expect(onSaved).toHaveBeenCalledWith("Saved", "Saved");
  });
  it("should retain pending drafts and ignore duplicate commit, cancel and reconciliation", async () => {
    let acknowledge!: (value: string) => void;
    const pending = new Promise<string>((resolve) => {
      acknowledge = resolve;
    });
    const save = vi.fn().mockReturnValue(pending);
    const read = vi.fn();
    const onSaved = vi.fn();
    const session = createRoot(() => createContentSave<string>({ save, read, onSaved, equal: (left, right) => left === right }));

    await session.commit();
    await session.reconcile();
    session.change("No active draft");
    expect(session.draft()).toBeUndefined();
    session.begin("Original");
    session.change("  山\n\n ");
    const writing = session.commit();
    await session.commit();
    await session.reconcile();
    session.cancel();

    expect(session.draft()).toEqual({ original: "Original", value: "  山\n\n " });
    expect(session.unavailable()).toBe("Saving content. Please wait.");
    expect(save).toHaveBeenCalledExactlyOnceWith("  山\n\n ");
    acknowledge("  山\n\n ");
    await writing;

    expect(onSaved).toHaveBeenCalledExactlyOnceWith("  山\n\n ", "  山\n\n ");
    expect(session.draft()).toBeUndefined();
    expect(read).not.toHaveBeenCalled();
  });

  it.each([undefined, new AcknowledgedWriteError("Malformed saved response")].map((failure) => ({ failure })))(
    "should retry only reads after acknowledgement with response=$failure",
    async ({ failure }) => {
      const save = failure === undefined ? vi.fn().mockResolvedValue(undefined) : vi.fn().mockRejectedValue(failure);
      const read = vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce("");
      const onSaved = vi.fn();
      const session = createRoot(() => createContentSave<string>({ save, read, onSaved, equal: (left, right) => left === right }));

      session.begin("");
      await session.commit();
      session.cancel();

      expect(session.recovery()).toBe("acknowledged");
      expect(session.draft()).toEqual({ original: "", value: "" });
      expect(session.error()).toContain("Saved content could not be read");
      expect(session.unavailable()).toBe("Read saved content before continuing.");
      await session.commit();

      expect(save).toHaveBeenCalledExactlyOnceWith("");
      expect(read).toHaveBeenCalledTimes(2);
      expect(onSaved).toHaveBeenCalledExactlyOnceWith("", "");
      expect(session.draft()).toBeUndefined();
    },
  );

  it.each([
    { failure: new Error("Connection lost"), saved: "Draft", confirmed: true },
    { failure: new AlbumRequestError(503, "Uncertain"), saved: "Original", confirmed: false },
  ])("should reconcile $failure before retrying a write with confirmed=$confirmed", async ({ failure, saved, confirmed }) => {
    const save = vi.fn().mockRejectedValueOnce(failure).mockResolvedValueOnce("Draft");
    const read = vi.fn().mockResolvedValue(saved);
    const onSaved = vi.fn();
    const session = createRoot(() => createContentSave<string>({ save, read, onSaved, equal: (left, right) => left === right }));

    session.begin("Draft");
    await session.commit();
    expect(session.recovery()).toBe("uncertain");
    expect(session.error()).toContain("uncertain");
    await session.commit();

    expect(save).toHaveBeenCalledTimes(1);
    expect(read).toHaveBeenCalledOnce();
    expect(session.recovery()).toBeUndefined();
    if (confirmed) expect(onSaved).toHaveBeenCalledExactlyOnceWith("Draft", "Draft");
    else {
      expect(session.draft()).toEqual({ original: "Draft", value: "Draft" });
      expect(session.error()).toBe("The draft is not saved. Review it before saving again.");
      await session.commit();
      expect(save).toHaveBeenCalledTimes(2);
      expect(onSaved).toHaveBeenCalledExactlyOnceWith("Draft", "Draft");
    }
  });

  it("should retain rejected drafts for correction or cancellation without reconciliation", async () => {
    const save = vi.fn().mockRejectedValue(new AlbumRequestError(400, "Rejected text"));
    const read = vi.fn();
    const session = createRoot(() => createContentSave<string>({ save, read, onSaved: vi.fn(), equal: (left, right) => left === right }));

    session.begin("Draft");
    await session.commit();

    expect(session.error()).toBe("Rejected text");
    expect(session.unavailable()).toBeUndefined();
    expect(session.draft()).toEqual({ original: "Draft", value: "Draft" });
    session.cancel();
    expect(session.draft()).toBeUndefined();
    expect(session.error()).toBeUndefined();
    expect(read).not.toHaveBeenCalled();
  });

  it.each(["write", "read"] as const)("should ignore late %s results and failures after disposal", async (phase) => {
    for (const reject of [false, true]) {
      let resolve!: (value: string) => void;
      let fail!: (failure: Error) => void;
      const pending = new Promise<string>((success, failure) => {
        resolve = success;
        fail = failure;
      });
      const onSaved = vi.fn();
      const save = vi.fn().mockReturnValue(phase === "write" ? pending : Promise.resolve(undefined));
      const read = vi.fn().mockReturnValue(pending);
      const { session, dispose } = createRoot((release) => ({
        session: createContentSave<string>({ save, read, onSaved, equal: (left, right) => left === right }),
        dispose: release,
      }));
      session.begin("Draft");
      const writing = session.commit();
      if (phase === "read") await vi.waitUntil(() => read.mock.calls.length === 1);
      dispose();
      if (reject) fail(new Error("offline"));
      else resolve("Draft");
      await writing;

      expect(onSaved).not.toHaveBeenCalled();
      expect(session.draft()).toEqual({ original: "Draft", value: "Draft" });
    }
  });
});
