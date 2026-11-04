import { Show, createMemo, createResource, createSignal, createUniqueId, untrack } from "solid-js";
import type { AlbumClient, AlbumSummary } from "../model/Album";
import type { Photo } from "../model/Photo";
import type { CoverPhotoFrame, PhotoTransform } from "../model/Placement";
import { SavedPhoto } from "../workspace/SavedPhoto";
import { CoverSurface } from "../workspace/CoverSurface";

export function AlbumCoverPreview(props: { album: AlbumSummary; client: Pick<AlbumClient, "readPlacements" | "photo"> }) {
  const client = untrack(() => props.client);
  const pageCountId = createUniqueId();
  const [preview, { refetch }] = createResource(
    () => props.album.id,
    async (album) => {
      const cover = await client.readPlacements(album, { kind: "cover" });
      const photos = new Map<string, Promise<Photo>>();
      async function readFace(side: "back" | "front"): Promise<CoverFace> {
        const slotId = side === "front" ? cover.frontSlotId : cover.backSlotId;
        const placement = cover.placements.find((row) => row.slotId === slotId);
        if (placement === undefined) return { side, kind: "empty" };
        let reading = photos.get(placement.photoId);
        if (reading === undefined) {
          reading = client.photo(placement.photoId);
          photos.set(placement.photoId, reading);
        }
        const photo = await reading;
        if (photo.id !== placement.photoId) throw new Error("Unexpected cover photograph identity");
        return { side, kind: "photo", photo, transform: placement.transform };
      }
      const [back, front] = await Promise.all([readFace("back"), readFace("front")]);
      return { geometry: cover.geometry, spineOwner: cover.spineOwner, pageCount: cover.pageCount, back, front };
    },
  );
  const savedPreview = createMemo(() => (preview.error === undefined ? preview() : undefined));
  return (
    <>
      <div class="album-cover-pair">
        <Show when={preview.loading}>
          <p class="preview-feedback" role="status">
            Loading cover preview…
          </p>
        </Show>
        <Show when={!preview.loading}>
          <Show when={savedPreview()}>
            {(saved) => (
              <CoverSurface
                geometry={saved().geometry}
                spineOwner={saved().spineOwner}
                back={<CoverFacePreview face={saved().back} frame={saved().geometry.back} album={props.album} />}
                front={<CoverFacePreview face={saved().front} frame={saved().geometry.front} album={props.album} />}
              />
            )}
          </Show>
        </Show>
      </div>
      <a
        class="album-card-open"
        href={`/albums/${encodeURIComponent(props.album.id)}`}
        aria-label={props.album.title}
        aria-describedby={savedPreview() === undefined ? undefined : pageCountId}
      >
        <div class="album-card-caption">
          <h2>{props.album.title}</h2>
          <Show when={savedPreview()}>{(saved) => <small id={pageCountId}>({saved().pageCount} pages)</small>}</Show>
        </div>
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
    </>
  );
}

type CoverFace = { readonly side: "front" | "back" } & (
  { readonly kind: "empty" } | { readonly kind: "photo"; readonly photo: Photo; readonly transform: PhotoTransform }
);

function CoverFacePreview(props: { face: CoverFace; frame: CoverPhotoFrame; album: AlbumSummary }) {
  const [mediaFailed, setMediaFailed] = createSignal(false);
  const [retry, setRetry] = createSignal(0);
  const occupied = createMemo(() => (props.face.kind === "photo" ? props.face : undefined));
  const label = () => (props.face.side === "front" ? "Front cover" : "Back cover");
  return (
    <div class="cover-face" role="group" aria-label={label()}>
      <a class="album-card-open cover-preview" href={`/albums/${encodeURIComponent(props.album.id)}`} aria-label={`${props.album.title}: ${label()}`}>
        <Show when={occupied()} fallback={<p class="preview-feedback">No {props.face.side}-cover photograph</p>}>
          {(saved) => (
            <SavedPhoto
              photo={saved().photo}
              transform={saved().transform}
              width={props.frame.width}
              height={props.frame.height}
              retry={retry()}
              onError={() => setMediaFailed(true)}
            />
          )}
        </Show>
      </a>
      <Show when={mediaFailed()}>
        <div role="alert" class="preview-feedback">
          {label()} photograph could not be loaded.
          <button
            type="button"
            onClick={() => {
              setMediaFailed(false);
              setRetry((value) => value + 1);
            }}
          >
            Retry {props.face.side}-cover photograph
          </button>
        </div>
      </Show>
    </div>
  );
}
