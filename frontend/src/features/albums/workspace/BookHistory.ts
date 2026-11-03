import { createSignal } from "solid-js";

export type HistoryDirection = "undo" | "redo";
export type HistoryOutcome =
  | { readonly kind: "saved" }
  | { readonly kind: "unchanged" }
  | { readonly kind: "blocked"; readonly message: string; readonly partial?: boolean }
  | { readonly kind: "conflict" | "uncertain"; readonly message: string };

export interface HistoryAction {
  readonly label: string;
  readonly apply: (direction: HistoryDirection) => Promise<HistoryOutcome>;
  readonly reconcile: (direction: HistoryDirection) => Promise<HistoryOutcome>;
}

export function createBookHistory() {
  const [undo, setUndo] = createSignal<readonly HistoryAction[]>([]);
  const [redo, setRedo] = createSignal<readonly HistoryAction[]>([]);
  const [pending, setPending] = createSignal(false);
  const [unresolved, setUnresolved] = createSignal<{ action: HistoryAction; direction: HistoryDirection }>();
  const [conflict, setConflict] = createSignal(false);
  const [interrupted, setInterrupted] = createSignal<HistoryDirection>();
  const [message, setMessage] = createSignal("");
  let generation = 0;
  const nextUndo = () => undo().at(-1);
  const nextRedo = () => redo().at(-1);
  const uncertain = () => unresolved() !== undefined;
  const unavailable = () => pending() || uncertain() || conflict() || interrupted() !== undefined;

  function record(action: HistoryAction): boolean {
    if (unavailable()) return false;
    setUndo((current) => [...current, action].slice(-50));
    setRedo([]);
    setMessage("");
    return true;
  }

  function reset(reason = "") {
    generation++;
    setUndo([]);
    setRedo([]);
    setUnresolved(undefined);
    setConflict(false);
    setInterrupted(undefined);
    setPending(false);
    setMessage(reason);
  }
  function block(reason: string) {
    setConflict(true);
    setMessage(reason);
  }

  function finish(action: HistoryAction, direction: HistoryDirection, outcome: HistoryOutcome) {
    if (outcome.kind === "saved") {
      if (direction === "undo") {
        setUndo((current) => current.slice(0, -1));
        setRedo((current) => [...current, action]);
      } else {
        setRedo((current) => current.slice(0, -1));
        setUndo((current) => [...current, action]);
      }
      setUnresolved(undefined);
      setInterrupted(undefined);
      setMessage("");
    } else if (outcome.kind === "unchanged") {
      setUnresolved(undefined);
      setMessage("The change was not saved. Review the saved book before retrying.");
    } else {
      setMessage(outcome.message);
      setUnresolved(outcome.kind === "uncertain" ? { action, direction } : undefined);
      setConflict(outcome.kind === "conflict");
      if (outcome.kind === "uncertain" || (outcome.kind === "blocked" && outcome.partial === true)) setInterrupted(direction);
    }
  }

  async function traverse(direction: HistoryDirection) {
    const action = direction === "undo" ? nextUndo() : nextRedo();
    if (action === undefined || pending() || uncertain() || conflict() || (interrupted() !== undefined && interrupted() !== direction)) return;
    const request = generation;
    setPending(true);
    setMessage("");
    try {
      const outcome = await action.apply(direction);
      if (request === generation) finish(action, direction, outcome);
    } catch {
      if (request === generation) {
        setUnresolved({ action, direction });
        setInterrupted(direction);
        setMessage("Save outcome is uncertain. Read saved content before continuing.");
      }
    } finally {
      if (request === generation) setPending(false);
    }
  }

  async function reconcile() {
    const retained = unresolved();
    if (retained === undefined || pending()) return;
    const request = generation;
    setPending(true);
    try {
      const outcome = await retained.action.reconcile(retained.direction);
      if (request === generation) finish(retained.action, retained.direction, outcome);
    } catch {
      if (request === generation) setMessage("Saved content could not be read. History is retained; retry reads only.");
    } finally {
      if (request === generation) setPending(false);
    }
  }

  return { nextUndo, nextRedo, pending, uncertain, conflict, interrupted, unavailable, message, record, reset, block, traverse, reconcile };
}

export type BookHistory = ReturnType<typeof createBookHistory>;
