import { render } from "solid-js/web";
import { ErrorBoundary, Show, createResource } from "solid-js";
import { App } from "./app/App";
import { makeHttpAlbumClient } from "./features/albums/api/HttpAlbumClient";
import { makeAlbumPhotoEvents } from "./features/albums/api/AlbumPhotoEvents";
import { createEditorSession } from "./features/editing/EditorSession";
import { makeHttpEditorSession } from "./features/editing/HttpEditorSession";
import { EditorSessionGate } from "./features/editing/EditorSessionGate";
import { makeEditorRequests } from "./features/editing/EditorRequests";
import { createBrowserIdentity, readBrowserIdentity } from "./features/editing/BrowserIdentity";

const target = document.getElementById("app");
if (target === null) {
  throw new Error("The frontend entry point is missing #app.");
}

const events = makeAlbumPhotoEvents((url) => new EventSource(url));
render(() => {
  const [identity, { refetch }] = createResource(() => readBrowserIdentity(indexedDB, createBrowserIdentity));
  return (
    <ErrorBoundary
      fallback={(failure: unknown, reset) => (
        <section class="session-feedback">
          <p role="alert">{failure instanceof Error ? failure.message : "Browser identification storage is unavailable."}</p>
          <button
            type="button"
            onClick={() => {
              void refetch();
              reset();
            }}
          >
            Retry browser identification
          </button>
        </section>
      )}
    >
      <Show when={identity()} fallback={<p role="status">Opening browser identification…</p>}>
        {(editorId) => {
          const reloading = performance.getEntriesByType("navigation").some((entry) => "type" in entry && entry.type === "reload");
          const session = createEditorSession(makeHttpEditorSession(fetch), localStorage, createBrowserIdentity, editorId(), reloading);
          const client = makeHttpAlbumClient(makeEditorRequests(fetch, session));
          return (
            <EditorSessionGate session={session}>
              <App client={client} events={events} session={session} />
            </EditorSessionGate>
          );
        }}
      </Show>
    </ErrorBoundary>
  );
}, target);
