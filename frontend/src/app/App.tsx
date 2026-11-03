import { Show, createEffect, createMemo, createSignal, onCleanup, onMount } from "solid-js";
import type { AlbumClient } from "../features/albums/model/Album";
import type { AlbumPhotoEvents } from "../features/albums/model/Photo";
import { AlbumDashboard } from "../features/albums/dashboard/AlbumDashboard";
import { AlbumOpen } from "../features/albums/open/AlbumOpen";
import "./app.css";
import type { WorkspaceEditingSession } from "../features/editing/EditorSession";

export function App(props: { client: AlbumClient; events: AlbumPhotoEvents; session: WorkspaceEditingSession }) {
  const [path, setPath] = createSignal(window.location.pathname);
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
    window.history.pushState(null, "", destination);
    setPath(destination.pathname);
  }
  const syncHistory = () => {
    queueMicrotask(() => {
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
  return (
    <>
      <header class="topbar">
        <a class="brand" href="/">
          Album Layout Studio
        </a>
      </header>
      <main class="page">
        <Show when={id()} fallback={<AlbumDashboard client={props.client} />}>
          {(albumId) => <AlbumOpen client={props.client} events={props.events} id={albumId()} session={props.session} />}
        </Show>
      </main>
    </>
  );
}
