import { coverGeometries } from "./CoverGeometry";
import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { AlbumOpen } from "../../../src/features/albums/open/AlbumOpen";
import { editingSession } from "../editing/EditingSessionStub";
import {
  AcknowledgedWriteError,
  AlbumRequestError,
  SpreadOccupiedError,
  type Album,
  type AlbumClient,
  type Author,
} from "../../../src/features/albums/model/Album";
import type { Chapter, ChapterDetails, Spread, SpreadContext as TemplateContext, Workspace } from "../../../src/features/albums/model/Workspace";
import type { CameraSelection } from "../../../src/features/albums/model/Gear";
import type { AlbumPhotoEvents, Photo, PhotoEvent, PhotoInventory } from "../../../src/features/albums/model/Photo";
import type {
  CoverSnapshot,
  PlacementContext,
  PlacementEdit,
  PlacementSnapshot,
  SpreadPlacement,
  SpreadSnapshot,
} from "../../../src/features/albums/model/Placement";

const album: Album = {
  id: "album",
  year: 2026,
  title: "Saved free-form album title",
  createdAt: "2026-10-25T12:00:00Z",
  synopsis: "Saved album synopsis",
  authors: ["Cosmin", "Mara", "Iacob"],
};
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
  bleed: { state: "disabled" as const, available: true },
  templateId: "layout",
  chapter: null,
  kind: "album_start" as const,
  slots: [photoSlot, textSlot],
  placements: [],
};
const vacationOpening = {
  id: "vacation-opening",
  bleed: { state: "disabled" as const, available: true },
  templateId: "layout",
  chapter: vacation.id,
  kind: "chapter_start" as const,
  slots: [photoSlot, textSlot],
  placements: [],
};
const vacationPhoto = {
  id: "vacation-photo",
  bleed: { state: "disabled" as const, available: true },
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
    {
      id: "home-opening",
      bleed: { state: "disabled" as const, available: true },
      templateId: "layout",
      chapter: home.id,
      kind: "chapter_start",
      slots: [textSlot],
      placements: [],
    },
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
  it("should read a missing chapter again before resuming its newest history action", async () => {
    const built = buildHistoryBook();
    window.history.replaceState(null, "", "?part=vacation");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
    const edit = await screen.findByRole("button", { name: "Edit chapter details" });
    await waitFor(() => expect(edit).toBeEnabled());
    fireEvent.click(edit);
    await waitFor(() => expect(screen.getByRole("button", { name: "Save chapter" })).toBeEnabled());
    fireEvent.input(screen.getByRole("textbox", { name: "Location / name" }), { target: { value: "Saved new name" } });
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Undo: Edit chapter details and gear" })).toBeEnabled());
    vi.mocked(built.client.chapters).mockResolvedValueOnce([]);

    await clickHistory("Undo: Edit chapter details and gear");

    const recovery = await screen.findByRole("button", { name: "Read saved history action" });
    expect(built.client.updateChapter).toHaveBeenCalledOnce();
    await waitFor(() => expect(recovery).toBeEnabled());
    fireEvent.click(recovery);
    await clickHistory("Undo: Edit chapter details and gear");
    await waitFor(() => {
      expect(built.chapters()[0]?.name).toBe(vacation.name);
    });
    expect(built.client.updateChapter).toHaveBeenCalledTimes(2);
  });

  it("should reject a retained spread confirmation when saved membership no longer contains its target", async () => {
    const built = buildHistoryBook();
    vi.mocked(built.client.deleteSpread).mockRejectedValueOnce(new SpreadOccupiedError(1));
    let receive: (event: PhotoEvent) => void = () => {
      throw new Error("Missing photo subscription.");
    };
    const liveEvents: AlbumPhotoEvents = {
      subscribe: (_id, listener) => {
        receive = listener;
        listener({ kind: "subscribed" });
        return vi.fn();
      },
    };
    window.history.replaceState(null, "", "?part=vacation-photo");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={liveEvents} />);
    const remove = await screen.findByRole("button", { name: "Delete spread" });
    await waitFor(() => expect(remove).toBeEnabled());
    fireEvent.click(remove);
    const dialog = await screen.findByRole("alertdialog", { name: "Delete occupied spread?" });
    await built.client.deleteSpread(album.id, vacationPhoto.id, true);
    receive({ kind: "interrupted" });
    receive({ kind: "subscribed" });
    await waitFor(() => expect(screen.queryByRole("link", { name: "Kyoto · Photo spread 1" })).not.toBeInTheDocument());

    fireEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));

    await within(dialog).findByText("The spread is no longer available.");
    expect(built.client.deleteSpread).toHaveBeenCalledTimes(2);
    expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled();
  });

  it("should retain a disconnected chapter draft and fence an author acknowledgement while the session is unavailable", async () => {
    const built = buildHistoryBook();
    const [available, setAvailable] = createSignal(true);
    const [lossRevision, setLossRevision] = createSignal(0);
    window.history.replaceState(null, "", "?part=vacation");
    render(() => <AlbumOpen session={{ ...editingSession, available, lossRevision }} id={album.id} client={built.client} events={events} />);
    const edit = await screen.findByRole("button", { name: "Edit chapter details" });
    await waitFor(() => expect(edit).toBeEnabled());
    fireEvent.click(edit);
    await waitFor(() => expect(screen.getByRole("button", { name: "Save chapter" })).toBeEnabled());
    setAvailable(false);
    setLossRevision(1);
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    await screen.findByText("Editing ownership is unavailable. The draft is retained.");
    expect(built.client.updateChapter).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    setAvailable(true);
    await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled());
    const authors = screen.getByRole("group", { name: "Album authors" });
    await waitFor(() => expect(within(authors).getByRole("button", { name: "Mara" })).toBeEnabled());
    let finish!: () => void;
    const pending = new Promise<void>((resolve) => {
      finish = resolve;
    });
    const save = vi.mocked(built.client.saveAuthors).getMockImplementation();
    if (save === undefined) throw new Error("Missing author writer.");
    vi.mocked(built.client.saveAuthors).mockImplementationOnce(async (...args) => {
      await pending;
      return save(...args);
    });
    fireEvent.click(within(authors).getByRole("button", { name: "Mara" }));
    await waitFor(() => {
      expect(built.client.saveAuthors).toHaveBeenCalledOnce();
    });
    setAvailable(false);
    setLossRevision(2);

    finish();

    await waitFor(() => expect(authors).toHaveAttribute("aria-busy", "false"));
    await waitFor(() => {
      expect(built.metadata().authors).toEqual(["Cosmin", "Iacob"]);
    });
    expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled();
  });

  it("should save gear for a newly acknowledged chapter without restoring generated identities", async () => {
    const phone = { id: "phone", code: "phone", name: "Phone", kind: "fixed_lens" as const };
    const selection = [{ cameraId: phone.id, lensIds: [] }];
    const client = buildClient({
      chapters: vi.fn().mockResolvedValueOnce([vacation]).mockResolvedValue(workspace.chapters),
      composition: vi
        .fn()
        .mockResolvedValueOnce(membership(workspace.spreads.slice(0, 3)))
        .mockResolvedValue(membership(workspace.spreads)),
      createChapter: vi.fn().mockResolvedValue({ id: home.id, scheduling: "not-requested" }),
      gearCatalog: vi.fn().mockResolvedValue({ cameras: [phone], lenses: [] }),
      chapterGear: vi.fn().mockResolvedValue(selection),
      saveChapterGear: vi.fn().mockResolvedValue(undefined),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    const add = await screen.findByRole("button", { name: "Add chapter" });
    await waitFor(() => expect(add).toBeEnabled());
    fireEvent.click(add);
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "home" } });
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Add camera" })).toBeEnabled());
    fireEvent.change(screen.getByRole("combobox", { name: "Add camera" }), { target: { value: phone.id } });

    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Add chapter" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(client.saveChapterGear).toHaveBeenCalledExactlyOnceWith(album.id, home.id, selection);
    expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled();
  });

  it.each(["revert", "replace"] as const)(
    "should record only changed saved authors after a rejected selection is corrected by %s",
    async (correction) => {
      const built = buildHistoryBook();
      await built.client.saveAuthors(album.id, ["Cosmin", "Mara"]);
      vi.mocked(built.client.saveAuthors).mockRejectedValueOnce(new AlbumRequestError(400, "Rejected authors"));
      render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
      const authors = await screen.findByRole("group", { name: "Album authors" });
      const mara = within(authors).getByRole("button", { name: "Mara" });
      await waitFor(() => expect(mara).toBeEnabled());
      fireEvent.click(mara);
      await screen.findByRole("button", { name: "Retry authors" });

      fireEvent.click(within(authors).getByRole("button", { name: correction === "revert" ? "Mara" : "Iacob" }));

      if (correction === "revert") {
        await waitFor(() => {
          expect(built.metadata().authors).toEqual(["Cosmin", "Mara"]);
        });
        await waitFor(() => expect(screen.queryByRole("button", { name: "Retry authors" })).not.toBeInTheDocument());
        expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled();
      } else {
        await waitFor(() => expect(screen.getByRole("button", { name: "Undo: Change authors" })).toBeEnabled());
        expect(built.metadata().authors).toEqual(["Cosmin", "Iacob"]);
        await clickHistory("Undo: Change authors");
        await waitFor(() => {
          expect(built.metadata().authors).toEqual(["Cosmin", "Mara"]);
        });
      }
    },
  );

  it.each([
    ...(["metadata", "gear"] as const).flatMap((portion) =>
      [false, true].map((loseAccess) => ({ portion, loseAccess, recovery: "stable" as const })),
    ),
    { portion: "gear" as const, loseAccess: true, recovery: "lost-read" as const },
    { portion: "gear" as const, loseAccess: true, recovery: "missing-chapter" as const },
  ])(
    "should undo reviewed chapter $portion to the independently read baseline after access loss=$loseAccess and $recovery recovery",
    async ({ portion, loseAccess, recovery }) => {
      const built = buildHistoryBook();
      const [lossRevision, setLossRevision] = createSignal(0);
      const phone = { id: "phone", code: "phone", name: "Phone", kind: "fixed_lens" as const };
      const independentGear = [{ cameraId: phone.id, lensIds: [] }];
      const desiredGear = [{ cameraId: "body", lensIds: ["lens"] }];
      if (portion === "metadata") vi.mocked(built.client.updateChapter).mockRejectedValueOnce(new Error("Lost metadata response"));
      else {
        vi.mocked(built.client.saveChapterGear).mockRejectedValueOnce(new Error("Lost gear response"));
        const readCatalog = vi.mocked(built.client.gearCatalog).getMockImplementation();
        if (readCatalog === undefined) throw new Error("Missing gear catalog reader.");
        vi.mocked(built.client.gearCatalog).mockImplementation(async (signal) => {
          const catalog = await readCatalog(signal);
          return { ...catalog, cameras: [...catalog.cameras, phone] };
        });
      }
      window.history.replaceState(null, "", "?part=vacation");
      render(() => <AlbumOpen session={{ ...editingSession, lossRevision }} id={album.id} client={built.client} events={events} />);
      const edit = await screen.findByRole("button", { name: "Edit chapter details" });
      await waitFor(() => expect(edit).toBeEnabled());
      fireEvent.click(edit);
      await waitFor(() => expect(screen.getByRole("button", { name: "Save chapter" })).toBeEnabled());
      fireEvent.input(screen.getByRole("textbox", { name: "Location / name" }), { target: { value: "My retained name" } });
      if (portion === "gear") {
        fireEvent.change(screen.getByRole("combobox", { name: "Add camera" }), { target: { value: "body" } });
        fireEvent.change(screen.getByRole("combobox", { name: "Add lens for Body" }), { target: { value: "lens" } });
      }
      fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
      await screen.findByRole("button", { name: "Read saved chapter" });
      if (loseAccess) setLossRevision(1);
      if (portion === "metadata") await built.client.updateChapter(album.id, vacation.id, { ...vacation, name: "Independent name" });
      else await built.client.saveChapterGear(album.id, vacation.id, independentGear);
      if (recovery === "missing-chapter") vi.mocked(built.client.chapters).mockResolvedValue([]);
      if (recovery === "lost-read") {
        const read = vi.mocked(built.client.chapterGear).getMockImplementation();
        if (read === undefined) throw new Error("Missing gear reader.");
        vi.mocked(built.client.chapterGear).mockImplementationOnce(async (...args) => {
          const saved = await read(...args);
          setLossRevision(2);
          return saved;
        });
      }
      fireEvent.click(screen.getByRole("button", { name: "Read saved chapter" }));
      if (recovery !== "stable") {
        await screen.findByText(
          recovery === "missing-chapter" ? /The saved chapter could not be found/ : /Editing ownership changed while reading saved content/,
        );
        if (recovery === "missing-chapter") vi.mocked(built.client.chapters).mockResolvedValue(built.chapters());
        else await built.client.updateChapter(album.id, vacation.id, { ...vacation, name: "Independent during recovery" });
        fireEvent.click(screen.getByRole("button", { name: "Read saved chapter" }));
      }
      const save = await screen.findByRole("button", { name: portion === "metadata" ? "Save chapter" : "Save gear" });
      await waitFor(() => expect(save).toBeEnabled());

      fireEvent.click(save);
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      await clickHistory("Undo: Edit chapter details and gear");

      await waitFor(() => {
        expect(built.chapters()[0]?.name).toBe(
          portion === "metadata"
            ? "Independent name"
            : recovery === "lost-read"
              ? "Independent during recovery"
              : loseAccess
                ? "My retained name"
                : vacation.name,
        );
      });
      await waitFor(() => {
        expect(built.gear()).toEqual(portion === "gear" ? independentGear : []);
      });
      await clickHistory("Redo: Edit chapter details and gear");
      await waitFor(() => {
        expect(built.chapters()[0]?.name).toBe(recovery === "lost-read" ? "Independent during recovery" : "My retained name");
      });
      await waitFor(() => {
        expect(built.gear()).toEqual(portion === "gear" ? desiredGear : []);
      });
    },
  );

  it("should preserve older history when a rejected caption is explicitly saved unchanged", async () => {
    const placed = {
      kind: "spread" as const,
      id: "history-placement",
      photoId: "history-photo",
      slotId: photoSlot.id,
      transform: { x: 0, y: 0, zoom: 1, mirrored: false },
      captionCorner: "bottom_right" as const,
    };
    const built = buildHistoryBook([opening, { ...vacationOpening, placements: [placed] }, ...workspace.spreads.slice(2)], true);
    vi.mocked(built.client.saveCaption).mockRejectedValueOnce(new AlbumRequestError(400, "Rejected caption"));
    window.history.replaceState(null, "", "?part=vacation");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
    const authors = await screen.findByRole("group", { name: "Album authors" });
    await waitFor(() => expect(within(authors).getByRole("button", { name: "Mara" })).toBeEnabled());
    fireEvent.click(within(authors).getByRole("button", { name: "Mara" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Undo: Change authors" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Edit caption for History.jpg" }));
    const editor = screen.getByRole("textbox", { name: "Caption for History.jpg" });
    editor.textContent = "Rejected text";
    fireEvent.input(editor);
    fireEvent(window, new MouseEvent("pointermove", { clientX: -1, clientY: -1 }));
    const retry = await screen.findByRole("button", { name: "Retry caption" });
    editor.textContent = "Saved caption";
    fireEvent.input(editor);

    fireEvent.click(retry);

    await waitFor(() => expect(screen.getByRole("button", { name: "Undo: Change authors" })).toBeEnabled());
    expect(built.photo().caption).toBe("Saved caption");
    expect(built.client.saveCaption).toHaveBeenCalledTimes(2);
  });

  it.each([
    { destination: "album", missingPhoto: false },
    { destination: "spread", missingPhoto: false },
    { destination: "spread", missingPhoto: true },
  ])(
    "should recover a cleared $destination caption without overwriting a missing photograph ($missingPhoto)",
    async ({ destination, missingPhoto }) => {
      const placed = {
        kind: "spread" as const,
        id: "history-placement",
        photoId: "history-photo",
        slotId: photoSlot.id,
        transform: { x: 0, y: 0, zoom: 1, mirrored: false },
        captionCorner: "bottom_right" as const,
      };
      const built = buildHistoryBook(
        [
          { ...opening, placements: destination === "album" ? [placed] : [] },
          { ...vacationOpening, placements: destination === "album" ? [] : [placed] },
          ...workspace.spreads.slice(2),
        ],
        true,
      );
      window.history.replaceState(null, "", destination === "album" ? "/albums/album" : "?part=vacation");
      render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
      const caption = await screen.findByRole("button", { name: "Edit caption for History.jpg" });
      await waitFor(() => expect(caption).not.toHaveAttribute("aria-disabled", "true"));
      fireEvent.click(caption);
      const editor = screen.getByRole("textbox", { name: "Caption for History.jpg" });
      editor.textContent = "";
      fireEvent.input(editor);
      fireEvent(window, new MouseEvent("pointermove", { clientX: -1, clientY: -1 }));
      await waitFor(() => expect(screen.getByRole("button", { name: "Undo: Edit caption" })).toBeEnabled());
      if (missingPhoto) vi.mocked(built.client.chapterPhotos).mockResolvedValue(inventory);

      await clickHistory("Undo: Edit caption");

      if (missingPhoto) {
        await screen.findByRole("button", { name: "Read saved history action" });
        expect(built.client.saveCaption).toHaveBeenCalledOnce();
      } else {
        await waitFor(() => {
          expect(built.photo().caption).toBe("Saved caption");
        });
        await clickHistory("Redo: Edit caption");
        await waitFor(() => {
          expect(built.photo().caption).toBeNull();
        });
        expect(built.client.saveCaption).toHaveBeenCalledTimes(3);
      }
    },
  );

  it.each(["missing", "lost-read"] as const)("should stop history when its destination is %s during refresh", async (outcome) => {
    const placed = {
      kind: "spread" as const,
      id: "history-placement",
      photoId: "history-photo",
      slotId: photoSlot.id,
      transform: { x: 0, y: 0, zoom: 1, mirrored: false },
      captionCorner: "bottom_right" as const,
    };
    const built = buildHistoryBook([opening, vacationOpening, { ...vacationPhoto, placements: [placed] }, ...workspace.spreads.slice(3)], true);
    const [lossRevision, setLossRevision] = createSignal(0);
    window.history.replaceState(null, "", "?part=vacation-photo");
    render(() => <AlbumOpen session={{ ...editingSession, lossRevision }} id={album.id} client={built.client} events={events} />);
    const edit = await screen.findByRole("button", { name: "Edit caption for History.jpg" });
    await waitFor(() => expect(edit).not.toHaveAttribute("aria-disabled", "true"));
    fireEvent.click(edit);
    const editor = screen.getByRole("textbox", { name: "Caption for History.jpg" });
    editor.textContent = "Changed caption";
    fireEvent.input(editor);
    fireEvent(window, new MouseEvent("pointermove", { clientX: -1, clientY: -1 }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Undo: Edit caption" })).toBeEnabled());
    await built.client.deleteSpread(album.id, vacationPhoto.id, false);
    fireEvent.click(screen.getByRole("button", { name: "Cover" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Undo: Edit caption" })).toBeEnabled());
    if (outcome === "lost-read")
      vi.mocked(built.client.composition).mockImplementationOnce(() => {
        setLossRevision(1);
        return Promise.resolve(membership(built.spreads()));
      });

    await clickHistory("Undo: Edit caption");

    if (outcome === "lost-read") await screen.findByText(/Editing access was lost. Undo history has been cleared/);
    else await screen.findByText("The saved spread is no longer available.");
    expect(built.client.saveCaption).toHaveBeenCalledOnce();
  });

  it.each(["owned", "contended", "lost-selection", "lost-retry"] as const)(
    "should acquire cover history normally with $0 access and fence interrupted acquisition",
    async (access) => {
      const built = buildHistoryBook();
      const [lossRevision, setLossRevision] = createSignal(0);
      const [blocked, setBlocked] = createSignal(false);
      let restoring = false;
      let waiting = false;
      let finish!: (value: boolean) => void;
      const select = vi.fn((_target: unknown, explicit?: boolean) => {
        if (!restoring || access === "owned") return Promise.resolve(true);
        setBlocked(true);
        if ((access === "lost-selection" && explicit !== true) || (access === "lost-retry" && explicit === true)) {
          waiting = true;
          return new Promise<boolean>((resolve) => {
            finish = resolve;
          });
        }
        return Promise.resolve(false);
      });
      let cover: CoverSnapshot = {
        kind: "cover",
        frontSlotId: "front",
        backSlotId: "back",
        pageCount: 30,
        geometry: coverGeometries.front,
        spineOwner: "front",
        placements: [],
      };
      const read = vi.mocked(built.client.readPlacements).getMockImplementation();
      if (read === undefined) throw new Error("Missing composition reader.");
      vi.mocked(built.client.readPlacements).mockImplementation((id, context) =>
        context.kind === "cover" ? Promise.resolve(cover) : read(id, context),
      );
      vi.mocked(built.client.editPlacements).mockImplementation((_album, _context, command) => {
        if (command.operation !== "spineOwner") throw new Error("Unexpected cover command.");
        cover = { ...cover, spineOwner: command.owner, pageCount: 30, geometry: coverGeometries[command.owner] };
        return Promise.resolve(cover);
      });
      window.history.replaceState(null, "", "?part=cover");
      render(() => (
        <AlbumOpen
          session={{ ...editingSession, select, lossRevision, targetReason: () => (blocked() ? "Another browser owns this cover." : undefined) }}
          id={album.id}
          client={built.client}
          events={events}
        />
      ));
      const owner = await screen.findByRole("combobox", { name: "Spine/gutter owner" });
      await waitFor(() => expect(owner).toBeEnabled());
      fireEvent.change(owner, { target: { value: "back" } });
      await waitFor(() => expect(screen.getByRole("button", { name: "Spreads" })).toBeEnabled());
      fireEvent.click(screen.getByRole("button", { name: "Spreads" }));
      await waitFor(() => expect(screen.getByRole("button", { name: "Undo: Change spine owner" })).toBeEnabled());
      restoring = true;
      fireEvent.click(screen.getByRole("button", { name: "Undo: Change spine owner" }));
      if (access === "lost-selection" || access === "lost-retry") {
        await waitFor(() => {
          expect(waiting).toBe(true);
        });
        setLossRevision(1);
        finish(true);
        await waitFor(() => expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled());
        expect(built.client.editPlacements).toHaveBeenCalledOnce();
      } else if (access === "contended") {
        await screen.findAllByText("Another browser owns this cover.");
        expect(built.client.editPlacements).toHaveBeenCalledOnce();
      } else {
        await waitFor(() => expect(screen.getByRole("button", { name: "Redo: Change spine owner" })).toBeEnabled());
        expect(cover.spineOwner).toBe("front");
        expect(built.client.editPlacements).toHaveBeenCalledTimes(2);
      }
    },
  );

  it.each(["synopsis", "authors"] as const)(
    "should undo a reviewed %s over independent saved content to its authoritative baseline",
    async (kind) => {
      const built = buildHistoryBook();
      const [authority, setAuthority] = createSignal(1);
      const [lossRevision, setLossRevision] = createSignal(0);
      const originalSynopsis = built.client.saveSynopsis;
      if (kind === "authors") vi.mocked(built.client.saveAuthors).mockRejectedValueOnce(new Error("Lost response"));
      render(() => <AlbumOpen session={{ ...editingSession, authority, lossRevision }} id={album.id} client={built.client} events={events} />);
      if (kind === "synopsis") {
        const opener = await screen.findByRole("button", { name: "Edit synopsis" });
        await waitFor(() => expect(opener).toBeEnabled());
        fireEvent.click(opener);
        fireEvent.input(screen.getByRole("textbox", { name: "Synopsis" }), { target: { value: "My retained text" } });
        await originalSynopsis(album.id, "Independent text");
      } else {
        const authors = await screen.findByRole("group", { name: "Album authors" });
        await waitFor(() => expect(within(authors).getByRole("button", { name: "Mara" })).toBeEnabled());
        fireEvent.click(within(authors).getByRole("button", { name: "Mara" }));
        await screen.findByRole("button", { name: "Read saved authors" });
        await built.client.saveAuthors(album.id, ["Mara"]);
      }
      setAuthority(2);
      setLossRevision(1);
      if (kind === "synopsis") {
        fireEvent.click(screen.getByRole("button", { name: "Save" }));
        await screen.findByText("The draft is not saved. Review it before saving again.");
        expect(screen.getByRole("textbox", { name: "Synopsis" })).toHaveValue("My retained text");
        fireEvent.click(screen.getByRole("button", { name: "Save" }));
        await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      } else {
        fireEvent.click(screen.getByRole("button", { name: "Read saved authors" }));
        fireEvent.click(await screen.findByRole("button", { name: "Retry authors" }));
      }

      const label = kind === "synopsis" ? "Edit synopsis" : "Change authors";
      await clickHistory(`Undo: ${label}`);
      await waitFor(() => {
        expect(kind === "synopsis" ? built.metadata().synopsis : built.metadata().authors).toEqual(
          kind === "synopsis" ? "Independent text" : ["Mara"],
        );
      });
      await clickHistory(`Redo: ${label}`);
      await waitFor(() => {
        expect(kind === "synopsis" ? built.metadata().synopsis : built.metadata().authors).toEqual(
          kind === "synopsis" ? "My retained text" : ["Cosmin", "Iacob"],
        );
      });
    },
  );

  it.each(["add", "template"] as const)("should retain a missing acknowledged %s reference and retry only reads", async (kind) => {
    const built = buildHistoryBook();
    if (kind === "add") {
      const command = vi.mocked(built.client.addSpread).getMockImplementation();
      if (command === undefined) throw new Error("Missing add command.");
      vi.mocked(built.client.addSpread).mockImplementationOnce(async (...args) => {
        const id = await command(...args);
        await built.client.deleteSpread(album.id, id, true);
        return id;
      });
    } else {
      const command = vi.mocked(built.client.changeSpreadTemplate).getMockImplementation();
      if (command === undefined) throw new Error("Missing template command.");
      vi.mocked(built.client.changeSpreadTemplate).mockImplementationOnce(async (...args) => {
        await command(...args);
        await built.client.deleteSpread(album.id, vacationPhoto.id, true);
      });
    }
    window.history.replaceState(null, "", "?part=vacation-photo");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
    const control = await screen.findByRole("button", { name: kind === "add" ? "Add spread" : "Alternate layout" });
    await waitFor(() => expect(control).toBeEnabled());
    fireEvent.click(control);
    const read = await screen.findByRole("button", { name: "Read saved spread" });
    fireEvent.click(read);

    await waitFor(() => {
      expect(built.client.chapters).toHaveBeenCalledTimes(3);
    });
    await screen.findByRole("button", { name: "Read saved spread" });
    expect(built.client.addSpread).toHaveBeenCalledTimes(kind === "add" ? 1 : 0);
    expect(built.client.changeSpreadTemplate).toHaveBeenCalledTimes(kind === "template" ? 1 : 0);
  });

  it.each(["synopsis", "chapter"] as const)("should retain older history when an unchanged %s is saved", async (kind) => {
    const built = buildHistoryBook();
    window.history.replaceState(null, "", kind === "synopsis" ? "/albums/album" : "?part=vacation");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
    const authors = await screen.findByRole("group", { name: "Album authors" });
    await waitFor(() => expect(within(authors).getByRole("button", { name: "Mara" })).toBeEnabled());
    fireEvent.click(within(authors).getByRole("button", { name: "Mara" }));
    await waitFor(() => expect(screen.getByRole("button", { name: kind === "synopsis" ? "Edit synopsis" : "Edit chapter details" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: kind === "synopsis" ? "Edit synopsis" : "Edit chapter details" }));
    const save = await screen.findByRole("button", { name: kind === "synopsis" ? "Save" : "Save chapter" });
    await waitFor(() => expect(save).toBeEnabled());
    fireEvent.click(save);

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole("button", { name: "Undo: Change authors" })).toBeEnabled());
  });

  it.each((["details", "source"] as const).flatMap((change) => [false, true].map((lostAccess) => ({ change, lostAccess }))))(
    "should fence $change history callbacks after loss=$lostAccess while accounting for saved gear",
    async ({ change, lostAccess }) => {
      const built = buildHistoryBook();
      const [lossRevision, setLossRevision] = createSignal(0);
      const update = vi.mocked(built.client.updateChapter).getMockImplementation();
      if (update === undefined) throw new Error("Missing chapter command.");
      vi.mocked(built.client.updateChapter).mockImplementationOnce(async (...args) => {
        const saved = await update(...args);
        if (lostAccess) setLossRevision(1);
        return saved;
      });
      window.history.replaceState(null, "", "?part=vacation");
      render(() => <AlbumOpen session={{ ...editingSession, lossRevision }} id={album.id} client={built.client} events={events} />);
      const edit = await screen.findByRole("button", { name: "Edit chapter details" });
      await waitFor(() => expect(edit).toBeEnabled());
      fireEvent.click(edit);
      const camera = await screen.findByRole("combobox", { name: "Add camera" });
      await waitFor(() => expect(camera).toBeEnabled());
      fireEvent.change(camera, { target: { value: "body" } });
      fireEvent.change(screen.getByRole("combobox", { name: "Add lens for Body" }), { target: { value: "lens" } });
      fireEvent.input(screen.getByRole("textbox", { name: change === "source" ? "Photo folder" : "Location / name" }), {
        target: { value: change === "source" ? "/mnt/photos/new-source" : "Changed Kyoto" },
      });
      fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));

      if (lostAccess) {
        await screen.findByText(/Editing access changed. Review the retained chapter/);
        expect(built.client.saveChapterGear).not.toHaveBeenCalled();
        expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled();
        fireEvent.click(screen.getByRole("button", { name: "Save gear" }));
        await screen.findByText(/The draft is not saved. Review it before saving again/);
        fireEvent.click(screen.getByRole("button", { name: "Save gear" }));
      }
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      if (lostAccess || change === "details")
        await waitFor(() => expect(screen.getByRole("button", { name: "Undo: Edit chapter details and gear" })).toBeEnabled());
      else {
        expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled();
        expect(screen.getByText(/A new Undo history has started/)).toBeInTheDocument();
      }
      expect(built.client.saveChapterGear).toHaveBeenCalledOnce();
      if (lostAccess) {
        await clickHistory("Undo: Edit chapter details and gear");
        await waitFor(() => {
          expect(built.gear()).toEqual([]);
        });
        expect(built.chapters()[0]).toMatchObject(change === "source" ? { sourceFolder: "/mnt/photos/new-source" } : { name: "Changed Kyoto" });
      }
    },
  );

  it.each(
    (["add", "template", "delete", "reorder"] as const).flatMap((kind) =>
      (["acknowledged", "uncertain"] as const).map((outcome) => ({ kind, outcome })),
    ),
  )("should not resurrect history for a late $kind $outcome after access loss", async ({ kind, outcome }) => {
    const built = buildHistoryBook([
      opening,
      vacationOpening,
      vacationPhoto,
      { ...vacationPhoto, id: "second-photo" },
      ...workspace.spreads.slice(3),
    ]);
    const [lossRevision, setLossRevision] = createSignal(0);
    const loseAccess = () => {
      setLossRevision(1);
      if (outcome === "uncertain") throw new Error("Lost response after access loss.");
    };
    if (kind === "add") {
      const command = vi.mocked(built.client.addSpread).getMockImplementation();
      if (command === undefined) throw new Error("Missing add command.");
      vi.mocked(built.client.addSpread).mockImplementationOnce(async (...args) => {
        const id = await command(...args);
        loseAccess();
        return id;
      });
    } else if (kind === "template") {
      const command = vi.mocked(built.client.changeSpreadTemplate).getMockImplementation();
      if (command === undefined) throw new Error("Missing template command.");
      vi.mocked(built.client.changeSpreadTemplate).mockImplementationOnce(async (...args) => {
        await command(...args);
        loseAccess();
      });
    } else if (kind === "delete") {
      const command = vi.mocked(built.client.deleteSpread).getMockImplementation();
      if (command === undefined) throw new Error("Missing delete command.");
      vi.mocked(built.client.deleteSpread).mockImplementationOnce(async (...args) => {
        await command(...args);
        loseAccess();
      });
    } else {
      const command = vi.mocked(built.client.replaceChapterComposition).getMockImplementation();
      if (command === undefined) throw new Error("Missing reorder command.");
      vi.mocked(built.client.replaceChapterComposition).mockImplementationOnce(async (...args) => {
        await command(...args);
        loseAccess();
      });
    }
    window.history.replaceState(null, "", "?part=vacation-photo");
    render(() => <AlbumOpen session={{ ...editingSession, lossRevision }} id={album.id} client={built.client} events={events} />);
    const control = await screen.findByRole("button", {
      name: kind === "add" ? "Add spread" : kind === "delete" ? "Delete spread" : kind === "template" ? "Alternate layout" : "Shift spread right",
    });
    await waitFor(() => expect(control).toBeEnabled());
    fireEvent.click(control);
    if (outcome === "uncertain") fireEvent.click(await screen.findByRole("button", { name: "Reload saved workspace" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Cover" })).toBeEnabled());
    await waitFor(() => expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled());
    await screen.findByText(/Editing access was lost. Undo history/);
  });

  it.each(["template", "delete", "reorder"] as const)(
    "should block an uncertain %s whose readback differs from both known outcomes",
    async (kind) => {
      const built = buildHistoryBook([
        opening,
        vacationOpening,
        vacationPhoto,
        { ...vacationPhoto, id: "second-photo" },
        ...workspace.spreads.slice(3),
      ]);
      const failure = () => {
        built.spreads().splice(2, 0, { ...vacationPhoto, id: "external-spread" });
        if (kind === "template") {
          const index = built.spreads().findIndex((spread) => spread.id === vacationPhoto.id);
          built.spreads().splice(index, 1, { ...vacationPhoto, templateId: "external-template" });
        }
        return Promise.reject(new Error("Lost response during an intervening change."));
      };
      if (kind === "template") vi.mocked(built.client.changeSpreadTemplate).mockImplementationOnce(failure);
      else if (kind === "delete") vi.mocked(built.client.deleteSpread).mockImplementationOnce(failure);
      else vi.mocked(built.client.replaceChapterComposition).mockImplementationOnce(failure);
      window.history.replaceState(null, "", "?part=vacation-photo");
      render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
      const control = await screen.findByRole("button", {
        name: kind === "delete" ? "Delete spread" : kind === "template" ? "Alternate layout" : "Shift spread right",
      });
      await waitFor(() => expect(control).toBeEnabled());
      fireEvent.click(control);
      fireEvent.click(await screen.findByRole("button", { name: "Reload saved workspace" }));

      await screen.findByRole("button", { name: "Keep current book and clear history" });
      expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled();
    },
  );

  it("should publish an acknowledged structural save by retrying reads without replaying the mutation", async () => {
    const built = buildHistoryBook();
    vi.mocked(built.client.chapters).mockResolvedValueOnce(workspace.chapters).mockRejectedValueOnce(new Error("Read unavailable"));
    window.history.replaceState(null, "", "?part=vacation-photo");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
    const add = await screen.findByRole("button", { name: "Add spread" });
    await waitFor(() => expect(add).toBeEnabled());
    fireEvent.click(add);
    fireEvent.click(await screen.findByRole("button", { name: "Retry workspace" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Undo: Add spread" })).toBeEnabled());
    expect(built.client.addSpread).toHaveBeenCalledOnce();
  });

  it("should undo a later lens selection before an earlier saved caption through the real workspace save owners", async () => {
    const placed = {
      kind: "spread" as const,
      id: "history-placement",
      photoId: "history-photo",
      slotId: photoSlot.id,
      transform: { x: 0, y: 0, zoom: 1, mirrored: false },
      captionCorner: "bottom_right" as const,
    };
    const built = buildHistoryBook([opening, { ...vacationOpening, placements: [placed] }, ...workspace.spreads.slice(2)], true);
    window.history.replaceState(null, "", "?part=vacation");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
    const caption = await screen.findByRole("button", { name: "Edit caption for History.jpg" });
    await waitFor(() => expect(caption).toBeEnabled());
    fireEvent.click(caption);
    const editor = screen.getByRole("textbox", { name: "Caption for History.jpg" });
    editor.textContent = "Changed caption";
    fireEvent.input(editor);
    fireEvent(window, new MouseEvent("pointermove", { clientX: -1, clientY: -1 }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Undo: Edit caption" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Edit chapter details" }));
    const camera = await screen.findByRole("combobox", { name: "Add camera" });
    await waitFor(() => expect(camera).toBeEnabled());
    fireEvent.change(camera, { target: { value: "body" } });
    fireEvent.change(screen.getByRole("combobox", { name: "Add lens for Body" }), { target: { value: "lens" } });
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await clickHistory("Undo: Edit chapter details and gear");
    await waitFor(() => {
      expect(built.gear()).toEqual([]);
    });
    expect(built.photo().caption).toBe("Changed caption");
    await clickHistory("Undo: Edit caption");
    await waitFor(() => {
      expect(built.photo().caption).toBe("Saved caption");
    });
    await clickHistory("Redo: Edit caption");
    await waitFor(() => {
      expect(built.photo().caption).toBe("Changed caption");
    });
    expect(built.client.saveCaption).toHaveBeenCalledTimes(3);
  });
  it.each((["add", "template", "delete", "reorder"] as const).flatMap((kind) => [false, true].map((saved) => ({ kind, saved }))))(
    "should read an unconfirmed $kind outcome without replaying it (saved=$saved) or skipping to an older edit",
    async ({ kind, saved }) => {
      const second = { ...vacationPhoto, id: "second-photo" };
      const built = buildHistoryBook([opening, vacationOpening, vacationPhoto, second, ...workspace.spreads.slice(3)]);
      window.history.replaceState(null, "", "?part=vacation-photo");
      render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
      const authors = await screen.findByRole("group", { name: "Album authors" });
      await waitFor(() => expect(within(authors).getByRole("button", { name: "Mara" })).toBeEnabled());
      fireEvent.click(within(authors).getByRole("button", { name: "Mara" }));
      await waitFor(() => expect(screen.getByRole("button", { name: "Undo: Change authors" })).toBeEnabled());
      if (kind === "add") {
        const command = vi.mocked(built.client.addSpread).getMockImplementation();
        if (command === undefined) throw new Error("Missing add command.");
        vi.mocked(built.client.addSpread).mockImplementationOnce(async (...args) => {
          if (saved) await command(...args);
          throw new Error("Lost add response.");
        });
        fireEvent.click(screen.getByRole("button", { name: "Add spread" }));
      } else if (kind === "template") {
        const command = vi.mocked(built.client.changeSpreadTemplate).getMockImplementation();
        if (command === undefined) throw new Error("Missing template command.");
        vi.mocked(built.client.changeSpreadTemplate).mockImplementationOnce(async (...args) => {
          if (saved) await command(...args);
          throw new Error("Lost template response.");
        });
        fireEvent.click(await screen.findByRole("button", { name: "Alternate layout" }));
      } else if (kind === "delete") {
        const command = vi.mocked(built.client.deleteSpread).getMockImplementation();
        if (command === undefined) throw new Error("Missing delete command.");
        vi.mocked(built.client.deleteSpread).mockImplementationOnce(async (...args) => {
          if (saved) await command(...args);
          throw new Error("Lost delete response.");
        });
        fireEvent.click(screen.getByRole("button", { name: "Delete spread" }));
      } else {
        const command = vi.mocked(built.client.replaceChapterComposition).getMockImplementation();
        if (command === undefined) throw new Error("Missing reorder command.");
        vi.mocked(built.client.replaceChapterComposition).mockImplementationOnce(async (...args) => {
          if (saved) await command(...args);
          throw new Error("Lost reorder response.");
        });
        fireEvent.click(screen.getByRole("button", { name: "Shift spread right" }));
      }
      const recover = await screen.findByRole("button", { name: "Reload saved workspace" });
      expect(screen.getByRole("button", { name: "Undo: Change authors" })).toBeDisabled();
      fireEvent.click(recover);
      if (kind === "add" && saved) {
        await screen.findByRole("button", { name: "Keep current book and clear history" });
        expect(screen.getByRole("button", { name: "Undo: Change authors" })).toBeDisabled();
      } else {
        const label = !saved ? "Change authors" : kind === "template" ? "Change template" : kind === "delete" ? "Delete spread" : "Reorder spread";
        await waitFor(() => expect(screen.getByRole("button", { name: `Undo: ${label}` })).toBeEnabled());
      }
      expect(built.client.addSpread).toHaveBeenCalledTimes(kind === "add" ? 1 : 0);
      expect(built.client.deleteSpread).toHaveBeenCalledTimes(kind === "delete" ? 1 : 0);
      expect(built.client.changeSpreadTemplate).toHaveBeenCalledTimes(kind === "template" ? 1 : 0);
      expect(built.client.replaceChapterComposition).toHaveBeenCalledTimes(kind === "reorder" ? 1 : 0);
    },
  );

  it("should fence compensation when editing access is lost during its saved-content comparison", async () => {
    const built = buildHistoryBook();
    const [lossRevision, setLossRevision] = createSignal(0);
    render(() => <AlbumOpen session={{ ...editingSession, lossRevision }} id={album.id} client={built.client} events={events} />);
    const authors = await screen.findByRole("group", { name: "Album authors" });
    await waitFor(() => expect(within(authors).getByRole("button", { name: "Mara" })).toBeEnabled());
    fireEvent.click(within(authors).getByRole("button", { name: "Mara" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Undo: Change authors" })).toBeEnabled());
    let finish!: (saved: Album) => void;
    vi.mocked(built.client.open).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Undo: Change authors" }));
    await waitFor(() => {
      expect(built.client.open).toHaveBeenCalledTimes(2);
    });
    setLossRevision(1);
    finish(built.metadata());
    await waitFor(() => expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled());
    expect(built.client.saveAuthors).toHaveBeenCalledOnce();
    expect(built.metadata().authors).toEqual(["Cosmin", "Iacob"]);
  });
  it.each(["synopsis", "description"] as const)("should undo and redo a saved %s while navigation retains history", async (kind) => {
    const built = buildHistoryBook();
    window.history.replaceState(null, "", kind === "synopsis" ? "/albums/album" : "?part=vacation");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
    const opener = await screen.findByRole("button", { name: kind === "synopsis" ? "Edit synopsis" : "Edit chapter description" });
    await waitFor(() => expect(opener).toBeEnabled());
    fireEvent.click(opener);
    fireEvent.input(screen.getByRole("textbox", { name: kind === "synopsis" ? "Synopsis" : "Chapter description" }), {
      target: { value: "New book text" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const label = kind === "synopsis" ? "Edit synopsis" : "Edit chapter description";
    await waitFor(() => expect(screen.getByRole("button", { name: "Cover" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Cover" }));
    await clickHistory(`Undo: ${label}`);
    await waitFor(() => {
      expect(kind === "synopsis" ? built.metadata().synopsis : built.chapters()[0]?.description).toBe(
        kind === "synopsis" ? album.synopsis : vacation.description,
      );
    });
    await clickHistory(`Redo: ${label}`);
    await waitFor(() => {
      expect(kind === "synopsis" ? built.metadata().synopsis : built.chapters()[0]?.description).toBe("New book text");
    });
  });

  it("should record metadata and gear as one action and compensate both saved portions", async () => {
    const built = buildHistoryBook();
    window.history.replaceState(null, "", "?part=vacation");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
    const edit = await screen.findByRole("button", { name: "Edit chapter details" });
    await waitFor(() => expect(edit).toBeEnabled());
    fireEvent.click(edit);
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Location / name" })).toBeEnabled());
    fireEvent.input(screen.getByRole("textbox", { name: "Location / name" }), { target: { value: "Changed Kyoto" } });
    fireEvent.change(await screen.findByRole("combobox", { name: "Add camera" }), { target: { value: "body" } });
    fireEvent.change(screen.getByRole("combobox", { name: "Add lens for Body" }), { target: { value: "lens" } });
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await clickHistory("Undo: Edit chapter details and gear");
    await waitFor(() => {
      expect(built.chapters()[0]?.name).toBe("Kyoto");
      expect(built.gear()).toEqual([]);
    });
    await clickHistory("Redo: Edit chapter details and gear");
    await waitFor(() => {
      expect(built.chapters()[0]?.name).toBe("Changed Kyoto");
      expect(built.gear()).toEqual([{ cameraId: "body", lensIds: ["lens"] }]);
    });
    expect(built.client.updateChapter).toHaveBeenCalledTimes(3);
    expect(built.client.saveChapterGear).toHaveBeenCalledTimes(3);
  });

  it("should reconcile an uncertain inverse using saved reads without issuing the inverse twice", async () => {
    const built = buildHistoryBook();
    const original = vi.mocked(built.client.saveAuthors).getMockImplementation();
    if (original === undefined) throw new Error("Missing author test command.");
    vi.mocked(built.client.saveAuthors)
      .mockImplementationOnce(original)
      .mockImplementationOnce(async (id, authors) => {
        await original(id, authors);
        throw new Error("Lost inverse response.");
      });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
    const authors = await screen.findByRole("group", { name: "Album authors" });
    await waitFor(() => expect(within(authors).getByRole("button", { name: "Mara" })).toBeEnabled());
    fireEvent.click(within(authors).getByRole("button", { name: "Mara" }));
    await clickHistory("Undo: Change authors");
    const recover = await screen.findByRole("button", { name: "Read saved history action" });
    await waitFor(() => expect(recover).toBeEnabled());
    fireEvent.click(recover);
    await waitFor(() => expect(screen.getByRole("button", { name: "Redo: Change authors" })).toBeEnabled());
    expect(built.metadata().authors).toEqual(album.authors);
    expect(built.client.saveAuthors).toHaveBeenCalledTimes(2);
  });

  it("should clear history on unexpected ownership loss and fence a late independent author acknowledgement", async () => {
    const built = buildHistoryBook();
    const [lossRevision, setLossRevision] = createSignal(0);
    let finish!: (saved: Album) => void;
    vi.mocked(built.client.saveAuthors).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    render(() => <AlbumOpen session={{ ...editingSession, lossRevision }} id={album.id} client={built.client} events={events} />);
    const authors = await screen.findByRole("group", { name: "Album authors" });
    await waitFor(() => expect(within(authors).getByRole("button", { name: "Mara" })).toBeEnabled());
    fireEvent.click(within(authors).getByRole("button", { name: "Mara" }));
    setLossRevision(1);
    finish({ ...album, authors: ["Cosmin", "Iacob"] });
    await waitFor(() => expect(within(authors).getByRole("button", { name: "Mara" })).toBeEnabled());
    expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("Editing access was lost.");
  });
  it("should undo a later lens selection before an earlier author change without ending book history", async () => {
    const built = buildHistoryBook();
    window.history.replaceState(null, "", "?part=vacation");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
    const authors = await screen.findByRole("group", { name: "Album authors" });
    await waitFor(() => expect(within(authors).getByRole("button", { name: "Mara" })).toBeEnabled());
    fireEvent.click(within(authors).getByRole("button", { name: "Mara" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit chapter details" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Edit chapter details" }));
    const addCamera = await screen.findByRole("combobox", { name: "Add camera" });
    fireEvent.change(addCamera, { target: { value: "body" } });
    fireEvent.change(screen.getByRole("combobox", { name: "Add lens for Body" }), { target: { value: "lens" } });
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const undoGear = await screen.findByRole("button", { name: "Undo: Edit chapter details and gear" });
    await waitFor(() => expect(undoGear).toBeEnabled());
    fireEvent.click(undoGear);
    await waitFor(() => {
      expect(built.gear()).toEqual([]);
    });
    const undoAuthors = await screen.findByRole("button", { name: "Undo: Change authors" });
    await waitFor(() => expect(undoAuthors).toBeEnabled());
    expect(built.metadata().authors).toEqual(["Cosmin", "Iacob"]);
    fireEvent.click(undoAuthors);
    await waitFor(() => {
      expect(built.metadata().authors).toEqual(album.authors);
    });
    expect(built.client.saveChapterGear).toHaveBeenCalledTimes(2);
  });

  it("should follow recreated spread identities across add, delete, undo and redo", async () => {
    const built = buildHistoryBook();
    window.history.replaceState(null, "", "?part=vacation-photo");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
    const add = await screen.findByRole("button", { name: "Add spread" });
    await waitFor(() => expect(add).toBeEnabled());
    fireEvent.click(add);
    await clickHistory("Undo: Add spread");
    await waitFor(() => {
      expect(built.spreads()).toHaveLength(workspace.spreads.length);
    });
    await clickHistory("Redo: Add spread");
    await waitFor(() => {
      expect(built.spreads().some((spread) => spread.id === "recreated-2")).toBe(true);
    });
    await waitFor(() => {
      expect(window.location.search).toBe("?part=recreated-2");
      expect(screen.getByRole("button", { name: "Delete spread" })).toBeEnabled();
    });
    fireEvent.click(screen.getByRole("button", { name: "Delete spread" }));
    await clickHistory("Undo: Delete spread");
    await waitFor(() => {
      expect(built.spreads().some((spread) => spread.id === "recreated-3")).toBe(true);
    });
    await clickHistory("Undo: Add spread");
    await waitFor(() => {
      expect(built.spreads()).toHaveLength(workspace.spreads.length);
    });
    await waitFor(() => {
      expect(built.client.deleteSpread).toHaveBeenLastCalledWith(album.id, "recreated-3", true);
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "Add spread" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Add spread" }));
    await clickHistory("Undo: Add spread");
    await clickHistory("Redo: Add spread");
    await waitFor(() => {
      expect(built.spreads().some((spread) => spread.id === "recreated-5")).toBe(true);
    });
  });

  it("should reverse template geometry through existing commands and track newly generated slots", async () => {
    const built = buildHistoryBook();
    window.history.replaceState(null, "", "?part=vacation-photo");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
    const alternate = await screen.findByRole("button", { name: "Alternate layout" });
    await waitFor(() => expect(alternate).toBeEnabled());
    fireEvent.click(alternate);
    await clickHistory("Undo: Change template");
    await waitFor(() => {
      expect(built.spreads().find((spread) => spread.id === vacationPhoto.id)?.templateId).toBe("layout");
    });
    await clickHistory("Redo: Change template");
    await waitFor(() => {
      expect(built.spreads().find((spread) => spread.id === vacationPhoto.id)?.templateId).toBe("alternate");
    });
    expect(built.client.changeSpreadTemplate).toHaveBeenCalledTimes(3);
  });

  it("should reverse a chapter reorder with expected membership and preserve other chapter blocks", async () => {
    const second = { ...vacationPhoto, id: "second-photo" };
    const built = buildHistoryBook([opening, vacationOpening, vacationPhoto, second, ...workspace.spreads.slice(3)]);
    window.history.replaceState(null, "", "?part=vacation-photo");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={built.client} events={events} />);
    const later = await screen.findByRole("button", { name: "Shift spread right" });
    await waitFor(() => expect(later).toBeEnabled());
    fireEvent.click(later);
    await clickHistory("Undo: Reorder spread");
    await waitFor(() => {
      expect(built.spreads().map((spread) => spread.id)).toEqual([opening.id, vacationOpening.id, vacationPhoto.id, second.id, "home-opening"]);
    });
    await clickHistory("Redo: Reorder spread");
    await waitFor(() => {
      expect(built.spreads()[2]?.id).toBe(second.id);
    });
    expect(built.client.replaceChapterComposition).toHaveBeenLastCalledWith(
      album.id,
      vacation.id,
      [vacationOpening.id, vacationPhoto.id, second.id],
      [vacationOpening.id, second.id, vacationPhoto.id],
    );
  });
  it("should rename the album from synopsis and undo/redo only its name", async () => {
    let metadata = album;
    const client = buildClient({
      open: vi.fn(() => Promise.resolve(metadata)),
      rename: vi.fn<AlbumClient["rename"]>((_id, title) => {
        metadata = { ...metadata, title };
        return Promise.resolve(metadata);
      }),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    const edit = await screen.findByRole("button", { name: "Edit album name" });
    await waitFor(() => expect(edit).toBeEnabled());
    fireEvent.click(edit);
    const field = screen.getByRole("textbox", { name: "Album name" });
    expect(field.tagName).toBe("INPUT");
    fireEvent.input(field, { target: { value: "  A quieter journey  " } });
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(metadata.title).toBe("A quieter journey");
    await waitFor(() => expect(edit).toHaveFocus());
    const undo = screen.getByRole("button", { name: "Undo: Rename album" });
    await waitFor(() => expect(undo).toBeEnabled());
    fireEvent.click(undo);
    await waitFor(() => expect(screen.getByRole("button", { name: "Redo: Rename album" })).toBeEnabled());
    expect(metadata).toEqual(album);
    fireEvent.click(screen.getByRole("button", { name: "Redo: Rename album" }));
    await waitFor(() => {
      expect(metadata.title).toBe("A quieter journey");
    });
    expect(metadata.synopsis).toBe(album.synopsis);
    expect(metadata.authors).toEqual(album.authors);
  });

  it("should validate an album-name draft before writing and preserve it on rejection", async () => {
    const client = buildClient({ rename: vi.fn().mockRejectedValue(new AlbumRequestError(400, "Name rejected")) });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    const edit = await screen.findByRole("button", { name: "Edit album name" });
    await waitFor(() => expect(edit).toBeEnabled());
    fireEvent.click(edit);
    const field = screen.getByRole("textbox", { name: "Album name" });
    fireEvent.input(field, { target: { value: " " } });
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Save" }));
    expect(screen.getByRole("alert")).toBeVisible();
    expect(client.rename).not.toHaveBeenCalled();
    fireEvent.input(field, { target: { value: "My journey" } });
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Name rejected")).toBeVisible();
    expect(field).toHaveValue("My journey");
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled();
  });

  it("should omit a normalized unchanged album name from history", async () => {
    const client = buildClient({ rename: vi.fn().mockResolvedValue(album) });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    const edit = await screen.findByRole("button", { name: "Edit album name" });
    await waitFor(() => expect(edit).toBeEnabled());
    fireEvent.click(edit);
    fireEvent.input(screen.getByRole("textbox", { name: "Album name" }), { target: { value: ` ${album.title} ` } });
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(client.rename).toHaveBeenCalledExactlyOnceWith(album.id, album.title);
    expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled();
  });

  it.each(["foreign acknowledgement", "lost acknowledgement"] as const)(
    "should reconcile a renamed album after %s without renaming twice",
    async (failure) => {
      let metadata = album;
      const client = buildClient({
        open: vi.fn(() => Promise.resolve(metadata)),
        rename: vi.fn<AlbumClient["rename"]>((_id, title) => {
          metadata = { ...metadata, title };
          return failure === "foreign acknowledgement" ? Promise.resolve({ ...metadata, id: "foreign" }) : Promise.reject(new Error("Disconnected"));
        }),
      });
      render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
      const edit = await screen.findByRole("button", { name: "Edit album name" });
      await waitFor(() => expect(edit).toBeEnabled());
      fireEvent.click(edit);
      fireEvent.input(screen.getByRole("textbox", { name: "Album name" }), { target: { value: "Renamed journey" } });
      fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Save" }));
      if (failure === "lost acknowledgement") {
        const read = await screen.findByRole("button", { name: "Read saved content" });
        await waitFor(() => expect(read).toBeEnabled());
        fireEvent.click(read);
      }
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(client.rename).toHaveBeenCalledExactlyOnceWith(album.id, "Renamed journey");
      expect(screen.getByRole("heading", { level: 1, name: "Renamed journey" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Undo: Rename album" })).toBeEnabled();
    },
  );

  it("should undo and redo authors through the saved content owner with descriptive controls", async () => {
    let metadata = album;
    const client = buildClient({
      open: vi.fn(() => Promise.resolve(metadata)),
      saveAuthors: vi.fn((_id: string, authors: readonly Author[]) => {
        metadata = { ...metadata, authors };
        return Promise.resolve(metadata);
      }),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    const authors = await screen.findByRole("group", { name: "Album authors" });

    fireEvent.click(within(authors).getByRole("button", { name: "Mara" }));
    fireEvent.click(await screen.findByRole("button", { name: "Undo: Change authors" }));
    await waitFor(() => {
      expect(metadata.authors).toEqual(album.authors);
    });
    const redo = await screen.findByRole("button", { name: "Redo: Change authors" });
    await waitFor(() => expect(redo).toBeEnabled());
    fireEvent.click(redo);
    await waitFor(() => {
      expect(metadata.authors).toEqual(["Cosmin", "Iacob"]);
    });

    expect(client.saveAuthors).toHaveBeenCalledTimes(3);
  });

  it.each(["Reader", "view-only"] as const)(
    "should refresh saved content after session reconnect in $0 without relying on inventory reconnect",
    async (view) => {
      const [available, setAvailable] = createSignal(true);
      const session = { ...editingSession, available, targetReason: () => "View only. Mara is editing this spread." };
      const client = buildClient();
      render(() => <AlbumOpen session={session} id={album.id} client={client} events={events} />);
      await screen.findByRole("group", { name: "Synopsis composition" });
      if (view === "Reader") fireEvent.click(screen.getByRole("button", { name: "Reader mode" }));
      setAvailable(false);
      vi.mocked(client.open).mockResolvedValue({ ...album, synopsis: "Fresh after session reconnect" });
      setAvailable(true);
      await waitFor(() => {
        expect(screen.getAllByText("Fresh after session reconnect").length).toBeGreaterThan(0);
      });
    },
  );

  it.each([false, true])("should release ownership through the application brand and fence departure after release=%s", async (released) => {
    window.history.replaceState(null, "", "/albums/album");
    let finish!: (value: boolean) => void;
    const pending = new Promise<boolean>((resolve) => {
      finish = resolve;
    });
    const select = vi.fn().mockResolvedValueOnce(true).mockReturnValueOnce(pending);
    const view = render(() => (
      <>
        <a href="/">Application brand</a>
        <a href="https://example.test/">External catalog</a>
        <AlbumOpen session={{ ...editingSession, select }} id={album.id} client={buildClient()} events={events} />
      </>
    ));
    await screen.findByRole("heading", { name: album.title });
    fireEvent.click(document);
    fireEvent.click(screen.getByRole("link", { name: "Application brand" }), { ctrlKey: true });
    fireEvent.click(screen.getByRole("link", { name: "External catalog" }));
    expect(select).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByRole("link", { name: "Application brand" }));
    expect(select).toHaveBeenLastCalledWith(null);
    if (released) view.unmount();
    finish(released);
    await pending;
    expect(window.location.pathname).not.toBe("/");
  });

  it("should fence acquisition completion while its saved-content refresh is superseded", async () => {
    const [authority, setAuthority] = createSignal(1);
    let finish!: (value: Album) => void;
    const pending = new Promise<Album>((resolve) => {
      finish = resolve;
    });
    const client = buildClient({ open: vi.fn().mockResolvedValueOnce(album).mockReturnValueOnce(pending).mockResolvedValue(album) });
    const select = vi
      .fn()
      .mockImplementationOnce(() => {
        setAuthority(2);
        return Promise.resolve(true);
      })
      .mockResolvedValue(true);
    render(() => <AlbumOpen session={{ ...editingSession, authority, select }} id={album.id} client={client} events={events} />);
    await waitFor(() => {
      expect(client.open).toHaveBeenCalledTimes(2);
    });
    fireEvent.click(screen.getByRole("button", { name: "Cover" }));
    await waitFor(() => {
      expect(select).toHaveBeenCalledTimes(2);
    });
    finish(album);
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Cover" })).toHaveAttribute("aria-pressed", "true");
    });
  });

  it.each(["apply", "discard"] as const)(
    "should retain a photo draft on ownership loss and require explicit %s after saved-state recovery",
    async (action) => {
      const [available, setAvailable] = createSignal(true);
      const [authority, setAuthority] = createSignal(1);
      const photo = {
        id: "photo",
        filename: "Mountain.jpg",
        width: 1000,
        height: 800,
        capturedLocal: null,
        caption: null,
        thumbnail: { url: "/thumbnails/mountain.jpg", width: 1000, height: 800 },
      };
      const placed = {
        id: "placement",
        slotId: photoSlot.id,
        photoId: photo.id,
        captionCorner: "bottom_left" as const,
        kind: "spread" as const,
        transform: { x: 0, y: 0, zoom: 2, mirrored: false },
      };
      const interior = { ...opening, placements: [placed] };
      const saved = {
        ...workspace,
        spreads: [interior, ...workspace.spreads.slice(1)],
        photoInventories: { ...workspace.photoInventories, vacation: { ...inventory, photos: [photo] } },
      };
      const snapshot = {
        kind: "spread" as const,
        id: interior.id,
        placements: [placed],
        layout: {
          kind: interior.kind,
          bleed: { state: "disabled" as const, available: true },
          templateId: interior.templateId,
          slots: interior.slots,
        },
      };
      const client = buildClient(
        {
          readPlacements: vi.fn().mockResolvedValue(snapshot),
          editPlacements: vi.fn().mockResolvedValue(snapshot),
        },
        saved,
      );
      const session = {
        ...editingSession,
        available,
        authority,
        targetReason: () => (available() ? undefined : "Editing ownership is unavailable."),
      };
      render(() => <AlbumOpen session={session} id={album.id} client={client} events={events} />);
      const surface = await screen.findByRole("button", { name: /: Mountain.jpg$/ });
      await waitFor(() => {
        expect(surface).toBeEnabled();
      });
      fireEvent.keyDown(surface, { key: "ArrowRight" });
      setAvailable(false);
      setAuthority(2);
      await screen.findByRole("button", { name: "Apply retained photo adjustments" });
      expect(surface).toBeDisabled();
      expect(screen.getByRole("button", { name: "Apply retained photo adjustments" })).toBeDisabled();
      await new Promise((resolve) => setTimeout(resolve, 270));
      setAuthority(3);
      setAvailable(true);
      await waitFor(() => {
        expect(screen.getByRole("button", { name: "Apply retained photo adjustments" })).toBeEnabled();
      });
      fireEvent.click(screen.getByRole("button", { name: "Retry photos" }));
      await waitFor(() => {
        expect(client.readPlacements).toHaveBeenCalledOnce();
        expect(screen.getByRole("button", { name: "Apply retained photo adjustments" })).toBeEnabled();
      });
      expect(client.editPlacements).not.toHaveBeenCalled();
      fireEvent.click(
        screen.getByRole("button", { name: action === "apply" ? "Apply retained photo adjustments" : "Discard retained photo adjustments" }),
      );
      await waitFor(() => {
        expect(screen.queryByRole("button", { name: "Apply retained photo adjustments" })).not.toBeInTheDocument();
        expect(client.editPlacements).toHaveBeenCalledTimes(action === "apply" ? 1 : 0);
      });
    },
  );

  it("should reconcile saved content after acquisition and reconnect, while keeping target and session availability distinct", async () => {
    const [available, setAvailable] = createSignal(true);
    const [authority, setAuthority] = createSignal(1);
    const [owned, setOwned] = createSignal(false);
    let acquire!: () => void;
    const pending = new Promise<void>((resolve) => {
      acquire = resolve;
    });
    const select = vi
      .fn()
      .mockImplementationOnce(async () => {
        await pending;
        setAuthority(2);
        setOwned(true);
        return true;
      })
      .mockResolvedValue(true);
    const client = buildClient();
    const session = {
      ...editingSession,
      available,
      authority,
      select,
      targetReason: () => (!available() ? "Session lost." : owned() ? undefined : "View only. Mara is editing this spread."),
    };
    render(() => <AlbumOpen session={session} id={album.id} client={client} events={events} />);
    expect(await screen.findByRole("button", { name: "Edit spread" })).toBeDisabled();
    vi.mocked(client.open).mockResolvedValue({ ...album, synopsis: "Saved before acquisition" });
    acquire();
    await waitFor(() => {
      expect(screen.getByRole("group", { name: "Synopsis composition" })).toHaveTextContent("Saved before acquisition");
      expect(screen.queryByRole("button", { name: "Edit spread" })).not.toBeInTheDocument();
    });
    setAvailable(false);
    expect(screen.getByRole("button", { name: "Mara" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Edit spread" })).toBeDisabled();
    setOwned(false);
    setAvailable(true);
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Edit spread" })).toBeEnabled();
    });
    select.mockImplementationOnce(() => {
      setAuthority(3);
      setOwned(true);
      return Promise.resolve(true);
    });
    fireEvent.click(screen.getByRole("button", { name: "Edit spread" }));
    await waitFor(() => {
      expect(select).toHaveBeenLastCalledWith({ kind: "spread", id: opening.id }, true);
    });
    await waitFor(() => {
      expect(screen.queryByRole("button", { name: "Edit spread" })).not.toBeInTheDocument();
    });
  });

  it.each(["resumed", "expired", "contended"] as const)(
    "should retain a synopsis draft through $0 ownership and require explicit review",
    async (recovery) => {
      const [available, setAvailable] = createSignal(true);
      const [authority, setAuthority] = createSignal(1);
      const [owned, setOwned] = createSignal(true);
      let contended = recovery === "contended";
      const reconnect = vi.fn().mockImplementation(() => {
        setAvailable(true);
        return Promise.resolve();
      });
      const select = vi.fn(editingSession.select).mockImplementation((_target, explicit) => {
        if (explicit === true && !contended) setOwned(true);
        return Promise.resolve(recovery === "resumed" || (explicit === true && !contended));
      });
      const session = {
        ...editingSession,
        available,
        authority,
        reconnect,
        select,
        targetReason: () => (owned() ? undefined : "View only. Mara is editing this spread."),
      };
      const text = "Retained offline text";
      const client = buildClient({ saveSynopsis: vi.fn().mockResolvedValue({ ...album, synopsis: text }) });
      render(() => <AlbumOpen session={session} id={album.id} client={client} events={events} />);
      fireEvent.click(await screen.findByRole("button", { name: "Edit synopsis" }));
      const editor = screen.getByRole("textbox", { name: "Synopsis" });
      fireEvent.input(editor, { target: { value: text } });
      setAvailable(false);
      if (recovery !== "resumed") setOwned(false);
      setAuthority(2);
      expect(editor).toHaveValue(text);
      expect(editor).toBeDisabled();
      expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "Cancel" })).toBeEnabled();
      fireEvent.click(screen.getByRole("button", { name: "Reconnect browser" }));
      expect(reconnect).toHaveBeenCalledOnce();
      if (recovery !== "resumed") {
        await waitFor(() => expect(within(screen.getByRole("dialog")).getByRole("button", { name: "Edit spread" })).toBeEnabled());
        if (contended) {
          fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Edit spread" }));
          await waitFor(() => expect(within(screen.getByRole("dialog")).getByRole("button", { name: "Edit spread" })).toBeEnabled());
          expect(within(screen.getByRole("dialog")).getByRole("status")).toHaveTextContent("Mara");
          expect(editor).toHaveValue(text);
          expect(editor).toBeDisabled();
          contended = false;
        }
        fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Edit spread" }));
      }
      await waitFor(() => {
        expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
      });
      fireEvent.click(screen.getByRole("button", { name: "Save" }));
      await waitFor(() => {
        expect(screen.getByRole("alert")).toHaveTextContent("Review");
      });
      expect(client.saveSynopsis).not.toHaveBeenCalled();
      expect(select).toHaveBeenCalledTimes(recovery === "resumed" ? 2 : recovery === "expired" ? 3 : 4);
      expect(editor).toHaveValue(text);
      fireEvent.click(screen.getByRole("button", { name: "Save" }));
      await waitFor(() => {
        expect(client.saveSynopsis).toHaveBeenCalledExactlyOnceWith(album.id, text);
      });
    },
  );

  it.each(["navigate", "dispose"] as const)("should fence pending ownership publication after %s", async (destination) => {
    let finish!: () => void;
    const pending = new Promise<void>((resolve) => {
      finish = resolve;
    });
    const select = vi
      .fn()
      .mockImplementationOnce(async () => {
        await pending;
        return true;
      })
      .mockResolvedValue(true);
    const view = render(() => <AlbumOpen session={{ ...editingSession, select }} id={album.id} client={buildClient()} events={events} />);
    await screen.findByRole("heading", { name: album.title });
    if (destination === "navigate") fireEvent.click(screen.getByRole("button", { name: "Cover" }));
    else view.unmount();
    finish();
    await waitFor(() => {
      expect(select).toHaveBeenCalledTimes(destination === "navigate" ? 2 : 1);
    });
  });

  it("should show a locked owner's alias while keeping Reader and independent edits available", async () => {
    const select = vi.fn().mockResolvedValue(undefined);
    const session = {
      ...editingSession,
      targetReason: () => "View only. Mara is editing this spread.",
      owner: () => "Mara",
      select,
    };
    const client = buildClient();
    render(() => <AlbumOpen session={session} id={album.id} client={client} events={events} />);

    expect(await screen.findByRole("button", { name: "Applied layout" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Edit spread" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("status").some((status) => status.textContent === "Mara")).toBe(true);
    expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled();
    expect(select).toHaveBeenCalledWith({ kind: "spread", id: opening.id }, false);
    expect(screen.getByRole("button", { name: "Mara" })).toBeEnabled();
    expect(client.editPlacements).not.toHaveBeenCalled();
  });

  it.each(["ready", "chapter dialog"] as const)("should acquire a released spread after a %s viewer becomes eligible", async (viewer) => {
    const [ownershipRevision, notify] = createSignal(0);
    const [owned, setOwned] = createSignal(false);
    const select = vi.fn(editingSession.select).mockImplementation((_target, explicit) => {
      if (explicit === true) setOwned(true);
      return Promise.resolve(true);
    });
    const client = buildClient();
    const session = {
      ...editingSession,
      ownershipRevision,
      select,
      owner: () => (owned() ? undefined : "Mara"),
      targetReason: () => (owned() ? undefined : "View only. Mara is editing this spread."),
    };
    if (viewer === "chapter dialog") window.history.replaceState(null, "", "?part=vacation");
    render(() => <AlbumOpen session={session} id={album.id} client={client} events={events} />);
    await screen.findByRole("heading", { name: album.title });
    await waitFor(() => {
      expect(select).toHaveBeenCalledOnce();
    });
    if (viewer === "chapter dialog") {
      await waitFor(() => {
        expect(screen.getByRole("button", { name: "Edit chapter details" })).toBeEnabled();
      });
      fireEvent.click(screen.getByRole("button", { name: "Edit chapter details" }));
      await waitFor(() => {
        expect(within(screen.getByRole("dialog", { name: "Chapter details" })).getByRole("button", { name: "Cancel" })).toBeEnabled();
        expect(screen.getByRole("button", { name: "Cover" })).toBeDisabled();
      });
    }
    const attemptsBeforeRelease = select.mock.calls.length;
    vi.mocked(client.open).mockResolvedValue({ ...album, synopsis: "Saved before release" });

    notify(1);
    if (viewer === "chapter dialog") {
      await Promise.resolve();
      expect(select).toHaveBeenCalledTimes(attemptsBeforeRelease);
      fireEvent.click(within(screen.getByRole("dialog", { name: "Chapter details" })).getByRole("button", { name: "Cancel" }));
    }

    await waitFor(() => expect(screen.getByRole("button", { name: "Applied layout" })).toBeEnabled());
    expect(select).toHaveBeenLastCalledWith({ kind: "spread", id: viewer === "chapter dialog" ? vacationOpening.id : opening.id }, true);
    if (viewer === "ready") expect(screen.getByRole("group", { name: "Synopsis composition" })).toHaveTextContent("Saved before release");
    expect(screen.queryAllByRole("status").some((status) => status.textContent === "Mara")).toBe(false);
    expect(client.saveSynopsis).not.toHaveBeenCalled();
    notify(2);
    await Promise.resolve();
    expect(select).toHaveBeenCalledTimes(attemptsBeforeRelease + 1);
  });

  it.each(["spread", "cover"] as const)("should publish deferred %s reconnect content after cancelling a photo preview", async (kind) => {
    const photo = {
      id: "photo",
      filename: "Mountain.jpg",
      width: 1000,
      height: 800,
      capturedLocal: null,
      caption: null,
      thumbnail: { url: "/thumbnails/mountain.jpg", width: 1000, height: 800 },
    };
    const transform = { x: 0, y: 0, zoom: 2, mirrored: false };
    const placed = { id: "placement", slotId: photoSlot.id, photoId: photo.id, captionCorner: "bottom_left" as const, transform };
    const interior = { ...opening, placements: [{ ...placed, kind: "spread" as const }] };
    const saved = {
      ...workspace,
      spreads: [interior, ...workspace.spreads.slice(1)],
      photoInventories: { ...workspace.photoInventories, vacation: { ...inventory, photos: [photo] } },
    };
    const cover = {
      kind: "cover" as const,
      placements: [{ ...placed, kind: "cover" as const, slotId: "front", side: "front" as const }],
      frontSlotId: "front",
      backSlotId: "back",
      pageCount: 30,
      geometry: coverGeometries.front,
      spineOwner: "front" as const,
    };
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const client = buildClient({ readPlacements: vi.fn().mockResolvedValue(cover) }, saved);
    const liveEvents: AlbumPhotoEvents = {
      subscribe: (_id, callback) => {
        receive.mockImplementation(callback);
        callback({ kind: "subscribed" });
        return vi.fn();
      },
    };
    if (kind === "cover") window.history.replaceState(null, "", "?part=cover");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={liveEvents} />);
    const surface = await screen.findByRole("button", { name: /: Mountain.jpg$/ });
    await waitFor(() => expect(surface).toBeEnabled());
    Object.defineProperty(surface, "setPointerCapture", { value: vi.fn() });
    vi.spyOn(surface, "getBoundingClientRect").mockReturnValue(new DOMRect(0, 0, 400, 400));
    fireEvent(surface, new MouseEvent("pointerdown", { bubbles: true, button: 0, clientX: 100, clientY: 100 }));
    fireEvent(surface, new MouseEvent("pointermove", { bubbles: true, clientX: 120, clientY: 100 }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeDisabled());
    vi.mocked(client.spread).mockImplementation((_album, id) => {
      const spread = saved.spreads.find((candidate) => candidate.id === id);
      if (spread === undefined) throw new Error("Unknown spread");
      return Promise.resolve({ ...spread, placements: [] });
    });
    vi.mocked(client.readPlacements).mockResolvedValue({ ...cover, placements: [] });

    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(client.chapters).toHaveBeenCalledTimes(2);
    });
    await waitFor(() => expect(screen.queryByText(/Catching up/)).not.toBeInTheDocument());
    expect(surface).toBeInTheDocument();
    fireEvent(surface, new MouseEvent("pointercancel", { bubbles: true }));

    await waitFor(() => expect(screen.queryByRole("button", { name: /: Mountain.jpg$/ })).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled());
    expect(client.editPlacements).not.toHaveBeenCalled();
    expect(client.readPlacements).toHaveBeenCalledTimes(kind === "cover" ? 2 : 0);
  });

  it("should retain book nodes and unchanged template and gear acquisitions through a caption-position save and workspace readback", async () => {
    const photo = {
      id: "photo",
      filename: "Mountain.jpg",
      width: 1000,
      height: 800,
      capturedLocal: null,
      caption: "Long line\nShort",
      thumbnail: { url: "/thumbnails/mountain.jpg", width: 1000, height: 800 },
    };
    const placed: SpreadPlacement = {
      kind: "spread",
      id: "placement",
      slotId: photoSlot.id,
      photoId: photo.id,
      captionCorner: "bottom_left",
      transform: { x: 0, y: 0, zoom: 1, mirrored: false },
    };
    let selectedCorner = placed.captionCorner;
    let acknowledge!: (saved: SpreadSnapshot) => void;
    const pending = new Promise<SpreadSnapshot>((resolve) => {
      acknowledge = resolve;
    });
    const saved = {
      ...workspace,
      spreads: workspace.spreads.map((spread) => (spread.id === vacationOpening.id ? { ...spread, placements: [placed] } : spread)),
      photoInventories: { ...workspace.photoInventories, vacation: { ...inventory, photos: [photo] } },
    };
    const readSpread = (id: string) => {
      const spread = saved.spreads.find((item) => item.id === id);
      if (spread === undefined) throw new Error("Unknown spread");
      return {
        ...spread,
        slots: spread.slots.map((slot) => ({ ...slot })),
        placements: spread.placements.map((row) => ({ ...row, captionCorner: selectedCorner })),
      };
    };
    const client = buildClient(
      {
        spread: vi.fn().mockImplementation((_album: string, id: string) => Promise.resolve(readSpread(id))),
        editPlacements: vi.fn().mockImplementation((_album: string, _context: PlacementContext, command: PlacementEdit) => {
          if (command.operation !== "captionCorner") throw new Error("Expected caption corner edit");
          selectedCorner = command.captionCorner;
          return pending;
        }),
      },
      saved,
    );
    window.history.replaceState(null, "", "?part=vacation");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled());
    const editorial = screen.getByRole("group", { name: "Chapter text" });
    const template = screen.getByRole("button", { name: "Applied layout" });
    const caption = screen.getByRole("button", { name: "Edit caption for Mountain.jpg" });
    const editText = screen.getByRole("button", { name: "Edit chapter description" });
    const metadata = screen.getByRole("region", { name: "Spread details" });
    const navigator = screen.getByRole("complementary", { name: "Spread navigator" });

    fireEvent.click(screen.getByRole("button", { name: "Move caption to top right" }));
    expect(screen.getByRole("button", { name: "Edit chapter details" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Edit chapter details" })).toHaveAccessibleDescription("Saving photos. Please wait.");
    expect(screen.getByRole("button", { name: "Add chapter" })).toHaveAccessibleDescription("Saving photos. Please wait.");
    expect(screen.getByRole("region", { name: "Spread details" })).toBe(metadata);
    expect(screen.getByRole("complementary", { name: "Spread navigator" })).toBe(navigator);
    const spread = readSpread(vacationOpening.id);
    acknowledge({
      kind: "spread",
      id: spread.id,
      placements: spread.placements,
      layout: { kind: spread.kind, bleed: { state: "disabled" as const, available: true }, templateId: spread.templateId, slots: spread.slots },
    });
    await waitFor(() => {
      expect(client.chapters).toHaveBeenCalledTimes(2);
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "Move caption to bottom left" })).toBeEnabled());

    expect(screen.getByRole("group", { name: "Chapter text" })).toBe(editorial);
    expect(screen.getByRole("button", { name: "Applied layout" })).toBe(template);
    expect(screen.getByRole("button", { name: "Edit caption for Mountain.jpg" })).toBe(caption);
    expect(screen.getByRole("button", { name: "Edit chapter description" })).toBe(editText);
    expect(screen.getByRole("region", { name: "Spread details" })).toBe(metadata);
    expect(screen.getByRole("complementary", { name: "Spread navigator" })).toBe(navigator);
    expect(client.templates).toHaveBeenCalledTimes(1);
    expect(client.gearCatalog).toHaveBeenCalledTimes(1);
    expect(client.chapterGear).toHaveBeenCalledTimes(1);
    expect(caption).toHaveTextContent("Long line Short");
  });
  it("should reconcile an acknowledged album publication failure without replaying synopsis", async () => {
    const text = "  Saved 山\n\n ";
    const client = buildClient({
      saveSynopsis: vi.fn().mockResolvedValue({ ...album, id: "unexpected", synopsis: text }),
      open: vi
        .fn()
        .mockResolvedValueOnce(album)
        .mockResolvedValue({ ...album, synopsis: text }),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit synopsis" }));
    fireEvent.input(screen.getByRole("textbox", { name: "Synopsis" }), { target: { value: text } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Album synopsis" })).not.toBeInTheDocument());
    expect(client.saveSynopsis).toHaveBeenCalledExactlyOnceWith(album.id, text);
    expect(client.open).toHaveBeenCalledTimes(2);
    expect(screen.getByRole("group", { name: "Synopsis composition" })).toHaveTextContent("Saved 山");
  });
  it("should return reconciled author focus to the group when the initiating author becomes the final saved selection", async () => {
    const client = buildClient({
      saveAuthors: vi.fn().mockRejectedValue(new AcknowledgedWriteError("Malformed saved metadata")),
      open: vi
        .fn()
        .mockResolvedValueOnce(album)
        .mockRejectedValueOnce(new Error("Read offline"))
        .mockResolvedValue({ ...album, authors: ["Iacob"] }),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    const author = await screen.findByRole("button", { name: "Iacob" });
    author.focus();
    fireEvent.click(author);
    await waitFor(() => expect(screen.getByRole("button", { name: "Read saved authors" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Read saved authors" }));
    const group = screen.getByRole("group", { name: "Album authors" });
    await waitFor(() => expect(group).toHaveAccessibleDescription("Choose at least one author."));
    await waitFor(() => expect(group).toHaveFocus());
    await waitFor(() => expect(author).toBeDisabled());
    expect(client.saveAuthors).toHaveBeenCalledOnce();
  });
  it("should deliver ordered country artwork, report a missing image explicitly and restore artwork after navigation", async () => {
    const client = buildClient();
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    const japan = await screen.findByRole("img", { name: "Japan flag" });
    expect(screen.getAllByRole("img", { name: / flag$/ }).map((image) => image.getAttribute("aria-label"))).toEqual(["Japan flag", "Romania flag"]);
    expect(japan).toHaveAttribute("href", "/flags/jp.png");
    fireEvent.error(japan);
    expect(screen.getByRole("alert")).toHaveTextContent("Flag artwork is unavailable for Japan.");
    expect(screen.queryByRole("img", { name: "Japan flag" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("link", { name: "Kyoto" }));
    fireEvent.click(screen.getByRole("link", { name: "Synopsis" }));
    expect(await screen.findByRole("img", { name: "Japan flag" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(client.updateChapter).not.toHaveBeenCalled();
  });
  it.each(["current", "stale", "disposed"] as const)(
    "should keep %s failed gear acquisition explicit without publishing stale or disposed feedback",
    async (phase) => {
      let fail!: (error: Error) => void;
      const delayed = new Promise<never>((_resolve, reject) => {
        fail = reject;
      });
      const client = buildClient({ gearCatalog: vi.fn().mockReturnValueOnce(delayed).mockResolvedValue({ cameras: [], lenses: [] }) });
      window.history.replaceState(null, "", "?part=vacation");
      const view = render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
      await screen.findByRole("button", { name: "Edit chapter details" });
      if (phase === "stale") fireEvent.click(screen.getByRole("link", { name: "Synopsis" }));
      if (phase === "disposed") view.unmount();
      fail(new Error("Catalog offline"));
      await expect(delayed).rejects.toThrow("Catalog offline");
      if (phase === "current") {
        expect(await screen.findByRole("alert")).toHaveTextContent("The request could not be completed.");
        fireEvent.click(screen.getByRole("button", { name: "Read chapter gear" }));
        await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
      } else {
        await Promise.resolve();
        expect(screen.queryByRole("alert")).not.toBeInTheDocument();
      }
    },
  );
  it.each(["resolve", "reject"] as const)("should cancel pending initial gear acquisition and ignore its late %s", async (outcome) => {
    let complete!: (value: { cameras: []; lenses: [] }) => void;
    let fail!: (error: Error) => void;
    const pending = new Promise<{ cameras: []; lenses: [] }>((resolve, reject) => {
      complete = resolve;
      fail = reject;
    });
    const client = buildClient({ gearCatalog: vi.fn().mockResolvedValueOnce({ cameras: [], lenses: [] }).mockReturnValue(pending) });
    window.history.replaceState(null, "", "?part=vacation");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit chapter details" }));
    await waitFor(() => {
      expect(client.gearCatalog).toHaveBeenCalledTimes(2);
    });
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    expect(client.updateChapter).not.toHaveBeenCalled();
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    const signal = vi.mocked(client.gearCatalog).mock.calls[1]?.[0];
    expect(signal?.aborted).toBe(true);
    if (outcome === "resolve") {
      complete({ cameras: [], lenses: [] });
      await pending;
    } else {
      fail(new Error("Offline after closing"));
      await expect(pending).rejects.toThrow("Offline after closing");
    }
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit chapter details" })).toHaveFocus());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(client.updateChapter).not.toHaveBeenCalled();
  });
  it.each(["uncertain", "acknowledged"] as const)(
    "should reconcile a created chapter identity after %s metadata and retain a missing-read draft",
    async (phase) => {
      const failure = phase === "uncertain" ? new Error("Lost metadata acknowledgement") : new AcknowledgedWriteError("Malformed saved identity");
      const client = buildClient({
        chapters: vi.fn().mockResolvedValueOnce([]).mockResolvedValueOnce([]).mockResolvedValue(workspace.chapters),
        composition: vi
          .fn()
          .mockResolvedValueOnce(membership([opening]))
          .mockResolvedValue(membership(workspace.spreads)),
        createChapter: vi.fn().mockRejectedValueOnce(failure).mockResolvedValue({ id: home.id, scheduling: "queued" }),
      });
      render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
      fireEvent.click(await screen.findByRole("button", { name: "Add chapter" }));
      await waitFor(() => expect(within(screen.getByRole("dialog")).getByRole("button", { name: "Add chapter" })).toBeEnabled());
      fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "home" } });
      fireEvent.input(screen.getByRole("textbox", { name: "Photo folder" }), { target: { value: home.sourceFolder } });
      fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Add chapter" }));
      await screen.findByRole("alert");
      fireEvent.click(screen.getByRole("button", { name: "Read saved chapter" }));
      if (phase === "uncertain") {
        await waitFor(() => expect(within(screen.getByRole("dialog")).getByRole("button", { name: "Add chapter" })).toBeEnabled());
        expect(screen.getByRole("textbox", { name: "Photo folder" })).toHaveValue(home.sourceFolder);
        fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Add chapter" }));
      }
      await waitFor(() => expect(screen.queryByRole("dialog", { name: "Add a chapter" })).not.toBeInTheDocument());

      expect(client.createChapter).toHaveBeenCalledTimes(phase === "uncertain" ? 2 : 1);
      expect(client.saveChapterGear).not.toHaveBeenCalled();
      expect(await screen.findByRole("heading", { name: "Home" })).toBeInTheDocument();
      await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled());
      expect(client.composition).toHaveBeenCalledTimes(2);
    },
  );
  it("should validate an unsaved interchangeable body before writing metadata and permit closing a rejected gear phase", async () => {
    const camera = {
      id: "body",
      name: "Body",
      code: "body",
      kind: "interchangeable_lens" as const,
      mount: "sony_e" as const,
      sensorFormat: "aps_c" as const,
    };
    const lens = { id: "lens", name: "Lens", code: "lens", mount: "sony_e" as const, coverage: "aps_c" as const };
    const client = buildClient({
      gearCatalog: vi.fn().mockResolvedValue({ cameras: [camera], lenses: [lens] }),
      saveChapterGear: vi.fn().mockRejectedValue(new AlbumRequestError(400, "Gear rejected")),
    });
    window.history.replaceState(null, "", "?part=vacation");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit chapter details" }));
    fireEvent.change(await screen.findByRole("combobox", { name: "Add camera" }), { target: { value: camera.id } });
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Choose at least one lens");
    expect(client.updateChapter).not.toHaveBeenCalled();
    fireEvent.change(screen.getByRole("combobox", { name: "Add lens for Body" }), { target: { value: lens.id } });
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    await screen.findByText(/gear remains unresolved/);
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Chapter details" })).not.toBeInTheDocument());
    expect(client.updateChapter).toHaveBeenCalledOnce();
    expect(client.saveChapterGear).toHaveBeenCalledOnce();
  });
  it.each(["stale", "disposed", "invalid"] as const)(
    "should discard %s gear acquisition and retain explicit retryable presentation failures",
    async (phase) => {
      let finish!: (catalog: { cameras: []; lenses: [] }) => void;
      const delayed = new Promise<{ cameras: []; lenses: [] }>((resolve) => {
        finish = resolve;
      });
      const client = buildClient({
        gearCatalog: vi
          .fn()
          .mockReturnValueOnce(phase === "invalid" ? Promise.resolve({ cameras: [], lenses: [] }) : delayed)
          .mockResolvedValue({ cameras: [], lenses: [] }),
        chapterGear: vi.fn().mockResolvedValue(phase === "invalid" ? [{ cameraId: "missing", lensIds: [] }] : []),
      });
      window.history.replaceState(null, "", "?part=vacation");
      const view = render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
      await screen.findByRole("button", { name: "Edit chapter details" });
      if (phase === "invalid") {
        expect(await screen.findByRole("alert")).toHaveTextContent("A selected camera is no longer available.");
        expect(screen.getByRole("button", { name: "Reader mode" })).toBeDisabled();
        vi.mocked(client.chapterGear).mockResolvedValue([]);
        fireEvent.click(screen.getByRole("button", { name: "Read chapter gear" }));
        await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
      } else {
        if (phase === "stale") fireEvent.click(screen.getByRole("link", { name: "Synopsis" }));
        else view.unmount();
        finish({ cameras: [], lenses: [] });
        await delayed;
        expect(screen.queryByRole("group", { name: "Chapter gear" })).not.toBeInTheDocument();
      }
    },
  );
  it.each([
    { failure: new AlbumRequestError(400, "Rejected gear"), saved: false, acknowledged: false },
    { failure: new Error("Lost gear acknowledgement"), saved: true, acknowledged: false },
    { failure: new Error("Lost gear acknowledgement"), saved: false, acknowledged: false },
    { failure: new AcknowledgedWriteError("Saved gear refresh failed"), saved: true, acknowledged: true },
  ])("should retain saved metadata through gear failure $failure with saved=$saved", async ({ failure, saved, acknowledged }) => {
    const camera = { id: "phone", code: "phone", name: "Phone", kind: "fixed_lens" as const };
    const selected = [{ cameraId: camera.id, lensIds: [] }];
    const client = buildClient({
      gearCatalog: vi.fn().mockResolvedValue({ cameras: [camera], lenses: [] }),
      chapterGear: vi
        .fn()
        .mockResolvedValueOnce([])
        .mockResolvedValueOnce([])
        .mockResolvedValueOnce(saved ? selected : [])
        .mockResolvedValue(selected),
      saveChapterGear: vi.fn().mockRejectedValueOnce(failure).mockResolvedValue(undefined),
    });
    window.history.replaceState(null, "", "?part=vacation");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit chapter details" }));
    fireEvent.change(await screen.findByRole("combobox", { name: "Add camera" }), { target: { value: camera.id } });
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    if (acknowledged) await waitFor(() => expect(screen.queryByRole("dialog", { name: "Chapter details" })).not.toBeInTheDocument());
    else {
      await screen.findByRole("alert");
      expect(screen.getByRole("textbox", { name: "Location / name" })).toBeDisabled();
      expect(screen.getByRole("textbox", { name: "Location / name" })).toHaveValue(vacation.name);
      if (failure instanceof AlbumRequestError) {
        fireEvent.click(screen.getByRole("button", { name: "Save gear" }));
      } else {
        expect(screen.getByRole("button", { name: "Save gear" })).toBeDisabled();
        fireEvent.click(screen.getByRole("button", { name: "Read saved chapter" }));
        if (!saved) {
          await waitFor(() => expect(screen.getByRole("button", { name: "Save gear" })).toBeEnabled());
          fireEvent.click(screen.getByRole("button", { name: "Save gear" }));
        }
      }
      await waitFor(() => expect(screen.queryByRole("dialog", { name: "Chapter details" })).not.toBeInTheDocument());
    }

    expect(client.updateChapter).toHaveBeenCalledOnce();
    expect(client.saveChapterGear).toHaveBeenCalledTimes(saved ? 1 : 2);
    expect(client.saveChapterGear).toHaveBeenCalledWith(album.id, vacation.id, selected);
  });
  it.each([
    { failure: new Error("Lost metadata acknowledgement"), found: true },
    { failure: new Error("Lost metadata acknowledgement"), found: false },
    { failure: new AcknowledgedWriteError("Malformed metadata response"), found: true },
  ])("should reconcile metadata $failure before another write with found=$found", async ({ failure, found }) => {
    const renamed = { ...vacation, name: "Renamed" };
    const client = buildClient({
      updateChapter: vi.fn().mockRejectedValueOnce(failure).mockResolvedValue({ id: vacation.id, scheduling: "not-requested" }),
      chapters: vi
        .fn()
        .mockResolvedValueOnce(workspace.chapters)
        .mockResolvedValueOnce(found ? [renamed, home] : workspace.chapters)
        .mockResolvedValue([renamed, home]),
    });
    window.history.replaceState(null, "", "?part=vacation");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit chapter details" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Save chapter" })).toBeEnabled());
    fireEvent.input(screen.getByRole("textbox", { name: "Location / name" }), { target: { value: renamed.name } });
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    if (!(failure instanceof AcknowledgedWriteError)) {
      await screen.findByRole("alert");
      expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
      fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
      expect(screen.getByRole("dialog", { name: "Chapter details" })).toBeInTheDocument();
      fireEvent.click(screen.getByRole("button", { name: "Read saved chapter" }));
      if (!found) {
        await waitFor(() => expect(screen.getByRole("button", { name: "Save chapter" })).toBeEnabled());
        fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
      }
    }
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Chapter details" })).not.toBeInTheDocument());

    expect(client.updateChapter).toHaveBeenCalledTimes(found ? 1 : 2);
    expect(client.saveChapterGear).not.toHaveBeenCalled();
    expect(await screen.findByRole("heading", { name: renamed.name })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled());
    expect(client.gearCatalog).toHaveBeenCalledTimes(3);
  });
  it("should retain failed gear acquisition and the metadata draft until a successful resource retry", async () => {
    const client = buildClient({
      gearCatalog: vi
        .fn()
        .mockResolvedValueOnce({ cameras: [], lenses: [] })
        .mockRejectedValueOnce(new Error("Catalog offline"))
        .mockResolvedValue({ cameras: [], lenses: [] }),
    });
    window.history.replaceState(null, "", "?part=vacation");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    await waitFor(() => expect(screen.queryByText("Loading saved chapter gear.")).not.toBeInTheDocument());
    fireEvent.click(await screen.findByRole("button", { name: "Edit chapter details" }));
    await screen.findByRole("alert");
    expect(screen.getByRole("button", { name: "Save chapter" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Save chapter" })).toHaveAccessibleDescription(
      expect.stringContaining("Load saved chapter gear before saving."),
    );
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Load saved chapter gear before saving.");
    fireEvent.click(screen.getByRole("button", { name: "Retry gear" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Save chapter" })).toBeEnabled());
    expect(screen.getByRole("textbox", { name: "Location / name" })).toHaveValue(vacation.name);
    fireEvent.click(screen.getByRole("button", { name: "Manage catalog" }));
    await screen.findByRole("dialog", { name: "Gear catalog" });
    fireEvent.click(within(screen.getByRole("dialog", { name: "Gear catalog" })).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Manage catalog" })).toHaveFocus());
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(client.updateChapter).not.toHaveBeenCalled();
  });
  it("should select gear inside the confirmed chapter editor and save it without another metadata write", async () => {
    const camera = { id: "camera", code: "Camera code", name: "Saved camera", kind: "fixed_lens" as const };
    const selected = [{ cameraId: camera.id, lensIds: [] }];
    const client = buildClient({
      gearCatalog: vi.fn().mockResolvedValue({ cameras: [camera], lenses: [] }),
      chapterGear: vi.fn().mockResolvedValueOnce([]).mockResolvedValueOnce([]).mockResolvedValue(selected),
      saveChapterGear: vi.fn().mockResolvedValue(undefined),
    });
    window.history.replaceState(null, "", "?part=vacation");
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit chapter details" }));
    fireEvent.change(await screen.findByRole("combobox", { name: "Add camera" }), { target: { value: camera.id } });
    expect(screen.getByRole("region", { name: "Chapter metadata" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Chapter gear selection" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    await waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Chapter details" })).not.toBeInTheDocument();
    });

    expect(client.updateChapter).toHaveBeenCalledOnce();
    expect(client.saveChapterGear).toHaveBeenCalledExactlyOnceWith(album.id, vacation.id, selected);
    await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled());
    expect(client.gearCatalog).toHaveBeenCalledTimes(3);
    expect(client.chapterGear).toHaveBeenCalledTimes(4);
  });
  it.each(["refresh", "spread save"] as const)("should explain content editing disabled during %s", async (phase) => {
    let finishWrite!: (id: string) => void;
    let finishRead!: (chapters: readonly Chapter[]) => void;
    const writing = new Promise<string>((resolve) => {
      finishWrite = resolve;
    });
    const reading = new Promise<readonly Chapter[]>((resolve) => {
      finishRead = resolve;
    });
    const client = buildClient({
      addSpread: vi.fn().mockReturnValue(writing),
      chapters:
        phase === "refresh"
          ? vi.fn().mockResolvedValueOnce(workspace.chapters).mockReturnValue(reading)
          : vi.fn().mockResolvedValue(workspace.chapters),
    });
    window.history.replaceState(null, "", "?part=vacation");
    const view = render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    await screen.findByRole("button", { name: "Edit chapter description" });
    if (phase === "refresh") {
      fireEvent.click(screen.getByRole("button", { name: "Edit chapter details" }));
      await waitFor(() => expect(screen.getByRole("button", { name: "Save chapter" })).toBeEnabled());
      fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
      await waitFor(() => {
        expect(client.chapters).toHaveBeenCalledTimes(2);
      });
    } else fireEvent.click(screen.getByRole("button", { name: "Add spread" }));
    const opener = screen.getByRole("button", { name: "Edit chapter description" });

    expect(opener).toBeDisabled();
    expect(opener).toHaveAccessibleDescription(phase === "refresh" ? "Refreshing saved workspace. Please wait." : "Saving spread. Please wait.");
    view.unmount();
    finishWrite("inserted");
    finishRead(workspace.chapters);
    await Promise.all([writing, reading]);
  });
  it("should retain chapter text when an acknowledged read finds that its chapter was removed", async () => {
    window.history.replaceState(null, "", "?part=vacation");
    const remaining = [opening, ...workspace.spreads.slice(-1)];
    const client = buildClient({
      chapters: vi.fn().mockResolvedValueOnce(workspace.chapters).mockResolvedValue([home]),
      composition: vi.fn().mockResolvedValueOnce(membership(workspace.spreads)).mockResolvedValue(membership(remaining)),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit chapter description" }));
    const dialog = screen.getByRole("dialog", { name: "Chapter description for Kyoto" });
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Chapter description" }), { target: { value: "Retained draft" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await within(dialog).findByRole("alert");

    expect(within(dialog).getByRole("textbox", { name: "Chapter description" })).toHaveValue("Retained draft");
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeDisabled();
    expect(client.saveDescription).toHaveBeenCalledExactlyOnceWith(album.id, vacation.id, "Retained draft");
  });

  it.each([false, true])("should retain an unplaced caption draft and report missing inventory with uncertain=%s", async (uncertain) => {
    const [available, setAvailable] = createSignal(true);
    const photo = {
      id: "photo",
      filename: "Mountain.jpg",
      width: 400,
      height: 200,
      capturedLocal: null,
      caption: null,
      thumbnail: { url: "/photo.jpg", width: 400, height: 200 },
    };
    const row: SpreadPlacement = {
      kind: "spread",
      captionCorner: "top_left",
      id: "p",
      slotId: photoSlot.id,
      photoId: photo.id,
      transform: { x: 0, y: 0, zoom: 1, mirrored: false },
    };
    const photos = { ...inventory, photos: [photo], placedPhotoIds: [photo.id], counts: { total: 1, unplaced: 0 } };
    const placed = {
      ...workspace,
      spreads: [{ ...opening, placements: [row] }, ...workspace.spreads.slice(1)],
      photoInventories: { vacation: photos, home: inventory },
    };
    const receive = vi.fn<(event: PhotoEvent) => void>();
    const liveEvents: AlbumPhotoEvents = {
      subscribe: (_id, callback) => {
        receive.mockImplementation(callback);
        callback({ kind: "subscribed" });
        return vi.fn();
      },
    };
    const client = buildClient({ saveCaption: vi.fn().mockRejectedValue(new Error("lost")) }, placed);
    render(() => <AlbumOpen session={{ ...editingSession, available }} id={album.id} client={client} events={liveEvents} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add caption for Mountain.jpg" }));
    const editor = screen.getByRole("textbox", { name: "Caption for Mountain.jpg" });
    editor.textContent = "Draft";
    fireEvent.input(editor);
    if (uncertain) {
      fireEvent(window, new MouseEvent("pointermove", { clientX: -1, clientY: -1 }));
      await screen.findByRole("button", { name: "Read saved caption" });
    }
    vi.mocked(client.spread).mockImplementation((_album, id) => {
      const spread = workspace.spreads.find((candidate) => candidate.id === id);
      if (spread === undefined) throw new Error("Unknown spread");
      return Promise.resolve(spread);
    });
    vi.mocked(client.chapterPhotos).mockResolvedValue(inventory);
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveFocus();
    });
    setAvailable(false);
    fireEvent.click(screen.getByRole("button", { name: uncertain ? "Read retained caption" : "Save retained caption" }));
    await screen.findByText("Editing ownership is unavailable. The draft is retained.");
    setAvailable(true);
    await waitFor(() => {
      expect(screen.queryByRole("button", { name: "Edit spread" })).not.toBeInTheDocument();
    });
    fireEvent.click(await screen.findByRole("button", { name: uncertain ? "Read retained caption" : "Save retained caption" }));
    if (uncertain) {
      await screen.findByText(/Saved content could not be read/);
      vi.mocked(client.chapterPhotos).mockImplementation((id) =>
        Promise.resolve(id === vacation.id ? { ...photos, photos: [{ ...photo, caption: "Draft" }] } : inventory),
      );
      fireEvent.click(screen.getByRole("button", { name: "Read retained caption" }));
    } else {
      await screen.findByText("The photo could not be found in its chapter");
      expect(client.saveCaption).not.toHaveBeenCalled();
      expect(screen.getByText("Draft")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Cover" })).toBeDisabled();
      screen.getByRole("button", { name: "Cancel retained caption" }).focus();
      fireEvent.click(screen.getByRole("button", { name: "Cancel retained caption" }));
    }
    await waitFor(() => expect(screen.queryByRole("button", { name: "Cancel retained caption" })).not.toBeInTheDocument());
    expect(client.saveCaption).toHaveBeenCalledTimes(uncertain ? 1 : 0);
    if (!uncertain) expect(screen.getByRole("region", { name: "Album workspace" })).toHaveFocus();
  });
  it.each([
    { failure: new AlbumRequestError(400, "Rejected authors"), uncertain: false },
    { failure: new Error("Connection lost"), uncertain: true },
  ])("should retain authors after $failure and recover without replaying acknowledged work", async ({ failure, uncertain }) => {
    const requested = ["Cosmin", "Mara"] as const;
    const metadata = { ...album, authors: requested };
    const client = buildClient({
      saveAuthors: vi.fn().mockRejectedValueOnce(failure).mockResolvedValue(metadata),
      open: vi.fn().mockResolvedValueOnce(album).mockRejectedValueOnce(new Error("read offline")).mockResolvedValue(metadata),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Iacob" }));
    await screen.findByRole("alert");
    if (uncertain) {
      expect(screen.getByRole("button", { name: "Iacob" })).toBeDisabled();
      fireEvent.click(screen.getByRole("button", { name: "Read saved authors" }));
      await waitFor(() => expect(screen.getByRole("button", { name: "Read saved authors" })).toBeEnabled());
      expect(screen.getByRole("button", { name: "Cover" })).toBeDisabled();
      fireEvent.click(screen.getByRole("button", { name: "Read saved authors" }));
    } else fireEvent.click(screen.getByRole("button", { name: "Retry authors" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Iacob" })).toHaveAttribute("aria-pressed", "false"));

    expect(client.saveAuthors).toHaveBeenCalledTimes(uncertain ? 1 : 2);
    expect(client.saveAuthors).toHaveBeenCalledWith(album.id, requested);
    await waitFor(() => expect(screen.getByRole("button", { name: "Cover" })).toBeEnabled());
  });

  it.each(["  山\n\n ", ""])(
    "should retain caption draft %j across failed acquisition and reconcile through chapter inventory without placement changes",
    async (text) => {
      const photo = {
        id: "photo",
        filename: "Mountain.jpg",
        width: 400,
        height: 200,
        capturedLocal: null,
        caption: "Original",
        thumbnail: { url: "/photo.jpg", width: 400, height: 200 },
      };
      const placement: SpreadPlacement = {
        kind: "spread",
        captionCorner: "bottom_left",
        id: "placed",
        slotId: photoSlot.id,
        photoId: photo.id,
        transform: { x: 4, y: 5, zoom: 2, mirrored: true },
      };
      const photographs = { ...inventory, photos: [photo], placedPhotoIds: [photo.id], counts: { total: 1, unplaced: 0 } };
      const displayed = {
        ...workspace,
        spreads: [{ ...opening, placements: [placement] }, ...workspace.spreads.slice(1)],
        photoInventories: { vacation: photographs, home: inventory },
      };
      const client = buildClient(
        {
          saveCaption: vi.fn().mockRejectedValueOnce(new Error("lost")),
          chapterPhotos: vi
            .fn()
            .mockResolvedValueOnce(photographs)
            .mockResolvedValueOnce(inventory)
            .mockRejectedValueOnce(new Error("read offline"))
            .mockResolvedValueOnce(inventory)
            .mockResolvedValueOnce({ ...photographs, photos: [{ ...photo, caption: text === "" ? null : text }] })
            .mockResolvedValue(inventory),
        },
        displayed,
      );
      render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
      fireEvent.click(await screen.findByRole("button", { name: "Edit caption for Mountain.jpg" }));
      const editor = screen.getByRole("textbox", { name: "Caption for Mountain.jpg" });
      editor.textContent = text;
      fireEvent.input(editor);
      fireEvent(window, new MouseEvent("pointermove", { clientX: -1, clientY: -1 }));
      await screen.findByRole("button", { name: "Read saved caption" });
      expect(screen.getByRole("button", { name: "Remove photo from Photo slot 1" })).toBeDisabled();
      fireEvent.click(screen.getByRole("button", { name: "Read saved caption" }));
      await waitFor(() => expect(screen.getByRole("button", { name: "Read saved caption" })).toBeEnabled());
      expect(screen.getByRole("textbox", { name: "Caption for Mountain.jpg" }).textContent).toBe(text);
      fireEvent.click(screen.getByRole("button", { name: "Read saved caption" }));
      await waitFor(() => expect(screen.queryByRole("textbox", { name: "Caption for Mountain.jpg" })).not.toBeInTheDocument());

      expect(screen.getByRole("button", { name: `${text === "" ? "Add" : "Edit"} caption for Mountain.jpg` }).textContent).toBe(
        text === "" ? "Add caption" : text,
      );
      expect(client.saveCaption).toHaveBeenCalledExactlyOnceWith(vacation.id, photo.id, text);
      expect(client.editPlacements).not.toHaveBeenCalled();
    },
  );
  it("should deliver complete saved synopsis metadata and attribution without a postwrite read", async () => {
    const text = "  山\n\n the end  ";
    const metadata = { ...album, title: "Authoritative saved title", synopsis: text, authors: ["Mara"] as const };
    let persisted = album;
    const client = buildClient({
      open: vi.fn(() => Promise.resolve(persisted)),
      saveSynopsis: vi.fn(() => {
        persisted = metadata;
        return Promise.resolve(metadata);
      }),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    const opener = await screen.findByRole("button", { name: "Edit synopsis" });
    const acquired = vi.mocked(client.open).mock.calls.length;

    fireEvent.click(opener);
    fireEvent.input(screen.getByRole("textbox", { name: "Synopsis" }), { target: { value: text } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Album synopsis" })).not.toBeInTheDocument());

    expect(client.saveSynopsis).toHaveBeenCalledExactlyOnceWith(album.id, text);
    expect(client.open).toHaveBeenCalledTimes(acquired);
    expect(screen.getByRole("heading", { name: metadata.title })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Synopsis composition" })).toHaveTextContent("山 the end");
    fireEvent.click(screen.getByRole("button", { name: "Cover" }));
    expect(await screen.findByRole("group", { name: `Cover: back on the left; front on the right. ${metadata.title}. by Mara` })).toBeInTheDocument();
    expect(await screen.findByText("by Mara · 2026")).toBeInTheDocument();
  });

  it("should retain a cleared chapter description after failed acknowledged acquisition and retry reads only", async () => {
    window.history.replaceState(null, "", "?part=vacation");
    const client = buildClient({
      chapters: vi
        .fn()
        .mockResolvedValueOnce(workspace.chapters)
        .mockRejectedValueOnce(new Error("offline"))
        .mockResolvedValue([{ ...vacation, description: "" }, home]),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit chapter description" }));
    const dialog = screen.getByRole("dialog", { name: "Chapter description for Kyoto" });
    const editor = within(dialog).getByRole("textbox", { name: "Chapter description" });

    fireEvent.input(editor, { target: { value: "" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await within(dialog).findByRole("alert");

    expect(editor).toHaveValue("");
    expect(editor).toBeDisabled();
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeDisabled();
    fireEvent(dialog, new Event("cancel", { cancelable: true }));
    expect(dialog).toBeInTheDocument();
    const departure = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(departure);
    expect(departure.defaultPrevented).toBe(true);
    fireEvent.click(within(dialog).getByRole("button", { name: "Read saved content" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Chapter description for Kyoto" })).not.toBeInTheDocument());

    expect(client.saveDescription).toHaveBeenCalledExactlyOnceWith(album.id, vacation.id, "");
    expect(client.chapters).toHaveBeenCalledTimes(3);
    expect(screen.getByRole("group", { name: "Kyoto composition" })).not.toHaveTextContent(vacation.description);
  });

  it("should retain uncertain synopsis drafts and reconcile before enabling another write", async () => {
    const text = "Uncertain draft";
    const client = buildClient({ saveSynopsis: vi.fn().mockRejectedValueOnce(new Error("Connection lost")) });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit synopsis" }));
    const dialog = screen.getByRole("dialog", { name: "Album synopsis" });
    const editor = within(dialog).getByRole("textbox", { name: "Synopsis" });
    fireEvent.input(editor, { target: { value: text } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await within(dialog).findByRole("alert");
    expect(editor).toHaveValue(text);
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Cover" })).toBeDisabled();
    fireEvent.click(within(dialog).getByRole("button", { name: "Read saved content" }));
    await waitFor(() => expect(within(dialog).getByRole("button", { name: "Save" })).toBeEnabled());
    expect(editor).toHaveValue(text);
    expect(editor).toHaveFocus();
    expect(client.saveSynopsis).toHaveBeenCalledOnce();
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
  });
  it("should edit synopsis without typing writes and cancel back to its persistent opener", async () => {
    const client = buildClient();
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    const opener = await screen.findByRole("button", { name: "Edit synopsis" });

    fireEvent.click(opener);
    const editor = screen.getByRole("textbox", { name: "Synopsis" });
    expect(editor).toHaveFocus();
    fireEvent.input(editor, { target: { value: "  山\n\n " } });
    expect(client.saveSynopsis).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(opener).toHaveFocus());
    expect(screen.queryByRole("textbox", { name: "Synopsis" })).not.toBeInTheDocument();
    expect(client.saveSynopsis).not.toHaveBeenCalled();
  });
  it.each([true, false])("should save grouped authors immediately and preserve owned focus with retainedFocus=%s", async (retainedFocus) => {
    let acknowledge!: (saved: Album) => void;
    const pending = new Promise<Album>((resolve) => {
      acknowledge = resolve;
    });
    const client = buildClient({
      saveAuthors: vi
        .fn()
        .mockReturnValueOnce(pending)
        .mockImplementation((_id: string, authors: readonly Author[]) => Promise.resolve({ ...album, authors })),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    const group = await screen.findByRole("group", { name: "Album authors" });
    const opener = within(group).getByRole("button", { name: "Iacob" });
    const other = screen.getByRole("link", { name: "Back to album catalog" });

    opener.focus();
    fireEvent.click(opener);

    expect(within(group).getByRole("button", { name: "Iacob" })).toHaveAttribute("aria-pressed", "false");
    expect(group).toHaveAttribute("aria-busy", "true");
    expect(within(group).getByRole("button", { name: "Mara" })).toBeDisabled();
    if (!retainedFocus) other.focus();
    acknowledge({ ...album, authors: ["Cosmin", "Mara"] });
    await waitFor(() => expect(within(group).getByRole("button", { name: "Mara" })).toBeEnabled());
    await waitFor(() => expect(within(group).getByRole("button", { name: "Iacob" })).toHaveAttribute("aria-pressed", "false"));
    await waitFor(() => expect(retainedFocus ? opener : other).toHaveFocus());
    expect(client.saveAuthors).toHaveBeenCalledWith(album.id, ["Cosmin", "Mara"]);
    fireEvent.click(within(group).getByRole("button", { name: "Mara" }));
    await waitFor(() => expect(within(group).getByRole("button", { name: "Cosmin" })).toHaveAccessibleDescription("Choose at least one author."));
    expect(within(group).getByRole("button", { name: "Cosmin" })).toBeDisabled();
    expect(client.saveAuthors).toHaveBeenCalledTimes(2);
  });
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
        layout: { kind: opening.kind, bleed: { state: "disabled" as const, available: true }, templateId: opening.templateId, slots: opening.slots },
      } satisfies SpreadSnapshot),
      templates: vi.fn().mockResolvedValue([
        { id: "layout", name: "Applied", slots: [photoSlot, textSlot] },
        { id: "new", name: "New layout", slots: [photoSlot, textSlot] },
      ]),
      changeSpreadTemplate: vi.fn().mockResolvedValue(undefined),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
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
      readPlacements: vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValue({
        kind: "cover",
        placements: [],
        frontSlotId: "front",
        backSlotId: "back",
        pageCount: 30,
        geometry: coverGeometries.front,
        spineOwner: "front",
      }),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
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
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
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
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
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

  it("should read current saved content on navigation and keep editing blocked until the saved workspace arrives", async () => {
    window.history.replaceState(null, "", "?part=vacation-photo");
    let finish!: (chapters: readonly Chapter[]) => void;
    const pending = new Promise<readonly Chapter[]>((resolve) => {
      finish = resolve;
    });
    const client = buildClient({
      chapters: vi.fn().mockResolvedValueOnce(workspace.chapters).mockReturnValueOnce(pending),
      open: vi
        .fn()
        .mockResolvedValueOnce(album)
        .mockResolvedValue({ ...album, authors: ["Mara"] }),
      readPlacements: vi.fn().mockRejectedValue(new Error("A second spread GET is unnecessary")),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);

    await screen.findByRole("button", { name: "Photo slot 1: empty" });
    fireEvent.click(screen.getByRole("link", { name: "Kyoto" }));
    expect(screen.getByRole("heading", { name: "Kyoto" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit chapter description" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Edit chapter description" })).toHaveAccessibleDescription("Refreshing saved workspace. Please wait.");
    finish(workspace.chapters.map((owner) => (owner.id === vacation.id ? { ...owner, description: "Newly saved description" } : owner)));
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit chapter description" })).toBeEnabled());

    expect(screen.getByRole("group", { name: "Kyoto composition" })).toHaveTextContent("Newly saved description");
    expect(client.spread).toHaveBeenCalledTimes(workspace.spreads.length * 2);
    expect(client.readPlacements).not.toHaveBeenCalled();
    expect(client.editPlacements).not.toHaveBeenCalled();
  });

  it.each(["front", "back"] as const)("should show the saved %s spine owner without acquiring new editing authority", async (owner) => {
    window.history.replaceState(null, "", "?part=cover");
    const photograph: Photo = {
      id: "cover-photo",
      filename: "Forest.jpg",
      width: 4000,
      height: 2000,
      caption: null,
      capturedLocal: null,
      thumbnail: { url: "/forest.jpg", width: 400, height: 200 },
    };
    const client = buildClient({
      chapterPhotos: vi.fn((id) =>
        Promise.resolve(
          id === vacation.id ? { ...inventory, photos: [photograph], placedPhotoIds: [photograph.id], counts: { total: 1, unplaced: 0 } } : inventory,
        ),
      ),
      readPlacements: vi.fn().mockResolvedValue({
        kind: "cover",
        placements: [
          {
            kind: "cover",
            id: "back-placement",
            slotId: "back",
            photoId: photograph.id,
            transform: { x: 10, y: -5, zoom: 2, mirrored: true },
          },
        ],
        frontSlotId: "front",
        backSlotId: "back",
        spineOwner: owner,
        pageCount: 30,
        geometry: coverGeometries[owner],
      }),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    expect(await screen.findByText(`Spine/gutter: ${owner} cover`)).toBeInTheDocument();
    const image = within(screen.getByRole("group", { name: /Cover: back on the left/ })).getByAltText(photograph.filename);
    expect(Number.parseFloat(image.style.width)).toBeCloseTo((2880 / coverGeometries[owner].back.width) * 100);
    expect(Number.parseFloat(image.style.height)).toBeCloseTo(200);
    expect(image).toHaveStyle({ transform: "scaleX(-1)" });
    expect(client.editPlacements).not.toHaveBeenCalled();
  });

  it("should refresh missing navigator photo data through the existing workspace read without writing", async () => {
    const placed = {
      kind: "spread" as const,
      id: "placement",
      slotId: photoSlot.id,
      photoId: "missing",
      captionCorner: "bottom_left" as const,
      transform: { x: 0, y: 0, zoom: 1, mirrored: false },
    };
    const saved = { ...workspace, spreads: [{ ...opening, placements: [placed] }, ...workspace.spreads.slice(1)] };
    const client = buildClient({}, saved);
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    const retry = await screen.findByRole("button", { name: "Reload navigator data" });
    const reads = vi.mocked(client.chapters).mock.calls.length;
    fireEvent.click(retry);
    await waitFor(() => {
      expect(client.chapters).toHaveBeenCalledTimes(reads + 1);
    });
    expect(client.editPlacements).not.toHaveBeenCalled();
  });

  it("should fail closed on a navigation read failure and retry saved content without changing focus or writing", async () => {
    const client = buildClient({
      chapters: vi.fn().mockResolvedValueOnce(workspace.chapters).mockRejectedValueOnce(new Error("offline")).mockResolvedValue(workspace.chapters),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    const chapter = await screen.findByRole("link", { name: "Kyoto" });
    chapter.focus();
    fireEvent.click(chapter);
    const retry = await screen.findByRole("button", { name: "Retry workspace" });

    expect(screen.getByRole("button", { name: "Add spread" })).toBeDisabled();
    expect(chapter).toHaveFocus();
    fireEvent.click(retry);
    await waitFor(() => expect(screen.getByRole("button", { name: "Add spread" })).toBeEnabled());

    expect(chapter).toHaveFocus();
    expect(window.location.search).toBe("?part=vacation");
    expect(client.chapters).toHaveBeenCalledTimes(3);
    expect(client.editPlacements).not.toHaveBeenCalled();
  });

  it.each(["history", "keyboard", "outline"] as const)("should acquire saved Synopsis content through %s navigation", async (entry) => {
    window.history.replaceState(null, "", "?part=vacation");
    const savedAlbum = { ...album, synopsis: "Synopsis saved in another browser" };
    const client = buildClient({
      open: vi.fn().mockResolvedValueOnce(album).mockResolvedValue(savedAlbum),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled());

    if (entry === "history") {
      window.history.replaceState(null, "", window.location.pathname);
      fireEvent(window, new PopStateEvent("popstate"));
    } else if (entry === "keyboard") fireEvent.keyDown(screen.getByRole("region", { name: "Album workspace" }), { key: "ArrowLeft" });
    else fireEvent.click(screen.getByRole("link", { name: "Synopsis" }));
    await waitFor(() => expect(screen.getByRole("group", { name: "Synopsis composition" })).toHaveTextContent(savedAlbum.synopsis));

    expect(window.location.search).toBe("");
    expect(client.open).toHaveBeenCalledTimes(2);
    expect(client.editPlacements).not.toHaveBeenCalled();
  });

  it("should read saved Cover metadata and placements when switching from an interior spread", async () => {
    const savedAlbum = { ...album, title: "Title saved in another browser", authors: ["Mara"] as const };
    const client = buildClient({
      open: vi.fn().mockResolvedValueOnce(album).mockResolvedValue(savedAlbum),
    });
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Cover" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled());

    expect(screen.getByRole("group", { name: /Cover: back on the left; front on the right/ })).toHaveAccessibleName(/Title saved in another browser/);
    expect(screen.getByText("by Mara · 2026")).toBeInTheDocument();
    expect(client.readPlacements).toHaveBeenCalledExactlyOnceWith(album.id, { kind: "cover" });
    expect(client.open).toHaveBeenCalledTimes(2);
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
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
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
        chapters: vi
          .fn()
          .mockResolvedValueOnce(workspace.chapters)
          .mockResolvedValueOnce(workspace.chapters)
          .mockRejectedValueOnce(new Error("offline"))
          .mockResolvedValue(workspace.chapters),
      });
      render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
      fireEvent.click(await screen.findByRole("link", { name: "Kyoto" }));
      await waitFor(() => expect(screen.getByRole("button", { name: "Add spread" })).toBeEnabled());
      fireEvent.click(screen.getByRole("button", { name: "Add spread" }));
      fireEvent.click(await screen.findByRole("button", { name: "Reload saved workspace" }));
      await screen.findByText("Saved workspace could not be refreshed. Displayed photos may be out of date.");
      fireEvent.click(screen.getByRole("button", { name: "Reload saved workspace" }));
      await waitFor(() => {
        expect(screen.queryByRole("button", { name: "Reload saved workspace" })).not.toBeInTheDocument();
      });

      expect(client.addSpread).toHaveBeenCalledTimes(1);
      expect(client.chapters).toHaveBeenCalledTimes(4);
    },
  );

  it("should add after selection, reload authoritative geometry and retain the deleted spread's album position", async () => {
    const inserted = { ...vacationPhoto, id: "inserted" };
    const added = { ...workspace, spreads: [opening, vacationOpening, inserted, ...workspace.spreads.slice(2)] };
    let finishAdd!: (spreadId: string) => void;
    const pendingAdd = new Promise<string>((resolve) => {
      finishAdd = resolve;
    });
    const client = buildClient(
      {
        composition: vi
          .fn()
          .mockResolvedValueOnce(membership(workspace.spreads))
          .mockResolvedValueOnce(membership(workspace.spreads))
          .mockResolvedValueOnce(membership(added.spreads))
          .mockResolvedValue(membership(workspace.spreads)),
        addSpread: vi.fn().mockReturnValueOnce(pendingAdd),
        deleteSpread: vi.fn().mockResolvedValue(undefined),
      },
      added,
    );
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("link", { name: "Kyoto" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Add spread" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Add spread" }));
    expect(screen.getByRole("button", { name: "Cover" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Cover" })).toHaveAccessibleDescription("Saving spread. Please wait.");
    window.history.replaceState(null, "", "?part=home");
    fireEvent(window, new PopStateEvent("popstate"));
    expect(window.location.search).toBe("?part=vacation");
    finishAdd(inserted.id);
    await waitFor(() => {
      expect(window.location.search).toBe("?part=inserted");
    });

    expect(client.addSpread).toHaveBeenCalledWith(album.id, vacation.id, "vacation-opening");
    expect(client.composition).toHaveBeenCalledTimes(3);
    fireEvent.click(screen.getByRole("button", { name: "Delete spread" }));
    await waitFor(() => {
      expect(window.location.search).toBe("?part=vacation-photo");
    });
    expect(client.deleteSpread).toHaveBeenCalledWith(album.id, "inserted", false);
    expect(client.composition).toHaveBeenCalledTimes(4);
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
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);

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
      render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
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
      render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
      await screen.findByRole("heading", { name: heading });
      const applied = await screen.findByRole("button", { name: "Applied two photos" });
      const categories = within(screen.getByRole("group", { name: "Photo slot count" }));

      expect(client.templates).toHaveBeenCalledWith(context);
      expect(categories.getAllByRole("button").map((button) => button.textContent)).toEqual(["1", "2", "10+"]);
      expect(applied).toHaveAttribute("aria-pressed", "true");
      await waitFor(() => expect(applied).toBeEnabled());
      fireEvent.click(applied);
      expect(client.changeSpreadTemplate).not.toHaveBeenCalled();
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
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
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
      render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);

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
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
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
    await waitFor(() => expect(earlier).toHaveAccessibleDescription("Select a photo spread first."));
    expect(later).toBeDisabled();
    fireEvent.click(screen.getByRole("link", { name: "Kyoto · Photo spread 2" }));
    await waitFor(() => expect(earlier).toBeEnabled());
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
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
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
    const view = render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
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
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
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
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
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

    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    await screen.findByRole("heading", { level: 1, name: album.title });
    const controls = within(screen.getByRole("group", { name: "Workspace controls" }));
    const organization = screen.getByRole("navigation", { name: "Album organization" });
    const links = within(organization).getAllByRole("link");
    const expectedLinks = ["Synopsis", "Kyoto", "Kyoto · Photo spread 1", "Home"];

    expect(screen.getByRole("img", { name: "Synopsis template geometry" })).toHaveAttribute("viewBox", "0 0 2381.102 765.354");
    expect(screen.getByRole("group", { name: "Synopsis composition" })).toHaveTextContent(album.synopsis);
    expect(screen.getByRole("group", { name: "Synopsis composition" })).toHaveTextContent(album.title);
    expect(controls.getAllByRole("button").map((button) => button.getAttribute("aria-label") ?? button.textContent)).toEqual([
      "Undo",
      "Redo",
      "Edit your name",
      "About browser alias",
      "Reader mode",
      "Spreads",
      "Cover",
    ]);
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
    expect(await screen.findByText("by Cosmin, Mara & Iacob · 2026")).toBeInTheDocument();

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

    expect(await screen.findByRole("alert")).toHaveTextContent("This album part could not be found.");

    fireEvent.click(screen.getByRole("button", { name: "Go to Synopsis" }));

    expect(screen.getByRole("heading", { name: "Synopsis" })).toBeInTheDocument();
  });

  it.each(["cover", "vacation-opening", "vacation-photo"])(
    "should restore a direct %s selection and collapse only photo controls, retaining right-column navigation",
    async (part) => {
      window.history.replaceState(null, "", `?part=${part}`);
      render(() => <AlbumOpen session={editingSession} id={album.id} client={buildClient()} events={events} />);
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
      const client = buildClient();
      const select = vi.fn().mockResolvedValue(undefined);
      const view = render(() => <AlbumOpen session={{ ...editingSession, select }} id={album.id} client={client} events={events} />);

      try {
        const authors = await screen.findByRole("group", { name: "Album authors" });
        await waitFor(() => expect(within(authors).getByRole("button", { name: "Mara" })).toBeEnabled());
        fireEvent.click(within(authors).getByRole("button", { name: "Mara" }));
        await screen.findByRole("button", { name: "Undo: Change authors" });
        await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled());
        fireEvent.click(await screen.findByRole("button", { name: "Reader mode" }));
        const exit = await screen.findByRole("button", { name: "Exit reader" });
        await waitFor(() => {
          expect(select).toHaveBeenLastCalledWith(null, false);
        });
        const readerControls = within(screen.getByRole("group", { name: "Reader controls" }));
        const description = "Saved chapter text read during Reader navigation";
        vi.mocked(client.chapters).mockResolvedValue(workspace.chapters.map((owner) => (owner.id === home.id ? { ...owner, description } : owner)));

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
        await waitFor(() => expect(screen.getByRole("img", { name: "Home composition" })).toHaveTextContent(description));
        expect(readerControls.getByRole("button", { name: "Next spread" })).toBeDisabled();

        fireEvent.keyDown(exit, { key: "ArrowLeft" });

        expect(window.location.search).toBe("?part=vacation-photo");

        fireEvent.click(exit);

        await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toHaveFocus());
        await waitFor(() => {
          expect(select).toHaveBeenLastCalledWith({ kind: "spread", id: vacationPhoto.id }, false);
        });
        expect(screen.getByRole("heading", { name: "Kyoto · Photo spread" })).toBeInTheDocument();
        expect(screen.queryByText(vacation.sourceFolder, { exact: true })).not.toBeInTheDocument();
        await waitFor(() => expect(screen.getByRole("button", { name: "Undo: Change authors" })).toBeEnabled());

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
      start: "2026-12-31",
      end: "2026-12-31",
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
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    const add = await screen.findByRole("button", { name: "Add chapter" });

    fireEvent.click(add);
    await waitFor(() => expect(within(screen.getByRole("dialog")).getByRole("button", { name: "Add chapter" })).toBeEnabled());
    expect(screen.queryByRole("textbox", { name: "Location / name" })).not.toBeInTheDocument();
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Add chapter" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Choose a chapter type.");
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "vacation" } });
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "" } });
    expect(screen.queryByRole("textbox", { name: "Location / name" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "vacation" } });
    expect(screen.getByRole("button", { name: "Remove Romania" })).toBeInTheDocument();
    expect(screen.getByLabelText("First day")).toHaveValue("01/01");
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
    fireEvent.input(screen.getByLabelText("First day"), { target: { value: "31/12" } });
    fireEvent.input(screen.getByLabelText("Last day"), { target: { value: "31/12" } });
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
      start: "2026-12-31",
      end: "2026-12-31",
      sourceFolder: "/nas/custom",
    };
    expect(client.createChapter).toHaveBeenCalledWith(album.id, expectedDetails);
    expect(window.location.search).toBe("?part=missed");
    expect(screen.getByRole("group", { name: "Rome composition" })).toBeInTheDocument();
    expect(screen.getByText("Countries", { exact: true })).toBeInTheDocument();
    await waitFor(() => expect(add).toHaveFocus());
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
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    const edit = await screen.findByRole("button", { name: "Edit chapter details" });

    fireEvent.click(edit);
    await waitFor(() => expect(screen.getByRole("button", { name: "Save chapter" })).toBeEnabled());
    expect(screen.queryByRole("combobox", { name: "Chapter type" })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Photo folder" })).toHaveValue(vacation.sourceFolder);
    expect(screen.getByRole("button", { name: "Remove Japan" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Remove Romania" })).not.toBeInTheDocument();
    fireEvent.input(screen.getByRole("textbox", { name: "Location / name" }), { target: { value: changed.name } });
    fireEvent.input(screen.getByLabelText("First day"), { target: { value: "01/01" } });
    fireEvent.input(screen.getByLabelText("Last day"), { target: { value: "01/01" } });
    const countrySearch = screen.getByRole("combobox", { name: "Search countries" });
    fireEvent.focus(countrySearch);
    fireEvent.input(countrySearch, { target: { value: "Romania" } });
    fireEvent.blur(countrySearch, { relatedTarget: screen.getByRole("button", { name: "Remove Japan" }) });
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    expect(screen.getByRole("option", { name: "Romania (RO)" })).toBeDisabled();
    expect(screen.getByRole("option", { name: "Romania (RO)" })).toHaveAccessibleDescription("Saving content. Please wait.");
    expect(screen.getByRole("button", { name: "Save chapter" })).toHaveAccessibleDescription("Saving content. Please wait.");
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
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add chapter" }));
    await waitFor(() => expect(within(screen.getByRole("dialog")).getByRole("button", { name: "Add chapter" })).toBeEnabled());
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
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit chapter details" })).toHaveFocus());
    fireEvent.click(screen.getByRole("button", { name: "Edit chapter details" }));
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    fireEvent.click(screen.getByRole("button", { name: "Edit chapter details" }));
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    fireEvent.click(screen.getByRole("button", { name: "Add chapter" }));
    expect(screen.queryByRole("combobox", { name: "Chapter type" })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Location / name" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  });

  it.each([
    { count: 64, lostAcknowledgement: false },
    { count: 65, lostAcknowledgement: false },
    { count: 64, lostAcknowledgement: true },
  ])(
    "should expose chapter creation capacity at $count persisted spreads with lostAcknowledgement=$lostAcknowledgement",
    async ({ count, lostAcknowledgement }) => {
      const spreads = [
        opening,
        vacationOpening,
        ...Array.from({ length: count - 3 }, (_, index) => ({ ...vacationPhoto, id: `spread-${String(index)}` })),
        ...workspace.spreads.slice(3),
      ];
      const finalChapter = {
        ...vacation,
        id: "final-chapter",
        name: "Final chapter",
        countries: ["ro"],
        start: "2026-01-01",
        end: "2026-01-01",
        sourceFolder: "/mnt/photos/2026/selection/Final chapter",
        description: "",
      };
      const finalOpening = { ...opening, id: "final-opening", chapter: finalChapter.id, kind: "chapter_start" as const };
      const savedWorkspace = {
        ...workspace,
        chapters: [vacation, finalChapter, home],
        spreads: [...spreads.slice(0, -1), finalOpening, ...spreads.slice(-1)],
      };
      const client = buildClient(
        {
          chapters: lostAcknowledgement
            ? vi
                .fn()
                .mockResolvedValueOnce(workspace.chapters)
                .mockRejectedValueOnce(new Error("Saved metadata offline"))
                .mockResolvedValue(savedWorkspace.chapters)
            : vi.fn().mockResolvedValueOnce(workspace.chapters).mockResolvedValue(savedWorkspace.chapters),
          composition: vi.fn().mockResolvedValueOnce(membership(spreads)).mockResolvedValue(membership(savedWorkspace.spreads)),
          createChapter: lostAcknowledgement
            ? vi.fn().mockRejectedValue(new AcknowledgedWriteError("Malformed created identity"))
            : vi.fn().mockResolvedValue({ id: finalChapter.id }),
        },
        { ...savedWorkspace, photoInventories: { ...workspace.photoInventories, [finalChapter.id]: inventory } },
      );
      render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
      const add = await screen.findByRole("button", { name: "Add chapter" });

      if (count === 65) {
        expect(add).toBeDisabled();
        await waitFor(() => expect(add).toHaveAccessibleDescription("This album has 65 interior spreads. No more chapters can be added."));
      } else {
        expect(add).toBeEnabled();
        fireEvent.click(add);
        await waitFor(() => expect(within(screen.getByRole("dialog")).getByRole("button", { name: "Add chapter" })).toBeEnabled());
        expect(screen.getByRole("dialog")).toBeInTheDocument();
        fireEvent.input(screen.getByRole("textbox", { name: "Location / name" }), { target: { value: finalChapter.name } });
        fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
        if (lostAcknowledgement) {
          await screen.findByRole("alert");
          fireEvent.click(screen.getByRole("button", { name: "Read saved chapter" }));
        }
        expect(await screen.findByRole("heading", { name: finalChapter.name })).toBeInTheDocument();
        await waitFor(() => {
          expect(screen.getByRole("region", { name: "Album workspace" })).toHaveFocus();
        });
        expect(add).toBeDisabled();
        await waitFor(() => expect(add).toHaveAccessibleDescription("This album has 65 interior spreads. No more chapters can be added."));
      }
    },
  );

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
    let failHomeRead = false;
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
        chapterPhotos: vi.fn().mockImplementation((id: string) => {
          if (id === home.id && failHomeRead) {
            failHomeRead = false;
            return Promise.reject(new Error("Inventory unavailable"));
          }
          return Promise.resolve(id === home.id ? { ...homePhotos, processing: { ...inventory.processing, state: "running" } } : vacationPhotos);
        }),
      },
      savedWorkspace,
    );
    const view = render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={liveEvents} />);

    expect(client.chapters).not.toHaveBeenCalled();
    receive({ kind: "subscribed" });
    await screen.findByRole("img", { name: "Kyoto.jpg" });
    expect(screen.getByRole("img", { name: "Home.jpg" })).toBeInTheDocument();
    expect(screen.getByText("1 unplaced / 2 total")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("link", { name: "Kyoto" }));
    expect(screen.queryByRole("img", { name: "Home.jpg" })).not.toBeInTheDocument();
    const refresh = screen.getByRole("button", { name: "Refresh photos" });
    await waitFor(() => expect(refresh).toBeEnabled());
    await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled());
    refresh.focus();
    fireEvent.click(refresh);
    await waitFor(() => {
      expect(client.refreshPhotos).toHaveBeenCalledExactlyOnceWith(vacation.id);
    });
    failHomeRead = true;
    receive({ kind: "inventory-changed", chapterId: home.id });
    await waitFor(() => {
      expect(client.chapterPhotos).toHaveBeenCalledWith(home.id, expect.any(AbortSignal));
    });
    expect(refresh).toHaveFocus();
    expect(window.location.search).toBe("?part=vacation");
    fireEvent.click(screen.getByRole("link", { name: "Synopsis" }));
    await waitFor(() => expect(within(screen.getByRole("region", { name: "Processing Home" })).getByText(/running/)).toBeInTheDocument());
    await waitFor(() => expect(refresh).toBeEnabled());
    await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled());
    expect(screen.queryByRole("button", { name: "Retry inventory for Home" })).not.toBeInTheDocument();
    failHomeRead = true;
    receive({ kind: "inventory-changed", chapterId: home.id });
    fireEvent.click(await screen.findByRole("button", { name: "Retry inventory for Home" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Retry inventory for Home" })).not.toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Cover" }));
    expect(screen.getByRole("img", { name: "Home.jpg" })).toBeInTheDocument();
    await waitFor(() => expect(refresh).toBeEnabled());
    await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Refresh photos" }));
    await waitFor(() => {
      expect(client.refreshPhotos).toHaveBeenCalledTimes(3);
    });
    receive({ kind: "interrupted" });
    expect(screen.getByText(/Live updates interrupted/)).toBeInTheDocument();
    receive({ kind: "subscribed" });
    await waitFor(() => expect(screen.queryByText(/Catching up/)).not.toBeInTheDocument());
    expect(client.chapters).toHaveBeenCalledTimes(5);
    expect(client.open).toHaveBeenCalledTimes(5);
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
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add chapter" }));
    await waitFor(() => expect(within(screen.getByRole("dialog")).getByRole("button", { name: "Add chapter" })).toBeEnabled());
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
    render(() => <AlbumOpen session={editingSession} id={id()} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add chapter" }));
    await waitFor(() => expect(within(screen.getByRole("dialog")).getByRole("button", { name: "Add chapter" })).toBeEnabled());
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
    render(() => <AlbumOpen session={editingSession} id={album.id} client={client} events={liveEvents} />);
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
    await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled());
    const readsBeforeReconnect = vi.mocked(client.chapters).mock.calls.length;
    vi.mocked(client.chapters).mockResolvedValue(
      workspace.chapters.map((owner) => (owner.id === vacation.id ? { ...owner, description: "Saved while disconnected" } : owner)),
    );
    receive({ kind: "subscribed" });
    expect(screen.getByRole("button", { name: "Edit chapter details" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Edit chapter details" })).toHaveAccessibleDescription("Refreshing saved workspace. Please wait.");
    await waitFor(() => {
      expect(client.chapters).toHaveBeenCalledTimes(readsBeforeReconnect + 1);
    });
    await waitFor(() => {
      expect(screen.queryByText(/Catching up/)).not.toBeInTheDocument();
    });
    expect(screen.getByRole("link", { name: "Kyoto" })).toBe(chapter);
    expect(chapter).toHaveFocus();
    expect(screen.getByRole("group", { name: "Kyoto composition" })).toHaveTextContent("Saved while disconnected");
    const grip = screen.getByRole("button", { name: "Collapse photo pane" });
    grip.focus();
    fireEvent.keyDown(grip, { key: "ArrowRight" });
    expect(window.location.search).toBe("?part=vacation-photo");
    expect(grip).toHaveAccessibleDescription(/320px/);
    await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled());
    fireEvent.keyDown(grip, { key: "ArrowLeft" });
    expect(window.location.search).toBe("?part=vacation");
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit chapter details" })).toBeEnabled());
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
    photo: vi.fn().mockRejectedValue(new Error("Unexpected individual photo read")),
    gearCatalog: vi.fn().mockResolvedValue({ cameras: [], lenses: [] }),
    chapterGear: vi.fn().mockResolvedValue([]),
    saveChapterGear: vi.fn().mockResolvedValue(undefined),
    createCamera: vi.fn(),
    updateCamera: vi.fn(),
    deleteCamera: vi.fn(),
    createLens: vi.fn(),
    updateLens: vi.fn(),
    deleteLens: vi.fn(),
    readPlacements: vi.fn().mockImplementation((_album: string, context: PlacementContext): Promise<PlacementSnapshot> =>
      Promise.resolve(
        context.kind === "cover"
          ? {
              kind: "cover",
              placements: [],
              frontSlotId: "front",
              backSlotId: "back",
              pageCount: 30,
              geometry: coverGeometries.front,
              spineOwner: "front",
            }
          : {
              kind: "spread",
              id: context.id,
              placements: [],
              layout: { kind: "photos_only", bleed: { state: "disabled" as const, available: true }, templateId: "layout", slots: [photoSlot] },
            },
      ),
    ),
    editPlacements: vi.fn().mockImplementation((_album: string, context: PlacementContext): Promise<PlacementSnapshot> =>
      Promise.resolve(
        context.kind === "cover"
          ? {
              kind: "cover",
              placements: [],
              frontSlotId: "front",
              backSlotId: "back",
              pageCount: 30,
              geometry: coverGeometries.front,
              spineOwner: "front",
            }
          : {
              kind: "spread",
              id: context.id,
              placements: [],
              layout: { kind: "photos_only", bleed: { state: "disabled" as const, available: true }, templateId: "layout", slots: [photoSlot] },
            },
      ),
    ),
    list: vi.fn(),
    create: vi.fn(),
    rename: vi.fn(),
    remove: vi.fn(),
    open: vi.fn().mockResolvedValue(album),
    saveSynopsis: vi.fn().mockImplementation((_id: string, text: string) => Promise.resolve({ ...album, synopsis: text })),
    saveAuthors: vi.fn().mockImplementation((_id: string, authors: readonly Author[]) => Promise.resolve({ ...album, authors })),
    saveDescription: vi.fn().mockResolvedValue(undefined),
    saveCaption: vi.fn().mockResolvedValue(undefined),
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

async function clickHistory(name: string) {
  const control = await screen.findByRole("button", { name });
  await waitFor(() => expect(control).toBeEnabled());
  fireEvent.click(control);
}

function buildHistoryBook(initialSpreads: readonly Spread[] = workspace.spreads, withPhoto = false) {
  let spreads = [...initialSpreads];
  let chapters = [...workspace.chapters];
  let metadata = album;
  let gear: readonly CameraSelection[] = [];
  let generation = 0;
  let photo: Photo = {
    id: "history-photo",
    filename: "History.jpg",
    width: 1200,
    height: 800,
    capturedLocal: null,
    caption: "Saved caption",
    thumbnail: { url: "/thumbnails/history.jpg", width: 600, height: 400 },
  };
  const client = buildClient({
    open: vi.fn(() => Promise.resolve(metadata)),
    chapters: vi.fn(() => Promise.resolve(chapters)),
    composition: vi.fn(() => Promise.resolve(membership(spreads))),
    spread: vi.fn((_album: string, id: string) => {
      const saved = spreads.find((spread) => spread.id === id);
      if (saved === undefined) throw new Error("Missing test spread.");
      return Promise.resolve(saved);
    }),
    readPlacements: vi.fn().mockImplementation((_album: string, context: PlacementContext): Promise<PlacementSnapshot> => {
      if (context.kind === "cover")
        return Promise.resolve({
          kind: "cover",
          placements: [],
          pageCount: 30,
          geometry: coverGeometries.front,
          spineOwner: "front",
          frontSlotId: "front",
          backSlotId: "back",
        });
      const saved = spreads.find((spread) => spread.id === context.id);
      if (saved === undefined) throw new Error("Missing test spread.");
      return Promise.resolve({
        kind: "spread",
        id: saved.id,
        placements: saved.placements,
        layout: { kind: saved.kind, templateId: saved.templateId, slots: saved.slots, bleed: saved.bleed },
      });
    }),
    saveAuthors: vi.fn((_id: string, authors: readonly Author[]) => {
      metadata = { ...metadata, authors };
      return Promise.resolve(metadata);
    }),
    gearCatalog: vi.fn().mockResolvedValue({
      cameras: [{ id: "body", name: "Body", code: "body", kind: "interchangeable_lens", mount: "sony_e", sensorFormat: "aps_c" }],
      lenses: [{ id: "lens", name: "Lens", code: "lens", mount: "sony_e", coverage: "aps_c" }],
    }),
    saveSynopsis: vi.fn((_id: string, synopsis: string) => {
      metadata = { ...metadata, synopsis };
      return Promise.resolve(metadata);
    }),
    saveDescription: vi.fn((_album: string, chapterId: string, description: string) => {
      chapters = chapters.map((chapter) => (chapter.id === chapterId ? { ...chapter, description } : chapter));
      return Promise.resolve();
    }),
    chapterGear: vi.fn(() => Promise.resolve(gear)),
    chapterPhotos: vi.fn((id: string) => {
      if (!withPhoto || id !== vacation.id) return Promise.resolve(inventory);
      return Promise.resolve({ ...inventory, photos: [photo], placedPhotoIds: [photo.id], counts: { total: 1, unplaced: 0 } });
    }),
    saveCaption: vi.fn((_chapter: string, _photo: string, caption: string) => {
      photo = { ...photo, caption: caption === "" ? null : caption };
      return Promise.resolve();
    }),
    saveChapterGear: vi.fn((_album: string, _chapter: string, selection: readonly CameraSelection[]) => {
      gear = selection;
      return Promise.resolve();
    }),
    updateChapter: vi.fn((_album: string, id: string, details: ChapterDetails) => {
      chapters = chapters.map((chapter) => (chapter.id === id ? { ...chapter, ...details } : chapter));
      return Promise.resolve({ id, scheduling: "not-requested" as const });
    }),
    addSpread: vi.fn((_album: string, chapterId: string, afterId: string) => {
      const id = `recreated-${String(++generation)}`;
      const fresh = { ...vacationPhoto, chapter: chapterId, id, slots: [{ ...photoSlot, id: `${id}-slot` }] };
      spreads.splice(spreads.findIndex((spread) => spread.id === afterId) + 1, 0, fresh);
      return Promise.resolve(id);
    }),
    deleteSpread: vi.fn((_album: string, id: string) => {
      spreads = spreads.filter((spread) => spread.id !== id);
      return Promise.resolve();
    }),
    templates: vi.fn((context: TemplateContext) =>
      Promise.resolve([
        { id: "layout", name: "Applied layout", slots: context === "photos_only" ? [photoSlot] : [photoSlot, textSlot] },
        { id: "alternate", name: "Alternate layout", slots: [{ ...photoSlot, x: 10, width: 40 }] },
      ]),
    ),
    changeSpreadTemplate: vi.fn((_album: string, id: string, templateId: string) => {
      const slots = templateId === "alternate" ? [{ ...photoSlot, x: 10, width: 40 }] : [photoSlot];
      spreads = spreads.map((spread) =>
        spread.id === id ? { ...spread, templateId, slots: slots.map((slot) => ({ ...slot, id: `slot-${String(++generation)}` })) } : spread,
      );
      return Promise.resolve();
    }),
    replaceChapterComposition: vi.fn((_album: string, chapterId: string, expected: readonly string[], proposed: readonly string[]) => {
      const old = spreads.filter((spread) => spread.chapter === chapterId);
      if (JSON.stringify(old.map((spread) => spread.id)) !== JSON.stringify(expected))
        return Promise.reject(new AlbumRequestError(409, "Order changed."));
      const reordered = proposed.map((id) => {
        const found = old.find((spread) => spread.id === id);
        if (found === undefined) throw new Error("Missing chapter member.");
        return found;
      });
      const index = spreads.findIndex((spread) => spread.chapter === chapterId);
      spreads.splice(index, old.length, ...reordered);
      return Promise.resolve();
    }),
  });
  return { client, spreads: () => spreads, metadata: () => metadata, chapters: () => chapters, gear: () => gear, photo: () => photo };
}
