import { Show, createEffect } from "solid-js";
import type { AlbumClient } from "../features/albums/model/Album";
import type { AlbumPhotoEvents } from "../features/albums/model/Photo";
import { AlbumDashboard } from "../features/albums/dashboard/AlbumDashboard";
import { AlbumOpen } from "../features/albums/open/AlbumOpen";
import "./app.css";
import type { WorkspaceEditingSession } from "../features/editing/EditorSession";

export function App(props: { client: AlbumClient; events: AlbumPhotoEvents; session: WorkspaceEditingSession }) {
  const id = /^\/albums\/([^/]+)\/?$/.exec(window.location.pathname)?.[1];
  createEffect(() => {
    if (id === undefined && props.session.available()) void props.session.select(null);
  });
  return (
    <>
      <header class="topbar">
        <a class="brand" href="/">
          Album Layout Studio
        </a>
      </header>
      <main class="page">
        <Show when={id} fallback={<AlbumDashboard client={props.client} />}>
          {(albumId) => <AlbumOpen client={props.client} events={props.events} id={albumId()} session={props.session} />}
        </Show>
      </main>
    </>
  );
}
