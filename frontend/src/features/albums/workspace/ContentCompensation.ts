import { AlbumRequestError } from "../model/Album";
import type { HistoryAction, HistoryDirection, HistoryOutcome } from "./BookHistory";

export function createContentCompensation<T>(operations: {
  label: string;
  before: T;
  after: T;
  read: () => Promise<T>;
  write: (value: T) => Promise<unknown>;
  equal: (left: T, right: T) => boolean;
  prepare: () => Promise<string | undefined>;
}): HistoryAction {
  const desired = (direction: HistoryDirection) => (direction === "undo" ? operations.before : operations.after);
  const expected = (direction: HistoryDirection) => (direction === "undo" ? operations.after : operations.before);

  async function apply(direction: HistoryDirection): Promise<HistoryOutcome> {
    const reason = await operations.prepare();
    if (reason !== undefined) return { kind: "blocked", message: reason };
    const saved = await operations.read();
    if (!operations.equal(saved, expected(direction)))
      return { kind: "conflict", message: "Saved content changed. This action cannot overwrite it." };
    const lostAccess = await operations.prepare();
    if (lostAccess !== undefined) return { kind: "blocked", message: lostAccess };
    try {
      await operations.write(desired(direction));
      return { kind: "saved" };
    } catch (failure) {
      return failure instanceof AlbumRequestError && failure.status < 500
        ? { kind: "blocked", message: failure.message }
        : { kind: "uncertain", message: "Compensation outcome is uncertain. Read saved content before continuing." };
    }
  }

  async function reconcile(direction: HistoryDirection): Promise<HistoryOutcome> {
    const saved = await operations.read();
    if (operations.equal(saved, desired(direction))) return { kind: "saved" };
    if (operations.equal(saved, expected(direction))) return { kind: "unchanged" };
    return { kind: "conflict", message: "Saved content differs from both known compensation outcomes." };
  }

  return { label: operations.label, apply, reconcile };
}
