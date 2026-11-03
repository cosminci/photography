import { Show, createSignal, createUniqueId, onMount } from "solid-js";
import type { WorkspaceEditingSession } from "./EditorSession";

export function BrowserAlias(props: { session: Pick<WorkspaceEditingSession, "alias" | "rename"> }) {
  const [editing, setEditing] = createSignal(false);
  const [draft, setDraft] = createSignal("");
  const [pending, setPending] = createSignal(false);
  const [maybeError, setError] = createSignal<string>();
  const [explaining, setExplaining] = createSignal(false);
  const explanationId = createUniqueId();
  let opener: HTMLButtonElement;
  function close() {
    setEditing(false);
    opener.focus();
  }
  async function save(event: SubmitEvent) {
    event.preventDefault();
    if (draft().trim() === "") {
      setError("Enter a name.");
      return;
    }
    setPending(true);
    setError(undefined);
    try {
      await props.session.rename(draft().trim());
      close();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Could not save your name.");
    } finally {
      setPending(false);
    }
  }
  function NameInput() {
    let input: HTMLInputElement;
    onMount(() => {
      input.focus();
      input.select();
    });
    return (
      <input
        ref={(element) => {
          input = element;
        }}
        aria-label="Your name"
        value={draft()}
        disabled={pending()}
        aria-describedby={pending() ? "browser-name-pending" : maybeError() === undefined ? undefined : "browser-name-error"}
        onInput={(event) => {
          setDraft(event.currentTarget.value);
        }}
      />
    );
  }
  return (
    <div class="browser-alias">
      <Show when={editing()} fallback={<span title={`Browser alias: ${props.session.alias()}`}>{props.session.alias()}</span>}>
        <form
          aria-label="Your name"
          aria-busy={pending()}
          onSubmit={(event) => {
            void save(event);
          }}
          onKeyDown={(event) => {
            if (event.key === "Escape" && !pending()) {
              event.preventDefault();
              close();
            }
          }}
        >
          <NameInput />
          <Show when={pending()}>
            <span id="browser-name-pending" role="status" class="visually-hidden">
              Saving your name.
            </span>
          </Show>
          <button
            type="submit"
            class="alias-action"
            aria-label="Save name"
            title="Save name"
            disabled={pending()}
            aria-describedby={pending() ? "browser-name-pending" : undefined}
          >
            ✓
          </button>
          <button
            type="button"
            class="alias-action"
            aria-label="Cancel name change"
            title="Cancel"
            disabled={pending()}
            aria-describedby={pending() ? "browser-name-pending" : undefined}
            onClick={close}
          >
            ×
          </button>
          <Show when={maybeError()}>
            {(error) => (
              <p id="browser-name-error" role="alert">
                {error()}
              </p>
            )}
          </Show>
        </form>
      </Show>
      <button
        ref={(element) => {
          opener = element;
        }}
        type="button"
        class="alias-action"
        aria-label="Edit your name"
        title="Edit your name"
        hidden={editing()}
        onClick={() => {
          setDraft(props.session.alias());
          setError(undefined);
          setEditing(true);
        }}
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="m15 4 5 5M4 20l5-1L21 7a2 2 0 0 0-5-5L4 14Z" />
        </svg>
      </button>
      <button
        type="button"
        class="alias-action"
        aria-label="About browser alias"
        title="Browser alias for concurrent editing"
        aria-expanded={explaining()}
        aria-controls={explanationId}
        aria-describedby={explaining() ? explanationId : undefined}
        onClick={() => setExplaining(!explaining())}
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            setExplaining(false);
            event.currentTarget.focus();
          }
        }}
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="12" cy="12" r="9" />
          <path d="M12 11v6M12 7v1" />
        </svg>
      </button>
      <Show when={explaining()}>
        <p id={explanationId} class="browser-alias-help">
          Browser alias for concurrent editing. Others see this name while you edit a spread. This is not a login.
        </p>
      </Show>
    </div>
  );
}
