import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import type { ComponentProps } from "solid-js";
import { AlbumCard } from "../../../src/features/albums/dashboard/AlbumCard";
import { AlbumCoverPreview } from "../../../src/features/albums/dashboard/AlbumCoverPreview";
import { buildAlbumClient } from "./AlbumClientStub";

vi.mock("../../../src/features/albums/dashboard/AlbumCoverPreview", () => ({
  AlbumCoverPreview: vi.fn((props: ComponentProps<typeof AlbumCoverPreview>) => <h2>{props.album.title}</h2>),
}));

describe("AlbumCard", () => {
  it("should forward its saved album to the preview and request deletion only when selected", () => {
    const album = { id: "album", year: 2026, title: "Journey", createdAt: "2026-10-24T12:00:00Z" };
    const client = buildAlbumClient();
    const remove = vi.fn();
    render(() => <AlbumCard album={album} client={client} onDelete={remove} />);
    expect(remove).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Delete Journey" }));

    expect(remove).toHaveBeenCalledOnce();
    expect(vi.mocked(AlbumCoverPreview).mock.lastCall?.[0]).toEqual({ album, client });
  });
});
