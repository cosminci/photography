import { Show, createResource, createSignal } from "solid-js";
import type { AlbumClient, AlbumSummary } from "../model/Album";
import { SavedPhoto } from "../workspace/SavedPhoto";
import { coverPhotoWidth, spreadHeight } from "../workspace/Canvas";

export function AlbumCoverPreview(props: { album: AlbumSummary; client: Pick<AlbumClient, "readPlacements" | "photo"> }) {
  const [mediaFailed, setMediaFailed] = createSignal(false);
  const [retry, setRetry] = createSignal(0);
  const [preview, { refetch }] = createResource(
    () => props.album.id,
    async (album) => {
      setMediaFailed(false);
      setRetry(0);
      const cover = await props.client.readPlacements(album, { kind: "cover" });
      const placement = cover.placements.find((row) => row.slotId === cover.frontSlotId);
      if (placement === undefined) return { kind: "empty" as const };
      const photo = await props.client.photo(placement.photoId);
      if (photo.id !== placement.photoId) throw new Error("Unexpected cover photograph identity");
      return { kind: "photo" as const, photo, transform: placement.transform };
    },
  );
  const occupied = () => {
    if (preview.error !== undefined || preview.loading) return undefined;
    const saved = preview();
    return saved?.kind === "photo" ? saved : undefined;
  };
  return (
    <>
      <a class="album-card-open" href={`/albums/${encodeURIComponent(props.album.id)}`} aria-label={props.album.title}>
        <div class="cover-preview">
          <Show when={preview.loading}>
            <p class="preview-feedback" role="status">
              Loading cover preview…
            </p>
          </Show>
          <Show when={occupied()}>
            {(photo) => (
              <SavedPhoto
                photo={photo().photo}
                transform={photo().transform}
                width={coverPhotoWidth}
                height={spreadHeight}
                retry={retry()}
                onError={() => setMediaFailed(true)}
              />
            )}
          </Show>
          <Show when={!preview.loading && preview.error === undefined && preview()?.kind === "empty"}>
            <p class="preview-feedback">No front-cover photograph</p>
          </Show>
          <span class="cover-spine" />
        </div>
        <h2>{props.album.title}</h2>
      </a>
      <Show when={preview.error !== undefined}>
        <div role="alert" class="preview-feedback">
          Cover preview could not be loaded.
          <button
            type="button"
            onClick={() => {
              void refetch();
            }}
          >
            Retry cover preview
          </button>
        </div>
      </Show>
      <Show when={mediaFailed()}>
        <div role="alert" class="preview-feedback">
          Cover photograph could not be loaded.
          <button
            type="button"
            onClick={() => {
              setMediaFailed(false);
              setRetry((value) => value + 1);
            }}
          >
            Retry cover photograph
          </button>
        </div>
      </Show>
    </>
  );
}
