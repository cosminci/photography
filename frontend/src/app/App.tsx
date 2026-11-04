import { Show, createEffect, createMemo, createSignal, onCleanup, onMount } from "solid-js";
import type { AlbumClient } from "../features/albums/model/Album";
import type { AlbumPhotoEvents } from "../features/albums/model/Photo";
import { AlbumDashboard } from "../features/albums/dashboard/AlbumDashboard";
import { AlbumOpen } from "../features/albums/open/AlbumOpen";
import "./app.css";
import type { WorkspaceEditingSession } from "../features/editing/EditorSession";

export function App(props: { client: AlbumClient; events: AlbumPhotoEvents; session: WorkspaceEditingSession; historyLimit?: number }) {
  const [path, setPath] = createSignal(window.location.pathname);
  const [opening, setOpening] = createSignal(false);
  let page: HTMLElement;
  const id = createMemo(() => /^\/albums\/([^/]+)\/?$/.exec(path())?.[1]);
  createEffect(() => {
    if (id() === undefined && props.session.available()) void props.session.select(null);
  });
  function navigate(event: MouseEvent) {
    if (id() !== undefined || event.defaultPrevented || event.button !== 0 || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey)
      return;
    const link = event.target instanceof Element ? event.target.closest<HTMLAnchorElement>("a[href]") : null;
    if (link?.target !== "" || link.hasAttribute("download")) return;
    const destination = new URL(link.href);
    if (destination.origin !== window.location.origin || !/^\/(?:albums\/[^/]+\/?)?$/.test(destination.pathname)) return;
    event.preventDefault();
    setOpening(destination.pathname !== "/");
    window.history.pushState(null, "", destination);
    setPath(destination.pathname);
  }
  const syncHistory = () => {
    queueMicrotask(() => {
      setOpening(false);
      setPath(window.location.pathname);
    });
  };
  onMount(() => {
    document.addEventListener("click", navigate);
    window.addEventListener("popstate", syncHistory);
  });
  onCleanup(() => {
    document.removeEventListener("click", navigate);
    window.removeEventListener("popstate", syncHistory);
  });
  function finishOpening() {
    if (!opening()) return;
    setOpening(false);
    queueMicrotask(() => {
      page.focus();
    });
  }
  return (
    <>
      <header class="topbar">
        <a class="brand" href="/">
          Album Layout Studio
        </a>
      </header>
      <main
        class="page"
        tabIndex={-1}
        ref={(element) => {
          page = element;
        }}
      >
        <Show when={id() === undefined || opening()}>
          <div class="catalog-content" inert={opening()} aria-busy={opening()}>
            <AlbumDashboard client={props.client} />
          </div>
        </Show>
        <Show when={opening()}>
          <p role="status" aria-label="Opening album" class="catalog-opening">
            Opening album…
          </p>
        </Show>
        <Show when={id()}>
          {(albumId) => (
            <div class="album-content" hidden={opening()}>
              <AlbumOpen
                client={props.client}
                events={props.events}
                id={albumId()}
                session={props.session}
                historyLimit={props.historyLimit}
                onReady={finishOpening}
              />
            </div>
          )}
        </Show>
      </main>
    </>
  );
}
