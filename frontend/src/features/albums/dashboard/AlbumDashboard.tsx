import { For, Index, Show, createMemo, createResource, createSignal } from "solid-js";
import type { AlbumClient, AlbumPage } from "../model/Album";
import { getPaginationFeedback } from "../model/Presentation";
import { AlbumDialog, type AlbumAction } from "../dialog/AlbumDialog";
import { AlbumCard } from "./AlbumCard";

export function AlbumDashboard(props: { client: AlbumClient }) {
  const [request, setRequest] = createSignal<{ page: number; direction: "forward" | "backward" | undefined }>({ page: 1, direction: undefined });
  const [albums, { refetch }] = createResource(
    request,
    async (selection, { value }): Promise<AlbumPage & { direction: "forward" | "backward" | undefined }> => {
      const result = await props.client.list(selection.page);
      return { ...result, direction: result.page === value?.page ? undefined : selection.direction };
    },
  );
  const [action, setAction] = createSignal<AlbumAction>();
  let addButton: HTMLButtonElement;

  async function handleSavedAlbum() {
    const created = action()?.kind === "create";
    if (created) setRequest({ page: 1, direction: undefined });
    else await refetch();
  }

  function changePage(page: number, direction: "forward" | "backward") {
    setRequest({ page, direction });
  }

  return (
    <>
      <div class="page-heading">
        <h1 class="dashboard-subtitle">Album dashboard</h1>
        <button
          ref={(element) => {
            addButton = element;
          }}
          type="button"
          onClick={() => {
            setAction({ kind: "create" });
          }}
        >
          <span aria-hidden="true">+ </span>Add album
        </button>
      </div>
      <Show when={albums.error !== undefined}>
        <div role="alert" class="state error-state">
          <h2>Albums could not be loaded</h2>
          <button
            type="button"
            onClick={() => {
              void refetch();
            }}
          >
            Retry albums
          </button>
        </div>
      </Show>
      <Show when={albums.error === undefined}>
        <Show when={albums.loading && albums.latest === undefined}>
          <div role="status" aria-label="Loading albums" class="album-grid">
            <Index each={Array.from({ length: 6 })}>
              {() => (
                <div class="album-card" aria-hidden="true">
                  <div class="album-cover-skeleton" />
                  <div class="album-title-skeleton">
                    <span />
                  </div>
                </div>
              )}
            </Index>
          </div>
        </Show>
        <Show when={albums.latest}>
          {(page) => {
            const controls = createMemo(() => getPaginationFeedback(page(), albums.loading));
            return (
              <Show when={page().albums.length > 0}>
                <div class="album-grid" data-direction={page().direction} aria-busy={albums.loading}>
                  <For each={page().albums.map((album) => album.id)}>
                    {(id) => (
                      <Show when={page().albums.find((album) => album.id === id)}>
                        {(album) => (
                          <AlbumCard
                            album={album()}
                            onRename={() => {
                              setAction({ kind: "rename", album: album() });
                            }}
                            onDelete={() => {
                              setAction({ kind: "delete", album: album() });
                            }}
                          />
                        )}
                      </Show>
                    )}
                  </For>
                </div>
                <nav class="pagination" aria-label="Album pages">
                  <div>
                    <button
                      type="button"
                      class="secondary"
                      disabled={controls().previous !== undefined}
                      title={controls().previous}
                      aria-describedby="previous-page-help"
                      onClick={() => {
                        changePage(page().page - 1, "backward");
                      }}
                    >
                      Previous
                    </button>
                    <small id="previous-page-help" class="control-help">
                      {controls().previous}
                    </small>
                  </div>
                  <span>Page {page().page}</span>
                  <div>
                    <button
                      type="button"
                      class="secondary"
                      disabled={controls().next !== undefined}
                      title={controls().next}
                      aria-describedby="next-page-help"
                      onClick={() => {
                        changePage(page().page + 1, "forward");
                      }}
                    >
                      Next
                    </button>
                    <small id="next-page-help" class="control-help">
                      {controls().next}
                    </small>
                  </div>
                </nav>
              </Show>
            );
          }}
        </Show>
      </Show>
      <Show when={action()} keyed>
        {(target) => (
          <AlbumDialog
            client={props.client}
            action={target}
            onClose={() => {
              setAction(undefined);
            }}
            onSaved={handleSavedAlbum}
            returnFocus={() => {
              const trigger = target.kind === "create" ? addButton : document.getElementById(`${target.kind}-${target.album.id}`);
              if (trigger instanceof HTMLElement) trigger.focus();
              else addButton.focus();
            }}
          />
        )}
      </Show>
    </>
  );
}
