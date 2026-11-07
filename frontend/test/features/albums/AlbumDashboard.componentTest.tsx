import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import type { ComponentProps } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { AlbumDashboard } from "../../../src/features/albums/dashboard/AlbumDashboard";
import { AlbumCard } from "../../../src/features/albums/dashboard/AlbumCard";
import { AlbumDialog } from "../../../src/features/albums/dialog/AlbumDialog";
import type { AlbumPage } from "../../../src/features/albums/model/Album";
import { buildAlbumClient } from "./AlbumClientStub";

vi.mock("../../../src/features/albums/dashboard/AlbumCard", () => ({
  AlbumCard: vi.fn((props: ComponentProps<typeof AlbumCard>) => <a href={`/albums/${props.album.id}`}>{props.album.title}</a>),
}));
vi.mock("../../../src/features/albums/dialog/AlbumDialog", () => ({
  AlbumDialog: vi.fn((props: ComponentProps<typeof AlbumDialog>) => (
    <div role="dialog" aria-label="create">
      <button onClick={() => void props.onSaved()}>Saved</button>
      <button
        onClick={() => {
          props.onClose();
          props.returnFocus();
        }}
      >
        Close
      </button>
    </div>
  )),
}));

describe("AlbumDashboard", () => {
  const album = { id: "album", year: 2026, title: "Journey", createdAt: "2026-10-24T12:00:00Z" };
  const firstPage: AlbumPage = { albums: [album], page: 1, pageSize: 6, totalAlbums: 7, totalPages: 2 };
  const secondPage: AlbumPage = { ...firstPage, albums: [{ ...album, id: "last", title: "Last album" }], page: 2 };
  const empty: AlbumPage = { albums: [], page: 1, pageSize: 6, totalAlbums: 0, totalPages: 0 };

  it("should retain the current albums while paging and allow returning to the previous page", async () => {
    let finish!: (page: AlbumPage) => void;
    const pending = new Promise<AlbumPage>((resolve) => {
      finish = resolve;
    });
    const list = vi.fn().mockResolvedValueOnce(firstPage).mockReturnValueOnce(pending).mockResolvedValueOnce(firstPage);
    const client = buildAlbumClient({ list });
    render(() => <AlbumDashboard client={client} />);
    await screen.findByRole("link", { name: "Journey" });

    fireEvent.click(screen.getByRole("button", { name: "Next" }));

    expect(screen.getByRole("link", { name: "Journey" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    finish(secondPage);
    await screen.findByRole("link", { name: "Last album" });
    fireEvent.click(screen.getByRole("button", { name: "Previous" }));
    await screen.findByRole("link", { name: "Journey" });
    expect(list.mock.calls).toEqual([[1], [2], [1]]);
    expect(vi.mocked(AlbumCard).mock.lastCall?.[0].client).toBe(client);
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
  });

  it("should show loading and retry a failed catalog read without confusing it with an empty catalog", async () => {
    const list = vi.fn().mockRejectedValueOnce(new Error("Offline")).mockResolvedValueOnce(empty);
    render(() => <AlbumDashboard client={buildAlbumClient({ list })} />);
    expect(screen.getByRole("status", { name: "Loading albums" })).toBeVisible();
    await screen.findByRole("alert");

    fireEvent.click(screen.getByRole("button", { name: "Retry albums" }));

    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    expect(screen.getAllByRole("button")).toEqual([screen.getByRole("button", { name: "Add album" })]);
    expect(screen.queryByRole("navigation", { name: "Album pages" })).not.toBeInTheDocument();
    expect(list).toHaveBeenCalledTimes(2);
  });

  it.each([1, 2])("should launch creation from page %s, reload the first page and restore the Add action on close", async (startingPage) => {
    const list = vi.fn().mockResolvedValueOnce(firstPage);
    if (startingPage === 2) list.mockResolvedValueOnce(secondPage);
    list.mockResolvedValueOnce(firstPage);
    const client = buildAlbumClient({ list });
    render(() => <AlbumDashboard client={client} />);
    await screen.findByRole("link", { name: "Journey" });
    if (startingPage === 2) {
      fireEvent.click(screen.getByRole("button", { name: "Next" }));
      await screen.findByRole("link", { name: "Last album" });
    }

    fireEvent.click(screen.getByRole("button", { name: "Add album" }));
    fireEvent.click(screen.getByRole("button", { name: "Saved" }));

    await screen.findByRole("link", { name: "Journey" });
    expect(screen.getByRole("dialog", { name: "create" })).toBeVisible();
    expect(vi.mocked(AlbumDialog).mock.lastCall?.[0].client).toBe(client);
    expect(list.mock.calls).toEqual(startingPage === 2 ? [[1], [2], [1]] : [[1], [1]]);
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add album" })).toHaveFocus();
  });
});
