import type { AlbumClient, AlbumSummary } from "../model/Album";
import { AlbumCoverPreview } from "./AlbumCoverPreview";

export function AlbumCard(props: {
  album: AlbumSummary;
  client: Pick<AlbumClient, "readPlacements" | "photo">;
  onRename: () => void;
  onDelete: () => void;
}) {
  return (
    <article class="album-card">
      <AlbumCoverPreview album={props.album} client={props.client} />
      <div class="album-card-actions">
        <button
          id={`rename-${props.album.id}`}
          type="button"
          class="icon-button"
          aria-label={`Rename ${props.album.title}`}
          onClick={() => {
            props.onRename();
          }}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="m15 4 5 5M4 20l5-1L20 8a2 2 0 0 0-5-5L4 14z" />
          </svg>
        </button>
        <button
          id={`delete-${props.album.id}`}
          type="button"
          class="icon-button delete-action"
          aria-label={`Delete ${props.album.title}`}
          onClick={() => {
            props.onDelete();
          }}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7" />
          </svg>
        </button>
      </div>
    </article>
  );
}
