import { Show, createMemo, untrack } from "solid-js";
import type { AlbumClient } from "../model/Album";
import type { AlbumPhotoEvents } from "../model/Photo";
import { isMissingAlbum } from "../model/Presentation";
import { AlbumWorkspace } from "../workspace/AlbumWorkspace";
import { createLiveWorkspace } from "./LiveWorkspace";

export function AlbumOpen(props: {
  client: Pick<
    AlbumClient,
    | "open"
    | "chapters"
    | "composition"
    | "spread"
    | "countries"
    | "photoSource"
    | "createChapter"
    | "updateChapter"
    | "chapterPhotos"
    | "refreshPhotos"
    | "templates"
    | "addSpread"
    | "replaceChapterComposition"
    | "changeSpreadTemplate"
    | "deleteSpread"
    | "readPlacements"
    | "editPlacements"
  >;
  events: AlbumPhotoEvents;
  id: string;
}) {
  const client = untrack(() => props.client);
  const events = untrack(() => props.events);
  const live = createLiveWorkspace(() => props.id, client, events);
  const missing = createMemo(() => isMissingAlbum(live.error()));
  return (
    <>
      <Show when={live.workspace() === undefined}>
        <a class="back-link" href="/">
          Back to album catalog
        </a>
      </Show>
      <Show when={live.loading() && live.workspace() === undefined && live.liveStatus() !== "invalid"}>
        <div role="status" aria-label="Loading album" class="loading-placeholder" />
      </Show>
      <Show when={live.workspace() === undefined && live.liveStatus() === "invalid" && live.error() === undefined}>
        <p role="alert">
          Live photo update could not be decoded. <a href={window.location.href}>Reopen album</a> to reconnect.
        </p>
      </Show>
      <Show when={live.workspace() === undefined && live.liveStatus() === "interrupted" && live.error() === undefined}>
        <p role="alert">Live photo updates interrupted. Reconnecting…</p>
      </Show>
      <Show when={live.error() !== undefined && live.workspace() === undefined}>
        <div role="alert">
          <Show
            when={missing()}
            fallback={
              <>
                The album could not be loaded.{" "}
                <button
                  type="button"
                  onClick={() => {
                    void live.reload();
                  }}
                >
                  Retry album
                </button>
              </>
            }
          >
            This album could not be found.
          </Show>
        </div>
      </Show>
      <Show when={live.workspace()}>
        {(workspace) => (
          <Show when={live.album()}>
            {(album) => (
              <AlbumWorkspace
                album={album()}
                workspace={workspace()}
                client={props.client}
                onRefresh={live.reload}
                onSaved={live.saved}
                workspaceError={live.error()}
                photoErrors={live.inventoryErrors()}
                scheduling={live.scheduling()}
                liveStatus={live.liveStatus()}
                onPhotoRefresh={live.refresh}
                onInventoryRetry={live.retryChapter}
              />
            )}
          </Show>
        )}
      </Show>
    </>
  );
}
