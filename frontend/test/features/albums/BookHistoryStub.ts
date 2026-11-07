import { createSignal } from "solid-js";
import { vi } from "vitest";
import type { BookHistory } from "../../../src/features/albums/workspace/BookHistory";

export function buildBookHistory() {
  const [unavailable, setUnavailable] = createSignal(false);
  const [message, setMessage] = createSignal("");
  const history = {
    unavailable,
    message,
    nextUndo: vi.fn<BookHistory["nextUndo"]>().mockReturnValue(undefined),
    nextRedo: vi.fn<BookHistory["nextRedo"]>().mockReturnValue(undefined),
    pending: vi.fn().mockReturnValue(false),
    uncertain: vi.fn().mockReturnValue(false),
    conflict: vi.fn().mockReturnValue(false),
    interrupted: vi.fn<BookHistory["interrupted"]>().mockReturnValue(undefined),
    record: vi.fn<BookHistory["record"]>().mockReturnValue(true),
    reset: vi.fn<BookHistory["reset"]>(),
    block: vi.fn<BookHistory["block"]>(),
    traverse: vi.fn<BookHistory["traverse"]>().mockResolvedValue(undefined),
    reconcile: vi.fn<BookHistory["reconcile"]>().mockResolvedValue(undefined),
  };
  return { history, setUnavailable, setMessage };
}
