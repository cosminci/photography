import { Show, createEffect, createUniqueId, on, onCleanup, onMount } from "solid-js";
import { createContentSave } from "./ContentSave";

export function TextEditor(props: {
  heading: string;
  label: string;
  text: string;
  save: (text: string) => Promise<string | undefined>;
  read: () => Promise<string>;
  onClose: () => void;
  onBlock: (reason: string | undefined) => void;
  returnFocus: () => void;
}) {
  const feedback = createUniqueId();
  const save = createContentSave({
    save: (text: string) => props.save(text),
    read: () => props.read(),
    equal: (left, right) => left === right,
    onSaved: () => {
      props.onClose();
    },
  });
  let dialog!: HTMLDialogElement;
  let input!: HTMLTextAreaElement;
  let status!: HTMLDivElement;
  onMount(() => {
    save.begin(props.text);
    dialog.showModal();
    input.focus();
  });
  createEffect(() => {
    props.onBlock(save.unavailable() ?? "Finish the content edit before leaving.");
  });
  createEffect(
    on(
      save.pending,
      (pending) => {
        if (!pending && save.error() !== undefined) {
          if (save.recovery() === undefined) input.focus();
          else status.focus();
        }
      },
      { defer: true },
    ),
  );
  onCleanup(() => {
    props.onBlock(undefined);
    dialog.close();
    queueMicrotask(props.returnFocus);
  });

  function cancel() {
    if (save.unavailable() !== undefined) return;
    save.cancel();
    props.onClose();
  }

  return (
    <dialog
      ref={(element) => {
        dialog = element;
      }}
      class="album-dialog content-dialog"
      aria-label={props.heading}
      onCancel={(event) => {
        event.preventDefault();
        cancel();
      }}
    >
      <form
        aria-label={props.heading}
        onSubmit={(event) => {
          event.preventDefault();
          void save.commit();
        }}
      >
        <header class="dialog-heading">
          <h2>{props.heading}</h2>
          <div class="content-actions" role="group" aria-label="Text actions">
            <button
              type="submit"
              class="content-action"
              aria-label="Save"
              title="Save"
              disabled={save.unavailable() !== undefined}
              aria-describedby={save.unavailable() === undefined ? undefined : feedback}
            >
              <svg class="workspace-action-icon" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M4 3h13l4 4v14H3V3ZM7 3v6h10V3M7 21v-8h10v8" />
              </svg>
            </button>
            <button
              type="button"
              class="content-action content-cancel"
              aria-label="Cancel"
              title="Cancel"
              disabled={save.unavailable() !== undefined}
              aria-describedby={save.unavailable() === undefined ? undefined : feedback}
              onClick={cancel}
            >
              <svg class="workspace-action-icon" viewBox="0 0 24 24" aria-hidden="true">
                <path d="m5 5 14 14M5 19 19 5" />
              </svg>
            </button>
          </div>
        </header>
        <textarea
          ref={(element) => {
            input = element;
          }}
          aria-label={props.label}
          value={save.draft()?.value ?? props.text}
          disabled={save.unavailable() !== undefined}
          aria-describedby={feedback}
          onInput={(event) => {
            save.change(event.currentTarget.value);
          }}
        />
        <div
          ref={(element) => {
            status = element;
          }}
          id={feedback}
          tabindex="-1"
        >
          <Show when={save.unavailable()}>{(reason) => <p role="status">{reason()}</p>}</Show>
          <Show when={save.error()}>{(message) => <p role="alert">{message()}</p>}</Show>
          <Show when={save.recovery() !== undefined}>
            <button
              type="button"
              disabled={save.pending()}
              aria-describedby={feedback}
              onClick={() => {
                void save.reconcile();
              }}
            >
              Read saved content
            </button>
          </Show>
        </div>
      </form>
    </dialog>
  );
}
