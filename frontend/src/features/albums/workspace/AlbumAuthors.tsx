import { For, Show, createEffect, createMemo, createUniqueId, onCleanup } from "solid-js";
import { albumAuthors, type Author } from "../model/Album";
import { createContentSave } from "./ContentSave";

export function AlbumAuthors(props: {
  authors: readonly Author[];
  save: (authors: readonly Author[]) => Promise<readonly Author[]>;
  read: () => Promise<readonly Author[]>;
  unavailable: string | undefined;
  onBlock: (reason: string | undefined) => void;
}) {
  const feedback = createUniqueId();
  const save = createContentSave({
    save: (authors: readonly Author[]) => props.save(authors),
    read: () => props.read(),
    equal: (left, right) => left.length === right.length && left.every((author) => right.includes(author)),
    onSaved: () => undefined,
  });
  const reason = createMemo(() => save.unavailable() ?? props.unavailable);
  createEffect(() => {
    props.onBlock(save.draft() === undefined ? undefined : (save.unavailable() ?? "Resolve the author save before leaving."));
  });
  onCleanup(() => {
    props.onBlock(undefined);
  });
  return (
    <>
      <div class="workspace-mode workspace-authors" role="group" aria-label="Album authors">
        <For each={albumAuthors}>
          {(author) => {
            const final = () => props.authors.length === 1 && props.authors.includes(author);
            const unavailable = () => reason() ?? (final() ? "Select at least one author." : undefined);
            return (
              <button
                type="button"
                aria-label={author}
                aria-pressed={props.authors.includes(author)}
                disabled={unavailable() !== undefined}
                aria-describedby={unavailable() === undefined ? undefined : `${feedback}-${author}`}
                onClick={() => {
                  const selected = albumAuthors.filter((candidate) =>
                    candidate === author ? !props.authors.includes(candidate) : props.authors.includes(candidate),
                  );
                  save.begin(selected);
                  void save.commit();
                }}
              >
                {author}
                <Show when={unavailable()}>
                  {(message) => (
                    <span class="visually-hidden" id={`${feedback}-${author}`}>
                      {message()}
                    </span>
                  )}
                </Show>
              </button>
            );
          }}
        </For>
      </div>
      <Show when={save.error()}>
        {(message) => (
          <div role="alert" id={feedback}>
            {message()}{" "}
            <button
              type="button"
              disabled={save.pending()}
              aria-describedby={feedback}
              onClick={() => {
                void save.commit();
              }}
            >
              {save.recovery() === undefined ? "Retry authors" : "Read saved authors"}
            </button>
          </div>
        )}
      </Show>
    </>
  );
}
