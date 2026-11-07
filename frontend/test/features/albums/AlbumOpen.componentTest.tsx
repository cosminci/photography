import { fireEvent, render, screen } from "@solidjs/testing-library";
import { createSignal, type ComponentProps } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { AlbumOpen } from "../../../src/features/albums/open/AlbumOpen";
import { createLiveWorkspace } from "../../../src/features/albums/open/LiveWorkspace";
import { AlbumWorkspace } from "../../../src/features/albums/workspace/AlbumWorkspace";
import { AlbumRequestError, type Album } from "../../../src/features/albums/model/Album";
import type { Workspace } from "../../../src/features/albums/model/Workspace";
import { editingSession } from "../editing/EditingSessionStub";
import { buildAlbumClient } from "./AlbumClientStub";

vi.mock("../../../src/features/albums/open/LiveWorkspace", () => ({ createLiveWorkspace: vi.fn() }));
vi.mock("../../../src/features/albums/workspace/AlbumWorkspace", () => ({
  AlbumWorkspace: vi.fn((props: ComponentProps<typeof AlbumWorkspace>) => <h1>{props.album.title}</h1>),
}));

describe("AlbumOpen", () => {
  const album: Album = { id: "album", year: 2026, title: "Journey", createdAt: "2026-10-24T12:00:00Z", synopsis: "", authors: [] };
  const workspace: Workspace = { year: 2026, chapters: [], countries: [], photoRoot: "/photos", spreads: [], photoInventories: {} };

  it("should show initial loading without partial metadata, then forward the loaded workspace and callbacks", () => {
    const [loaded, setLoaded] = createSignal<Workspace>();
    const live = buildLive({ workspace: loaded, album: () => album });
    vi.mocked(createLiveWorkspace).mockReturnValue(live);
    const ready = vi.fn();
    const client = buildAlbumClient();
    const events = { subscribe: vi.fn() };
    render(() => <AlbumOpen id={album.id} client={client} events={events} session={editingSession} historyLimit={1} onReady={ready} />);
    expect(screen.getByRole("status", { name: "Loading album" })).toBeVisible();
    expect(screen.queryByRole("heading", { name: album.title })).not.toBeInTheDocument();

    setLoaded(workspace);

    expect(screen.getByRole("heading", { name: album.title })).toBeVisible();
    expect(screen.queryByRole("status", { name: "Loading album" })).not.toBeInTheDocument();
    expect(vi.mocked(AlbumWorkspace).mock.lastCall?.[0]).toEqual({
      album,
      workspace,
      client,
      session: editingSession,
      historyLimit: 1,
      onReady: ready,
      onRefresh: live.reload,
      onSaved: live.saved,
      workspaceError: undefined,
      workspaceLoading: true,
      photoErrors: {},
      scheduling: {},
      liveStatus: "connecting",
      onPhotoRefresh: live.refresh,
      onInventoryRetry: live.retryChapter,
      onAlbumSaved: live.publishAlbum,
    });
    const acquisition = vi.mocked(createLiveWorkspace).mock.lastCall;
    expect(acquisition?.[0]()).toBe(album.id);
    expect(acquisition?.slice(1)).toEqual([client, events]);
  });

  it.each([
    { status: 404, message: "could not be found", retryable: false },
    { status: 500, message: "could not be loaded", retryable: true },
  ])("should present HTTP $status opening failure and expose retry only when recoverable", ({ status, message, retryable }) => {
    const live = buildLive({ loading: () => false, error: () => new AlbumRequestError(status, "Failed") });
    vi.mocked(createLiveWorkspace).mockReturnValue(live);
    const ready = vi.fn();

    render(() => <AlbumOpen id={album.id} client={buildAlbumClient()} events={{ subscribe: vi.fn() }} session={editingSession} onReady={ready} />);

    expect(screen.getByRole("alert")).toHaveTextContent(message);
    expect(screen.queryByRole("heading", { name: album.title })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to album catalog" })).toHaveAttribute("href", "/");
    expect(ready).toHaveBeenCalled();
    if (retryable) {
      fireEvent.click(screen.getByRole("button", { name: "Retry album" }));
      expect(live.reload).toHaveBeenCalledOnce();
    } else expect(screen.queryByRole("button", { name: "Retry album" })).not.toBeInTheDocument();
  });

  it.each(["invalid", "interrupted"] as const)("should reveal initial %s live feedback and replace it with the loaded workspace", (status) => {
    const [loaded, setLoaded] = createSignal<Workspace>();
    const [liveStatus, setLiveStatus] = createSignal<ReturnType<ReturnType<typeof createLiveWorkspace>["liveStatus"]>>(status);
    vi.mocked(createLiveWorkspace).mockReturnValue(buildLive({ workspace: loaded, album: () => album, liveStatus }));
    const ready = vi.fn();
    render(() => <AlbumOpen id={album.id} client={buildAlbumClient()} events={{ subscribe: vi.fn() }} session={editingSession} onReady={ready} />);
    expect(screen.getByRole("alert")).toHaveTextContent(status === "invalid" ? "could not be decoded" : "interrupted");
    expect(ready).toHaveBeenCalled();
    if (status === "invalid") {
      expect(screen.getByRole("link", { name: "Reopen album" })).toHaveAttribute("href", window.location.href);
      expect(screen.queryByRole("status", { name: "Loading album" })).not.toBeInTheDocument();
    }

    setLiveStatus("live");
    setLoaded(workspace);

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: album.title })).toBeVisible();
  });
});

function buildLive(overrides: Partial<ReturnType<typeof createLiveWorkspace>> = {}): ReturnType<typeof createLiveWorkspace> {
  return {
    album: () => undefined,
    workspace: () => undefined,
    loading: () => true,
    error: () => undefined,
    liveStatus: () => "connecting",
    inventoryErrors: () => ({}),
    scheduling: () => ({}),
    reload: vi.fn().mockResolvedValue(undefined),
    retryChapter: vi.fn(),
    refresh: vi.fn().mockResolvedValue(undefined),
    saved: vi.fn().mockResolvedValue(undefined),
    publishAlbum: vi.fn(),
    ...overrides,
  };
}
