import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import type { ComponentProps } from "solid-js";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../../src/app/App";
import { AlbumDashboard } from "../../src/features/albums/dashboard/AlbumDashboard";
import { AlbumOpen } from "../../src/features/albums/open/AlbumOpen";
import type { AlbumPhotoEvents } from "../../src/features/albums/model/Photo";
import { editingSession } from "../features/editing/EditingSessionStub";
import { buildAlbumClient } from "../features/albums/AlbumClientStub";

vi.mock("../../src/features/albums/dashboard/AlbumDashboard", () => ({
  AlbumDashboard: vi.fn(() => (
    <>
      <h1>Album dashboard</h1>
      <a href="/albums/album">Open album</a>
      <a href="/albums/other" target="_blank">
        Open another tab
      </a>
      <a href="/albums/download" download="album.json">
        Download
      </a>
      <a href="https://example.com/albums/remote">External album</a>
      <a href="/settings">Settings</a>
    </>
  )),
}));
vi.mock("../../src/features/albums/open/AlbumOpen", () => ({
  AlbumOpen: vi.fn((props: ComponentProps<typeof AlbumOpen>) => (
    <>
      <h1>Opened {props.id}</h1>
      <button onClick={() => props.onReady?.()}>Finish opening</button>
    </>
  )),
}));

describe("App", () => {
  const client = buildAlbumClient();
  const events: AlbumPhotoEvents = { subscribe: vi.fn() };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should retain the catalog until the opened album is ready, then focus the workspace", async () => {
    const select = vi.fn().mockResolvedValue(true);
    render(() => <App client={client} events={events} session={{ ...editingSession, select }} />);

    fireEvent.click(screen.getByRole("link", { name: "Open album" }));

    expect(window.location.pathname).toBe("/albums/album");
    expect(screen.getByRole("main")).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("heading", { name: "Album dashboard" })).toBeVisible();
    expect(screen.getByRole("heading", { name: "Opened album", hidden: true })).not.toBeVisible();
    expect(select).toHaveBeenCalledWith(null);

    fireEvent.click(screen.getByRole("button", { name: "Finish opening", hidden: true }));

    expect(screen.queryByRole("heading", { name: "Album dashboard" })).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Opened album" })).toBeVisible();
    expect(screen.getByRole("main")).toHaveAttribute("aria-busy", "false");
    await waitFor(() => expect(screen.getByRole("main")).toHaveFocus());
  });

  it("should forward the addressed album, collaborators and history limit without exercising editing", () => {
    window.history.replaceState(null, "", "/albums/album/");
    render(() => <App client={client} events={events} session={editingSession} historyLimit={1} />);

    expect(screen.getByRole("heading", { name: "Opened album" })).toBeVisible();
    expect(screen.queryByRole("heading", { name: "Album dashboard" })).not.toBeInTheDocument();
    expect(vi.mocked(AlbumOpen).mock.lastCall?.[0]).toMatchObject({
      client,
      events,
      session: editingSession,
      id: "album",
      historyLimit: 1,
    });
    expect(typeof vi.mocked(AlbumOpen).mock.lastCall?.[0].onReady).toBe("function");
    fireEvent.click(screen.getByRole("button", { name: "Finish opening" }));
    expect(screen.getByRole("main")).toHaveAttribute("aria-busy", "false");
  });

  it("should follow browser history without replacing the document and release editing on catalog return", async () => {
    window.history.replaceState(null, "", "/albums/album");
    const select = vi.fn().mockResolvedValue(true);
    const originalDocument = document;
    render(() => <App client={client} events={events} session={{ ...editingSession, select }} />);

    window.history.pushState(null, "", "/");
    window.dispatchEvent(new PopStateEvent("popstate"));

    await screen.findByRole("heading", { name: "Album dashboard" });
    expect(screen.queryByRole("heading", { name: "Opened album" })).not.toBeInTheDocument();
    expect(select).toHaveBeenCalledWith(null);
    expect(document).toBe(originalDocument);
    expect(vi.mocked(AlbumDashboard).mock.lastCall?.[0]).toEqual({ client });
  });

  it.each([
    { name: "Open album", event: { ctrlKey: true } },
    { name: "Open album", event: { metaKey: true } },
    { name: "Open album", event: { shiftKey: true } },
    { name: "Open album", event: { altKey: true } },
    { name: "Open album", event: { button: 1 } },
    { name: "Open another tab", event: {} },
    { name: "Download", event: {} },
    { name: "External album", event: {} },
    { name: "Settings", event: {} },
  ])("should leave native navigation alone for $name with $event", ({ name, event }) => {
    render(() => <App client={client} events={events} session={editingSession} />);
    const click = new MouseEvent("click", { bubbles: true, cancelable: true, ...event });

    screen.getByRole("link", { name }).dispatchEvent(click);

    expect(click.defaultPrevented).toBe(false);
    expect(screen.getByRole("main")).toHaveAttribute("aria-busy", "false");
  });
});
