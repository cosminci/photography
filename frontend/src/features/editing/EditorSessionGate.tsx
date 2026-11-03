import { Show, createSignal, createUniqueId, onCleanup, onMount, type JSX } from "solid-js";
import type { EditorSession } from "./EditorSession";

function SessionDialog(props: { title: string; children: JSX.Element }) {
  const heading = createUniqueId();
  let dialog: HTMLDialogElement;
  onMount(() => {
    dialog.showModal();
  });
  onCleanup(() => {
    dialog.close();
  });
  return (
    <dialog
      ref={(element) => {
        dialog = element;
      }}
      class="album-dialog session-dialog"
      aria-labelledby={heading}
      onCancel={(event) => {
        event.preventDefault();
      }}
    >
      <header class="dialog-heading">
        <h2 id={heading}>{props.title}</h2>
      </header>
      {props.children}
    </dialog>
  );
}

export function EditorSessionGate(props: {
  session: Pick<EditorSession, "opened" | "blocked" | "available" | "message" | "identifying" | "connecting" | "identify" | "reconnect">;
  children: JSX.Element;
}) {
  const [alias, setAlias] = createSignal("");
  return (
    <>
      <Show
        when={props.session.opened()}
        fallback={
          <header class="topbar">
            <span class="brand">Album Layout Studio</span>
          </header>
        }
      >
        <div class="session-application" hidden={props.session.blocked()} inert={props.session.blocked() || !props.session.available()}>
          {props.children}
        </div>
      </Show>
      <Show when={props.session.identifying()}>
        <SessionDialog title="Your browser name">
          <form
            aria-label="Browser identification"
            onSubmit={(event) => {
              event.preventDefault();
              void props.session.identify(alias());
            }}
          >
            <label for="editor-alias">Identification alias</label>
            <input
              id="editor-alias"
              name="alias"
              autocomplete="nickname"
              required
              value={alias()}
              aria-describedby="editor-alias-help"
              onInput={(event) => {
                setAlias(event.currentTarget.value);
              }}
            />
            <p id="editor-alias-help">Shown to others while you edit. Remembered on this browser.</p>
            <Show when={props.session.message()}>{(message) => <p role="status">{message()}</p>}</Show>
            <div class="dialog-actions">
              <button type="submit">Open application</button>
            </div>
          </form>
        </SessionDialog>
      </Show>
      <Show when={!props.session.identifying() && !props.session.available() && props.session.message()}>
        <SessionDialog
          title={props.session.blocked() ? "Already open in another tab" : props.session.connecting() ? "Connecting…" : "Connection lost"}
        >
          <p role="status">
            {props.session.blocked()
              ? "Return to the other tab, or close it to continue here."
              : "Your work is kept safe. The workspace will be available when the connection is restored."}
          </p>
          <details>
            <summary>Connection details</summary>
            <p>{props.session.message()}</p>
          </details>
          <div class="dialog-actions">
            <button
              type="button"
              aria-label="Reconnect browser"
              onClick={() => {
                void props.session.reconnect();
              }}
            >
              {props.session.connecting() ? "Retry connection" : "Reconnect"}
            </button>
          </div>
        </SessionDialog>
      </Show>
    </>
  );
}
