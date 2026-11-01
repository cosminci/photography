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
  let group!: HTMLDivElement;
  let opener!: HTMLButtonElement;
  function restoreFocus() {
    queueMicrotask(() => {
      if (document.activeElement === document.body || group.contains(document.activeElement)) {
        if (opener.disabled) group.focus();
        else opener.focus();
      }
    });
  }
  const save = createContentSave({
    save: (authors: readonly Author[]) => props.save(authors),
    read: () => props.read(),
    equal: (left, right) => left.length === right.length && left.every((author) => right.includes(author)),
    onSaved: restoreFocus,
  });
  const reason = createMemo(() => save.unavailable() ?? props.unavailable);
  const selection = createMemo(() => save.draft()?.value ?? props.authors);
  const finalAuthor = createMemo(() => (selection().length === 1 ? selection()[0] : undefined));
  const finalReasonId = createMemo(() => {
    const author = finalAuthor();
    return author === undefined ? undefined : `${feedback}-${author}`;
  });
  createEffect(() => {
    if (save.error() !== undefined && !save.pending() && (document.activeElement === document.body || group.contains(document.activeElement)))
      group.focus();
  });
  createEffect(() => {
    props.onBlock(save.draft() === undefined ? undefined : (save.unavailable() ?? "Resolve the author save before leaving."));
  });
  onCleanup(() => {
    props.onBlock(undefined);
  });
  return (
    <div
      ref={(element) => {
        group = element;
      }}
      class="album-author-selector"
      role="group"
      aria-label="Album authors"
      aria-busy={save.pending()}
      tabindex="-1"
      aria-describedby={save.error() !== undefined ? feedback : finalReasonId()}
    >
      <div class="workspace-mode workspace-authors">
        <For each={albumAuthors}>
          {(author) => {
            const final = () => finalAuthor() === author;
            const unavailable = () => reason() ?? (final() ? "Choose at least one author." : undefined);
            return (
              <button
                type="button"
                aria-label={author}
                aria-pressed={selection().includes(author)}
                disabled={unavailable() !== undefined}
                aria-describedby={unavailable() === undefined ? undefined : `${feedback}-${author}`}
                onClick={(event) => {
                  opener = event.currentTarget;
                  const selected = albumAuthors.filter((candidate) =>
                    candidate === author ? !selection().includes(candidate) : selection().includes(candidate),
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
          <div role="alert" class="author-inline-feedback" id={feedback}>
            {message()}{" "}
            <button
              type="button"
              class="gear-manage-link"
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
    </div>
  );
}
