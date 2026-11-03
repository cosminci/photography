import { Show, createUniqueId, onCleanup } from "solid-js";
import type { BookHistory, HistoryDirection } from "./BookHistory";

export function BookHistoryControls(props: {
  history: BookHistory;
  unavailable: string | undefined;
  traverse: (direction: HistoryDirection) => Promise<void>;
  reconcile: () => Promise<void>;
}) {
  const feedback = createUniqueId();
  const recoveryReason = () => props.unavailable ?? (props.history.pending() ? "Saving the history action. Please wait." : undefined);
  let controls!: HTMLDivElement;
  function reason(direction: HistoryDirection) {
    return (
      props.unavailable ??
      (props.history.pending()
        ? "Saving the history action. Please wait."
        : props.history.uncertain()
          ? "Read saved content before continuing."
          : props.history.conflict()
            ? "Resolve the history conflict before continuing."
            : props.history.interrupted() !== undefined && props.history.interrupted() !== direction
              ? "Finish the interrupted history action first."
              : (direction === "undo" ? props.history.nextUndo() : props.history.nextRedo()) === undefined
                ? `Nothing to ${direction}.`
                : undefined)
    );
  }
  function label(direction: HistoryDirection) {
    const action = direction === "undo" ? props.history.nextUndo() : props.history.nextRedo();
    return `${direction === "undo" ? "Undo" : "Redo"}${action === undefined ? "" : `: ${action.label}`}`;
  }
  async function traverse(direction: HistoryDirection, opener: HTMLElement | null) {
    if (reason(direction) !== undefined) return;
    await props.traverse(direction);
    if (document.activeElement === document.body) {
      if (opener?.isConnected === true && !(opener instanceof HTMLButtonElement && opener.disabled)) opener.focus({ preventScroll: true });
      else controls.focus({ preventScroll: true });
    }
  }
  function keyboard(event: KeyboardEvent) {
    if (event.defaultPrevented || event.isComposing || event.altKey || !(event.metaKey || event.ctrlKey)) return;
    if (
      event.target instanceof Element &&
      event.target.closest('input, textarea, select, [contenteditable]:not([contenteditable="false"]), dialog') !== null
    )
      return;
    const key = event.key.toLowerCase();
    const direction = key === "z" ? (event.shiftKey ? "redo" : "undo") : key === "y" && !event.shiftKey ? "redo" : undefined;
    if (direction === undefined) return;
    event.preventDefault();
    void traverse(direction, document.activeElement instanceof HTMLElement ? document.activeElement : null);
  }
  document.addEventListener("keydown", keyboard);
  onCleanup(() => {
    document.removeEventListener("keydown", keyboard);
  });
  return (
    <div
      ref={(element) => {
        controls = element;
      }}
      class="book-history"
      role="group"
      aria-label="Book edit history"
      tabindex="-1"
    >
      <div class="workspace-mode">
        <button
          type="button"
          class="editor-icon-action"
          title={label("undo")}
          aria-label={label("undo")}
          disabled={reason("undo") !== undefined}
          aria-describedby={reason("undo") === undefined ? undefined : `${feedback}-undo`}
          onClick={(event) => void traverse("undo", event.currentTarget)}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M9 5 3 11l6 6M3 11h11a6 6 0 0 1 6 6v2" />
          </svg>
        </button>
        <button
          type="button"
          class="editor-icon-action"
          title={label("redo")}
          aria-label={label("redo")}
          disabled={reason("redo") !== undefined}
          aria-describedby={reason("redo") === undefined ? undefined : `${feedback}-redo`}
          onClick={(event) => void traverse("redo", event.currentTarget)}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="m15 5 6 6-6 6m6-6H10a6 6 0 0 0-6 6v2" />
          </svg>
        </button>
      </div>
      <span class="visually-hidden" id={`${feedback}-undo`}>
        {reason("undo")}
      </span>
      <span class="visually-hidden" id={`${feedback}-redo`}>
        {reason("redo")}
      </span>
      <span class="visually-hidden" id={`${feedback}-recovery`}>
        {recoveryReason()}
      </span>
      <Show when={props.history.message()}>
        {(message) => (
          <p role="status" class="control-help">
            {message()}
          </p>
        )}
      </Show>
      <Show when={props.history.uncertain()}>
        <button
          type="button"
          disabled={recoveryReason() !== undefined}
          aria-describedby={recoveryReason() === undefined ? undefined : `${feedback}-recovery`}
          onClick={() => void props.reconcile()}
        >
          Read saved history action
        </button>
      </Show>
      <Show when={props.history.conflict() || props.history.interrupted() !== undefined}>
        <button
          type="button"
          disabled={recoveryReason() !== undefined}
          aria-describedby={recoveryReason() === undefined ? undefined : `${feedback}-recovery`}
          onClick={() => {
            props.history.reset("Current saved book kept. Undo history cleared.");
          }}
        >
          Keep current book and clear history
        </button>
      </Show>
    </div>
  );
}
