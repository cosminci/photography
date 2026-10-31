import { Show, createEffect, createMemo, createRoot, createSignal, createUniqueId, on, onCleanup, onMount, untrack } from "solid-js";
import { createContentSave } from "./ContentSave";
import type { Photo } from "../model/Photo";
import type { CaptionCorner } from "../model/Placement";

export function createCaptionEditing(operations: {
  save: (photoId: string, text: string) => Promise<void>;
  read: (photoId: string) => Promise<string>;
  onBlock: (photoId: string, reason: string | undefined) => void;
}) {
  const [sessions, setSessions] = createSignal<readonly { photoId: string; save: ReturnType<typeof createContentSave<string>> }[]>([]);
  const retained = new Map<string, { save: ReturnType<typeof createContentSave<string>>; dispose: () => void }>();
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
  return { getPhotoSave, sessions };
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
  let label!: HTMLDivElement;
  const save = untrack(() => props.editing.getPhotoSave(photoId));
  const text = createMemo(() => props.photo.caption ?? "");
  const editing = createMemo(() => save.draft() !== undefined);
  const reason = createMemo(() => save.unavailable() ?? (editing() ? undefined : props.unavailable));
  const editable = createMemo(() => !props.reader);
  createEffect(() => {
    if (!editing()) label.textContent = text() === "" && editable() ? "Add caption" : text();
  });
  onMount(() => {
    const retained = save.draft();
    if (retained !== undefined) label.textContent = retained.value;
  });
  createEffect(
    on(
      editing,
      (active, previous) => {
        if (previous === true && !active) label.focus();
      },
      { defer: true },
    ),
  );

  function begin() {
    if (!editable() || editing() || reason() !== undefined) return;
    save.begin(text());
    label.textContent = text();
    label.focus();
  }

  function commit() {
    if (!editing() || save.error() !== undefined || save.unavailable() !== undefined) return;
    if (save.isUnchanged()) save.cancel();
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
        contentEditable={editing() && save.unavailable() === undefined ? "plaintext-only" : false}
        onClick={begin}
        onInput={() => {
          save.change(label.innerText);
        }}
        onPointerLeave={commit}
        onBlur={commit}
        onKeyDown={(event) => {
          if (editing()) {
            event.stopPropagation();
            if (event.key === "Escape") {
              event.preventDefault();
              if (save.unavailable() === undefined) save.cancel();
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
