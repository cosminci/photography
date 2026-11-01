import { Show, createEffect, createMemo, createRoot, createSignal, createUniqueId, onCleanup, onMount, untrack } from "solid-js";
import { createContentSave, type ContentSave } from "./ContentSave";
import type { Photo } from "../model/Photo";
import type { CaptionCorner } from "../model/Placement";

export interface CaptionState {
  readonly photoId: string;
  readonly save: ContentSave<string>;
}

export function createCaptionEditing(operations: {
  save: (photoId: string, text: string) => Promise<void>;
  read: (photoId: string) => Promise<string>;
  onBlock: (photoId: string, reason: string | undefined) => void;
}) {
  const [sessions, setSessions] = createSignal<readonly CaptionState[]>([]);
  const [requestedFocus, setRequestedFocus] = createSignal<string>();
  const focus = {
    requested: requestedFocus,
    request: (photoId: string) => {
      setRequestedFocus(photoId);
    },
    clear: () => {
      setRequestedFocus(undefined);
    },
  };
  const retained = new Map<string, { save: ContentSave<string>; dispose: () => void }>();
  onCleanup(() => {
    for (const state of retained.values()) state.dispose();
  });
  function getPhotoSave(photoId: string) {
    const existing = retained.get(photoId);
    if (existing !== undefined) return existing.save;
    const state = createRoot((dispose) => {
      const save = createContentSave<string>({
        save: async (text) => {
          await operations.save(photoId, text);
          return undefined;
        },
        read: () => operations.read(photoId),
        equal: (left, right) => left === right,
        onSaved: () => undefined,
      });
      createEffect(() => {
        operations.onBlock(photoId, save.draft() === undefined ? undefined : (save.unavailable() ?? "Finish the caption edit before leaving."));
      });
      return { save, dispose };
    });
    retained.set(photoId, state);
    setSessions((current) => [...current, { photoId, save: state.save }]);
    return state.save;
  }
  return { getPhotoSave, sessions, focus };
}
export type CaptionEditing = ReturnType<typeof createCaptionEditing>;

export function PhotoCaption(props: {
  photo: Pick<Photo, "id" | "filename" | "caption">;
  corner: CaptionCorner;
  reader: boolean;
  unavailable: string | undefined;
  editing: CaptionEditing;
}) {
  const feedback = createUniqueId();
  const photoId = untrack(() => props.photo.id);
  const captionEditing = untrack(() => props.editing);
  const selection = window.getSelection();
  let label!: HTMLDivElement;
  let composing = false;
  let departureRequested = false;
  const save = captionEditing.getPhotoSave(photoId);
  const text = createMemo(() => props.photo.caption ?? "");
  const editing = createMemo(() => save.draft() !== undefined);
  const editable = createMemo(() => !props.reader);
  const reason = createMemo(
    () =>
      save.unavailable() ??
      (editable() && selection === null ? "Caption editing is not supported by this browser." : editing() ? undefined : props.unavailable),
  );
  createEffect(() => {
    if (!editing()) label.textContent = text() === "" && editable() ? "Add caption" : text();
  });
  onMount(() => {
    const retained = save.draft();
    if (retained !== undefined) renderDraft(retained.value);
    window.addEventListener("pointermove", pointerMoved);
  });
  createEffect(() => {
    if (captionEditing.focus.requested() === photoId) {
      label.focus();
      captionEditing.focus.clear();
    }
  });
  onCleanup(() => {
    window.removeEventListener("pointermove", pointerMoved);
    if (editing() && document.activeElement === label) captionEditing.focus.request(photoId);
  });
  function readText() {
    if (selection === null) return undefined;
    const ranges = Array.from({ length: selection.rangeCount }, (_, index) => selection.getRangeAt(index).cloneRange());
    try {
      selection.selectAllChildren(label);
      return selection.toString();
    } finally {
      selection.removeAllRanges();
      for (const range of ranges) selection.addRange(range);
    }
  }
  function readInput() {
    const value = readText();
    if (value !== undefined) save.change(value);
  }
  function renderDraft(value: string) {
    label.textContent = value === "" || value.endsWith("\n") ? `${value}\n` : value;
  }
  function pointerMoved(event: PointerEvent) {
    if (!editable() || !editing()) return;
    const box = label.getBoundingClientRect();
    if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) commit();
  }

  function begin() {
    if (!editable() || editing() || reason() !== undefined) return;
    save.begin(text());
    composing = false;
    departureRequested = false;
    renderDraft(text());
    label.focus();
  }

  function commit() {
    const draft = save.draft();
    if (draft === undefined || save.error() !== undefined || reason() !== undefined) return;
    if (composing) {
      departureRequested = true;
      return;
    }
    if (draft.value === draft.original) save.cancel();
    else void save.commit();
  }

  return (
    <>
      <div
        ref={(element) => {
          label = element;
        }}
        class="photo-caption"
        classList={{ "photo-caption-empty": text() === "" && !editing() }}
        data-corner={props.corner}
        role={editing() ? "textbox" : editable() ? "button" : undefined}
        aria-label={
          editing()
            ? `Caption for ${props.photo.filename}`
            : editable()
              ? `${text() === "" ? "Add" : "Edit"} caption for ${props.photo.filename}`
              : undefined
        }
        aria-multiline={editing() ? true : undefined}
        aria-disabled={editable() && reason() !== undefined ? true : undefined}
        aria-describedby={reason() === undefined && save.error() === undefined ? undefined : feedback}
        tabindex={editable() ? 0 : undefined}
        contentEditable={editable() && editing() && reason() === undefined ? "plaintext-only" : false}
        onClick={begin}
        onInput={() => {
          if (!composing) readInput();
        }}
        onCompositionStart={() => {
          composing = true;
        }}
        onCompositionEnd={() => {
          composing = false;
          readInput();
          if (departureRequested) {
            departureRequested = false;
            commit();
          }
        }}
        onBlur={commit}
        onKeyDown={(event) => {
          if (editing()) {
            event.stopPropagation();
            if (event.key === "Escape") {
              event.preventDefault();
              if (save.unavailable() === undefined) {
                composing = false;
                departureRequested = false;
                save.cancel();
              }
            }
          } else if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            begin();
          }
        }}
      />
      <Show when={reason() !== undefined || save.error() !== undefined}>
        <div id={feedback} class="caption-feedback">
          <Show when={reason()}>{(message) => <span role="status">{message()}</span>}</Show>
          <Show when={save.error()}>{(message) => <span role="alert">{message()}</span>}</Show>
          <Show when={save.error()}>
            <button
              type="button"
              disabled={save.pending()}
              aria-describedby={feedback}
              onClick={() => {
                label.focus();
                void save.commit();
              }}
            >
              {save.recovery() === undefined ? "Retry caption" : "Read saved caption"}
            </button>
          </Show>
        </div>
      </Show>
    </>
  );
}
