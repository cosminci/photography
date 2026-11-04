import { Show, createEffect, createMemo, createSignal, createUniqueId, on, onCleanup, onMount } from "solid-js";
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
  onCompleted?: (before: string, after: string) => void;
  singleLine?: boolean;
  validate?: ((text: string) => string | undefined) | undefined;
  ownership: { maybeAuthority: number | undefined; recoveryLabel: string; reason: string; pending: boolean; recover: () => Promise<void> };
}) {
  const feedback = createUniqueId();
  const [validation, setValidation] = createSignal<string>();
  const save = createContentSave({
    maybeAuthority: () => props.ownership.maybeAuthority,
    save: (text: string) => props.save(text),
    read: () => props.read(),
    equal: (left, right) => left === right,
    rebase: (saved) => saved,
    onCompleted: (before, after) => props.onCompleted?.(before, after),
    onSaved: () => {
      props.onClose();
    },
  });
  const needsOwnership = createMemo(() => props.ownership.maybeAuthority === undefined);
  const unavailable = createMemo(() => (needsOwnership() ? props.ownership.reason : save.unavailable()));
  let dialog!: HTMLDialogElement;
  let input!: HTMLTextAreaElement | HTMLInputElement;
  let status!: HTMLDivElement;
  onMount(() => {
    save.begin(props.text);
    dialog.showModal();
    input.focus();
    input.setSelectionRange(0, 0);
    input.scrollTop = 0;
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
      <header class="dialog-heading">
        <h2>{props.heading}</h2>
      </header>
      <form
        aria-label={props.heading}
        onSubmit={(event) => {
          event.preventDefault();
          const error = props.validate?.(input.value);
          setValidation(error);
          if (error !== undefined) {
            input.focus();
            return;
          }
          void save.commit(true);
        }}
      >
        <Show when={needsOwnership()}>
          <button
            type="button"
            disabled={props.ownership.pending}
            aria-describedby={feedback}
            onClick={() => {
              void props.ownership.recover();
            }}
          >
            {props.ownership.recoveryLabel}
          </button>
        </Show>
        <Show
          when={props.singleLine}
          fallback={
            <textarea
              ref={(element) => {
                input = element;
              }}
              aria-label={props.label}
              rows={14}
              value={save.draft()?.value ?? props.text}
              disabled={unavailable() !== undefined}
              aria-describedby={feedback}
              onInput={(event) => {
                setValidation(undefined);
                save.change(event.currentTarget.value);
              }}
            />
          }
        >
          <input
            ref={(element) => {
              input = element;
            }}
            aria-label={props.label}
            value={save.draft()?.value ?? props.text}
            disabled={unavailable() !== undefined}
            aria-describedby={feedback}
            onInput={(event) => {
              setValidation(undefined);
              save.change(event.currentTarget.value);
            }}
          />
        </Show>
        <div
          ref={(element) => {
            status = element;
          }}
          id={feedback}
          tabindex="-1"
        >
          <Show when={unavailable()}>{(reason) => <p role="status">{reason()}</p>}</Show>
          <Show when={save.error()}>{(message) => <p role="alert">{message()}</p>}</Show>
          <Show when={validation()}>{(message) => <p role="alert">{message()}</p>}</Show>
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
        <div class="dialog-actions" role="group" aria-label="Text actions">
          <button
            type="button"
            class="editor-icon-action editor-cancel"
            aria-label="Cancel"
            title="Cancel"
            disabled={save.unavailable() !== undefined}
            aria-describedby={save.unavailable() === undefined ? undefined : feedback}
            onClick={cancel}
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="m6 6 12 12M6 18 18 6" />
            </svg>
          </button>
          <button
            type="submit"
            class="editor-icon-action"
            aria-label="Save"
            title="Save"
            disabled={unavailable() !== undefined}
            aria-describedby={unavailable() === undefined ? undefined : feedback}
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M5 3h12l4 4v14H3V3ZM7 3v6h10V3M7 21v-8h10v8M14 4v3" />
            </svg>
          </button>
        </div>
      </form>
    </dialog>
  );
}
