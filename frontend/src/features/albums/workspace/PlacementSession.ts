import { createEffect, createMemo, createSignal } from "solid-js";
import type { PlacementClient, PlacementContext, PlacementEdit, PlacementSnapshot, PhotoTransform, SpreadSnapshot } from "../model/Placement";
import { AlbumRequestError } from "../model/Album";

type Recovery = "read" | "uncertain" | "refresh" | "ownership";
interface Completion {
  before: PlacementSnapshot;
  command: PlacementEdit;
  authority: number | undefined;
  generation: number;
}

export function createPlacementSession(
  client: PlacementClient,
  refreshInventory: () => Promise<void>,
  access?: { available: () => boolean; authority: () => number },
  onCompleted?: (before: PlacementSnapshot, after: PlacementSnapshot, edit: PlacementEdit) => void,
) {
  const [snapshot, setSnapshot] = createSignal<PlacementSnapshot>();
  const [previews, setPreviews] = createSignal<Readonly<Record<string, PhotoTransform>>>({});
  const [saving, setSaving] = createSignal(false);
  const [adjusting, setAdjusting] = createSignal(false);
  const [loading, setLoading] = createSignal(false);
  const [error, setError] = createSignal<string>();
  const [recovery, setRecovery] = createSignal<Recovery>();
  const [retained, setRetained] = createSignal(false);
  let queue: { id: string; transform: PhotoTransform }[] = [];
  let maybeUnconfirmed: Completion | undefined;
  const [target, setTarget] = createSignal<{ album: string; context: PlacementContext }>();
  let generation = 0;
  let authority = access?.authority();
  function ownsDrafts() {
    return access === undefined || (access.available() && authority === access.authority());
  }
  function canRecord(completion: Completion) {
    return completion.generation === generation && (access === undefined || (access.available() && completion.authority === access.authority()));
  }
  function canWrite() {
    if (ownsDrafts()) return true;
    setRetained(queue.length > 0 || Object.keys(previews()).length > 0);
    setRecovery("ownership");
    setError("Editing ownership changed. Read saved photos, review retained adjustments, then explicitly apply or discard them.");
    return false;
  }
  createEffect(() => {
    const current = access?.authority();
    if (current === authority) return;
    if (queue.length > 0 || Object.keys(previews()).length > 0) canWrite();
    else authority = current;
  });
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
    authority = access?.authority();
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
    if (navigationReason() === undefined && !loading()) setSnapshot(saved);
  }

  function clear() {
    generation++;
    setTarget(undefined);
    queue = [];
    maybeUnconfirmed = undefined;
    setSnapshot(undefined);
    setPreviews({});
    setRecovery(undefined);
    setRetained(false);
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
    queue.push({ id, transform });
    void drain();
  }

  async function drain() {
    const selected = target();
    if (saving() || recovery() !== undefined || selected === undefined || queue.length === 0 || !canWrite()) return;
    setSaving(true);
    setAdjusting(true);
    try {
      while (queue.length > 0) {
        for (const { id, transform } of [...queue]) {
          if (!canWrite()) {
            setSaving(false);
            setAdjusting(false);
            return;
          }
          const before = snapshot();
          /* v8 ignore next -- Canvas gestures require an acquired snapshot and navigation blocks while the FIFO drains. */
          if (before === undefined) throw new Error("Saved photo state is unavailable.");
          const command = { operation: "adjust", placementId: id, transform } as const;
          const completion = { before, command, authority, generation };
          maybeUnconfirmed = completion;
          const saved = await client.editPlacements(selected.album, selected.context, command);
          setSnapshot(saved);
          queue.shift();
          maybeUnconfirmed = undefined;
          if (canRecord(completion)) onCompleted?.(before, saved, command);
          setPreviews((current) => {
            if (current[id] !== transform) return current;
            return withoutPreview(current, id);
          });
        }
      }
    } catch {
      setRecovery("uncertain");
      setError("Photo adjustment is unsaved; its outcome is uncertain. Retry reads saved photos before attempting the adjustment again.");
      setSaving(false);
      setAdjusting(false);
      return;
    }
    try {
      await refreshInventory();
    } catch {
      setRecovery("refresh");
      setError("Photos saved, but the collection could not be refreshed. Retry reads only.");
    } finally {
      setSaving(false);
      setAdjusting(false);
    }
    if (queue.length > 0 && recovery() === undefined) await drain();
  }

  async function edit(command: PlacementEdit) {
    const selected = target();
    if (selected === undefined || unavailable() !== undefined || navigationReason() !== undefined || !canWrite()) return;
    setSaving(true);
    try {
      const before = snapshot();
      /* v8 ignore next -- unavailable() rejects an absent snapshot before this synchronous read. */
      if (before === undefined) throw new Error("Saved photo state is unavailable.");
      const completion = { before, command, authority, generation };
      maybeUnconfirmed = completion;
      const saved = await client.editPlacements(selected.album, selected.context, command);
      setSnapshot(saved);
      maybeUnconfirmed = undefined;
      if (canRecord(completion)) onCompleted?.(before, saved, command);
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
    if (selected === undefined || saving() || loading()) return false;
    setLoading(true);
    try {
      const saved = await client.readPlacements(selected.album, selected.context);
      setSnapshot(saved);
      const ownershipChanged = !ownsDrafts();
      const unconfirmed = maybeUnconfirmed;
      if (unconfirmed !== undefined && canRecord(unconfirmed) && matchesCompletedEdit(saved, unconfirmed.command))
        onCompleted?.(unconfirmed.before, saved, unconfirmed.command);
      maybeUnconfirmed = undefined;
      for (const { id, transform } of [...queue]) {
        const confirmed = saved.placements.find((row) => row.id === id);
        if ((confirmed === undefined && !ownershipChanged) || (confirmed !== undefined && sameTransform(confirmed.transform, transform))) {
          queue = queue.filter((entry) => entry.id !== id || entry.transform !== transform);
          setPreviews((current) => {
            return current[id] === transform ? withoutPreview(current, id) : current;
          });
        }
      }
      await refreshInventory();
      const hasDrafts = queue.length > 0 || Object.keys(previews()).length > 0;
      if (hasDrafts && !ownsDrafts()) {
        setRetained(true);
        setRecovery("ownership");
        setError("Editing ownership changed. Review retained photo adjustments before applying or discarding them.");
      } else {
        authority = access?.authority();
        setRetained(false);
        setError(undefined);
        setRecovery(undefined);
      }
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
      return false;
    } finally {
      setLoading(false);
    }
    if (recovery() === undefined) await drain();
    return true;
  }

  async function applyRetained() {
    if (saving() || loading() || (access !== undefined && !access.available())) return;
    const reviewedAuthority = access?.authority();
    if (!(await retry()) || recovery() !== "ownership" || (access !== undefined && (!access.available() || reviewedAuthority !== access.authority())))
      return;
    authority = access?.authority();
    setRecovery(undefined);
    setError(undefined);
    setRetained(false);
    queue = Object.entries(previews()).map(([id, transform]) => ({ id, transform }));
    await drain();
  }

  async function discardRetained() {
    if (saving() || loading()) return;
    queue = [];
    setPreviews({});
    setRetained(false);
    authority = access?.authority();
    await retry();
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
    retained,
    applyRetained,
    discardRetained,
  };
}

export function sameTransform(left: PhotoTransform, right: PhotoTransform): boolean {
  return left.x === right.x && left.y === right.y && left.zoom === right.zoom && left.mirrored === right.mirrored;
}

export type PlacementSession = ReturnType<typeof createPlacementSession>;

export function matchesCompletedEdit(saved: PlacementSnapshot, command: PlacementEdit): boolean {
  if (command.operation === "place") return saved.placements.some((row) => row.slotId === command.slotId && row.photoId === command.photoId);
  if (command.operation === "bleed") return saved.kind === "spread" && saved.layout.bleed.state === (command.enabled ? "enabled" : "disabled");
  if (command.operation === "spineOwner") return saved.kind === "cover" && saved.spineOwner === command.owner;
  const placement = saved.placements.find((row) => row.id === command.placementId);
  if (command.operation === "remove") return placement === undefined;
  if (command.operation === "move") return placement?.slotId === command.slotId;
  if (command.operation === "adjust") return placement !== undefined && sameTransform(placement.transform, command.transform);
  return placement?.kind === "spread" && placement.captionCorner === command.captionCorner;
}

function withoutPreview(current: Readonly<Record<string, PhotoTransform>>, id: string) {
  return Object.fromEntries(Object.entries(current).filter(([key]) => key !== id));
}
