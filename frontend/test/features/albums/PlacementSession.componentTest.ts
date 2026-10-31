import { createRoot } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { createPlacementSession } from "../../../src/features/albums/workspace/PlacementSession";
import type { CoverSnapshot, PlacementSnapshot, SpreadContext, SpreadSnapshot } from "../../../src/features/albums/model/Placement";
import { AlbumRequestError } from "../../../src/features/albums/model/Album";

const initial: SpreadSnapshot = {
  kind: "spread",
  id: "spread",
  layout: { kind: "photos_only", templateId: "layout", slots: [{ id: "s", kind: "photo", x: 0, y: 0, width: 100, height: 100 }] },
  placements: [
    { kind: "spread", captionCorner: "top_left", id: "p", slotId: "s", photoId: "photo", transform: { x: 0, y: 0, zoom: 1, mirrored: false } },
  ],
};
const context: SpreadContext = { kind: "spread", id: "spread" };

describe("placement save lifecycle", () => {
  it("should ignore a late Cover response after navigation uses an already acquired spread", async () => {
    let resolve!: (value: PlacementSnapshot) => void;
    const old = new Promise<PlacementSnapshot>((finish) => {
      resolve = finish;
    });
    const read = vi.fn().mockReturnValue(old);
    const session = createRoot(() => createPlacementSession({ readPlacements: read, editPlacements: vi.fn() }, vi.fn()));

    const pending = session.load("album", { kind: "cover" });
    session.selectSpread("album", initial);
    resolve({ kind: "cover", frontSlotId: "front", backSlotId: "back", spineOwner: "front", placements: [] });
    await pending;

    expect(session.snapshot()).toEqual(initial);
    expect(session.error()).toBeUndefined();
    expect(session.loading()).toBe(false);
    expect(read).toHaveBeenCalledExactlyOnceWith("album", { kind: "cover" });
  });

  it("clears stale feedback when an authoritative reload removes the selected part", async () => {
    const clearSelection = vi.fn<() => void>();
    const refresh = () => {
      clearSelection();
      return Promise.resolve();
    };
    const read = vi.fn().mockRejectedValue(new AlbumRequestError(404, "missing"));
    const session = createRoot(() => createPlacementSession({ readPlacements: read, editPlacements: vi.fn() }, refresh));
    clearSelection.mockImplementation(session.clear);
    await session.load("album", context);

    await session.retry();

    expect(session.error()).toBeUndefined();
    expect(session.navigationReason()).toBeUndefined();
  });
  it.each([404, 500])("keeps missing-target recovery explicit for HTTP %s when workspace reload fails", async (status) => {
    const read = vi.fn().mockRejectedValue(new AlbumRequestError(status, "missing"));
    const refresh = vi.fn().mockRejectedValue(new Error("workspace offline"));
    const session = createRoot(() => createPlacementSession({ readPlacements: read, editPlacements: vi.fn() }, refresh));
    await session.load("album", context);

    await session.retry();

    expect(session.error()).toContain(status === 404 ? "Saved workspace recovery failed" : "Saved photo recovery failed");
    expect(refresh).toHaveBeenCalledTimes(status === 404 ? 1 : 0);
  });
  it("does not mutate or retry before a context has been loaded", async () => {
    const edit = vi.fn();
    const session = createRoot(() => createPlacementSession({ readPlacements: vi.fn(), editPlacements: edit }, vi.fn()));

    await session.edit({ operation: "remove", placementId: "p" });
    await session.retry();

    expect(edit).not.toHaveBeenCalled();
  });

  it("ignores an obsolete read failure after a newer context has loaded", async () => {
    let reject!: (error: Error) => void;
    const old = new Promise<PlacementSnapshot>((_resolve, fail) => {
      reject = fail;
    });
    const cover: CoverSnapshot = { kind: "cover", frontSlotId: "front", backSlotId: "back", spineOwner: "front", placements: [] };
    const read = vi.fn().mockReturnValueOnce(old).mockResolvedValue(cover);
    const session = createRoot(() => createPlacementSession({ readPlacements: read, editPlacements: vi.fn() }, vi.fn()));

    const first = session.load("album", context);
    await session.load("album", { kind: "cover" });
    reject(new Error("old"));
    await first;

    expect(session.snapshot()).toEqual(cover);
    expect(session.error()).toBeUndefined();
  });
  it("serializes completed transforms and keeps the latest local preview while the first save is slow", async () => {
    const middle = { x: 1, y: 2, zoom: 2, mirrored: false };
    const latest = { x: 3, y: 4, zoom: 3, mirrored: true };
    const savedMiddle = { ...initial, placements: initial.placements.map((row) => ({ ...row, transform: middle })) };
    const savedLatest = { ...initial, placements: initial.placements.map((row) => ({ ...row, transform: latest })) };
    let acknowledge!: (value: PlacementSnapshot) => void;
    const first = new Promise<PlacementSnapshot>((resolve) => {
      acknowledge = resolve;
    });
    const edit = vi.fn().mockReturnValueOnce(first).mockResolvedValue(savedLatest);
    const refresh = vi.fn().mockResolvedValue(undefined);
    const session = createRoot(() => createPlacementSession({ readPlacements: vi.fn().mockResolvedValue(initial), editPlacements: edit }, refresh));
    await session.load("album", context);

    session.complete("p", middle);
    session.complete("p", latest);
    const latestPreviewWhilePending = session.placements()[0]?.transform;
    const writesWhilePending = edit.mock.calls.length;
    acknowledge(savedMiddle);
    await vi.waitUntil(() => !session.saving());

    expect(latestPreviewWhilePending).toEqual(latest);
    expect(writesWhilePending).toBe(1);
    expect(edit).toHaveBeenCalledTimes(2);
    expect(session.placements()).toEqual(savedLatest.placements);
    expect(edit.mock.calls[1]?.[2]).toEqual({ operation: "adjust", placementId: "p", transform: latest });
    expect(refresh).not.toHaveBeenCalled();
    expect(session.navigationReason()).toBeUndefined();
  });

  it("retains a failed adjustment, reads before retry and avoids replaying an already committed transform", async () => {
    const transform = { x: 1, y: 2, zoom: 2, mirrored: false };
    const saved = { ...initial, placements: initial.placements.map((row) => ({ ...row, transform })) };
    const read = vi.fn().mockResolvedValueOnce(initial).mockRejectedValueOnce(new Error("offline")).mockResolvedValue(saved);
    const edit = vi.fn().mockRejectedValue(new Error("connection lost"));
    const session = createRoot(() => createPlacementSession({ readPlacements: read, editPlacements: edit }, vi.fn().mockResolvedValue(undefined)));
    await session.load("album", context);

    session.complete("p", transform);
    await vi.waitUntil(() => !session.saving());
    const failedSaveFeedback = session.error();
    await session.retry();
    const previewAfterReadFailure = session.placements()[0]?.transform;
    const readFailureFeedback = session.error();
    await session.retry();

    expect(failedSaveFeedback).toContain("uncertain");
    expect(previewAfterReadFailure).toEqual(transform);
    expect(readFailureFeedback).toContain("recovery failed");
    expect(edit).toHaveBeenCalledTimes(1);
    expect(session.error()).toBeUndefined();
    expect(session.navigationReason()).toBeUndefined();
  });

  it("retries acknowledged-save collection failures as reads only", async () => {
    const edit = vi.fn().mockResolvedValue(initial);
    const newer = { ...initial, placements: initial.placements.map((row) => ({ ...row, transform: { ...row.transform, zoom: 3 } })) };
    const read = vi.fn().mockResolvedValueOnce(initial).mockResolvedValue(newer);
    const refresh = vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValue(undefined);
    const session = createRoot(() => createPlacementSession({ readPlacements: read, editPlacements: edit }, refresh));
    await session.load("album", context);

    await session.edit({ operation: "place", photoId: "photo", slotId: "s" });
    const acknowledgedSaveFeedback = session.error();
    await session.retry();

    expect(acknowledgedSaveFeedback).toContain("Photos saved");
    expect(read).toHaveBeenCalledTimes(2);
    expect(edit).toHaveBeenCalledTimes(1);
    expect(refresh).toHaveBeenCalledTimes(2);
    expect(session.error()).toBeUndefined();
    expect(session.placements()).toEqual(newer.placements);
  });

  it("fails closed on initial reads and recovers by reading before enabling edits", async () => {
    const read = vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValue(initial);
    const session = createRoot(() => createPlacementSession({ readPlacements: read, editPlacements: vi.fn() }, vi.fn()));

    await session.load("album", context);
    const failedReadReason = session.unavailable();
    await session.retry();

    expect(failedReadReason).toContain("could not be loaded");
    expect(session.error()).toBeUndefined();
    expect(session.placements()).toEqual(initial.placements);
  });

  it("retains confirmed composition after structural failure and never replays an uncertain structural mutation", async () => {
    const read = vi.fn().mockResolvedValue(initial);
    const edit = vi.fn().mockRejectedValue(new Error("offline"));
    const session = createRoot(() => createPlacementSession({ readPlacements: read, editPlacements: edit }, vi.fn()));
    await session.load("album", context);

    await session.edit({ operation: "remove", placementId: "p" });
    const structuralFailure = session.error();
    const confirmedComposition = session.placements();
    await session.retry();

    expect(structuralFailure).toContain("could not be confirmed");
    expect(confirmedComposition).toEqual(initial.placements);
    expect(edit).toHaveBeenCalledTimes(1);
    expect(read).toHaveBeenCalledTimes(2);
  });

  it("retries an uncommitted adjustment only after reconciliation and drops vanished sources", async () => {
    const read = vi
      .fn()
      .mockResolvedValueOnce(initial)
      .mockResolvedValueOnce(initial)
      .mockResolvedValue({ ...initial, placements: [] });
    const edit = vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce(initial).mockRejectedValueOnce(new Error("stale"));
    const session = createRoot(() => createPlacementSession({ readPlacements: read, editPlacements: edit }, vi.fn()));
    await session.load("album", context);
    const transform = { x: 1, y: 1, zoom: 2, mirrored: false };

    session.complete("p", transform);
    await vi.waitUntil(() => !session.saving());
    const uncommittedFeedback = session.error();
    await session.retry();
    const writesAfterReconciliation = edit.mock.calls.length;
    session.preview("p", { ...transform, x: 4 });
    session.restore("p", transform);
    session.complete("p", transform);
    await vi.waitUntil(() => !session.saving());
    const staleSourceFeedback = session.error();
    await session.retry();

    expect(uncommittedFeedback).toBeDefined();
    expect(writesAfterReconciliation).toBe(2);
    expect(staleSourceFeedback).toBeDefined();
    expect(session.placements()).toEqual([]);
    expect(session.navigationReason()).toBeUndefined();
  });
});
