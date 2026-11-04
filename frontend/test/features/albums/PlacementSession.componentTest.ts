import { createRoot } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { createPlacementSession } from "../../../src/features/albums/workspace/PlacementSession";
import type { CoverSnapshot, PlacementSnapshot, SpreadContext, SpreadSnapshot } from "../../../src/features/albums/model/Placement";
import { AlbumRequestError } from "../../../src/features/albums/model/Album";

const initial: SpreadSnapshot = {
  kind: "spread",
  id: "spread",
  layout: {
    kind: "photos_only",
    bleed: { state: "disabled" as const, available: true },
    templateId: "layout",
    slots: [{ id: "s", kind: "photo", x: 0, y: 0, width: 100, height: 100 }],
  },
  placements: [
    { kind: "spread", captionCorner: "top_left", id: "p", slotId: "s", photoId: "photo", transform: { x: 0, y: 0, zoom: 1, mirrored: false } },
  ],
};
const context: SpreadContext = { kind: "spread", id: "spread" };

describe("placement save lifecycle", () => {
  it.each(["retry", "apply"] as const)("should retain drafts when ownership changes during %s reconciliation", async (action) => {
    let authority = 1;
    const transform = { x: 2, y: 3, zoom: 2, mirrored: false };
    const saved = { ...initial, placements: initial.placements.map((row) => ({ ...row, transform })) };
    const edit = vi.fn().mockRejectedValueOnce(new Error("lost acknowledgement")).mockResolvedValue(saved);
    const refresh = vi.fn().mockResolvedValue(undefined);
    const session = createRoot(() =>
      createPlacementSession({ readPlacements: vi.fn().mockResolvedValue(initial), editPlacements: edit }, refresh, {
        available: () => true,
        authority: () => authority,
      }),
    );
    session.selectSpread("album", initial);
    session.complete("p", transform);
    await vi.waitFor(() => {
      expect(session.saving()).toBe(false);
    });
    if (action === "apply") {
      authority++;
      await session.retry();
    }
    let finish!: () => void;
    const pending = new Promise<void>((resolve) => {
      finish = resolve;
    });
    refresh.mockReturnValueOnce(pending);
    const reconciling = action === "retry" ? session.retry() : session.applyRetained();
    await vi.waitFor(() => {
      expect(refresh).toHaveBeenCalledTimes(action === "retry" ? 1 : 2);
    });
    authority++;
    finish();
    await reconciling;
    expect(edit).toHaveBeenCalledOnce();
    expect(session.retained()).toBe(true);
    expect(session.placements()[0]?.transform).toEqual(transform);
    await session.applyRetained();
    expect(edit).toHaveBeenCalledTimes(2);
    expect(session.retained()).toBe(false);
  });

  it("should stop queued writes after ownership loss and retain drafts when authoritative recovery fails", async () => {
    let authority = 1;
    let available = true;
    let finish!: (value: PlacementSnapshot) => void;
    const pending = new Promise<PlacementSnapshot>((resolve) => {
      finish = resolve;
    });
    const first = { x: 1, y: 2, zoom: 2, mirrored: false };
    const latest = { ...first, x: 3 };
    const saved = { ...initial, placements: initial.placements.map((row) => ({ ...row, transform: latest })) };
    const edit = vi.fn().mockReturnValueOnce(pending).mockResolvedValue(saved);
    const read = vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValue(initial);
    const session = createRoot(() =>
      createPlacementSession({ readPlacements: read, editPlacements: edit }, vi.fn().mockResolvedValue(undefined), {
        available: () => available,
        authority: () => authority,
      }),
    );
    session.selectSpread("album", initial);
    session.complete("p", first);
    session.complete("p", latest);
    available = false;
    authority++;
    finish({ ...initial, placements: initial.placements.map((row) => ({ ...row, transform: first })) });
    await vi.waitFor(() => {
      expect(session.saving()).toBe(false);
    });
    expect(edit).toHaveBeenCalledOnce();
    expect(session.retained()).toBe(true);
    expect(session.placements()[0]?.transform).toEqual(latest);
    await session.applyRetained();
    expect(read).not.toHaveBeenCalled();
    available = true;
    authority++;
    await session.applyRetained();
    expect(edit).toHaveBeenCalledOnce();
    expect(session.retained()).toBe(true);
    expect(session.error()).toMatch(/recovery failed/);
    await session.applyRetained();
    expect(edit).toHaveBeenCalledTimes(2);
    expect(session.retained()).toBe(false);
    expect(session.placements()[0]?.transform).toEqual(latest);
  });

  it.each(["loading", "saving"] as const)("should not apply or discard retained adjustments while %s is pending", async (phase) => {
    let finish!: (value: PlacementSnapshot) => void;
    const pending = new Promise<PlacementSnapshot>((resolve) => {
      finish = resolve;
    });
    const read = vi.fn().mockReturnValue(pending);
    const edit = vi.fn().mockReturnValue(pending);
    const session = createRoot(() => createPlacementSession({ readPlacements: read, editPlacements: edit }, vi.fn().mockResolvedValue(undefined)));
    session.selectSpread("album", initial);
    const operation = phase === "loading" ? session.load("album", context) : session.edit({ operation: "remove", placementId: "p" });
    await session.applyRetained();
    await session.discardRetained();
    expect(edit).toHaveBeenCalledTimes(phase === "saving" ? 1 : 0);
    finish(initial);
    await operation;
    await session.applyRetained();
    expect(session.retained()).toBe(false);
    expect(session.error()).toBeUndefined();
  });

  it("should retain adjustments without replay after ownership loss, read recovery or reacquisition", async () => {
    let authority = 1;
    let available = true;
    const transformed = { x: 2, y: 3, zoom: 2, mirrored: false };
    const edit = vi.fn().mockRejectedValue(new Error("lost acknowledgement"));
    const read = vi.fn().mockResolvedValue(initial);
    const session = createRoot(() =>
      createPlacementSession({ readPlacements: read, editPlacements: edit }, vi.fn().mockResolvedValue(undefined), {
        available: () => available,
        authority: () => authority,
      }),
    );
    session.selectSpread("album", initial);
    session.complete("p", transformed);
    await vi.waitFor(() => {
      expect(session.saving()).toBe(false);
    });
    available = false;
    authority++;

    await session.retry();
    available = true;
    authority++;
    await session.retry();

    expect(edit).toHaveBeenCalledOnce();
    expect(session.placements()[0]?.transform).toEqual(transformed);
    expect(session.error()).toMatch(/review|ownership/i);
    edit.mockResolvedValue({ ...initial, placements: initial.placements.map((row) => ({ ...row, transform: transformed })) });
    await session.applyRetained();
    expect(edit).toHaveBeenCalledTimes(2);
    expect(session.error()).toBeUndefined();
  });

  it.each(["preview", "pending", "uncertain", "acknowledged"] as const)(
    "should retain %s photo state when a workspace read tries to reconcile older content",
    async (state) => {
      let finish!: (value: PlacementSnapshot) => void;
      const pending = new Promise<PlacementSnapshot>((resolve) => {
        finish = resolve;
      });
      const edit = vi.fn().mockReturnValue(pending);
      const refresh = state === "acknowledged" ? vi.fn().mockRejectedValue(new Error("offline")) : vi.fn().mockResolvedValue(undefined);
      const session = createRoot(() => createPlacementSession({ readPlacements: vi.fn(), editPlacements: edit }, refresh));
      const transformed = { x: 2, y: 3, zoom: 2, mirrored: false };
      const saved = { ...initial, placements: initial.placements.map((row) => ({ ...row, transform: transformed })) };
      session.selectSpread("album", initial);
      if (state === "preview") session.preview("p", transformed);
      else if (state === "pending") session.complete("p", transformed);
      else {
        if (state === "uncertain") edit.mockRejectedValueOnce(new Error("lost acknowledgement"));
        else edit.mockResolvedValueOnce(saved);
        await session.edit({ operation: "remove", placementId: "p" });
      }

      session.reconcile({ ...initial, placements: [] });

      expect(session.snapshot()).toEqual(state === "acknowledged" ? saved : initial);
      if (state === "preview" || state === "pending") expect(session.placements()[0]?.transform).toEqual(transformed);
      if (state === "pending") {
        finish(saved);
        await vi.waitFor(() => {
          expect(session.saving()).toBe(false);
        });
        expect(session.snapshot()).toEqual(saved);
        expect(refresh).toHaveBeenCalledOnce();
      }
    },
  );

  it("should finish a newer adjustment queued during acknowledged saved-content reconciliation", async () => {
    let finish!: () => void;
    const reading = new Promise<void>((resolve) => {
      finish = resolve;
    });
    const first = { x: 1, y: 2, zoom: 2, mirrored: false };
    const latest = { ...first, x: 3 };
    const firstSaved = { ...initial, placements: initial.placements.map((row) => ({ ...row, transform: first })) };
    const latestSaved = { ...initial, placements: initial.placements.map((row) => ({ ...row, transform: latest })) };
    const edit = vi.fn().mockResolvedValueOnce(firstSaved).mockResolvedValueOnce(latestSaved);
    const refresh = vi.fn().mockReturnValueOnce(reading).mockResolvedValue(undefined);
    const session = createRoot(() => createPlacementSession({ readPlacements: vi.fn(), editPlacements: edit }, refresh));
    session.selectSpread("album", initial);
    session.complete("p", first);
    await vi.waitFor(() => {
      expect(refresh).toHaveBeenCalledOnce();
    });

    session.complete("p", latest);
    finish();
    await vi.waitFor(() => {
      expect(session.saving()).toBe(false);
    });

    expect(session.snapshot()).toEqual(latestSaved);
    expect(session.placements()).toEqual(latestSaved.placements);
    expect(edit).toHaveBeenCalledTimes(2);
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it("should ignore a late Cover response after navigation uses an already acquired spread", async () => {
    let resolve!: (value: PlacementSnapshot) => void;
    const old = new Promise<PlacementSnapshot>((finish) => {
      resolve = finish;
    });
    const read = vi.fn().mockReturnValue(old);
    const session = createRoot(() => createPlacementSession({ readPlacements: read, editPlacements: vi.fn() }, vi.fn()));

    const pending = session.load("album", { kind: "cover" });
    session.selectSpread("album", initial);
    resolve({
      kind: "cover",
      frontSlotId: "front",
      backSlotId: "back",
      spineOwner: "front",
      placements: [],
      pageCount: 30,
      geometry: coverGeometries.front,
    });
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
    const cover: CoverSnapshot = {
      kind: "cover",
      frontSlotId: "front",
      backSlotId: "back",
      spineOwner: "front",
      placements: [],
      pageCount: 30,
      geometry: coverGeometries.front,
    };
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
    expect(refresh).toHaveBeenCalledOnce();
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

  it.each(["composition", "adjustment"] as const)("should retry acknowledged %s reconciliation failures as reads only", async (operation) => {
    const edit = vi.fn().mockResolvedValue(initial);
    const newer = { ...initial, placements: initial.placements.map((row) => ({ ...row, transform: { ...row.transform, zoom: 3 } })) };
    const read = vi.fn().mockResolvedValueOnce(initial).mockResolvedValue(newer);
    const refresh = vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValue(undefined);
    const session = createRoot(() => createPlacementSession({ readPlacements: read, editPlacements: edit }, refresh));
    await session.load("album", context);

    if (operation === "composition") await session.edit({ operation: "place", photoId: "photo", slotId: "s" });
    else {
      session.complete("p", { x: 0, y: 0, zoom: 1, mirrored: false });
      await vi.waitFor(() => {
        expect(session.saving()).toBe(false);
      });
    }
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
import { coverGeometries } from "./CoverGeometry";
