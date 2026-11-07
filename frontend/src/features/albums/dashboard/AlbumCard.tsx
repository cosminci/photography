import type { AlbumClient, AlbumSummary } from "../model/Album";
import { AlbumCoverPreview } from "./AlbumCoverPreview";

export function AlbumCard(props: { album: AlbumSummary; client: Pick<AlbumClient, "readPlacements" | "photo"> }) {
  return (
    <article class="album-card">
      <AlbumCoverPreview album={props.album} client={props.client} />
    </article>
  );
}
