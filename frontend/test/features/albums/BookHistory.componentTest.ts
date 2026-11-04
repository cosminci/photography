import { createRoot } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { createBookHistory, type HistoryAction, type HistoryOutcome } from "../../../src/features/albums/workspace/BookHistory";

function buildAction(label: string): HistoryAction {
  return {
    label,
    apply: vi.fn().mockResolvedValue({ kind: "saved" }),
    reconcile: vi.fn().mockResolvedValue({ kind: "saved" }),
  };
}

describe("chronological book history", () => {
  it("should stop at an unconfirmed new book edit instead of undoing an older action", async () => {
    const history = createRoot(() => createBookHistory());
    const older = buildAction("Edit caption");
    history.record(older);
    history.block("A new spread appeared without a confirmed identity. Review the saved book.");
    await history.traverse("undo");
    expect(older.apply).not.toHaveBeenCalled();
    expect(history.record(buildAction("Another edit"))).toBe(false);
    expect(history.conflict()).toBe(true);
    history.reset();
    expect(history.conflict()).toBe(false);
  });
  it("should undo a later lens selection before a caption and redo in chronological order", async () => {
    const history = createRoot(() => createBookHistory());
    const caption = buildAction("Edit caption");
    const lens = buildAction("Change chapter gear");

    history.record(caption);
    history.record(lens);
    await history.traverse("undo");
    await history.traverse("undo");
    await history.traverse("redo");
    await history.traverse("redo");

    expect(lens.apply).toHaveBeenNthCalledWith(1, "undo");
    expect(caption.apply).toHaveBeenNthCalledWith(1, "undo");
    expect(caption.apply).toHaveBeenNthCalledWith(2, "redo");
    expect(lens.apply).toHaveBeenNthCalledWith(2, "redo");
    expect(history.nextUndo()).toBe(lens);
    expect(history.nextRedo()).toBeUndefined();
  });

  it.each([
    { limit: undefined, retained: 10 },
    { limit: 1, retained: 1 },
    { limit: 3, retained: 3 },
    { limit: 50, retained: 50 },
  ])("should retain $retained completed edits and discard redo only after a new recorded edit", async ({ limit, retained }) => {
    const history = createRoot(() => createBookHistory(limit));
    const actions = Array.from({ length: retained + 1 }, (_, index) => buildAction(`Edit ${String(index)}`));

    for (const action of actions) history.record(action);
    for (let index = 0; index < retained; index++) await history.traverse("undo");

    expect(actions[0]?.apply).not.toHaveBeenCalled();
    expect(history.nextUndo()).toBeUndefined();
    expect(history.nextRedo()).toBe(actions[1]);
    for (let index = 0; index < retained; index++) await history.traverse("redo");
    for (let index = 0; index < retained; index++) await history.traverse("undo");
    expect(history.nextUndo()).toBeUndefined();
    expect(history.nextRedo()).toBe(actions[1]);
    history.record(buildAction("New edit"));
    expect(history.nextRedo()).toBeUndefined();
  });

  it.each(["blocked", "conflict"] as const)("should never skip a %s top action", async (kind) => {
    const history = createRoot(() => createBookHistory());
    const caption = buildAction("Edit caption");
    const lens = buildAction("Change chapter gear");
    vi.mocked(lens.apply).mockResolvedValue({ kind, message: "The saved gear changed." });
    history.record(caption);
    history.record(lens);

    await history.traverse("undo");
    await history.traverse("undo");

    expect(history.nextUndo()).toBe(lens);
    expect(history.nextRedo()).toBeUndefined();
    expect(history.message()).toBe("The saved gear changed.");
    expect(caption.apply).not.toHaveBeenCalled();
    expect(lens.apply).toHaveBeenCalledTimes(kind === "conflict" ? 1 : 2);
  });

  it("should preserve the pending top action and refuse concurrent traversal and recording", async () => {
    const history = createRoot(() => createBookHistory());
    const action = buildAction("Remove photo");
    let finish!: (outcome: HistoryOutcome) => void;
    vi.mocked(action.apply).mockReturnValue(new Promise((resolve) => (finish = resolve)));
    history.record(action);

    const saving = history.traverse("undo");
    await history.traverse("redo");

    expect(history.pending()).toBe(true);
    expect(history.nextUndo()).toBe(action);
    expect(history.record(buildAction("Overlapping edit"))).toBe(false);
    expect(action.apply).toHaveBeenCalledOnce();
    finish({ kind: "saved" });
    await saving;
    expect(history.pending()).toBe(false);
    expect(history.nextRedo()).toBe(action);
  });

  it.each(["saved", "unchanged", "conflict"] as const)("should reconcile uncertain %s outcomes without replaying the write", async (kind) => {
    const history = createRoot(() => createBookHistory());
    const action = buildAction("Delete spread");
    vi.mocked(action.apply).mockRejectedValue(new Error("Lost acknowledgement"));
    vi.mocked(action.reconcile).mockResolvedValue(kind === "conflict" ? { kind, message: "Saved state differs from both outcomes." } : { kind });
    history.record(action);

    await history.traverse("undo");
    await history.traverse("undo");
    expect(history.uncertain()).toBe(true);
    expect(action.apply).toHaveBeenCalledOnce();
    await history.reconcile();

    expect(action.reconcile).toHaveBeenCalledWith("undo");
    expect(action.apply).toHaveBeenCalledOnce();
    expect(history.uncertain()).toBe(false);
    expect(history.nextRedo()).toBe(kind === "saved" ? action : undefined);
    expect(history.nextUndo()).toBe(kind === "saved" ? undefined : action);
    expect(history.message()).toMatch(kind === "unchanged" ? /not saved/ : kind === "conflict" ? /differs/ : /^$/);
  });

  it("should retain uncertainty after a failed recovery read and allow an explicit read retry", async () => {
    const history = createRoot(() => createBookHistory());
    const action = buildAction("Move photo");
    vi.mocked(action.apply).mockResolvedValue({ kind: "uncertain", message: "Read saved photos." });
    vi.mocked(action.reconcile).mockRejectedValueOnce(new Error("Offline"));
    history.record(action);

    await history.traverse("undo");
    await history.reconcile();

    expect(history.uncertain()).toBe(true);
    expect(history.message()).toMatch(/could not be read/);
    await history.reconcile();
    expect(history.nextRedo()).toBe(action);
    expect(action.apply).toHaveBeenCalledOnce();
  });

  it.each((["apply", "reconcile"] as const).flatMap((operation) => (["saved", "failed"] as const).map((outcome) => ({ operation, outcome }))))(
    "should fence late $operation $outcome after access loss without repopulating history",
    async ({ operation, outcome }) => {
      const history = createRoot(() => createBookHistory());
      const action = buildAction("Change template");
      let finish!: (outcome: HistoryOutcome) => void;
      let fail!: (failure: Error) => void;
      const pending = new Promise<HistoryOutcome>((resolve, reject) => {
        finish = resolve;
        fail = reject;
      });
      if (operation === "apply") vi.mocked(action.apply).mockReturnValue(pending);
      else {
        vi.mocked(action.apply).mockResolvedValue({ kind: "uncertain", message: "Read saved state." });
        vi.mocked(action.reconcile).mockReturnValue(pending);
      }
      history.record(action);
      const applying = history.traverse("undo");
      if (operation === "reconcile") await applying;
      const working = operation === "apply" ? applying : history.reconcile();

      history.reset("Editing access was lost. Undo history has been cleared.");
      if (outcome === "saved") finish({ kind: "saved" });
      else fail(new Error("Late failed request"));
      await working;

      expect(history.nextUndo()).toBeUndefined();
      expect(history.nextRedo()).toBeUndefined();
      expect(history.message()).toMatch(/access was lost/);
      expect(history.pending()).toBe(false);
      expect(history.uncertain()).toBe(false);
    },
  );

  it("should preserve redo while a blocked inverse is retried and reset explicitly after conflict", async () => {
    const history = createRoot(() => createBookHistory());
    const action = buildAction("Edit synopsis");
    vi.mocked(action.apply).mockResolvedValueOnce({ kind: "unchanged" });
    history.record(action);

    await history.traverse("undo");
    expect(history.nextUndo()).toBe(action);
    await history.traverse("undo");
    vi.mocked(action.apply).mockResolvedValue({ kind: "conflict", message: "Synopsis changed." });
    await history.traverse("redo");

    expect(history.nextRedo()).toBe(action);
    expect(history.record(buildAction("Another edit"))).toBe(false);
    history.reset();
    expect(history.record(buildAction("Another edit"))).toBe(true);
    expect(history.message()).toBe("");
  });

  it("should make empty traversal and reconciliation no-ops", async () => {
    const history = createRoot(() => createBookHistory());

    await history.traverse("undo");
    await history.traverse("redo");
    await history.reconcile();

    expect(history.pending()).toBe(false);
    expect(history.message()).toBe("");
  });

  it("should retain partially saved compensation and allow only an explicit retry of that direction", async () => {
    const history = createRoot(() => createBookHistory());
    const action = buildAction("Remove photo");
    vi.mocked(action.apply).mockResolvedValueOnce({ kind: "blocked", message: "Framing was not saved.", partial: true });
    history.record(action);

    await history.traverse("undo");

    expect(history.unavailable()).toBe(true);
    expect(history.record(buildAction("Another edit"))).toBe(false);
    await history.traverse("redo");
    expect(action.apply).toHaveBeenCalledOnce();
    await history.traverse("undo");
    expect(action.apply).toHaveBeenCalledTimes(2);
    expect(history.nextRedo()).toBe(action);
    expect(history.unavailable()).toBe(false);
  });
});
