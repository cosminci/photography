import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { AlbumOpen } from "../../../src/features/albums/open/AlbumOpen";
import { AlbumRequestError, SpreadOccupiedError, type AlbumClient } from "../../../src/features/albums/model/Album";
import type { Chapter, Spread, Workspace } from "../../../src/features/albums/model/Workspace";
import type { AlbumPhotoEvents, PhotoEvent, PhotoInventory } from "../../../src/features/albums/model/Photo";
import type { PlacementContext, PlacementSnapshot, SpreadPlacement, SpreadSnapshot } from "../../../src/features/albums/model/Placement";

const album = { id: "album", year: 2026, title: "Saved free-form album title", createdAt: "2026-10-25T12:00:00Z", synopsis: "Saved album synopsis" };
const inventory: PhotoInventory = {
  placedPhotoIds: [],
  photos: [],
  counts: { unplaced: 0, total: 0 },
  processing: { state: "idle", unsupportedCount: 0, skippedCount: 0, issues: [] },
};
const events: AlbumPhotoEvents = {
  subscribe: (_id, receive) => {
    receive({ kind: "subscribed" });
    return vi.fn();
  },
};
const vacation: Chapter = {
  id: "vacation",
  kind: "vacation",
  name: "Kyoto",
  countries: ["jp"],
  start: "2026-04-02",
  end: "2026-04-14",
  sourceFolder: "/mnt/saved/Kyoto",
  description: "Spring in Japan",
};
const home: Chapter = {
  id: "home",
  kind: "home",
  name: "Home",
  countries: ["ro"],
  start: "2026-01-01",
  end: "2026-12-31",
  sourceFolder: "/mnt/saved/Home",
  description: "",
};
const photoSlot = { id: "photo-slot", kind: "photo" as const, x: 2, y: 4, width: 23, height: 42 };
const textSlot = { id: "text-slot", kind: "text" as const, x: 50, y: 4, width: 48, height: 92 };
const opening = {
  id: "album-opening",
  templateId: "layout",
  chapter: null,
  kind: "album_start" as const,
  slots: [photoSlot, textSlot],
  placements: [],
};
const vacationOpening = {
  id: "vacation-opening",
  templateId: "layout",
  chapter: vacation.id,
  kind: "chapter_start" as const,
  slots: [photoSlot, textSlot],
  placements: [],
};
const vacationPhoto = {
  id: "vacation-photo",
  templateId: "layout",
  chapter: vacation.id,
  kind: "photos_only" as const,
  slots: [photoSlot],
  placements: [],
};
const workspace: Workspace = {
  photoInventories: { vacation: inventory, home: inventory },
  year: 2026,
  photoRoot: "/mnt/photos",
  chapters: [vacation, home],
  spreads: [
    opening,
    vacationOpening,
    vacationPhoto,
    { id: "home-opening", templateId: "layout", chapter: home.id, kind: "chapter_start", slots: [textSlot], placements: [] },
  ],
  countries: [
    { id: "ro", name: "Romania", code: "RO" },
    { id: "jp", name: "Japan", code: "JP" },
    { id: "it", name: "Italy", code: "IT" },
  ],
};
const nativeMethods = {
  showModal: Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "showModal"),
  close: Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "close"),
};
const nativeScroll = Object.getOwnPropertyDescriptor(Element.prototype, "scrollIntoView");

describe("album workspace browser boundary", () => {
  beforeAll(() => {
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe = vi.fn();
        disconnect = vi.fn();
      },
    );
    Object.defineProperty(Element.prototype, "scrollIntoView", { configurable: true, value: vi.fn() });
    HTMLDialogElement.prototype.showModal = function () {
      this.setAttribute("open", "");
      this.querySelector<HTMLElement>("select, input, button")?.focus();
    };
    HTMLDialogElement.prototype.close = function () {
      this.removeAttribute("open");
    };
  });
  afterAll(() => {
    vi.unstubAllGlobals();
    if (nativeScroll === undefined) Reflect.deleteProperty(Element.prototype, "scrollIntoView");
    else Object.defineProperty(Element.prototype, "scrollIntoView", nativeScroll);
    for (const [name, descriptor] of Object.entries(nativeMethods)) {
      if (descriptor === undefined) Reflect.deleteProperty(HTMLDialogElement.prototype, name);
      else Object.defineProperty(HTMLDialogElement.prototype, name, descriptor);
    }
  });

  it("places selected canonical photos, announces keyboard choices and immediately reloads retained placements after a template change", async () => {
    const photo = {
      id: "mountain",
      filename: "Mountain.jpg",
      capturedLocal: null,
      width: 400,
      height: 200,
      caption: null,
      thumbnail: { url: "/mountain.jpg", width: 400, height: 200 },
    };
    const photos = { ...inventory, photos: [photo], counts: { unplaced: 1, total: 1 } };
    const available = { ...workspace, photoInventories: { vacation: photos } };
    const used = { ...available, photoInventories: { vacation: { ...photos, placedPhotoIds: [photo.id], counts: { unplaced: 0, total: 1 } } } };
    const placement: SpreadPlacement = {
      kind: "spread",
      captionCorner: "top_left",
      id: "placed",
      photoId: photo.id,
      slotId: photoSlot.id,
      transform: { x: 0, y: 0, zoom: 1, mirrored: false },
    };
    const changed = {
      ...used,
      spreads: [{ ...opening, templateId: "new", slots: [{ ...photoSlot, id: "new-slot" }, textSlot] }, ...used.spreads.slice(1)],
    };
    const client = buildClient({
      chapterPhotos: vi.fn().mockResolvedValueOnce(photos).mockResolvedValueOnce(inventory).mockResolvedValue(used.photoInventories.vacation),
      spread: vi
        .fn()
        .mockImplementation((_album, id) => {
          const spread = changed.spreads.find((candidate) => candidate.id === id);
          if (spread === undefined) throw new Error("Unknown spread");
          return Promise.resolve(spread.id === opening.id ? { ...spread, placements: [{ ...placement, slotId: "new-slot" }] } : spread);
        })
        .mockResolvedValueOnce(opening)
        .mockResolvedValueOnce(vacationOpening)
        .mockResolvedValueOnce(vacationPhoto)
        .mockResolvedValueOnce(workspace.spreads[3])
        .mockResolvedValueOnce({ ...opening, placements: [placement] })
        .mockResolvedValueOnce(vacationOpening)
        .mockResolvedValueOnce(vacationPhoto)
        .mockResolvedValueOnce(workspace.spreads[3]),
      editPlacements: vi.fn().mockResolvedValue({
        kind: "spread",
        id: opening.id,
        placements: [placement],
        layout: { kind: opening.kind, templateId: opening.templateId, slots: opening.slots },
      } satisfies SpreadSnapshot),
      templates: vi.fn().mockResolvedValue([
        { id: "layout", name: "Applied", slots: [photoSlot, textSlot] },
        { id: "new", name: "New layout", slots: [photoSlot, textSlot] },
      ]),
      changeSpreadTemplate: vi.fn().mockResolvedValue(undefined),
    });
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Choose Mountain.jpg" }));
    fireEvent.click(screen.getByRole("button", { name: "Photo slot 1: empty" }));
    await screen.findByRole("button", { name: "Photo slot 1: Mountain.jpg" });
    await waitFor(() => {
      expect(screen.queryByRole("button", { name: "Choose Mountain.jpg" })).not.toBeInTheDocument();
    });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Move photo from Photo slot 1" })).toBeEnabled();
    });
    fireEvent.click(screen.getByRole("button", { name: "Move photo from Photo slot 1" }));
    expect(screen.getByText("Move selected. Choose a destination slot to move or swap.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cancel photo choice" }));
    fireEvent.click(screen.getByRole("button", { name: "New layout" }));
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "New layout" })).toHaveAttribute("aria-pressed", "true");
    });

    expect(screen.getByRole("button", { name: "Photo slot 1: Mountain.jpg" })).toBeInTheDocument();
    const expectedContext = { kind: "spread", id: opening.id };
    const expectedEdit = { operation: "place", photoId: photo.id, slotId: photoSlot.id };
    expect(client.editPlacements).toHaveBeenCalledWith(album.id, expectedContext, expectedEdit);
    expect(client.readPlacements).not.toHaveBeenCalled();
  });

  it("shows failed Cover reads and retries through the authoritative read boundary", async () => {
    window.history.replaceState(null, "", "?part=cover");
    const client = buildClient({
      readPlacements: vi
        .fn()
        .mockRejectedValueOnce(new Error("offline"))
        .mockResolvedValue({ kind: "cover", placements: [], frontSlotId: "front", backSlotId: "back", spineOwner: "front" }),
    });
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Cover" }));
    fireEvent.click(await screen.findByRole("button", { name: "Retry photos" }));
    await waitFor(() => {
      expect(screen.queryByRole("button", { name: "Retry photos" })).not.toBeInTheDocument();
    });

    expect(client.readPlacements).toHaveBeenCalledTimes(2);
    expect(client.editPlacements).not.toHaveBeenCalled();
  });

  it("should preserve unresolved Cover state against navigation and page departure", async () => {
    window.history.replaceState(null, "", "/albums/album?part=cover");
    const client = buildClient({ readPlacements: vi.fn().mockRejectedValue(new Error("offline")) });
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    await screen.findByRole("button", { name: "Retry photos" });
    const before = window.location.search;

    window.history.replaceState(null, "", "/?part=cover");
    window.dispatchEvent(new PopStateEvent("popstate"));
    fireEvent.keyDown(document.body, { key: "ArrowRight" });
    const departure = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(departure);
    fireEvent.click(screen.getByRole("link", { name: "Back to album catalog" }));

    expect(window.location.search).toBe(before);
    expect(window.location.pathname).toBe("/albums/album");
    expect(departure.defaultPrevented).toBe(true);
    expect(screen.getByRole("button", { name: "Cover" })).toHaveAccessibleDescription("Reload saved photos before leaving.");
  });

  it("should keep an uncertain Synopsis placement read-only and preserve its URL against history and keyboard navigation", async () => {
    window.history.replaceState(null, "", "/albums/album");
    const photo = {
      id: "mountain",
      filename: "Mountain.jpg",
      capturedLocal: null,
      width: 400,
      height: 200,
      caption: null,
      thumbnail: { url: "/mountain.jpg", width: 400, height: 200 },
    };
    const placement: SpreadPlacement = {
      kind: "spread",
      captionCorner: "top_left",
      id: "placed",
      photoId: photo.id,
      slotId: photoSlot.id,
      transform: { x: 0, y: 0, zoom: 1, mirrored: false },
    };
    const saved = {
      ...workspace,
      spreads: [{ ...opening, placements: [placement] }, ...workspace.spreads.slice(1)],
      photoInventories: {
        ...workspace.photoInventories,
        vacation: { ...inventory, photos: [photo], placedPhotoIds: [photo.id], counts: { unplaced: 0, total: 1 } },
      },
    };
    const client = buildClient({ editPlacements: vi.fn().mockRejectedValue(new Error("Acknowledgement lost")) }, saved);
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Remove photo from Photo slot 1" }));
    await screen.findByRole("button", { name: "Retry photos" });

    window.history.replaceState(null, "", "/?part=home");
    window.dispatchEvent(new PopStateEvent("popstate"));
    fireEvent.keyDown(document.body, { key: "ArrowRight" });
    const departure = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(departure);

    expect(window.location.pathname).toBe("/albums/album");
    expect(window.location.search).toBe("");
    expect(departure.defaultPrevented).toBe(true);
    expect(client.editPlacements).toHaveBeenCalledOnce();
    expect(screen.getByRole("button", { name: "Cover" })).toHaveAccessibleDescription("Reload saved photos before leaving.");
  });

  it("should use acquired saved spreads for selection and navigation without a second full GET", async () => {
    window.history.replaceState(null, "", "?part=vacation-photo");
    const client = buildClient({
      readPlacements: vi.fn().mockRejectedValue(new Error("A second spread GET is unnecessary")),
    });
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);

    await screen.findByRole("button", { name: "Photo slot 1: empty" });
    fireEvent.click(screen.getByRole("link", { name: "Kyoto" }));
    expect(screen.getByRole("heading", { name: "Kyoto" })).toBeInTheDocument();
    expect(client.spread).toHaveBeenCalledTimes(workspace.spreads.length);
    expect(client.readPlacements).not.toHaveBeenCalled();
    expect(client.editPlacements).not.toHaveBeenCalled();
  });

  it("reports an acknowledged placement whose workspace refresh failed and retries reads without replaying it", async () => {
    const photo = {
      id: "mountain",
      filename: "Mountain.jpg",
      capturedLocal: null,
      width: 400,
      height: 200,
      caption: null,
      thumbnail: { url: "/mountain.jpg", width: 400, height: 200 },
    };
    const available = { ...workspace, photoInventories: { vacation: { ...inventory, photos: [photo], counts: { unplaced: 1, total: 1 } } } };
    const client = buildClient({
      chapters: vi.fn().mockResolvedValueOnce(available.chapters).mockRejectedValueOnce(new Error("offline")).mockResolvedValue(available.chapters),
      chapterPhotos: vi
        .fn()
        .mockImplementation((id: string) => Promise.resolve(id === vacation.id ? available.photoInventories.vacation : inventory)),
    });
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    const choose = await screen.findByRole("button", { name: "Choose Mountain.jpg" });
    const departure = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(departure);
    fireEvent.click(screen.getByRole("link", { name: "Back to album catalog" }));
    fireEvent.click(choose);
    fireEvent.click(screen.getByRole("button", { name: "Photo slot 1: empty" }));
    await screen.findByText("Photos saved, but the collection could not be refreshed. Retry reads only.");
    fireEvent.click(screen.getByRole("button", { name: "Retry photos" }));
    await waitFor(() => {
      expect(screen.queryByRole("button", { name: "Retry photos" })).not.toBeInTheDocument();
    });

    expect(departure.defaultPrevented).toBe(false);
    expect(client.editPlacements).toHaveBeenCalledTimes(1);
    expect(client.chapters).toHaveBeenCalledTimes(3);
  });

  it.each([new Error("connection lost"), new AlbumRequestError(500, "server failure")])(
    "reconciles an uncertain spread outcome without replaying %s",
    async (failure) => {
      const client = buildClient({
        addSpread: vi.fn().mockRejectedValue(failure),
        chapters: vi.fn().mockResolvedValueOnce(workspace.chapters).mockRejectedValueOnce(new Error("offline")).mockResolvedValue(workspace.chapters),
      });
      render(() => <AlbumOpen id={album.id} client={client} events={events} />);
      fireEvent.click(await screen.findByRole("link", { name: "Kyoto" }));
      fireEvent.click(screen.getByRole("button", { name: "Add spread" }));
      fireEvent.click(await screen.findByRole("button", { name: "Reload saved workspace" }));
      await screen.findByText("Saved workspace could not be refreshed. Displayed photos may be out of date.");
      fireEvent.click(screen.getByRole("button", { name: "Reload saved workspace" }));
      await waitFor(() => {
        expect(screen.queryByRole("button", { name: "Reload saved workspace" })).not.toBeInTheDocument();
      });

      expect(client.addSpread).toHaveBeenCalledTimes(1);
      expect(client.chapters).toHaveBeenCalledTimes(3);
    },
  );

  it("should add after selection, reload authoritative geometry and retain the deleted spread's album position", async () => {
    const inserted = { ...vacationPhoto, id: "inserted" };
    const added = { ...workspace, spreads: [opening, vacationOpening, inserted, ...workspace.spreads.slice(2)] };
    const client = buildClient(
      {
        composition: vi
          .fn()
          .mockResolvedValueOnce(membership(workspace.spreads))
          .mockResolvedValueOnce(membership(added.spreads))
          .mockResolvedValue(membership(workspace.spreads)),
        addSpread: vi.fn().mockResolvedValue("inserted"),
        deleteSpread: vi.fn().mockResolvedValue(undefined),
      },
      added,
    );
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("link", { name: "Kyoto" }));
    fireEvent.click(screen.getByRole("button", { name: "Add spread" }));
    await waitFor(() => {
      expect(window.location.search).toBe("?part=inserted");
    });

    expect(client.addSpread).toHaveBeenCalledWith(album.id, vacation.id, "vacation-opening");
    expect(client.composition).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByRole("button", { name: "Delete spread" }));
    await waitFor(() => {
      expect(window.location.search).toBe("?part=vacation-photo");
    });
    expect(client.deleteSpread).toHaveBeenCalledWith(album.id, "inserted", false);
    expect(client.composition).toHaveBeenCalledTimes(3);
  });

  it.each(["next-photo", "next-chapter", "last"] as const)("should retain the deletion index at %s across authoritative reload", async (position) => {
    const first = { ...vacationPhoto, id: "first" };
    const selected = { ...vacationPhoto, id: "selected" };
    const next = { ...vacationPhoto, id: "next" };
    const homeOpening = { ...opening, kind: "chapter_start" as const, chapter: home.id, id: "home-opening" };
    const spreads =
      position === "next-photo"
        ? [opening, vacationOpening, first, selected, next, homeOpening]
        : position === "next-chapter"
          ? [opening, vacationOpening, first, selected, homeOpening]
          : [opening, vacationOpening, first, homeOpening, { ...first, id: "home-first", chapter: home.id }, { ...selected, chapter: home.id }];
    const remaining = spreads.filter((spread) => spread.id !== selected.id);
    const client = buildClient(
      {
        composition: vi.fn().mockResolvedValueOnce(membership(spreads)).mockResolvedValue(membership(remaining)),
        deleteSpread: vi.fn().mockResolvedValue(undefined),
      },
      { ...workspace, spreads },
    );
    const expectedPart = position === "next-photo" ? "next" : position === "last" ? "home-first" : "home";
    window.history.replaceState(null, "", "?part=selected");
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);

    fireEvent.click(await screen.findByRole("button", { name: "Delete spread" }));

    await waitFor(() => {
      expect(window.location.search).toBe(`?part=${expectedPart}`);
    });
    expect(client.deleteSpread).toHaveBeenCalledExactlyOnceWith(album.id, selected.id, false);
  });

  it.each(["template", "delete"] as const)(
    "should confirm occupied %s only explicitly, preserve cancellation and restore focus through retry",
    async (kind) => {
      const operation = vi
        .fn<(...args: (string | boolean)[]) => Promise<void>>()
        .mockRejectedValueOnce(new SpreadOccupiedError(7))
        .mockRejectedValueOnce(new SpreadOccupiedError(7))
        .mockRejectedValueOnce(new AlbumRequestError(400, "Try again"))
        .mockResolvedValue(undefined);
      const client = buildClient({
        composition:
          kind === "delete"
            ? vi
                .fn()
                .mockResolvedValueOnce(membership(workspace.spreads))
                .mockResolvedValue(membership(workspace.spreads.filter((spread) => spread.id !== vacationPhoto.id)))
            : vi.fn().mockResolvedValue(membership(workspace.spreads)),
        changeSpreadTemplate: kind === "template" ? operation : vi.fn(),
        deleteSpread: kind === "delete" ? operation : vi.fn(),
        templates: vi.fn().mockResolvedValue([
          { id: "layout", name: "Applied", slots: [photoSlot] },
          { id: "other", name: "Other", slots: [photoSlot] },
        ]),
      });
      window.history.replaceState(null, "", "?part=vacation-photo");
      render(() => <AlbumOpen id={album.id} client={client} events={events} />);
      const opener = await screen.findByRole("button", { name: kind === "template" ? "Other" : "Delete spread" });
      fireEvent.click(opener);
      const warning = await screen.findByRole("alertdialog");

      expect(warning).toHaveTextContent("7 placements");
      expect(within(warning).getByRole("button", { name: "Cancel" })).toHaveFocus();
      fireEvent(warning, new Event("cancel", { cancelable: true }));
      await waitFor(() => expect(opener).toHaveFocus());
      expect(operation).toHaveBeenCalledTimes(1);
      expect(client.composition).toHaveBeenCalledOnce();
      expect(window.location.search).toBe("?part=vacation-photo");

      fireEvent.click(opener);
      fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Confirm" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("Try again");
      fireEvent.click(screen.getByRole("button", { name: "Retry confirmation" }));
      await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
      expect(operation.mock.calls.map((call) => call.at(-1))).toEqual([false, false, true, true]);
      expect(client.composition).toHaveBeenCalledTimes(2);
      await waitFor(() => {
        expect(window.location.search).toBe(kind === "delete" ? "?part=home" : "?part=vacation-photo");
      });
    },
  );

  it.each([
    ["album_start", null, "Synopsis"],
    ["chapter_start", "vacation", "Kyoto"],
    ["photos_only", "vacation-photo", "Kyoto · Photo spread"],
  ] as const)(
    "should load only %s layouts, derive photo categories, follow applied identity and hide the selector on Cover",
    async (context, part, heading) => {
      const two = { ...photoSlot, id: "second" };
      const client = buildClient({
        templates: vi.fn().mockResolvedValue([
          { id: "one", name: "One photo", slots: [photoSlot, textSlot] },
          { id: "layout", name: "Applied two photos", slots: [photoSlot, two, textSlot] },
          { id: "ten", name: "Many photos", slots: Array.from({ length: 11 }, () => photoSlot) },
        ]),
      });
      window.history.replaceState(null, "", part === null ? "/albums/album" : `?part=${part}`);
      render(() => <AlbumOpen id={album.id} client={client} events={events} />);
      await screen.findByRole("heading", { name: heading });
      const applied = await screen.findByRole("button", { name: "Applied two photos" });
      const categories = within(screen.getByRole("group", { name: "Photo slot count" }));

      expect(client.templates).toHaveBeenCalledWith(context);
      expect(categories.getAllByRole("button").map((button) => button.textContent)).toEqual(["1", "2", "10+"]);
      expect(applied).toHaveAttribute("aria-pressed", "true");
      expect(categories.getByRole("button", { name: "2" })).toHaveAttribute("aria-pressed", "true");
      fireEvent.click(categories.getByRole("button", { name: "1" }));
      expect(screen.getByRole("button", { name: "One photo" })).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "All" })).not.toBeInTheDocument();
      fireEvent.click(screen.getByRole("button", { name: "Cover" }));
      expect(screen.queryByRole("region", { name: "Spread template selector" })).not.toBeInTheDocument();
    },
  );

  it("should retry catalog and mutation failures without losing selection and retry only reload after a successful add", async () => {
    const inserted = { ...vacationPhoto, id: "inserted" };
    const client = buildClient(
      {
        templates: vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValue([]),
        addSpread: vi.fn().mockRejectedValueOnce(new AlbumRequestError(404, "Stale spread")).mockResolvedValue("inserted"),
        chapters: vi.fn().mockResolvedValueOnce(workspace.chapters).mockRejectedValueOnce(new Error("offline")).mockResolvedValue(workspace.chapters),
        composition: vi
          .fn()
          .mockResolvedValueOnce(membership(workspace.spreads))
          .mockResolvedValue(membership([opening, vacationOpening, vacationPhoto, inserted, ...workspace.spreads.slice(3)])),
      },
      { ...workspace, spreads: [...workspace.spreads, inserted] },
    );

    window.history.replaceState(null, "", "?part=vacation-photo");
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Retry templates" }));
    await waitFor(() => {
      expect(client.templates).toHaveBeenCalledTimes(2);
    });
    fireEvent.click(screen.getByRole("button", { name: "Add spread" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Stale spread");
    expect(window.location.search).toBe("?part=vacation-photo");
    fireEvent.click(screen.getByRole("button", { name: "Retry spread action" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Spread saved, but");
    expect(window.location.search).toBe("?part=vacation-photo");
    await waitFor(() => {
      expect(screen.getByRole("region", { name: "Album workspace" })).toHaveFocus();
    });
    fireEvent.click(screen.getByRole("button", { name: "Retry workspace" }));
    await waitFor(() => {
      expect(window.location.search).toBe("?part=inserted");
    });
    expect(client.addSpread).toHaveBeenCalledTimes(2);
    expect(client.chapters).toHaveBeenCalledTimes(3);
  });

  it.each(["offline", "empty"] as const)(
    "should retain a committed deletion's index through an %s reload and retry only the read",
    async (failure) => {
      const remaining = { ...workspace, spreads: workspace.spreads.filter((spread) => spread.id !== vacationPhoto.id) };
      const reads = vi.fn().mockResolvedValueOnce(membership(workspace.spreads));
      if (failure === "offline") reads.mockRejectedValueOnce(new Error("Offline"));
      else reads.mockResolvedValueOnce([]).mockResolvedValueOnce([]).mockResolvedValueOnce([]);
      reads.mockResolvedValue(membership(remaining.spreads));
      const client = buildClient({ composition: reads, deleteSpread: vi.fn().mockResolvedValue(undefined) });
      window.history.replaceState(null, "", "?part=vacation-photo");
      render(() => <AlbumOpen id={album.id} client={client} events={events} />);

      fireEvent.click(await screen.findByRole("button", { name: "Delete spread" }));
      await screen.findByRole("alert");
      expect(window.location.search).toBe("?part=vacation-photo");
      fireEvent.click(screen.getByRole("button", { name: "Retry workspace" }));

      await waitFor(() => {
        expect(window.location.search).toBe("?part=home");
      });
      expect(client.deleteSpread).toHaveBeenCalledExactlyOnceWith(album.id, vacationPhoto.id, false);
      expect(reads).toHaveBeenCalledTimes(failure === "empty" ? 5 : 3);
    },
  );

  it("should keep movement controls visible with chapter-local endpoint reasons and reload after movement", async () => {
    const second = { ...vacationPhoto, id: "second-photo" };
    const withSecond = { ...workspace, spreads: [...workspace.spreads.slice(0, 3), second, ...workspace.spreads.slice(3)] };
    const moved = { ...workspace, spreads: [opening, vacationOpening, second, vacationPhoto, ...workspace.spreads.slice(3)] };
    const client = buildClient(
      {
        composition: vi.fn().mockResolvedValueOnce(membership(withSecond.spreads)).mockResolvedValue(membership(moved.spreads)),
        replaceChapterComposition: vi.fn().mockResolvedValue(undefined),
      },
      withSecond,
    );
    window.history.replaceState(null, "", "?part=vacation-photo");
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    const earlier = await screen.findByRole("button", { name: "Shift spread left" });
    const later = screen.getByRole("button", { name: "Shift spread right" });

    expect(earlier).toBeDisabled();
    expect(earlier).toHaveAccessibleDescription("This is the first photo spread in its chapter.");
    fireEvent.click(later);
    await waitFor(() => expect(later).toHaveAccessibleDescription("This is the last photo spread in its chapter."));
    expect(client.replaceChapterComposition).toHaveBeenCalledWith(
      album.id,
      vacation.id,
      ["vacation-opening", "vacation-photo", "second-photo"],
      ["vacation-opening", "second-photo", "vacation-photo"],
    );
    expect(window.location.search).toBe("?part=vacation-photo");
    fireEvent.click(screen.getByRole("link", { name: "Home" }));
    expect(earlier).toHaveAccessibleDescription("Select a photo spread first.");
    expect(later).toBeDisabled();
    fireEvent.click(screen.getByRole("link", { name: "Kyoto · Photo spread 2" }));
    fireEvent.click(earlier);
    await waitFor(() => {
      expect(client.replaceChapterComposition).toHaveBeenCalledTimes(2);
    });
    expect(client.replaceChapterComposition).toHaveBeenLastCalledWith(
      album.id,
      vacation.id,
      ["vacation-opening", "second-photo", "vacation-photo"],
      ["vacation-opening", "vacation-photo", "second-photo"],
    );
  });

  it("should reload a concurrently changed chapter composition without replaying the rejected editor intent", async () => {
    const second = { ...vacationPhoto, id: "second-photo" };
    const displayed = { ...workspace, spreads: [...workspace.spreads.slice(0, 3), second, ...workspace.spreads.slice(3)] };
    const changed = { ...workspace, spreads: [opening, vacationOpening, second, vacationPhoto, ...workspace.spreads.slice(3)] };
    const client = buildClient(
      {
        composition: vi.fn().mockResolvedValueOnce(membership(displayed.spreads)).mockResolvedValue(membership(changed.spreads)),
        replaceChapterComposition: vi.fn().mockRejectedValue(new AlbumRequestError(409, "Composition changed")),
      },
      displayed,
    );
    window.history.replaceState(null, "", "?part=vacation-photo");
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Shift spread right" }));
    const reload = await screen.findByRole("button", { name: "Reload saved workspace" });
    expect(screen.getByRole("alert")).toHaveTextContent("This chapter's composition changed.");
    expect(screen.getByRole("alert")).not.toHaveTextContent("outcome is uncertain");
    fireEvent.click(reload);
    await waitFor(() => {
      expect(screen.queryByRole("button", { name: "Reload saved workspace" })).not.toBeInTheDocument();
    });
    expect(client.replaceChapterComposition).toHaveBeenCalledExactlyOnceWith(
      album.id,
      vacation.id,
      ["vacation-opening", "vacation-photo", "second-photo"],
      ["vacation-opening", "second-photo", "vacation-photo"],
    );
  });

  it.each(["resolve", "reject", "reload"] as const)("should discard a late spread %s after leaving the workspace", async (outcome) => {
    let resolve: (value: string) => void = () => {
      throw new Error("Missing operation");
    };
    let reject: (error: Error) => void = () => {
      throw new Error("Missing operation");
    };
    const pending = new Promise<string>((success, failure) => {
      resolve = success;
      reject = failure;
    });
    const client = buildClient({
      addSpread: outcome === "reload" ? vi.fn().mockResolvedValue("inserted") : vi.fn().mockReturnValue(pending),
      chapters:
        outcome === "reload"
          ? vi.fn().mockResolvedValueOnce(workspace.chapters).mockReturnValue(pending)
          : vi.fn().mockResolvedValue(workspace.chapters),
    });
    window.history.replaceState(null, "", "?part=vacation-photo");
    const view = render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add spread" }));
    if (outcome === "reload")
      await waitFor(() => {
        expect(client.chapters).toHaveBeenCalledTimes(2);
      });

    view.unmount();
    if (outcome === "reject") reject(new Error("Late failure"));
    else resolve("inserted");
    await pending.catch(() => undefined);
    expect(window.location.search).toBe("?part=vacation-photo");
  });

  it("should cancel an occupied action with the Cancel button and ignore Escape during its confirmed request", async () => {
    let finish: () => void = () => {
      throw new Error("Missing request");
    };
    const pending = new Promise<void>((resolve) => {
      finish = resolve;
    });
    const client = buildClient({
      deleteSpread: vi
        .fn()
        .mockRejectedValueOnce(new SpreadOccupiedError(2))
        .mockRejectedValueOnce(new SpreadOccupiedError(3))
        .mockReturnValue(pending),
    });
    window.history.replaceState(null, "", "?part=vacation-photo");
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Delete spread" }));
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Cancel" }));
    fireEvent.click(screen.getByRole("button", { name: "Delete spread" }));
    const warning = await screen.findByRole("alertdialog");
    expect(warning).toHaveTextContent("3 placements");
    fireEvent.click(within(warning).getByRole("button", { name: "Confirm" }));
    fireEvent(warning, new Event("cancel", { cancelable: true }));

    expect(warning).toBeInTheDocument();
    expect(within(warning).getByRole("button", { name: "Confirm" })).toHaveAccessibleDescription("Saving spread. Please wait.");
    finish();
    await waitFor(() => {
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    });
  });

  it("should ignore an obsolete failed catalog load and keep the newly selected context usable", async () => {
    let reject: (error: Error) => void = () => {
      throw new Error("Missing catalog");
    };
    const pending = new Promise<never>((_resolve, failure) => {
      reject = failure;
    });
    const client = buildClient({
      templates: vi
        .fn()
        .mockReturnValueOnce(pending)
        .mockResolvedValue([{ id: "layout", name: "Chapter layout", slots: [photoSlot] }]),
    });
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("link", { name: "Kyoto" }));
    await screen.findByRole("button", { name: "Chapter layout" });

    reject(new Error("Obsolete catalog failure"));
    await pending.catch(() => undefined);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Chapter layout" }));
    expect(client.changeSpreadTemplate).not.toHaveBeenCalled();
  });

  it("should select chapter-grouped spreads below the canvas, remember interiors, restore Back and recover invalid selections", async () => {
    const client = buildClient();

    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    await screen.findByRole("heading", { level: 1, name: album.title });
    const controls = within(screen.getByRole("group", { name: "Workspace controls" }));
    const organization = screen.getByRole("navigation", { name: "Album organization" });
    const links = within(organization).getAllByRole("link");
    const expectedLinks = ["Synopsis", "Kyoto", "Kyoto · Photo spread 1", "Home"];

    expect(screen.getByRole("img", { name: "Synopsis template geometry" })).toHaveAttribute("viewBox", "0 0 2381.102 765.354");
    expect(screen.getByRole("group", { name: "Synopsis composition" })).toHaveTextContent(album.synopsis);
    expect(screen.getByRole("group", { name: "Synopsis composition" })).toHaveTextContent(album.title);
    expect(controls.getAllByRole("button").map((button) => button.textContent)).toEqual(["", "Spreads", "Cover"]);
    expect(screen.queryByText("Photo")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled();
    expect(links.map((link) => link.getAttribute("aria-label"))).toEqual(expectedLinks);

    fireEvent.click(screen.getByRole("link", { name: "Kyoto" }));

    expect(screen.getByRole("heading", { name: "Kyoto" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Kyoto composition" })).toHaveTextContent(vacation.description);
    expect(screen.getByRole("group", { name: "Kyoto composition" })).toHaveTextContent(vacation.name);
    expect(screen.queryByRole("button", { name: /Change chapter/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit chapter details" })).toBeInTheDocument();
    expect(screen.getByText("Country", { exact: true })).toBeInTheDocument();
    expect(window.location.search).toBe("?part=vacation");

    fireEvent.click(screen.getByRole("link", { name: "Kyoto · Photo spread 1" }));

    expect(screen.getByRole("heading", { name: "Kyoto · Photo spread" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Cover" }));

    expect(screen.queryByRole("navigation", { name: "Album organization" })).not.toBeInTheDocument();
    expect(screen.getByRole("group", { name: /Cover: back on the left; front on the right/ })).toHaveAccessibleName(/Saved free-form album title/);
    expect(screen.getByText("by Cosmin, Mara & Iacob · 2026")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Spreads" }));

    expect(window.location.search).toBe("?part=vacation-photo");

    fireEvent.click(screen.getByRole("link", { name: "Synopsis" }));

    expect(window.location.search).toBe("");

    window.history.replaceState(null, "", "?part=home");
    fireEvent(window, new PopStateEvent("popstate"));

    expect(screen.getByRole("heading", { name: "Home" })).toBeInTheDocument();

    window.history.replaceState(null, "", "?part=cover");
    fireEvent(window, new PopStateEvent("popstate"));

    expect(screen.getByRole("heading", { name: "Cover" })).toBeInTheDocument();

    window.history.replaceState(null, "", "?part=foreign");
    fireEvent(window, new PopStateEvent("popstate"));

    expect(screen.getByRole("alert")).toHaveTextContent("This album part could not be found.");

    fireEvent.click(screen.getByRole("button", { name: "Go to Synopsis" }));

    expect(screen.getByRole("heading", { name: "Synopsis" })).toBeInTheDocument();
  });

  it.each(["cover", "vacation-opening", "vacation-photo"])(
    "should restore a direct %s selection and collapse only photo controls, retaining right-column navigation",
    async (part) => {
      window.history.replaceState(null, "", `?part=${part}`);
      render(() => <AlbumOpen id={album.id} client={buildClient()} events={events} />);
      await screen.findByRole("heading", { level: 1, name: album.title });

      if (part === "cover") {
        fireEvent.click(screen.getByRole("button", { name: "Spreads" }));
        expect(screen.getByRole("heading", { name: "Synopsis" })).toBeInTheDocument();
      }
      const collapse = screen.getByRole("button", { name: "Collapse photo pane" });

      expect(collapse).toHaveTextContent(/^$/);

      fireEvent.click(collapse);

      expect(screen.getByRole("navigation", { name: "Album organization" })).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Refresh photos" })).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Expand photo pane" })).toHaveFocus();
      expect(window.location.search).toBe(part === "cover" ? "" : `?part=${part}`);
      fireEvent.click(screen.getByRole("button", { name: "Expand photo pane" }));
      expect(screen.getByRole("button", { name: "Collapse photo pane" })).toHaveFocus();
      expect(screen.getByRole("navigation", { name: "Album organization" })).toBeInTheDocument();
    },
  );

  it.each(["cover", "vacation"])(
    "should read from %s across chapter boundaries and retain the current URL, history and remembered interior on exit",
    async (part) => {
      let fullscreen: Element | null = null;
      const fullscreenDescriptor = Object.getOwnPropertyDescriptor(document, "fullscreenElement");
      const requestDescriptor = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "requestFullscreen");
      const exitDescriptor = Object.getOwnPropertyDescriptor(document, "exitFullscreen");
      Object.defineProperty(document, "fullscreenElement", { configurable: true, get: () => fullscreen });
      Object.defineProperty(HTMLElement.prototype, "requestFullscreen", {
        configurable: true,
        value: function (this: HTMLElement) {
          fullscreen = document.contains(this) ? this : null;
          document.dispatchEvent(new Event("fullscreenchange"));
          return Promise.resolve();
        },
      });
      Object.defineProperty(document, "exitFullscreen", {
        configurable: true,
        value: () => {
          fullscreen = null;
          document.dispatchEvent(new Event("fullscreenchange"));
          return Promise.resolve();
        },
      });
      window.history.replaceState(null, "", `?part=${part}`);
      const view = render(() => <AlbumOpen id={album.id} client={buildClient()} events={events} />);

      try {
        fireEvent.click(await screen.findByRole("button", { name: "Reader mode" }));
        const exit = await screen.findByRole("button", { name: "Exit reader" });
        const readerControls = within(screen.getByRole("group", { name: "Reader controls" }));

        if (part === "cover") {
          expect(readerControls.getByRole("button", { name: "Previous spread" })).toBeDisabled();

          fireEvent.keyDown(exit, { key: "ArrowRight" });

          expect(window.location.search).toBe("");

          fireEvent.click(readerControls.getByRole("button", { name: "Next spread" }));
        }

        expect(screen.getByRole("img", { name: "Kyoto composition" })).toBeInTheDocument();

        fireEvent.keyDown(exit, { key: "ArrowRight" });

        expect(window.location.search).toBe("?part=vacation-photo");

        fireEvent.keyDown(exit, { key: "ArrowRight" });

        expect(screen.getByRole("img", { name: "Home composition" })).toBeInTheDocument();
        expect(readerControls.getByRole("button", { name: "Next spread" })).toBeDisabled();

        fireEvent.keyDown(exit, { key: "ArrowLeft" });

        expect(window.location.search).toBe("?part=vacation-photo");

        fireEvent.click(exit);

        await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toHaveFocus());
        expect(screen.getByRole("heading", { name: "Kyoto · Photo spread" })).toBeInTheDocument();
        expect(screen.queryByText(vacation.sourceFolder, { exact: true })).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", { name: "Cover" }));
        fireEvent.click(screen.getByRole("button", { name: "Spreads" }));

        expect(window.location.search).toBe("?part=vacation-photo");

        window.history.replaceState(null, "", "?part=home");
        fireEvent(window, new PopStateEvent("popstate"));

        expect(screen.getByRole("heading", { name: "Home" })).toBeInTheDocument();
      } finally {
        view.unmount();
        for (const [owner, key, descriptor] of [
          [document, "fullscreenElement", fullscreenDescriptor],
          [HTMLElement.prototype, "requestFullscreen", requestDescriptor],
          [document, "exitFullscreen", exitDescriptor],
        ] as const) {
          if (descriptor === undefined) Reflect.deleteProperty(owner, key);
          else Object.defineProperty(owner, key, descriptor);
        }
      }
    },
  );

  it("should create a missed vacation through catalog keyboard selection and refresh its persisted opening in server order", async () => {
    const empty = { ...workspace, chapters: [], spreads: [opening] };
    const missed = {
      ...vacation,
      id: "missed",
      name: "Rome",
      countries: ["ro", "it"],
      start: "2025-12-31",
      end: "2026-01-01",
      sourceFolder: "/nas/custom",
    };
    const saved = {
      ...empty,
      chapters: [missed],
      spreads: [opening, { ...vacationOpening, id: "missed-opening", chapter: missed.id }],
    };
    const client = buildClient(
      {
        chapters: vi.fn().mockResolvedValueOnce(empty.chapters).mockResolvedValue(saved.chapters),
        composition: vi.fn().mockResolvedValueOnce(membership(empty.spreads)).mockResolvedValue(membership(saved.spreads)),
        createChapter: vi.fn().mockResolvedValue({ id: missed.id }),
      },
      { ...saved, photoInventories: { [missed.id]: inventory } },
    );
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    const add = await screen.findByRole("button", { name: "Add chapter" });

    fireEvent.click(add);
    expect(screen.queryByRole("textbox", { name: "Location / name" })).not.toBeInTheDocument();
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Add chapter" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Choose a chapter type.");
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "vacation" } });
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "" } });
    expect(screen.queryByRole("textbox", { name: "Location / name" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "vacation" } });
    expect(screen.getByRole("button", { name: "Remove Romania" })).toBeInTheDocument();
    expect(screen.getByLabelText("First day")).toHaveValue("01/01/2026");
    fireEvent.input(screen.getByRole("textbox", { name: "Location / name" }), { target: { value: "Rome" } });
    expect(screen.getByRole("textbox", { name: "Photo folder" })).toHaveValue("/mnt/photos/2026/selection/Rome");
    fireEvent.input(screen.getByRole("textbox", { name: "Photo folder" }), { target: { value: "/nas/custom" } });
    fireEvent.input(screen.getByRole("textbox", { name: "Location / name" }), { target: { value: " Rome " } });
    const search = screen.getByRole("combobox", { name: "Search countries" });
    fireEvent.input(search, { target: { value: "Atlantis" } });
    expect(screen.getByText(/No countries match/)).toBeInTheDocument();
    fireEvent.keyDown(search, { key: "ArrowDown" });
    await Promise.resolve();
    fireEvent.keyDown(search, { key: "Enter" });
    fireEvent.input(search, { target: { value: "it" } });
    fireEvent.keyDown(search, { key: "ArrowDown" });
    fireEvent.keyDown(search, { key: "Enter" });
    expect(screen.getByRole("option", { name: "Italy (IT) ✓" })).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(search, { key: "Escape" });
    fireEvent.keyDown(search, { key: "Escape" });
    fireEvent.keyDown(search, { key: "Tab" });
    fireEvent.keyDown(search, { key: "Enter" });
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    fireEvent.input(screen.getByLabelText("First day"), { target: { value: "31/12/2025" } });
    fireEvent.input(screen.getByLabelText("Last day"), { target: { value: "01/01/2026" } });
    expect(screen.getByRole("textbox", { name: "Photo folder" })).toHaveValue("/nas/custom");
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(await screen.findByRole("heading", { name: missed.name })).toBeInTheDocument();
    const expectedDetails = {
      kind: "vacation",
      name: "Rome",
      countries: ["ro", "it"],
      start: "2025-12-31",
      end: "2026-01-01",
      sourceFolder: "/nas/custom",
    };
    expect(client.createChapter).toHaveBeenCalledWith(album.id, expectedDetails);
    expect(window.location.search).toBe("?part=missed");
    expect(screen.getByRole("group", { name: "Rome composition" })).toBeInTheDocument();
    expect(screen.getByText("Countries", { exact: true })).toBeInTheDocument();
    expect(add).toHaveFocus();
  });

  it("should retain failed inputs, prevent duplicate submission, then keep a successful save distinct from a failed refresh", async () => {
    let rejectSave: (error: Error) => void = () => {
      throw new Error("Save was not requested");
    };
    const pending = new Promise<{ id: string }>((_resolve, reject) => {
      rejectSave = reject;
    });
    const changed = { ...vacation, name: "Earlier Kyoto", start: "2026-01-01", end: "2026-01-01" };
    const client = buildClient({
      chapters: vi
        .fn()
        .mockResolvedValueOnce(workspace.chapters)
        .mockRejectedValueOnce(new Error("Refresh offline"))
        .mockResolvedValueOnce([changed, home]),
      updateChapter: vi.fn().mockReturnValueOnce(pending).mockResolvedValueOnce({ id: vacation.id }),
    });
    window.history.replaceState(null, "", "?part=vacation-photo");
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    const edit = await screen.findByRole("button", { name: "Edit chapter details" });

    fireEvent.click(edit);
    expect(screen.queryByRole("combobox", { name: "Chapter type" })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Photo folder" })).toHaveValue(vacation.sourceFolder);
    expect(screen.getByRole("button", { name: "Remove Japan" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Remove Romania" })).not.toBeInTheDocument();
    fireEvent.input(screen.getByRole("textbox", { name: "Location / name" }), { target: { value: changed.name } });
    fireEvent.input(screen.getByLabelText("First day"), { target: { value: "01/01/2026" } });
    fireEvent.input(screen.getByLabelText("Last day"), { target: { value: "01/01/2026" } });
    const countrySearch = screen.getByRole("combobox", { name: "Search countries" });
    fireEvent.focus(countrySearch);
    fireEvent.input(countrySearch, { target: { value: "Romania" } });
    fireEvent.blur(countrySearch, { relatedTarget: screen.getByRole("button", { name: "Remove Japan" }) });
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    expect(screen.getByRole("option", { name: "Romania (RO)" })).toBeDisabled();
    expect(screen.getByRole("option", { name: "Romania (RO)" })).toHaveAccessibleDescription("Saving chapter. Please wait.");
    expect(screen.getByRole("button", { name: "Save chapter" })).toHaveAccessibleDescription("Saving chapter. Please wait.");
    expect(screen.getByRole("button", { name: "Save chapter" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    expect(client.updateChapter).toHaveBeenCalledTimes(1);
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    rejectSave(new AlbumRequestError(400, "Another chapter already uses that source folder"));
    expect(await screen.findByRole("alert")).toHaveTextContent("Another chapter");
    expect(screen.getByRole("textbox", { name: "Location / name" })).toHaveValue(changed.name);
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Chapter saved, but");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add chapter" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Retry workspace" }));

    expect(await screen.findByRole("heading", { name: "Earlier Kyoto · Photo spread" })).toBeInTheDocument();
    expect(window.location.search).toBe("?part=vacation-photo");
    expect(client.updateChapter).toHaveBeenCalledTimes(2);
  });

  it("should use exact Home fields and saved values, and open vacation creation directly after Home exists", async () => {
    const empty = { ...workspace, chapters: [], spreads: [opening] };
    const client = buildClient({
      chapters: vi.fn().mockResolvedValueOnce(empty.chapters).mockResolvedValue(workspace.chapters),
      composition: vi.fn().mockResolvedValueOnce(membership(empty.spreads)).mockResolvedValue(membership(workspace.spreads)),
    });
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add chapter" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "home" } });
    expect(screen.queryByRole("textbox", { name: "Location / name" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("First day")).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Photo folder" })).toHaveValue("/mnt/photos/2026/selection/Home");
    fireEvent.click(screen.getByRole("button", { name: "Remove Romania" }));
    expect(screen.getByRole("combobox", { name: "Search countries" })).toHaveFocus();
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Select at least one country.");
    const search = screen.getByRole("combobox", { name: "Search countries" });
    fireEvent.focus(search);
    fireEvent.keyDown(search, { key: "ArrowUp" });
    fireEvent.keyDown(search, { key: "ArrowDown" });
    fireEvent.keyDown(search, { key: "ArrowUp" });
    fireEvent.mouseDown(screen.getByRole("option", { name: "Romania (RO)" }));
    fireEvent.click(screen.getByRole("option", { name: "Romania (RO)" }));
    fireEvent.click(screen.getByRole("option", { name: "Romania (RO) ✓" }));
    fireEvent.click(screen.getByRole("option", { name: "Romania (RO)" }));
    fireEvent.blur(search, { relatedTarget: screen.getByRole("button", { name: "Remove Romania" }) });
    fireEvent.blur(search, { relatedTarget: screen.getByRole("textbox", { name: "Photo folder" }) });
    fireEvent.blur(search);
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    await screen.findByRole("navigation", { name: "Album organization" });
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(client.createChapter).toHaveBeenCalledWith(album.id, { kind: "home", countries: ["ro"], sourceFolder: "/mnt/photos/2026/selection/Home" });
    fireEvent.click(await screen.findByRole("link", { name: "Home" }));
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Edit chapter details" })).toBeEnabled();
    });
    fireEvent.click(screen.getByRole("button", { name: "Edit chapter details" }));
    expect(screen.queryByRole("combobox", { name: "Chapter type" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("First day")).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Photo folder" })).toHaveValue(home.sourceFolder);
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("button", { name: "Edit chapter details" })).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "Edit chapter details" }));
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    fireEvent.click(screen.getByRole("button", { name: "Edit chapter details" }));
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    fireEvent.click(screen.getByRole("button", { name: "Add chapter" }));
    expect(screen.queryByRole("combobox", { name: "Chapter type" })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Location / name" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  });

  it.each([64, 65])("should expose chapter creation capacity at %i persisted interior spreads", async (count) => {
    const spreads = [
      opening,
      vacationOpening,
      ...Array.from({ length: count - 3 }, (_, index) => ({ ...vacationPhoto, id: `spread-${String(index)}` })),
      ...workspace.spreads.slice(3),
    ];
    const finalChapter = { ...vacation, id: "final-chapter", name: "Final chapter" };
    const finalOpening = { ...opening, id: "final-opening", chapter: finalChapter.id, kind: "chapter_start" as const };
    const savedWorkspace = {
      ...workspace,
      chapters: [vacation, finalChapter, home],
      spreads: [...spreads.slice(0, -1), finalOpening, ...spreads.slice(-1)],
    };
    const client = buildClient(
      {
        chapters: vi.fn().mockResolvedValueOnce(workspace.chapters).mockResolvedValue(savedWorkspace.chapters),
        composition: vi.fn().mockResolvedValueOnce(membership(spreads)).mockResolvedValue(membership(savedWorkspace.spreads)),
        createChapter: vi.fn().mockResolvedValue({ id: finalChapter.id }),
      },
      { ...savedWorkspace, photoInventories: { ...workspace.photoInventories, [finalChapter.id]: inventory } },
    );
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    const add = await screen.findByRole("button", { name: "Add chapter" });

    if (count === 65) {
      expect(add).toBeDisabled();
      expect(add).toHaveAccessibleDescription("This album has 65 interior spreads. No more chapters can be added.");
    } else {
      expect(add).toBeEnabled();
      fireEvent.click(add);
      expect(screen.getByRole("dialog")).toBeInTheDocument();
      fireEvent.input(screen.getByRole("textbox", { name: "Location / name" }), { target: { value: finalChapter.name } });
      fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
      expect(await screen.findByRole("heading", { name: finalChapter.name })).toBeInTheDocument();
      await waitFor(() => {
        expect(screen.getByRole("region", { name: "Album workspace" })).toHaveFocus();
      });
      expect(add).toBeDisabled();
      expect(add).toHaveAccessibleDescription("This album has 65 interior spreads. No more chapters can be added.");
    }
  });

  it("should browse canonical chapter and album photos, apply unselected live changes and Refresh the selected scope", async () => {
    const photo = {
      id: "photo",
      filename: "Kyoto.jpg",
      capturedLocal: "2026-04-02T10:00",
      width: 1200,
      height: 800,
      caption: null,
      thumbnail: { url: "/thumbnails/key/version.jpg", width: 600, height: 400 },
    };
    const vacationPhotos = { ...inventory, photos: [photo], counts: { unplaced: 0, total: 1 } };
    const homePhotos = { ...inventory, photos: [{ ...photo, id: "home-photo", filename: "Home.jpg" }], counts: { unplaced: 1, total: 1 } };
    const savedWorkspace = { ...workspace, photoInventories: { vacation: vacationPhotos, home: homePhotos } };
    let receive: (event: PhotoEvent) => void = () => {
      throw new Error("Not subscribed");
    };
    const close = vi.fn();
    const liveEvents: AlbumPhotoEvents = {
      subscribe: (_id, listener) => {
        receive = listener;
        return close;
      },
    };
    const client = buildClient(
      {
        chapterPhotos: vi
          .fn()
          .mockResolvedValueOnce(vacationPhotos)
          .mockResolvedValueOnce(homePhotos)
          .mockRejectedValueOnce(new Error("Inventory unavailable"))
          .mockImplementation((id: string) =>
            Promise.resolve(id === home.id ? { ...homePhotos, processing: { ...inventory.processing, state: "running" } } : vacationPhotos),
          ),
      },
      savedWorkspace,
    );
    const view = render(() => <AlbumOpen id={album.id} client={client} events={liveEvents} />);

    expect(client.chapters).not.toHaveBeenCalled();
    receive({ kind: "subscribed" });
    await screen.findByRole("img", { name: "Kyoto.jpg" });
    expect(screen.getByRole("img", { name: "Home.jpg" })).toBeInTheDocument();
    expect(screen.getByText("1 unplaced / 2 total")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("link", { name: "Kyoto" }));
    expect(screen.queryByRole("img", { name: "Home.jpg" })).not.toBeInTheDocument();
    const refresh = screen.getByRole("button", { name: "Refresh photos" });
    refresh.focus();
    fireEvent.click(refresh);
    await waitFor(() => {
      expect(client.refreshPhotos).toHaveBeenCalledExactlyOnceWith(vacation.id);
    });
    receive({ kind: "inventory-changed", chapterId: home.id });
    await waitFor(() => {
      expect(client.chapterPhotos).toHaveBeenCalledWith(home.id, expect.any(AbortSignal));
    });
    expect(refresh).toHaveFocus();
    expect(window.location.search).toBe("?part=vacation");
    fireEvent.click(screen.getByRole("link", { name: "Synopsis" }));
    fireEvent.click(await screen.findByRole("button", { name: "Retry inventory for Home" }));
    await waitFor(() => expect(within(screen.getByRole("region", { name: "Processing Home" })).getByText(/running/)).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Cover" }));
    expect(screen.getByRole("img", { name: "Home.jpg" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Refresh photos" }));
    await waitFor(() => {
      expect(client.refreshPhotos).toHaveBeenCalledTimes(3);
    });
    receive({ kind: "interrupted" });
    expect(screen.getByText(/Live updates interrupted/)).toBeInTheDocument();
    receive({ kind: "subscribed" });
    await waitFor(() => expect(screen.queryByText(/Catching up/)).not.toBeInTheDocument());
    expect(client.chapters).toHaveBeenCalledTimes(2);
    expect(client.open).toHaveBeenCalledTimes(2);
    expect(window.location.search).toBe("?part=cover");
    view.unmount();
    expect(close).toHaveBeenCalledOnce();
  });

  it("should keep a chapter saved after scheduling failure and retry only photo Refresh", async () => {
    const emptyWorkspace = { ...workspace, chapters: [], spreads: [opening], photoInventories: {} };
    const savedWorkspace = {
      ...workspace,
      chapters: [home],
      spreads: [opening, ...workspace.spreads.filter((spread) => spread.chapter === home.id)],
      photoInventories: { home: inventory },
    };
    const client = buildClient(
      {
        chapters: vi.fn().mockResolvedValueOnce(emptyWorkspace.chapters).mockResolvedValue(savedWorkspace.chapters),
        composition: vi.fn().mockResolvedValueOnce(membership(emptyWorkspace.spreads)).mockResolvedValue(membership(savedWorkspace.spreads)),
        createChapter: vi.fn().mockResolvedValue({ id: home.id, scheduling: "failed" }),
      },
      savedWorkspace,
    );
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add chapter" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "home" } });
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    fireEvent.click(await screen.findByRole("button", { name: "Retry refresh for Home" }));

    await waitFor(() => {
      expect(client.refreshPhotos).toHaveBeenCalledExactlyOnceWith(home.id);
    });
    expect(client.createChapter).toHaveBeenCalledOnce();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Home" })).toBeInTheDocument();
  });

  it("should leave a superseding album untouched when an old chapter save completes after workspace exit", async () => {
    let finishSave: (result: { id: string; scheduling: "queued" }) => void = () => {
      throw new Error("Save not started");
    };
    const pending = new Promise<{ id: string; scheduling: "queued" }>((resolve) => {
      finishSave = resolve;
    });
    const emptyWorkspace = { ...workspace, chapters: [], spreads: [opening], photoInventories: {} };
    const client = buildClient({
      chapters: vi.fn().mockResolvedValue(emptyWorkspace.chapters),
      composition: vi.fn().mockResolvedValue(membership(emptyWorkspace.spreads)),
      open: vi
        .fn()
        .mockResolvedValueOnce(album)
        .mockResolvedValue({ ...album, id: "other", year: 2027 }),
      createChapter: vi.fn().mockReturnValue(pending),
    });
    const [id, changeAlbum] = createSignal(album.id);
    render(() => <AlbumOpen id={id()} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add chapter" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "home" } });
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    await waitFor(() => {
      expect(client.createChapter).toHaveBeenCalledOnce();
    });
    changeAlbum("other");
    await screen.findByRole("heading", { level: 1, name: album.title });
    finishSave({ id: "old-chapter", scheduling: "queued" });
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(client.chapters).toHaveBeenCalledTimes(2);
    expect(window.location.search).toBe("");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("should navigate First, Previous, Next and Last below the canvas with accessible endpoint context and no wrapping", async () => {
    let receive: (event: PhotoEvent) => void = () => {
      throw new Error("Not subscribed");
    };
    const liveEvents: AlbumPhotoEvents = {
      subscribe: (_id, listener) => {
        receive = listener;
        receive({ kind: "subscribed" });
        return vi.fn();
      },
    };
    const freshWorkspace = {
      ...workspace,
      chapters: workspace.chapters.map((chapter) => ({ ...chapter })),
      spreads: workspace.spreads.map((spread) => ({ ...spread })),
    };
    const client = buildClient({
      chapters: vi.fn().mockResolvedValueOnce(workspace.chapters).mockResolvedValue(freshWorkspace.chapters),
    });
    render(() => <AlbumOpen id={album.id} client={client} events={liveEvents} />);
    const first = await screen.findByRole("button", { name: "First spread" });
    const previous = screen.getByRole("button", { name: "Previous spread" });
    const next = screen.getByRole("button", { name: "Next spread" });
    const last = screen.getByRole("button", { name: "Last spread" });
    const addSpread = screen.getByRole("button", { name: "Add spread" });

    expect(addSpread).toBeDisabled();
    expect(addSpread).toHaveAccessibleDescription("Select a chapter opening or photo spread first.");
    expect(first).toBeDisabled();
    expect(previous).toBeDisabled();
    expect(first).toHaveAccessibleDescription("Spread 1 / 4");
    fireEvent.click(next);
    expect(window.location.search).toBe("?part=vacation");
    expect(screen.getByRole("link", { name: "Kyoto" })).toHaveAttribute("aria-current", "page");
    fireEvent.click(last);
    expect(window.location.search).toBe("?part=home");
    expect(next).toBeDisabled();
    expect(last).toHaveAccessibleDescription("Spread 4 / 4");
    fireEvent.click(previous);
    expect(window.location.search).toBe("?part=vacation-photo");
    fireEvent.click(first);
    expect(window.location.search).toBe("");
    const chapter = screen.getByRole("link", { name: "Kyoto" });
    fireEvent.click(chapter);
    chapter.focus();
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(client.chapters).toHaveBeenCalledTimes(2);
    });
    await waitFor(() => {
      expect(screen.queryByText(/Catching up/)).not.toBeInTheDocument();
    });
    expect(screen.getByRole("link", { name: "Kyoto" })).toBe(chapter);
    expect(chapter).toHaveFocus();
    const grip = screen.getByRole("button", { name: "Collapse photo pane" });
    grip.focus();
    fireEvent.keyDown(grip, { key: "ArrowRight" });
    expect(window.location.search).toBe("?part=vacation-photo");
    expect(grip).toHaveAccessibleDescription(/320px/);
    fireEvent.keyDown(grip, { key: "ArrowLeft" });
    expect(window.location.search).toBe("?part=vacation");
    fireEvent.click(screen.getByRole("button", { name: "Edit chapter details" }));
    fireEvent.keyDown(screen.getByRole("textbox", { name: "First day" }), { key: "ArrowRight" });
    expect(window.location.search).toBe("?part=vacation");
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    fireEvent.click(screen.getByRole("button", { name: "Cover" }));
    fireEvent.keyDown(screen.getByRole("button", { name: "Cover" }), { key: "ArrowRight" });
    expect(window.location.search).toBe("?part=cover");
  });
});

function buildClient(overrides: Partial<AlbumClient> = {}, saved: Workspace = workspace): AlbumClient {
  return {
    readPlacements: vi
      .fn()
      .mockImplementation((_album: string, context: PlacementContext): Promise<PlacementSnapshot> =>
        Promise.resolve(
          context.kind === "cover"
            ? { kind: "cover", placements: [], frontSlotId: "front", backSlotId: "back", spineOwner: "front" }
            : { kind: "spread", id: context.id, placements: [], layout: { kind: "photos_only", templateId: "layout", slots: [photoSlot] } },
        ),
      ),
    editPlacements: vi
      .fn()
      .mockImplementation((_album: string, context: PlacementContext): Promise<PlacementSnapshot> =>
        Promise.resolve(
          context.kind === "cover"
            ? { kind: "cover", placements: [], frontSlotId: "front", backSlotId: "back", spineOwner: "front" }
            : { kind: "spread", id: context.id, placements: [], layout: { kind: "photos_only", templateId: "layout", slots: [photoSlot] } },
        ),
      ),
    list: vi.fn(),
    create: vi.fn(),
    rename: vi.fn(),
    remove: vi.fn(),
    open: vi.fn().mockResolvedValue(album),
    chapters: vi.fn().mockResolvedValue(saved.chapters),
    composition: vi.fn().mockResolvedValue(membership(saved.spreads)),
    spread: vi.fn().mockImplementation((_album: string, id: string) => {
      const spread = saved.spreads.find((candidate) => candidate.id === id);
      if (spread === undefined) throw new Error("Unknown spread");
      return Promise.resolve(spread);
    }),
    countries: vi.fn().mockResolvedValue(saved.countries),
    photoSource: vi.fn().mockResolvedValue(saved.photoRoot),
    chapterPhotos: vi.fn().mockImplementation((id: string) => {
      const photos = saved.photoInventories[id];
      if (photos === undefined) throw new Error("Unknown chapter inventory");
      return Promise.resolve(photos);
    }),
    refreshPhotos: vi.fn().mockResolvedValue("queued"),
    createChapter: vi.fn().mockResolvedValue({ id: home.id, scheduling: "queued" }),
    updateChapter: vi.fn().mockResolvedValue({ id: vacation.id, scheduling: "not-requested" }),
    templates: vi.fn().mockResolvedValue([{ id: "layout", name: "Applied layout", slots: [photoSlot, textSlot] }]),
    addSpread: vi.fn(),
    replaceChapterComposition: vi.fn(),
    changeSpreadTemplate: vi.fn(),
    deleteSpread: vi.fn(),
    ...overrides,
  };
}

function membership(spreads: readonly Spread[]) {
  return spreads.map(({ id, chapter }) => ({ id, chapter }));
}
