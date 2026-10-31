import { createRoot, createSignal } from "solid-js";
import { waitFor } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { createLiveWorkspace } from "../../../src/features/albums/open/LiveWorkspace";
import type { AlbumPhotoEvents, PhotoEvent, PhotoInventory } from "../../../src/features/albums/model/Photo";
import type { Workspace } from "../../../src/features/albums/model/Workspace";
import { AlbumRequestError, type AlbumClient } from "../../../src/features/albums/model/Album";

const inventory: PhotoInventory = {
  photos: [],
  placedPhotoIds: [],
  counts: { unplaced: 0, total: 0 },
  processing: { state: "idle", unsupportedCount: 0, skippedCount: 0, issues: [] },
};
const album = { id: "album", year: 2026, title: "Album", synopsis: "", createdAt: "2026-01-01T00:00:00Z" };
const workspace: Workspace = {
  year: 2026,
  photoRoot: "/photos",
  countries: [],
  chapters: ["a", "b"].map((id) => ({
    id,
    kind: id === "b" ? "home" : "vacation",
    name: id,
    countries: [],
    start: "2026-01-01",
    end: "2026-12-31",
    sourceFolder: "/photos",
    description: "",
  })),
  spreads: [
    { id: "opening", chapter: null, kind: "album_start", templateId: "layout", slots: [], placements: [] },
    { id: "a-opening", chapter: "a", kind: "chapter_start", templateId: "layout", slots: [], placements: [] },
    { id: "b-opening", chapter: "b", kind: "chapter_start", templateId: "layout", slots: [], placements: [] },
  ],
  photoInventories: { a: inventory, b: inventory },
};
type ResourceClient = Parameters<typeof createLiveWorkspace>[1];

describe("live workspace application coordination", () => {
  it("should abort acquisition after disposal without publishing late spread and inventory results", async () => {
    const photos = pendingRead<PhotoInventory>();
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const client = buildClient({ chapterPhotos: vi.fn().mockReturnValue(photos.promise) });
    const close = vi.fn();
    const { live, dispose } = createRoot((release) => ({
      live: createLiveWorkspace(() => album.id, client, buildEvents(receive, close)),
      dispose: release,
    }));
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(client.chapterPhotos).toHaveBeenCalledTimes(2);
    });

    dispose();
    photos.resolve(inventory);
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(live.workspace()).toBeUndefined();
    expect(live.liveStatus()).toBe("catching-up");
    expect(client.chapterPhotos.mock.calls[0]?.[1].aborted).toBe(true);
    expect(close).toHaveBeenCalledOnce();
  });

  it("should wait for subscription and independently timed resources before publishing a coherent workspace", async () => {
    const metadata = pendingRead<typeof album>();
    const chapters = pendingRead<Workspace["chapters"]>();
    const composition = pendingRead<Awaited<ReturnType<AlbumClient["composition"]>>>();
    const photos = pendingRead<PhotoInventory>();
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const close = vi.fn();
    const client = buildClient({
      open: vi.fn().mockReturnValue(metadata.promise),
      chapters: vi.fn().mockReturnValue(chapters.promise),
      composition: vi.fn().mockReturnValue(composition.promise),
      chapterPhotos: vi.fn().mockReturnValueOnce(photos.promise).mockResolvedValue(inventory),
    });
    const { live, dispose } = createRoot((release) => ({
      live: createLiveWorkspace(() => album.id, client, buildEvents(receive, close)),
      dispose: release,
    }));

    expect(client.chapters).not.toHaveBeenCalled();
    receive({ kind: "subscribed" });
    metadata.resolve(album);
    composition.resolve(workspace.spreads.map(({ id, chapter }) => ({ id, chapter })));
    await Promise.resolve();
    expect(client.spread).not.toHaveBeenCalled();
    chapters.resolve(workspace.chapters);
    await waitFor(() => {
      expect(client.chapterPhotos).toHaveBeenCalledTimes(2);
    });
    receive({ kind: "inventory-changed", chapterId: "b" });
    expect(live.workspace()).toBeUndefined();
    photos.resolve(inventory);
    await waitFor(() => {
      expect(live.liveStatus()).toBe("live");
    });

    expect(live.workspace()).toEqual(workspace);
    expect(client.open).toHaveBeenCalledOnce();
    expect(client.spread).toHaveBeenCalledTimes(3);
    await waitFor(() => {
      expect(client.chapterPhotos).toHaveBeenCalledTimes(3);
    });
    dispose();
    expect(close).toHaveBeenCalledOnce();
  });

  it("should reconcile a chapter created between chapter and composition reads without publishing the incomplete join", async () => {
    const freshChapters = pendingRead<Workspace["chapters"]>();
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const client = buildClient({
      chapters: vi.fn().mockResolvedValueOnce(workspace.chapters.slice(0, 1)).mockReturnValueOnce(freshChapters.promise),
    });
    const { live, dispose } = createRoot((release) => ({
      live: createLiveWorkspace(() => album.id, client, buildEvents(receive, vi.fn())),
      dispose: release,
    }));

    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(client.chapters).toHaveBeenCalledTimes(2);
    });
    expect(live.workspace()).toBeUndefined();
    expect(live.liveStatus()).toBe("catching-up");
    freshChapters.resolve(workspace.chapters);
    await waitFor(() => {
      expect(live.workspace()).toEqual(workspace);
    });

    expect(live.liveStatus()).toBe("live");
    expect(client.refreshPhotos).not.toHaveBeenCalled();
    dispose();
  });

  it("should reconcile a spread deleted after membership acquisition without treating its album as missing", async () => {
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const members = workspace.spreads.map(({ id, chapter }) => ({ id, chapter }));
    const beforeDeletion = [...members.slice(0, 2), { id: "deleted-photo", chapter: "a" }, ...members.slice(2)];
    const client = buildClient({
      composition: vi.fn().mockResolvedValueOnce(beforeDeletion).mockResolvedValue(members),
      spread: vi.fn().mockImplementation((_album: string, id: string) => {
        if (id === "deleted-photo") return Promise.reject(new AlbumRequestError(404, "Spread not found"));
        const spread = workspace.spreads.find((candidate) => candidate.id === id);
        if (spread === undefined) throw new Error("Unexpected spread");
        return Promise.resolve(spread);
      }),
    });
    const { live, dispose } = createRoot((release) => ({
      live: createLiveWorkspace(() => album.id, client, buildEvents(receive, vi.fn())),
      dispose: release,
    }));

    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.workspace()).toEqual(workspace);
    });

    expect(client.composition).toHaveBeenCalledTimes(2);
    expect(live.error()).toBeUndefined();
    expect(live.liveStatus()).toBe("live");
    dispose();
  });

  it("should reconcile a chapter deleted before its inventory read without publishing the obsolete chapter", async () => {
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const remainingChapters = workspace.chapters.filter((chapter) => chapter.id === "b");
    const remainingSpreads = workspace.spreads.filter((spread) => spread.chapter === null || spread.chapter === "b");
    const client = buildClient({
      chapters: vi.fn().mockResolvedValueOnce(workspace.chapters).mockResolvedValue(remainingChapters),
      composition: vi
        .fn()
        .mockResolvedValueOnce(workspace.spreads.map(({ id, chapter }) => ({ id, chapter })))
        .mockResolvedValue(remainingSpreads.map(({ id, chapter }) => ({ id, chapter }))),
      chapterPhotos: vi.fn().mockRejectedValueOnce(new AlbumRequestError(404, "Chapter not found")).mockResolvedValue(inventory),
    });
    const { live, dispose } = createRoot((release) => ({
      live: createLiveWorkspace(() => album.id, client, buildEvents(receive, vi.fn())),
      dispose: release,
    }));

    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.workspace()?.chapters).toEqual(remainingChapters);
    });

    expect(live.workspace()?.spreads).toEqual(remainingSpreads);
    expect(live.workspace()?.photoInventories).toEqual({ b: inventory });
    expect(live.error()).toBeUndefined();
    expect(client.chapters).toHaveBeenCalledTimes(2);
    dispose();
  });

  it("should expose a child service failure without retrying it as a structural race", async () => {
    const failure = new AlbumRequestError(500, "Spread service unavailable");
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const client = buildClient({ spread: vi.fn().mockRejectedValue(failure) });
    const { live, dispose } = createRoot((release) => ({
      live: createLiveWorkspace(() => album.id, client, buildEvents(receive, vi.fn())),
      dispose: release,
    }));

    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.error()).toBe(failure);
    });

    expect(live.workspace()).toBeUndefined();
    expect(client.chapters).toHaveBeenCalledOnce();
    dispose();
  });

  it("should retain the last usable workspace when independently read chapter blocks remain incoherent after bounded reconciliation", async () => {
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const client = buildClient();
    const { live, dispose } = createRoot((release) => ({
      live: createLiveWorkspace(() => album.id, client, buildEvents(receive, vi.fn())),
      dispose: release,
    }));
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.workspace()).toEqual(workspace);
    });
    const invalid = [...workspace.chapters].reverse();
    client.chapters.mockResolvedValue(invalid);

    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.error()).toEqual(new Error("Home must be the final chapter"));
    });

    expect(client.chapters).toHaveBeenCalledTimes(4);
    expect(live.workspace()).toEqual(workspace);
    expect(live.liveStatus()).toBe("catching-up");
    expect(live.loading()).toBe(false);
    dispose();
  });

  it("should reject duplicate chapters, duplicate spreads, missing openings and mismatched spread identities", async () => {
    const cases: readonly { name: string; overrides: Partial<ResourceClient>; message: string }[] = [
      {
        name: "duplicate chapters",
        overrides: { chapters: vi.fn().mockResolvedValue([...workspace.chapters, workspace.chapters[0]]) },
        message: "Duplicate chapter identities",
      },
      {
        name: "duplicate spreads",
        overrides: {
          composition: vi.fn().mockResolvedValue([
            { id: "opening", chapter: null },
            { id: "opening", chapter: null },
          ]),
        },
        message: "Duplicate spread identities",
      },
      { name: "missing opening", overrides: { composition: vi.fn().mockResolvedValue([]) }, message: "Incomplete chapter composition" },
      {
        name: "missing chapter blocks",
        overrides: { composition: vi.fn().mockResolvedValue([{ id: "opening", chapter: null }]) },
        message: "Incomplete chapter composition",
      },
      {
        name: "missing Synopsis",
        overrides: {
          composition: vi.fn().mockResolvedValue([
            { id: "a-opening", chapter: "a" },
            { id: "b-opening", chapter: "b" },
          ]),
        },
        message: "Missing Synopsis opening",
      },
      {
        name: "changed chapter order",
        overrides: {
          composition: vi.fn().mockResolvedValue([
            { id: "opening", chapter: null },
            { id: "b-opening", chapter: "b" },
            { id: "a-opening", chapter: "a" },
          ]),
        },
        message: "Chapter composition changed during acquisition",
      },
      {
        name: "photo spread without its chapter opening",
        overrides: {
          composition: vi.fn().mockResolvedValue([
            { id: "opening", chapter: null },
            { id: "photo", chapter: "a" },
          ]),
          spread: vi
            .fn()
            .mockImplementation((_album: string, id: string) =>
              Promise.resolve(
                id === "opening"
                  ? workspace.spreads[0]
                  : { id: "photo", chapter: "a", kind: "photos_only", templateId: "layout", slots: [], placements: [] },
              ),
            ),
        },
        message: "Invalid chapter spread block",
      },
      {
        name: "foreign spread",
        overrides: { spread: vi.fn().mockResolvedValue(workspace.spreads[1]) },
        message: "Spread identity mismatch",
      },
      {
        name: "changed spread ownership",
        overrides: { composition: vi.fn().mockResolvedValue([{ id: "opening", chapter: "a" }]) },
        message: "Spread identity mismatch",
      },
      {
        name: "another album opening inside a chapter block",
        overrides: {
          composition: vi.fn().mockResolvedValue([
            { id: "opening", chapter: null },
            { id: "another-opening", chapter: null },
          ]),
          spread: vi
            .fn()
            .mockImplementation((_album: string, id: string) =>
              Promise.resolve({ id, chapter: null, kind: "album_start", templateId: "layout", slots: [], placements: [] }),
            ),
        },
        message: "Invalid chapter spread block",
      },
    ];
    for (const scenario of cases) {
      const receive = vi.fn<(event: PhotoEvent) => void>();
      const client = buildClient(scenario.overrides);
      const { live, dispose } = createRoot((release) => ({
        live: createLiveWorkspace(() => album.id, client, buildEvents(receive, vi.fn())),
        dispose: release,
      }));

      receive({ kind: "subscribed" });
      await waitFor(() => {
        expect(live.error(), scenario.name).toEqual(new Error(scenario.message));
      });

      expect(live.workspace(), scenario.name).toBeUndefined();
      expect(client.chapters, scenario.name).toHaveBeenCalledTimes(3);
      dispose();
    }
  });

  it("should buffer invalidations during catch-up and serialize a trailing inventory read", async () => {
    const first = pendingRead<PhotoInventory>();
    const second = pendingRead<PhotoInventory>();
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const client = buildClient();
    const { live, dispose } = createRoot((release) => ({
      live: createLiveWorkspace(() => album.id, client, buildEvents(receive, vi.fn())),
      dispose: release,
    }));
    receive({ kind: "subscribed" });
    receive({ kind: "inventory-changed", chapterId: "b" });
    await waitFor(() => {
      expect(client.chapterPhotos).toHaveBeenCalledTimes(3);
    });
    client.chapterPhotos.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);

    receive({ kind: "inventory-changed", chapterId: "b" });
    receive({ kind: "inventory-changed", chapterId: "b" });
    first.resolve({ ...inventory, processing: { ...inventory.processing, state: "running" } });
    await waitFor(() => {
      expect(client.chapterPhotos).toHaveBeenCalledTimes(5);
    });
    second.resolve({ ...inventory, processing: { ...inventory.processing, state: "completed" } });
    await waitFor(() => {
      expect(live.workspace()?.photoInventories["b"]?.processing.state).toBe("completed");
    });

    expect(live.workspace()?.photoInventories["a"]).toEqual(inventory);
    expect(client.refreshPhotos).not.toHaveBeenCalled();
    dispose();
  });

  it("should refresh mutable metadata on reconnect and ignore superseded inventory results", async () => {
    const old = pendingRead<PhotoInventory>();
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const client = buildClient();
    const { live, dispose } = createRoot((release) => ({
      live: createLiveWorkspace(() => album.id, client, buildEvents(receive, vi.fn())),
      dispose: release,
    }));
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.workspace()).toEqual(workspace);
    });
    client.chapterPhotos.mockReturnValueOnce(old.promise);
    receive({ kind: "inventory-changed", chapterId: "a" });
    receive({ kind: "interrupted" });
    expect(live.liveStatus()).toBe("interrupted");
    client.open.mockResolvedValue({ ...album, title: "Renamed", synopsis: "Saved synopsis" });

    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.album()?.title).toBe("Renamed");
    });
    old.resolve({ ...inventory, processing: { ...inventory.processing, state: "failed" } });
    await Promise.resolve();

    expect(live.album()?.synopsis).toBe("Saved synopsis");
    expect(live.workspace()?.photoInventories["a"]).toEqual(inventory);
    expect(live.liveStatus()).toBe("live");
    expect(client.open).toHaveBeenCalledTimes(2);
    receive({ kind: "invalid" });
    expect(live.liveStatus()).toBe("invalid");
    dispose();
  });

  it("should retain photos and expose a failed chapter read until an explicit read retry succeeds", async () => {
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const client = buildClient();
    const { live, dispose } = createRoot((release) => ({
      live: createLiveWorkspace(() => album.id, client, buildEvents(receive, vi.fn())),
      dispose: release,
    }));
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.workspace()).toEqual(workspace);
    });
    client.chapterPhotos.mockRejectedValueOnce(new Error("offline"));

    live.retryChapter("b");
    await waitFor(() => {
      expect(live.inventoryErrors()["b"]).toBeDefined();
    });
    expect(live.workspace()?.photoInventories["b"]).toEqual(inventory);
    live.retryChapter("b");
    await waitFor(() => {
      expect(live.inventoryErrors()["b"]).toBeUndefined();
    });

    expect(client.refreshPhotos).not.toHaveBeenCalled();
    dispose();
  });

  it("should close subscriptions, abort resource acquisition and ignore late results after album switching", async () => {
    const oldMetadata = pendingRead<typeof album>();
    const oldChapters = pendingRead<Workspace["chapters"]>();
    const listeners: ((event: PhotoEvent) => void)[] = [];
    const closers = [vi.fn(), vi.fn()];
    const events: AlbumPhotoEvents = {
      subscribe: (_id, listener) => {
        listeners.push(listener);
        return closers[listeners.length - 1] ?? vi.fn();
      },
    };
    const client = buildClient({
      open: vi
        .fn()
        .mockReturnValueOnce(oldMetadata.promise)
        .mockResolvedValue({ ...album, id: "other", year: 2027 }),
      chapters: vi.fn().mockReturnValueOnce(oldChapters.promise).mockResolvedValue(workspace.chapters),
    });
    const { live, change, dispose } = createRoot((release) => {
      const [id, changeAlbum] = createSignal(album.id);
      return { live: createLiveWorkspace(id, client, events), change: changeAlbum, dispose: release };
    });
    listeners[0]?.({ kind: "subscribed" });

    change("other");
    await waitFor(() => {
      expect(listeners).toHaveLength(2);
    });
    listeners[1]?.({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.workspace()?.year).toBe(2027);
    });
    oldMetadata.resolve(album);
    oldChapters.resolve(workspace.chapters);
    listeners[0]?.({ kind: "inventory-changed", chapterId: "a" });
    await Promise.resolve();

    expect(live.album()?.id).toBe("other");
    expect(closers[0]).toHaveBeenCalledOnce();
    expect(client.chapters.mock.calls[0]?.[1].aborted).toBe(true);
    dispose();
    expect(closers[1]).toHaveBeenCalledOnce();
  });

  it("should preserve scheduling failure after save and coalesce duplicate scoped import requests", async () => {
    const pending = pendingRead<"queued" | "coalesced">();
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const client = buildClient();
    const { live, dispose } = createRoot((release) => ({
      live: createLiveWorkspace(() => album.id, client, buildEvents(receive, vi.fn())),
      dispose: release,
    }));
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.workspace()).toEqual(workspace);
    });
    await live.saved({ id: "a", scheduling: "failed" });
    expect(live.scheduling()["a"]).toBe("failed");
    client.refreshPhotos.mockReturnValueOnce(pending.promise).mockRejectedValueOnce(new Error("Scheduling unavailable"));

    const refresh = live.refresh(["a", "b"]);
    const duplicate = live.refresh(["a"]);
    await waitFor(() => {
      expect(live.scheduling()["b"]).toBe("failed");
    });
    expect(live.scheduling()["a"]).toBe("pending");
    pending.resolve("queued");
    await Promise.all([refresh, duplicate]);

    expect(client.refreshPhotos.mock.calls).toEqual([["a"], ["b"]]);
    expect(live.scheduling()).toEqual({ a: "queued", b: "failed" });
    expect(live.workspace()?.photoInventories["a"]?.processing.state).toBe("idle");
    await live.refresh(["b"]);
    expect(live.scheduling()["b"]).toBe("coalesced");
    dispose();
  });

  it("should coalesce reloads, retain explicit resource failures and never start imports during recovery", async () => {
    const pending = pendingRead<Workspace["chapters"]>();
    const failure = new Error("Chapter acquisition failed");
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const client = buildClient({
      chapters: vi.fn().mockRejectedValueOnce(failure).mockReturnValueOnce(pending.promise).mockResolvedValue(workspace.chapters),
    });
    const { live, dispose } = createRoot((release) => ({
      live: createLiveWorkspace(() => album.id, client, buildEvents(receive, vi.fn())),
      dispose: release,
    }));
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.error()).toBe(failure);
    });

    const retry = live.reload();
    const trailing = live.reload();
    pending.resolve(workspace.chapters);
    await Promise.all([retry, trailing]);

    expect(live.error()).toBeUndefined();
    expect(live.workspace()).toEqual(workspace);
    expect(client.chapters).toHaveBeenCalledTimes(3);
    expect(client.refreshPhotos).not.toHaveBeenCalled();
    dispose();
  });

  it("should reacquire unknown chapter signals and leave disposed operations inert", async () => {
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const client = buildClient();
    const { live, dispose } = createRoot((release) => ({
      live: createLiveWorkspace(() => album.id, client, buildEvents(receive, vi.fn())),
      dispose: release,
    }));
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.workspace()).toEqual(workspace);
    });
    client.chapters.mockRejectedValueOnce("Unreadable chapters");

    receive({ kind: "inventory-changed", chapterId: "new" });
    await waitFor(() => {
      expect(live.error()).toEqual(new Error("Saved workspace could not be loaded"));
    });
    receive({ kind: "inventory-changed", chapterId: "new" });
    await waitFor(() => {
      expect(live.error()).toBeUndefined();
    });
    dispose();
    const reads = client.chapterPhotos.mock.calls.length;
    live.retryChapter("a");
    await live.reload();
    await live.refresh(["a"]);
    await live.saved({ id: "a", scheduling: "failed" });

    expect(client.chapters).toHaveBeenCalledTimes(3);
    expect(client.chapterPhotos).toHaveBeenCalledTimes(reads);
    expect(client.refreshPhotos).not.toHaveBeenCalled();
  });

  it.each(["chapters", "inventory", "inventory-failure", "refresh", "refresh-failure", "metadata"] as const)(
    "should dispose without publishing a late %s result",
    async (operation) => {
      const metadata = pendingRead<typeof album>();
      const chapters = pendingRead<Workspace["chapters"]>();
      const photos = pendingRead<PhotoInventory>();
      const command = pendingRead<"queued" | "coalesced">();
      const receive = vi.fn<(event: PhotoEvent) => void>();
      const close = vi.fn();
      const client = buildClient(operation === "metadata" ? { open: vi.fn().mockReturnValue(metadata.promise) } : {});
      const { live, dispose } = createRoot((release) => ({
        live: createLiveWorkspace(() => album.id, client, buildEvents(receive, close)),
        dispose: release,
      }));
      receive({ kind: "subscribed" });
      if (operation !== "metadata")
        await waitFor(() => {
          expect(live.workspace()).toEqual(workspace);
        });
      client.chapters.mockReturnValue(chapters.promise);
      client.chapterPhotos.mockReturnValue(photos.promise);
      client.refreshPhotos.mockReturnValue(command.promise);

      const pending = operation === "chapters" ? live.reload() : operation.startsWith("refresh") ? live.refresh(["a"]) : Promise.resolve();
      if (operation.startsWith("inventory")) live.retryChapter("a");
      dispose();
      const failure = new Error("Late failure");
      if (operation === "chapters") chapters.reject(failure);
      if (operation === "inventory") photos.resolve(inventory);
      if (operation === "inventory-failure") photos.reject(failure);
      if (operation === "refresh") command.resolve("queued");
      if (operation === "refresh-failure") command.reject(failure);
      if (operation === "metadata") metadata.reject(failure);
      await pending;
      await new Promise((resolve) => setTimeout(resolve, 0));

      expect(close).toHaveBeenCalledOnce();
      expect(live.error()).toBeUndefined();
      if (operation.startsWith("refresh")) expect(live.scheduling()["a"]).toBe("pending");
    },
  );

  it("should ignore old inventory reads while the next album has not subscribed", async () => {
    const photos = pendingRead<PhotoInventory>();
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const client = buildClient();
    const { live, change, dispose } = createRoot((release) => {
      const [id, changeAlbum] = createSignal(album.id);
      return { live: createLiveWorkspace(id, client, buildEvents(receive, vi.fn())), change: changeAlbum, dispose: release };
    });
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(live.workspace()).toEqual(workspace);
    });
    client.chapterPhotos.mockReturnValue(photos.promise);
    live.retryChapter("a");

    change("other");
    await waitFor(() => {
      expect(live.workspace()).toBeUndefined();
    });
    photos.resolve(inventory);
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(live.workspace()).toBeUndefined();
    dispose();
  });

  it("should expose subscription construction failure and reject a foreign album identity", async () => {
    const failure = new Error("EventSource unavailable");
    const client = buildClient();
    const { live, dispose } = createRoot((release) => ({
      live: createLiveWorkspace(() => album.id, client, {
        subscribe: () => {
          throw failure;
        },
      }),
      dispose: release,
    }));
    expect(live.error()).toBe(failure);
    expect(live.loading()).toBe(false);
    expect(live.liveStatus()).toBe("interrupted");
    expect(client.chapters).not.toHaveBeenCalled();
    dispose();
    await Promise.resolve();

    const receive = vi.fn<(event: PhotoEvent) => void>();
    const foreign = buildClient({ open: vi.fn().mockResolvedValue({ ...album, id: "foreign" }) });
    const next = createRoot((release) => ({
      live: createLiveWorkspace(() => album.id, foreign, buildEvents(receive, vi.fn())),
      dispose: release,
    }));
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(next.live.error()).toEqual(new Error("Unexpected album identity"));
    });
    expect(next.live.workspace()).toBeUndefined();
    next.dispose();
  });
});

function buildClient(overrides: Partial<ResourceClient> = {}) {
  return {
    open: vi.fn<ResourceClient["open"]>(overrides.open ?? (() => Promise.resolve(album))),
    chapters: vi.fn<ResourceClient["chapters"]>(overrides.chapters ?? (() => Promise.resolve(workspace.chapters))),
    composition: vi.fn<ResourceClient["composition"]>(
      overrides.composition ?? (() => Promise.resolve(workspace.spreads.map(({ id, chapter }) => ({ id, chapter })))),
    ),
    spread: vi.fn<ResourceClient["spread"]>(
      overrides.spread ??
        ((_album, id) => {
          const spread = workspace.spreads.find((candidate) => candidate.id === id);
          if (spread === undefined) throw new Error("Unknown spread");
          return Promise.resolve(spread);
        }),
    ),
    countries: vi.fn<ResourceClient["countries"]>(overrides.countries ?? (() => Promise.resolve(workspace.countries))),
    photoSource: vi.fn<ResourceClient["photoSource"]>(overrides.photoSource ?? (() => Promise.resolve(workspace.photoRoot))),
    chapterPhotos: vi.fn<ResourceClient["chapterPhotos"]>(overrides.chapterPhotos ?? (() => Promise.resolve(inventory))),
    refreshPhotos: vi.fn<ResourceClient["refreshPhotos"]>(overrides.refreshPhotos ?? (() => Promise.resolve("coalesced"))),
  };
}

function buildEvents(receive: ReturnType<typeof vi.fn<(event: PhotoEvent) => void>>, close: () => void): AlbumPhotoEvents {
  return {
    subscribe: (_id, listener) => {
      receive.mockImplementation(listener);
      return close;
    },
  };
}

function pendingRead<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((finish, fail) => {
    resolve = finish;
    reject = fail;
  });
  return { promise, resolve, reject };
}
