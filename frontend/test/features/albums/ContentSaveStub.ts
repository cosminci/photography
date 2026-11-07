import { createSignal } from "solid-js";
import { vi } from "vitest";
import type { ContentSave } from "../../../src/features/albums/workspace/ContentSave";

export function buildContentSave<T>() {
  const [draft, setDraft] = createSignal<ReturnType<ContentSave<T>["draft"]>>();
  const [pending, setPending] = createSignal(false);
  const [recovery, setRecovery] = createSignal<ReturnType<ContentSave<T>["recovery"]>>();
  const [error, setError] = createSignal<string>();
  const [unavailable, setUnavailable] = createSignal<ReturnType<ContentSave<T>["unavailable"]>>();
  const save = {
    draft,
    pending,
    recovery,
    error,
    unavailable,
    begin: vi.fn<ContentSave<T>["begin"]>(),
    change: vi.fn<ContentSave<T>["change"]>(),
    cancel: vi.fn<ContentSave<T>["cancel"]>(),
    commit: vi.fn<ContentSave<T>["commit"]>().mockResolvedValue(undefined),
    reconcile: vi.fn<ContentSave<T>["reconcile"]>().mockResolvedValue(undefined),
  };
  return { save, setDraft, setPending, setRecovery, setError, setUnavailable };
}
