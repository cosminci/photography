import type { AlbumClient, AlbumSummary } from "../model/Album";
import { AlbumCoverPreview } from "./AlbumCoverPreview";

export function AlbumCard(props: { album: AlbumSummary; client: Pick<AlbumClient, "readPlacements" | "photo">; onDelete: () => void }) {
  return (
    <article class="album-card">
      <AlbumCoverPreview album={props.album} client={props.client} />
      <div class="album-card-actions">
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
