import { createMemo, createSignal } from "solid-js";
import type { PlacementClient, PlacementContext, PlacementEdit, PlacementSnapshot, PhotoTransform, SpreadSnapshot } from "../model/Placement";
import { AlbumRequestError } from "../model/Album";

type Recovery = "read" | "uncertain" | "refresh";

export function createPlacementSession(client: PlacementClient, refreshInventory: () => Promise<void>) {
  const [snapshot, setSnapshot] = createSignal<PlacementSnapshot>();
  const [previews, setPreviews] = createSignal<Readonly<Record<string, PhotoTransform>>>({});
  const [saving, setSaving] = createSignal(false);
  const [adjusting, setAdjusting] = createSignal(false);
  const [loading, setLoading] = createSignal(false);
  const [error, setError] = createSignal<string>();
  const [recovery, setRecovery] = createSignal<Recovery>();
  const queue = new Map<string, PhotoTransform>();
  const [target, setTarget] = createSignal<{ album: string; context: PlacementContext }>();
  let generation = 0;
  const placements = createMemo(() => snapshot()?.placements.map((row) => ({ ...row, transform: previews()[row.id] ?? row.transform })) ?? []);
  const navigationReason = createMemo(() =>
    saving()
      ? "Saving photos. Please wait."
      : Object.keys(previews()).length > 0
        ? "Unsaved photo adjustments. Finish or retry before leaving."
        : recovery() !== undefined
          ? "Reload saved photos before leaving."
          : undefined,
  );
  const unavailable = createMemo(() =>
    loading()
      ? "Loading saved photos. Please wait."
      : recovery() !== undefined
        ? error()
        : saving() && !adjusting()
          ? "Saving photos. Please wait."
          : snapshot() === undefined
            ? "Saved photos are unavailable. Reload photos."
            : undefined,
  );

  function beginSelection(album: string, context: PlacementContext) {
    setTarget({ album, context });
    const request = ++generation;
    setSnapshot(undefined);
    setError(undefined);
    setRecovery(undefined);
    return request;
  }

  function selectSpread(album: string, saved: SpreadSnapshot) {
    beginSelection(album, { kind: "spread", id: saved.id });
    setSnapshot(saved);
    setLoading(false);
  }

  async function load(album: string, context: PlacementContext) {
    const request = beginSelection(album, context);
    setLoading(true);
    try {
      const saved = await client.readPlacements(album, context);
      if (request === generation) setSnapshot(saved);
    } catch {
      if (request === generation) {
        setRecovery("read");
        setError("Saved photos could not be loaded. Reload photos.");
      }
    } finally {
      if (request === generation) setLoading(false);
    }
  }

  function reconcile(saved: SpreadSnapshot) {
    setSnapshot(saved);
  }

  function clear() {
    generation++;
    setTarget(undefined);
    queue.clear();
    setSnapshot(undefined);
    setPreviews({});
    setRecovery(undefined);
    setError(undefined);
    setLoading(false);
  }

  function preview(id: string, transform: PhotoTransform) {
    setPreviews((current) => ({ ...current, [id]: transform }));
  }

  function restore(id: string, transform: PhotoTransform) {
    const saved = snapshot()?.placements.find((row) => row.id === id)?.transform;
    setPreviews((current) => {
      return saved !== undefined && sameTransform(saved, transform) ? withoutPreview(current, id) : { ...current, [id]: transform };
    });
  }

  function complete(id: string, transform: PhotoTransform) {
    preview(id, transform);
    queue.set(id, transform);
    void drain();
  }

  async function drain() {
    const selected = target();
    if (saving() || recovery() !== undefined || selected === undefined) return;
    setSaving(true);
    setAdjusting(true);
    try {
      while (queue.size > 0) {
        for (const [id, transform] of new Map(queue)) {
          const saved = await client.editPlacements(selected.album, selected.context, { operation: "adjust", placementId: id, transform });
          setSnapshot(saved);
          if (queue.get(id) === transform) queue.delete(id);
          setPreviews((current) => {
            if (current[id] !== transform) return current;
            return withoutPreview(current, id);
          });
        }
      }
    } catch {
      setRecovery("uncertain");
      setError("Photo adjustment is unsaved; its outcome is uncertain. Retry reads saved photos before attempting the adjustment again.");
    } finally {
      setSaving(false);
      setAdjusting(false);
    }
  }

  async function edit(command: PlacementEdit) {
    const selected = target();
    if (selected === undefined || unavailable() !== undefined || navigationReason() !== undefined) return;
    setSaving(true);
    try {
      const saved = await client.editPlacements(selected.album, selected.context, command);
      setSnapshot(saved);
    } catch {
      setRecovery("uncertain");
      setError("Photo change could not be confirmed. Reload saved photos before choosing the action again.");
      setSaving(false);
      return;
    }
    try {
      await refreshInventory();
    } catch {
      setRecovery("refresh");
      setError("Photos saved, but the collection could not be refreshed. Retry reads only.");
    } finally {
      setSaving(false);
    }
  }

  async function retry() {
    const selected = target();
    if (selected === undefined || saving()) return;
    setLoading(true);
    try {
      const saved = await client.readPlacements(selected.album, selected.context);
      setSnapshot(saved);
      for (const [id, transform] of queue) {
        const confirmed = saved.placements.find((row) => row.id === id);
        if (confirmed === undefined || sameTransform(confirmed.transform, transform)) {
          queue.delete(id);
          setPreviews((current) => {
            return withoutPreview(current, id);
          });
        }
      }
      await refreshInventory();
      setError(undefined);
      setRecovery(undefined);
    } catch (failure) {
      let message = "Saved photo recovery failed. Unsaved adjustments are retained. Retry reads saved state first.";
      if (failure instanceof AlbumRequestError && failure.status === 404) {
        try {
          await refreshInventory();
        } catch {
          message = "Saved workspace recovery failed. Retry reads saved state first.";
        }
      }
      if (target() !== undefined) setError(message);
    } finally {
      setLoading(false);
    }
    if (recovery() === undefined) await drain();
  }

  return {
    snapshot,
    placements,
    saving,
    loading,
    error,
    navigationReason,
    unavailable,
    load,
    selectSpread,
    reconcile,
    clear,
    preview,
    restore,
    complete,
    edit,
    retry,
  };
}

export function sameTransform(left: PhotoTransform, right: PhotoTransform): boolean {
  return left.x === right.x && left.y === right.y && left.zoom === right.zoom && left.mirrored === right.mirrored;
}

export type PlacementSession = ReturnType<typeof createPlacementSession>;

function withoutPreview(current: Readonly<Record<string, PhotoTransform>>, id: string) {
  return Object.fromEntries(Object.entries(current).filter(([key]) => key !== id));
}
