import { Show, createSignal, type JSX } from "solid-js";
import type { EditorSession } from "./EditorSession";

export function EditorSessionGate(props: {
  session: Pick<EditorSession, "opened" | "blocked" | "message" | "identifying" | "connecting" | "identify" | "reconnect">;
  children: JSX.Element;
}) {
  const [alias, setAlias] = createSignal("");
  return (
    <>
      <Show when={props.session.message()}>
        {(message) => (
          <section class="session-feedback" aria-label="Browser session">
            <p role="status" id="editor-session-message">
              {message()}
            </p>
            <Show when={props.session.identifying()}>
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
                  onInput={(event) => setAlias(event.currentTarget.value)}
                />
                <p id="editor-alias-help">Remembered in this browser profile and shown to other editors. It is not a login.</p>
                <button type="submit">Open application</button>
              </form>
            </Show>
            <Show when={!props.session.identifying()}>
              <button
                type="button"
                aria-describedby={props.session.connecting() ? "editor-session-message" : undefined}
                onClick={() => {
                  void props.session.reconnect();
                }}
              >
                Reconnect browser
              </button>
            </Show>
          </section>
        )}
      </Show>
      <Show when={props.session.opened()}>
        <div class="session-application" hidden={props.session.blocked()} inert={props.session.blocked()}>
          {props.children}
        </div>
      </Show>
    </>
  );
}
