import { Show, createEffect, createMemo, createSignal, onCleanup, onMount } from "solid-js";
import { AlbumRequestError, type AlbumClient, type AlbumSummary } from "../model/Album";
import { describeFailure, validateYear } from "../model/Presentation";
import { createContentSave } from "../workspace/ContentSave";

export function AlbumDialog(props: {
  client: Pick<AlbumClient, "create" | "list">;
  onClose: () => void;
  onSaved: () => void | Promise<void>;
  returnFocus: () => void;
}) {
  const [year, setYear] = createSignal(String(new Date().getFullYear()));
  const [error, setError] = createSignal<string>();
  const [confirmed, setConfirmed] = createSignal(false);
  const [publishing, setPublishing] = createSignal(false);
  const [closing, setClosing] = createSignal(false);
  const save = createContentSave<number, AlbumSummary | undefined>({
    save: (requestedYear) => props.client.create(requestedYear),
    read: async (requestedYear) => {
      let totalPages = 1;
      for (let page = 1; page <= totalPages; page++) {
        const catalog = await props.client.list(page);
        const saved = catalog.albums.find((album) => album.year === requestedYear);
        if (saved !== undefined) return saved;
        totalPages = catalog.totalPages;
      }
      if (save.recovery() === "acknowledged") throw new AlbumRequestError(404, "The saved album could not be found.");
      return undefined;
    },
    equal: (saved, requestedYear) => saved?.year === requestedYear,
    onSaved: () => setConfirmed(true),
  });
  const unavailable = createMemo(
    () => save.unavailable() ?? (publishing() ? "Refreshing albums. Please wait." : closing() ? "Closing album dialog." : undefined),
  );
  let dialog: HTMLDialogElement;
  let input: HTMLInputElement;
  let feedback: HTMLDivElement;
  const [active, setActive] = createSignal(true);

  onMount(() => {
    dialog.showModal();
    input.focus();
  });
  createEffect(() => {
    if ((error() ?? save.error()) !== undefined && !save.pending() && !publishing()) feedback.focus();
  });
  onCleanup(() => {
    setActive(false);
    dialog.close();
    props.returnFocus();
  });

  async function close() {
    setClosing(true);
    await Promise.resolve();
    await Promise.allSettled(dialog.getAnimations().map((animation) => animation.finished));
    if (active()) props.onClose();
  }

  async function finish() {
    if (!active() || !confirmed()) return;
    setPublishing(true);
    setError(undefined);
    try {
      await props.onSaved();
      if (active()) await close();
    } catch (failure) {
      if (active()) {
        setClosing(false);
        setError(describeFailure(failure));
      }
    } finally {
      setPublishing(false);
    }
  }

  async function reconcile() {
    await save.reconcile();
    await finish();
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    /* v8 ignore next */
    if (unavailable() !== undefined) return;
    if (confirmed()) {
      await finish();
      return;
    }
    const validation = validateYear(year());
    if (validation !== undefined) {
      setError(validation);
      return;
    }
    const selectedYear = Number(year());
    setError(undefined);
    if (save.draft() === undefined) save.begin(selectedYear);
    else save.change(selectedYear);
    await save.commit();
    await finish();
  }

  return (
    <dialog
      ref={(element) => {
        dialog = element;
      }}
      aria-label="Create an album"
      role="dialog"
      class="album-dialog"
      classList={{ "is-closing": closing() }}
      onCancel={(event) => {
        event.preventDefault();
        if (unavailable() === undefined) void close();
      }}
    >
      <header class="dialog-heading">
        <h2>Create an album</h2>
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
      </header>
      <form
        aria-label="Album details"
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <label>
          Album year
          <input
            ref={(element) => {
              input = element;
            }}
            value={year()}
            inputmode="numeric"
            disabled={unavailable() !== undefined || confirmed()}
            onInput={(event) => {
              setYear(event.currentTarget.value);
              setError(undefined);
            }}
            aria-describedby="album-dialog-feedback"
          />
        </label>
        <div
          id="album-dialog-feedback"
          ref={(element) => {
            feedback = element;
          }}
          tabindex="-1"
        >
          <Show when={error() ?? save.error()}>{(message) => <p role="alert">{message()}</p>}</Show>
          <div class="dialog-status">
            <Show when={unavailable()}>{(reason) => <p role="status">{reason()}</p>}</Show>
            <Show when={confirmed() && unavailable() === undefined}>
              <p role="status">Album saved. Refresh albums to continue.</p>
            </Show>
          </div>
          <Show when={save.recovery() !== undefined}>
            <button
              type="button"
              disabled={save.pending()}
              aria-describedby="album-dialog-feedback"
              onClick={() => {
                void reconcile();
              }}
            >
              Read saved album
            </button>
          </Show>
        </div>
        <div class="dialog-actions">
          <button
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
          <button type="submit" disabled={unavailable() !== undefined} aria-describedby="album-dialog-feedback">
            {confirmed() ? "Refresh albums" : "Create"}
          </button>
        </div>
      </form>
    </dialog>
  );
}
