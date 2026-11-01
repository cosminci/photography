import { Show, createEffect, onCleanup, onMount } from "solid-js";

export function SpreadConfirmation(props: {
  placements: number;
  deleting: boolean;
  pending: boolean;
  error: string | undefined;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  let dialog: HTMLDialogElement;
  let cancel: HTMLButtonElement;
  onMount(() => {
    dialog.showModal();
    createEffect(() => {
      if (!props.pending) cancel.focus();
    });
  });
  onCleanup(() => {
    dialog.close();
  });
  return (
    <dialog
      ref={(element) => {
        dialog = element;
      }}
      class="album-dialog confirmation-dialog"
      role="alertdialog"
      aria-label={props.deleting ? "Delete occupied spread?" : "Change occupied spread template?"}
      aria-describedby="spread-confirmation-details"
      onCancel={(event) => {
        event.preventDefault();
        if (!props.pending) props.onCancel();
      }}
    >
      <h2>{props.deleting ? "Delete occupied spread?" : "Change occupied spread template?"}</h2>
      <p id="spread-confirmation-details">
        This spread contains {props.placements} placements.{" "}
        {props.deleting
          ? "Deleting releases all its photos."
          : "Changing the template fits photos into the new slots where possible and returns photos that do not fit to selection."}{" "}
        Photos remain in the chapter collection.
      </p>
      <Show when={props.error}>{(message) => <p role="alert">{message()}</p>}</Show>
      <Show when={props.pending}>
        <p id="spread-confirmation-pending" role="status" class="control-help">
          Saving spread. Please wait.
        </p>
      </Show>
      <div class="dialog-actions">
        <button
          ref={(element) => {
            cancel = element;
          }}
          type="button"
          disabled={props.pending}
          aria-describedby={props.pending ? "spread-confirmation-pending" : undefined}
          onClick={() => {
            props.onCancel();
          }}
        >
          Cancel
        </button>
        <button
          type="button"
          disabled={props.pending}
          aria-describedby={props.pending ? "spread-confirmation-pending" : undefined}
          onClick={() => {
            props.onConfirm();
          }}
        >
          {props.error === undefined ? "Confirm" : "Retry confirmation"}
        </button>
      </div>
    </dialog>
  );
}
