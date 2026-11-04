import { Show, createMemo, createSignal, onCleanup, onMount } from "solid-js";
import type { AlbumClient, AlbumSummary } from "../model/Album";
import { describeFailure, validateYear } from "../model/Presentation";

export type AlbumAction = { kind: "create" } | { kind: "delete"; album: AlbumSummary };

export function AlbumDialog(props: {
  client: AlbumClient;
  action: AlbumAction;
  onClose: () => void;
  onSaved: () => Promise<void>;
  returnFocus: () => void;
}) {
  const [year, setYear] = createSignal(String(new Date().getFullYear()));
  const [error, setError] = createSignal<string>();
  const [pending, setPending] = createSignal(false);
  const [closing, setClosing] = createSignal(false);
  const deleting = createMemo(() => props.action.kind === "delete");
  const heading = createMemo(() => (props.action.kind === "delete" ? `Delete ${props.action.album.title}?` : "Create an album"));
  const unavailable = createMemo(() => (pending() ? "Saving album. Please wait." : closing() ? "Closing album dialog." : undefined));
  let dialog: HTMLDialogElement;
  let input: HTMLInputElement;
  let cancel: HTMLButtonElement;

  onMount(() => {
    dialog.showModal();
    if (deleting()) cancel.focus();
    else input.focus();
  });
  onCleanup(() => {
    dialog.close();
    props.returnFocus();
  });

  async function close() {
    setClosing(true);
    // Let Solid apply the closing animation before awaiting it.
    await Promise.resolve();
    // Native animation cancellation (including reduced motion) must still finish closing.
    await Promise.allSettled(dialog.getAnimations().map((animation) => animation.finished));
    props.onClose();
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    // Native disabled controls cannot submit while saving or closing.
    /* v8 ignore next */
    if (unavailable() !== undefined) return;
    const validation = deleting() ? undefined : validateYear(year());
    if (validation !== undefined) {
      setError(validation);
      return;
    }
    setPending(true);
    try {
      const action = props.action;
      if (action.kind === "create") await props.client.create(Number(year()));
      else await props.client.remove(action.album.id);
      await props.onSaved();
      await close();
    } catch (failure) {
      setError(describeFailure(failure));
      setPending(false);
    }
  }

  return (
    <dialog
      ref={(element) => {
        dialog = element;
      }}
      aria-label={heading()}
      role={deleting() ? "alertdialog" : "dialog"}
      class="album-dialog"
      classList={{ "confirmation-dialog": deleting(), "is-closing": closing() }}
      onCancel={(event) => {
        event.preventDefault();
        if (unavailable() === undefined) void close();
      }}
    >
      <Show when={deleting()}>
        <span class="confirmation-warning" aria-hidden="true">
          !
        </span>
      </Show>
      <header class="dialog-heading">
        <h2>{heading()}</h2>
        <Show when={!deleting()}>
          <button
            type="button"
            class="panel-close"
            aria-label="Close"
            disabled={unavailable() !== undefined}
            aria-describedby="album-dialog-feedback"
            onClick={() => {
              void close();
            }}
          >
            ×
          </button>
        </Show>
      </header>
      <form
        aria-label="Album details"
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <Show
          when={deleting()}
          fallback={
            <label>
              Album year
              <input
                ref={(element) => {
                  input = element;
                }}
                value={year()}
                inputmode="numeric"
                disabled={unavailable() !== undefined}
                onInput={(event) => {
                  setYear(event.currentTarget.value);
                  setError(undefined);
                }}
                aria-describedby="album-dialog-feedback"
              />
            </label>
          }
        >
          <p>This permanently deletes the album. Deleting cannot be undone.</p>
        </Show>
        <div id="album-dialog-feedback">
          <Show when={error()}>
            {(message) => (
              <p role="alert" aria-hidden={pending()}>
                {message()}
              </p>
            )}
          </Show>
          <div class="dialog-status">
            <Show when={unavailable()}>{(reason) => <p role="status">{reason()}</p>}</Show>
          </div>
        </div>
        <div class="dialog-actions">
          <button
            ref={(element) => {
              cancel = element;
            }}
            type="button"
            class="secondary"
            disabled={unavailable() !== undefined}
            aria-describedby="album-dialog-feedback"
            onClick={() => {
              void close();
            }}
          >
            Cancel
          </button>
          <button type="submit" classList={{ danger: deleting() }} disabled={unavailable() !== undefined} aria-describedby="album-dialog-feedback">
            {deleting() ? "Delete permanently" : "Create"}
          </button>
        </div>
      </form>
    </dialog>
  );
}
