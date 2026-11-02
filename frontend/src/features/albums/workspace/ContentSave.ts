import { createMemo, createSignal, onCleanup } from "solid-js";
import { AcknowledgedWriteError, AlbumRequestError } from "../model/Album";
import { describeFailure } from "../model/Presentation";

export function createContentSave<T, Saved = T>(operations: {
  save: (draft: T) => Promise<Saved | undefined>;
  read: (draft: T) => Promise<Saved>;
  equal: (left: Saved, right: T) => boolean;
  onSaved: (saved: Saved, draft: T) => void;
  maybeAuthority?: (() => number | undefined) | undefined;
}) {
  const [draft, setDraft] = createSignal<{ original: T; value: T }>();
  const [pending, setPending] = createSignal(false);
  const [recovery, setRecovery] = createSignal<"acknowledged" | "uncertain">();
  const [error, setError] = createSignal<string>();
  const [active, setActive] = createSignal(true);
  let maybeAuthority: number | undefined;
  let maybeReviewedAuthority: number | undefined;
  let ownershipChanged = false;
  onCleanup(() => {
    setActive(false);
  });
  const unavailable = createMemo(() =>
    pending() ? "Saving content. Please wait." : recovery() !== undefined ? "Read saved content before continuing." : undefined,
  );

  function begin(value: T) {
    maybeAuthority = operations.maybeAuthority?.();
    maybeReviewedAuthority = maybeAuthority;
    ownershipChanged = false;
    setDraft({ original: value, value });
    setError(undefined);
  }

  function change(value: T) {
    const retained = draft();
    if (retained !== undefined) setDraft({ ...retained, value });
  }

  function cancel() {
    if (unavailable() !== undefined) return;
    setDraft(undefined);
    setError(undefined);
  }

  function finish(saved: Saved, value: T) {
    setRecovery("acknowledged");
    try {
      operations.onSaved(saved, value);
    } catch (cause) {
      throw new AcknowledgedWriteError("The saved result could not be published. Read saved content again.", { cause });
    }
    setRecovery(undefined);
    setError(undefined);
    setDraft(undefined);
  }

  async function reconcile() {
    const retained = draft();
    if (!active() || retained === undefined || pending()) return;
    setPending(true);
    const maybeObservedAuthority = operations.maybeAuthority?.();
    try {
      const saved = await operations.read(retained.value);
      if (!active()) return;
      if (operations.maybeAuthority !== undefined && maybeObservedAuthority !== operations.maybeAuthority()) {
        setError("Editing ownership changed while reading saved content. The draft is retained. Read saved content again.");
        return;
      }
      if (recovery() === "acknowledged" || operations.equal(saved, retained.value)) finish(saved, retained.value);
      else {
        maybeReviewedAuthority = maybeObservedAuthority;
        setRecovery(undefined);
        setError("The draft is not saved. Review it before saving again.");
      }
    } catch (failure) {
      if (!active()) return;
      setError(`Saved content could not be read. The draft is retained. ${describeFailure(failure)}`);
    } finally {
      setPending(false);
    }
  }

  async function commit(reviewed = false) {
    const retained = draft();
    if (!active() || retained === undefined || pending()) return;
    if (operations.maybeAuthority !== undefined) {
      const maybeCurrentAuthority = operations.maybeAuthority();
      if (maybeCurrentAuthority === undefined) {
        ownershipChanged = true;
        setError("Editing ownership is unavailable. The draft is retained.");
        return;
      }
      if (maybeCurrentAuthority !== maybeAuthority) ownershipChanged = true;
      if (ownershipChanged) {
        if (!reviewed) {
          setError("Editing ownership changed. Read saved content and explicitly review the draft before saving.");
          return;
        }
        if (maybeReviewedAuthority !== maybeCurrentAuthority) {
          setRecovery("uncertain");
          await reconcile();
          return;
        }
        maybeAuthority = maybeCurrentAuthority;
        ownershipChanged = false;
      }
    }
    if (recovery() !== undefined) {
      await reconcile();
      return;
    }
    setPending(true);
    setError(undefined);
    try {
      const saved = await operations.save(retained.value);
      if (!active()) return;
      if (saved !== undefined) finish(saved, retained.value);
      else setRecovery("acknowledged");
    } catch (failure) {
      if (!active()) return;
      if (failure instanceof AcknowledgedWriteError) setRecovery("acknowledged");
      else if (!(failure instanceof AlbumRequestError) || failure.status >= 500) {
        setRecovery("uncertain");
        setError("Save outcome is uncertain. Read saved content before trying again.");
      } else setError(describeFailure(failure));
    } finally {
      setPending(false);
    }
    if (recovery() === "acknowledged") await reconcile();
  }

  return { draft, pending, recovery, error, unavailable, begin, change, cancel, commit, reconcile };
}

export type ContentSave<T, Saved = T> = ReturnType<typeof createContentSave<T, Saved>>;
