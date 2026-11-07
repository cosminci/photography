import { createSignal } from "solid-js";
import { vi } from "vitest";
import type { PlacementSession } from "../../../src/features/albums/workspace/PlacementSession";

export function buildPlacementSession() {
  const [snapshot, setSnapshot] = createSignal<ReturnType<PlacementSession["snapshot"]>>();
  const [placements, setPlacements] = createSignal<ReturnType<PlacementSession["placements"]>>([]);
  const [saving, setSaving] = createSignal(false);
  const [loading, setLoading] = createSignal(false);
  const [error, setError] = createSignal<string>();
  const [retained, setRetained] = createSignal(false);
  const [navigationReason, setNavigationReason] = createSignal<ReturnType<PlacementSession["navigationReason"]>>();
  const [unavailable, setUnavailable] = createSignal<ReturnType<PlacementSession["unavailable"]>>();
  const session = {
    snapshot,
    placements,
    saving,
    loading,
    error,
    retained,
    navigationReason,
    unavailable,
    load: vi.fn<PlacementSession["load"]>().mockResolvedValue(undefined),
    selectSpread: vi.fn<PlacementSession["selectSpread"]>(),
    reconcile: vi.fn<PlacementSession["reconcile"]>(),
    clear: vi.fn<PlacementSession["clear"]>(),
    preview: vi.fn<PlacementSession["preview"]>(),
    restore: vi.fn<PlacementSession["restore"]>(),
    complete: vi.fn<PlacementSession["complete"]>().mockResolvedValue(undefined),
    edit: vi.fn<PlacementSession["edit"]>().mockResolvedValue(undefined),
    retry: vi.fn<PlacementSession["retry"]>().mockResolvedValue(false),
    applyRetained: vi.fn<PlacementSession["applyRetained"]>().mockResolvedValue(undefined),
    discardRetained: vi.fn<PlacementSession["discardRetained"]>().mockResolvedValue(undefined),
  };
  return { session, setSnapshot, setPlacements, setSaving, setLoading, setError, setRetained, setNavigationReason, setUnavailable };
}
