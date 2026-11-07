import { createSignal, getOwner, runWithOwner, type JSX, type Owner } from "solid-js";
import { cleanup, fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AlbumWorkspace } from "../../../src/features/albums/workspace/AlbumWorkspace";
import { AlbumAuthors } from "../../../src/features/albums/workspace/AlbumAuthors";
import { ChapterDialog } from "../../../src/features/albums/workspace/ChapterDialog";
import { ChapterMetadata } from "../../../src/features/albums/workspace/ChapterMetadata";
import { ChapterOutline } from "../../../src/features/albums/workspace/ChapterOutline";
import { CoverCanvas, SpreadCanvas } from "../../../src/features/albums/workspace/Canvas";
import { FullscreenReader } from "../../../src/features/albums/workspace/FullscreenReader";
import { PhotoPane } from "../../../src/features/albums/workspace/PhotoPane";
import { TextEditor } from "../../../src/features/albums/workspace/TextEditor";
import { TemplateSelector } from "../../../src/features/albums/workspace/TemplateSelector";
import { SpreadConfirmation } from "../../../src/features/albums/workspace/SpreadConfirmation";
import { BookHistoryControls } from "../../../src/features/albums/workspace/BookHistoryControls";
import { BookExportControl } from "../../../src/features/albums/workspace/BookExportControl";
import { createBookHistory, type HistoryAction } from "../../../src/features/albums/workspace/BookHistory";
import { createPlacementSession } from "../../../src/features/albums/workspace/PlacementSession";
import { createCaptionEditing, type CaptionEditing } from "../../../src/features/albums/workspace/PhotoCaption";
import { createSpreadEditing } from "../../../src/features/albums/workspace/SpreadEditing";
import { createContentCompensation } from "../../../src/features/albums/workspace/ContentCompensation";
import { createChapterCompensation } from "../../../src/features/albums/workspace/ChapterCompensation";
import { createPlacementCompensation } from "../../../src/features/albums/workspace/PlacementCompensation";
import { AcknowledgedWriteError, AlbumRequestError, SpreadOccupiedError, type Album } from "../../../src/features/albums/model/Album";
import { snapshotSpread, type Chapter, type Workspace } from "../../../src/features/albums/model/Workspace";
import type { PlacementEdit, PlacementSnapshot } from "../../../src/features/albums/model/Placement";
import type { WorkspaceEditingSession } from "../../../src/features/editing/EditorSession";
import { editingSession } from "../editing/EditingSessionStub";
import { buildAlbumClient } from "./AlbumClientStub";
import { coverGeometries } from "./CoverGeometry";
import { buildBookHistory } from "./BookHistoryStub";
import { buildPlacementSession } from "./PlacementSessionStub";
import { buildContentSave } from "./ContentSaveStub";
import { CompositionControls } from "../../../src/features/albums/workspace/CompositionControls";
import { BrowserAlias } from "../../../src/features/editing/BrowserAlias";
import { RetainedCaption } from "../../../src/features/albums/workspace/RetainedCaption";
import type * as CanvasModule from "../../../src/features/albums/workspace/Canvas";
import type * as OutlineModule from "../../../src/features/albums/workspace/ChapterOutline";
import type * as ChapterCompensationModule from "../../../src/features/albums/workspace/ChapterCompensation";
import type * as PlacementCompensationModule from "../../../src/features/albums/workspace/PlacementCompensation";

vi.mock("../../../src/features/albums/workspace/AlbumAuthors", () => ({ AlbumAuthors: vi.fn(recordChildOwner) }));
vi.mock("../../../src/features/albums/workspace/ChapterDialog", () => ({ ChapterDialog: vi.fn(recordChildOwner) }));
vi.mock("../../../src/features/albums/workspace/ChapterMetadata", () => ({ ChapterMetadata: vi.fn(recordChildOwner) }));
vi.mock("../../../src/features/albums/workspace/TextEditor", () => ({ TextEditor: vi.fn(recordChildOwner) }));
vi.mock("../../../src/features/albums/workspace/TemplateSelector", () => ({ TemplateSelector: vi.fn(recordChildOwner) }));
vi.mock("../../../src/features/albums/workspace/SpreadConfirmation", () => ({
  SpreadConfirmation: vi.fn(recordChildOwner),
}));
vi.mock("../../../src/features/albums/workspace/BookHistoryControls", () => ({
  BookHistoryControls: vi.fn(recordChildOwner),
}));
vi.mock("../../../src/features/albums/workspace/BookExportControl", () => ({ BookExportControl: vi.fn(recordChildOwner) }));
vi.mock("../../../src/features/albums/workspace/CompositionControls", () => ({
  CompositionControls: vi.fn(recordChildOwner),
}));
vi.mock("../../../src/features/albums/workspace/RetainedCaption", () => ({ RetainedCaption: vi.fn(recordChildOwner) }));
vi.mock("../../../src/features/editing/BrowserAlias", () => ({ BrowserAlias: vi.fn(recordChildOwner) }));
vi.mock("../../../src/features/albums/workspace/PhotoPane", () => ({
  PhotoPane: vi.fn((props: Parameters<typeof PhotoPane>[0]) => {
    childOwners.set(props, getOwner());
    return <>{props.children}</>;
  }),
}));
vi.mock("../../../src/features/albums/workspace/FullscreenReader", () => ({
  FullscreenReader: vi.fn((props: Parameters<typeof FullscreenReader>[0]) => {
    childOwners.set(props, getOwner());
    return <>{props.children}</>;
  }),
}));
vi.mock("../../../src/features/albums/workspace/Canvas", async (original) => ({
  ...(await original<typeof CanvasModule>()),
  CoverCanvas: vi.fn(recordChildOwner),
  SpreadCanvas: vi.fn(recordChildOwner),
}));
vi.mock("../../../src/features/albums/workspace/ChapterOutline", async (original) => ({
  ...(await original<typeof OutlineModule>()),
  ChapterOutline: vi.fn(recordChildOwner),
}));
vi.mock("../../../src/features/albums/workspace/BookHistory", () => ({ createBookHistory: vi.fn() }));
vi.mock("../../../src/features/albums/workspace/PlacementSession", () => ({ createPlacementSession: vi.fn() }));
vi.mock("../../../src/features/albums/workspace/PhotoCaption", () => ({ createCaptionEditing: vi.fn() }));
vi.mock("../../../src/features/albums/workspace/SpreadEditing", () => ({ createSpreadEditing: vi.fn() }));
vi.mock("../../../src/features/albums/workspace/ContentCompensation", () => ({ createContentCompensation: vi.fn() }));
vi.mock("../../../src/features/albums/workspace/ChapterCompensation", async (original) => ({
  ...(await original<typeof ChapterCompensationModule>()),
  createChapterCompensation: vi.fn(),
}));
vi.mock("../../../src/features/albums/workspace/PlacementCompensation", async (original) => ({
  ...(await original<typeof PlacementCompensationModule>()),
  createPlacementCompensation: vi.fn(),
}));

const album: Album = {
  id: "album",
  year: 2026,
  title: "Saved album",
  createdAt: "2026-10-25T12:00:00Z",
  synopsis: "Saved synopsis",
  authors: ["Cosmin", "Mara"],
};
const chapter: Chapter = {
  id: "chapter",
  kind: "vacation",
  name: "Kyoto",
  countries: ["jp"],
  start: "2026-04-02",
  end: "2026-04-14",
  sourceFolder: "/photos/Kyoto",
  description: "Spring in Japan",
};
const textSlot = { id: "text", kind: "text" as const, x: 50, y: 4, width: 48, height: 92 };
const photoSlot = { id: "photo-slot", kind: "photo" as const, x: 2, y: 4, width: 23, height: 42 };
const opening = {
  id: "opening",
  chapter: null,
  kind: "album_start" as const,
  templateId: "layout",
  bleed: { state: "disabled" as const, available: true },
  slots: [textSlot],
  placements: [],
};
const chapterOpening = {
  ...opening,
  id: "chapter-opening",
  chapter: chapter.id,
  kind: "chapter_start" as const,
  slots: [textSlot, photoSlot],
};
const photoSpread = { ...chapterOpening, id: "photo-spread", kind: "photos_only" as const, slots: [photoSlot] };
const photo = {
  id: "photo",
  filename: "Saved.jpg",
  width: 1200,
  height: 800,
  capturedLocal: null,
  caption: "Saved caption",
  thumbnail: { url: "/thumbnails/saved.jpg", width: 600, height: 400 },
};
const workspace: Workspace = {
  year: 2026,
  photoRoot: "/photos",
  chapters: [chapter],
  spreads: [opening, chapterOpening, photoSpread],
  countries: [
    { id: "ro", name: "Romania", code: "RO" },
    { id: "jp", name: "Japan", code: "JP" },
  ],
  photoInventories: {
    chapter: {
      photos: [photo],
      placedPhotoIds: [],
      counts: { unplaced: 1, total: 1 },
      processing: { state: "idle", unsupportedCount: 0, skippedCount: 0, issues: [] },
    },
  },
};
const action: HistoryAction = {
  label: "Configured compensation",
  apply: vi.fn().mockResolvedValue({ kind: "saved" }),
  reconcile: vi.fn().mockResolvedValue({ kind: "saved" }),
};
const childOwners = new WeakMap<object, Owner | null>();

beforeEach(() => {
  vi.clearAllMocks();
  window.history.replaceState(null, "", "/albums/album");
});
afterEach(() => {
  cleanup();
  document.querySelectorAll("[data-workspace-test-opener]").forEach((element) => {
    element.remove();
  });
});

describe("AlbumWorkspace", () => {
  it("should present supplied workspace data and complement its collaborators without acquiring an album", async () => {
    const refs = buildRefs();
    refs.setWorkspace({ ...workspace, chapters: [{ ...chapter, countries: ["jp", "ro"] }] });
    renderWorkspace(refs);
    await waitFor(() => {
      expect(refs.onReady).toHaveBeenCalledOnce();
    });

    expect(screen.getByRole("heading", { name: album.title })).toBeInTheDocument();
    expect(latest(PhotoPane).inventories).toBe(workspace.photoInventories);
    expect(latest(ChapterOutline).spreads).toEqual(workspace.spreads);
    expect(latest(SpreadCanvas).synopsisCountries).toEqual([workspace.countries[1], workspace.countries[0]]);
    expect(vi.mocked(refs.client.open)).not.toHaveBeenCalled();
    expect(vi.mocked(createPlacementSession).mock.lastCall?.[0]).toBe(refs.client);
    expect(vi.mocked(createSpreadEditing).mock.lastCall?.[0].history).toBe(refs.history);
    expect(refs.placement.selectSpread).toHaveBeenCalledWith(album.id, snapshotSpread(opening));
    expect(latest(CompositionControls).session).toBe(refs.placement);
    expect(latest(BrowserAlias).session).toBe(refs.session);
    const spreads = provided(vi.mocked(createSpreadEditing).mock.lastCall?.[0]);
    const access = provided(vi.mocked(createPlacementSession).mock.lastCall?.[2]);
    expect(spreads.spreads()).toBe(workspace.spreads);
    expect(spreads.lossRevision()).toBe(0);
    expect(access.available()).toBe(true);
    expect(access.authority()).toBe(1);
    refs.setTargetReason("Another browser owns this spread");
    expect(access.available()).toBe(false);
    expect(latest(SpreadCanvas).editing?.textUnavailable).toBe("Another browser owns this spread");
    refs.setLossRevision(1);
    expect(spreads.lossRevision()).toBe(1);
    spreads.removed(chapter.id);
    expect(window.location.search).toBe("?part=chapter");
  });

  it("should refresh navigation, remember interior selection and restore browser history without wrapping", async () => {
    const refs = buildRefs();
    renderWorkspace(refs);
    await settle();

    latest(ChapterOutline).onSelect(chapter.id);
    await settle();
    expect(window.location.search).toBe("?part=chapter");
    expect(latest(ChapterMetadata).chapter).toBe(chapter);
    expect(latest(PhotoPane).chapters).toEqual([chapter]);
    fireEvent.click(screen.getByRole("button", { name: "Cover" }));
    await settle();
    expect(refs.placement.load).toHaveBeenCalledWith(album.id, { kind: "cover" });
    expect(latest(CoverCanvas).title).toBe(album.title);
    expect(latest(CompositionControls).reader).toBe(false);
    expect(latest(CompositionControls).unavailable).toBeUndefined();
    fireEvent.click(screen.getByRole("button", { name: "Spreads" }));
    expect(window.location.search).toBe("?part=chapter");
    window.history.replaceState(null, "", "/albums/album?part=photo-spread");
    window.dispatchEvent(new PopStateEvent("popstate"));
    await settle();
    expect(latest(ChapterOutline).part).toBe(photoSpread.id);
    latest(FullscreenReader).onNavigate(-1);
    expect(window.location.search).toBe("?part=chapter-opening");
    latest(FullscreenReader).onNavigate(1);
    expect(window.location.search).toBe("?part=photo-spread");
  });

  it.each([null, chapter.id])(
    "should refuse departure, history and keyboard navigation from %s while a collaborator retains unresolved content",
    async (part) => {
      const refs = buildRefs();
      renderWorkspace(refs, part);
      await settle();
      latest(AlbumAuthors).onBlock("Finish authors");

      latest(ChapterOutline).onSelect(chapter.id);
      fireEvent.keyDown(document, { key: "ArrowRight" });
      window.history.replaceState(null, "", "/");
      window.dispatchEvent(new PopStateEvent("popstate"));
      const unloading = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(unloading);
      fireEvent.click(screen.getByRole("link", { name: "Back to album catalog" }));

      expect(window.location.pathname).toBe("/albums/album");
      expect(window.location.search).toBe(part === null ? "" : "?part=chapter");
      expect(unloading.defaultPrevented).toBe(true);
      expect(latest(BookExportControl).unavailable).toBe("Finish authors");
      latest(AlbumAuthors).onBlock(undefined);
      const allowedUnload = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(allowedUnload);
      expect(allowedUnload.defaultPrevented).toBe(false);
      latest(ChapterOutline).onSelect(chapter.id);
      expect(window.location.search).toBe("?part=chapter");
    },
  );

  it("should release the selected editing target before leaving and fence completion after disposal", async () => {
    const refs = buildRefs();
    const release = deferred<boolean>();
    renderWorkspace(refs);
    await settle();
    refs.session.select.mockReturnValue(release.promise);

    fireEvent.click(screen.getByRole("link", { name: "Back to album catalog" }));
    expect(window.location.pathname).toBe("/albums/album");
    expect(refs.session.select).toHaveBeenLastCalledWith(null);
    cleanup();
    release.resolve(true);
    await settle();
    expect(window.location.pathname).toBe("/albums/album");
  });

  it.each([
    { kind: "created", part: null, disconnected: false },
    { kind: "existing", part: chapter.id, disconnected: false },
    { kind: "existing", part: chapter.id, disconnected: true },
  ])("should forward $kind chapter saves and reconcile selection with opener disconnected=$disconnected", async ({ kind, part, disconnected }) => {
    const refs = buildRefs();
    renderWorkspace(refs, part);
    await settle();
    if (kind === "created") latest(ChapterOutline).onCreate(refs.opener);
    else latest(ChapterMetadata).onEdit(refs.opener);
    const dialog = latest(ChapterDialog);
    const details = {
      kind: chapter.kind,
      name: chapter.name,
      countries: chapter.countries,
      start: chapter.start,
      end: chapter.end,
      sourceFolder: chapter.sourceFolder,
    };
    const result = { id: chapter.id, scheduling: "queued" as const };

    expect(await dialog.save(details)).toEqual(result);
    expect(kind === "created" ? refs.client.createChapter : refs.client.updateChapter).toHaveBeenCalledWith(
      ...(kind === "created" ? [album.id, details] : [album.id, chapter.id, details]),
    );
    if (disconnected) refs.opener.remove();
    dialog.onSaved(result);
    await settle();
    expect(refs.onSaved).toHaveBeenCalledWith(result);
    expect(disconnected ? screen.getByRole("region", { name: "Album workspace" }) : refs.opener).toHaveFocus();
    expect(window.location.search).toBe("?part=chapter");
    expect(dialog.resources.maybeChapterId).toBe(kind === "created" ? undefined : chapter.id);
  });

  it("should refresh a reconciled identity, return focus safely and retain saved chapter feedback on failed refresh", async () => {
    const refs = buildRefs();
    renderWorkspace(refs);
    await settle();
    latest(ChapterOutline).onCreate(refs.opener);
    const dialog = latest(ChapterDialog);
    refs.setWorkspaceError(new Error("Saved acquisition failed"));
    refs.opener.disabled = true;
    dialog.returnFocus();

    dialog.onReconciled(chapter.id);
    await settle();

    expect(refs.onRefresh).toHaveBeenCalled();
    expect(screen.getByRole("region", { name: "Album workspace" })).toHaveFocus();
    expect(window.location.search).toBe("?part=chapter");
    expect(refs.client.createChapter).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Retry workspace" }));
    await settle();
    expect(refs.onRefresh).toHaveBeenCalledTimes(2);
  });

  it("should publish exact acknowledged authors, synopsis and trimmed name without emulating storage", async () => {
    const refs = buildRefs();
    const saved = { ...album, authors: ["Mara"] as const, synopsis: "Acknowledged synopsis", title: "Acknowledged name" };
    vi.mocked(refs.client.saveAuthors).mockResolvedValue(saved);
    vi.mocked(refs.client.saveSynopsis).mockResolvedValue(saved);
    vi.mocked(refs.client.rename).mockResolvedValue(saved);
    renderWorkspace(refs);
    await settle();

    expect(await latest(AlbumAuthors).save(["Mara"])).toEqual(["Mara"]);
    provided(latest(SpreadCanvas).editing?.onEditText)(refs.opener);
    expect(await latest(TextEditor).save("Requested synopsis")).toBe(saved.synopsis);
    latest(TextEditor).onClose();
    fireEvent.click(screen.getByRole("button", { name: "Edit album name" }));
    expect(await latest(TextEditor).save("  Requested name  ")).toBe(saved.title);

    expect(refs.client.saveSynopsis).toHaveBeenCalledWith(album.id, "Requested synopsis");
    expect(refs.client.rename).toHaveBeenCalledWith(album.id, "Requested name");
    expect(refs.onAlbumSaved.mock.calls.map(([metadata]) => metadata)).toEqual([saved, saved, { ...album, title: saved.title }]);
    expect(refs.onRefresh).not.toHaveBeenCalled();
    expect(await latest(TextEditor).read()).toBe(album.title);
    provided(latest(TextEditor).onCompleted)(album.title, album.title);
    expect(refs.history.record).not.toHaveBeenCalled();
  });

  it("should classify publication and identity failures as acknowledged instead of requesting another write", async () => {
    const refs = buildRefs();
    vi.mocked(refs.client.saveAuthors).mockResolvedValue(album);
    vi.mocked(refs.client.rename).mockResolvedValue({ ...album, id: "other", title: "Wrong identity" });
    refs.onAlbumSaved.mockImplementation(() => {
      throw new Error("Publication failed");
    });
    renderWorkspace(refs);
    await settle();

    await expect(latest(AlbumAuthors).save(album.authors)).rejects.toBeInstanceOf(AcknowledgedWriteError);
    fireEvent.click(screen.getByRole("button", { name: "Edit album name" }));
    await expect(latest(TextEditor).save("New name")).rejects.toBeInstanceOf(AcknowledgedWriteError);

    expect(refs.client.saveAuthors).toHaveBeenCalledOnce();
    expect(refs.client.rename).toHaveBeenCalledOnce();
    expect(refs.onRefresh).not.toHaveBeenCalled();
  });

  it("should scope description and caption commands to saved membership and read independent refreshed values", async () => {
    const refs = buildRefs();
    renderWorkspace(refs, chapter.id);
    await settle();
    provided(latest(SpreadCanvas).editing?.onEditText)(refs.opener);
    const editor = latest(TextEditor);
    const captions = vi.mocked(createCaptionEditing).mock.lastCall?.[0];
    if (captions === undefined) throw new Error("Caption operations were not supplied");

    await editor.save("Requested description");
    await captions.save(photo.id, "Requested caption");
    expect(await editor.read()).toBe(chapter.description);
    expect(await captions.read(photo.id)).toBe(photo.caption);

    expect(refs.client.saveDescription).toHaveBeenCalledWith(album.id, chapter.id, "Requested description");
    expect(refs.client.saveCaption).toHaveBeenCalledWith(chapter.id, photo.id, "Requested caption");
    expect(refs.onRefresh).toHaveBeenCalledTimes(2);
    await expect(captions.save("absent", "Text")).rejects.toBeInstanceOf(AlbumRequestError);
    await expect(captions.read("absent")).rejects.toThrow("The saved photo could not be found");
    refs.setWorkspace({ ...workspace, chapters: [] });
    await expect(editor.read()).rejects.toThrow("The saved chapter could not be found");
  });

  it("should retain child blocking and recovery wiring while author, text and chapter edits coexist", async () => {
    const refs = buildRefs();
    renderWorkspace(refs);
    await settle();
    fireEvent.click(screen.getByRole("button", { name: "Edit album name" }));
    const editor = latest(TextEditor);
    editor.onBlock("Finish name");
    latest(AlbumAuthors).onBlock("Finish authors");

    expect(latest(AlbumAuthors).unavailable).toBe("Finish name");
    expect(latest(BookHistoryControls).unavailable).toBe("Finish name");
    expect(editor.label).toBe("Album name");
    expect(editor.validate?.("")).toBeDefined();
    editor.onBlock(undefined);
    latest(AlbumAuthors).onBlock(undefined);
    editor.returnFocus();
    editor.onClose();
    await settle();
    expect(screen.getByRole("button", { name: "Edit album name" })).toHaveFocus();
    latest(ChapterOutline).onCreate(refs.opener);
    provided(latest(ChapterDialog).onBlock)("Finish chapter");
    expect(latest(PhotoPane).unavailable).toBe("Finish chapter");
    provided(latest(ChapterDialog).onBlock)(undefined);
    provided(latest(ChapterDialog).returnFocus)();
    latest(ChapterDialog).onClose();
    expect(refs.opener).toHaveFocus();
  });

  it("should record owner-local content completions and group chapter metadata with its gear", async () => {
    const refs = buildRefs();
    renderWorkspace(refs, chapter.id);
    await settle();
    provided(latest(SpreadCanvas).editing?.onEditText)(refs.opener);
    provided(latest(TextEditor).onCompleted)("Before", "After");
    provided(latest(TextEditor).onCompleted)("Same", "Same");
    provided(latest(AlbumAuthors).onCompleted)(["Cosmin"], ["Mara"]);
    provided(latest(AlbumAuthors).onCompleted)(["Cosmin", "Mara"], ["Mara", "Cosmin"]);
    const captions = vi.mocked(createCaptionEditing).mock.lastCall?.[0];
    captions?.onCompleted?.(photo.id, "Before caption", "After caption");
    captions?.onCompleted?.(photo.id, "Same", "Same");
    latest(ChapterMetadata).onEdit(refs.opener);
    const dialog = latest(ChapterDialog);
    const details = {
      kind: chapter.kind,
      name: chapter.name,
      countries: chapter.countries,
      start: chapter.start,
      end: chapter.end,
      sourceFolder: chapter.sourceFolder,
    };
    const before = { details, gear: [] };
    const after = { details: { ...details, name: "Osaka" }, gear: [] };

    dialog.onBookSaved?.(before, before);
    dialog.onBookSaved?.(before, after);
    dialog.onBookSaved?.(before, { ...after, details: { ...after.details, name: "Tokyo" } });

    expect(refs.history.record).toHaveBeenCalledTimes(4);
    expect(vi.mocked(createContentCompensation).mock.calls.map(([operations]) => operations.label)).toEqual([
      "Edit chapter description",
      "Change authors",
      "Edit caption",
    ]);
    expect(createChapterCompensation).toHaveBeenCalledOnce();
    expect(vi.mocked(createChapterCompensation).mock.lastCall?.[0].after.details).toEqual({ ...after.details, name: "Tokyo" });
    provided(dialog.onHistoryBoundary)();
    expect(refs.history.reset).toHaveBeenLastCalledWith("The chapter or photo source was saved. A new Undo history has started.");
    refs.setLossRevision(1);
    await settle();
    expect(refs.history.reset).toHaveBeenLastCalledWith(
      "Editing access was lost. Undo history has been cleared; retained drafts still require review.",
    );
  });

  it("should delegate history traversal and reconciliation, then refresh once without implementing an undo engine", async () => {
    const refs = buildRefs();
    renderWorkspace(refs);
    await settle();
    const controls = latest(BookHistoryControls);
    const pending = deferred<undefined>();
    refs.history.traverse.mockReturnValueOnce(pending.promise);

    const traversal = controls.traverse("undo");
    expect(latest(BookHistoryControls).unavailable).toBe("Finishing the history action. Please wait.");
    pending.resolve(undefined);
    await traversal;
    await controls.traverse("redo");
    await controls.reconcile();

    expect(refs.history.traverse.mock.calls).toEqual([["undo"], ["redo"]]);
    expect(refs.history.reconcile).toHaveBeenCalledOnce();
    expect(refs.onRefresh).toHaveBeenCalledTimes(3);
  });

  it.each([
    { kind: "add", destination: { kind: "part" as const, part: photoSpread.id } },
    { kind: "move", destination: { kind: "part" as const, part: photoSpread.id } },
    { kind: "delete", destination: { kind: "index" as const, index: 2 } },
    { kind: "template", destination: { kind: "part" as const, part: photoSpread.id } },
  ])("should forward $kind intent and reconcile only after authoritative refresh", async ({ kind, destination }) => {
    const refs = buildRefs();
    refs.spreads.execute.mockResolvedValue(destination);
    renderWorkspace(refs, chapter.id);
    await settle();

    if (kind === "add") latest(ChapterOutline).onAdd(chapter.id, chapterOpening.id, refs.opener);
    else if (kind === "move") latest(ChapterOutline).onMove(photoSpread.id, "earlier", refs.opener);
    else if (kind === "delete") latest(ChapterOutline).onDelete(photoSpread.id, refs.opener);
    else latest(TemplateSelector).onChange("new-layout", refs.opener);
    await waitFor(() => {
      expect(refs.spreads.reconcile).toHaveBeenCalledOnce();
    });

    expect(refs.spreads.execute.mock.lastCall?.[1]).toBe(chapter.id);
    expect(refs.spreads.execute.mock.lastCall?.[2]).toBe(false);
    expect(refs.spreads.execute.mock.lastCall?.[3]).toEqual(workspace.spreads);
    expect(refs.spreads.execute.mock.lastCall?.[0]).toMatchObject({ kind, opener: refs.opener });
    expect(refs.onRefresh).toHaveBeenCalledOnce();
    expect(refs.opener).toHaveFocus();
  });

  it("should preserve occupied-action confirmation and never replay a saved mutation after failed refresh", async () => {
    const refs = buildRefs();
    refs.spreads.execute.mockRejectedValueOnce(new SpreadOccupiedError(2)).mockResolvedValue({ kind: "part", part: photoSpread.id });
    renderWorkspace(refs, chapter.id);
    await settle();
    latest(ChapterOutline).onDelete(photoSpread.id, refs.opener);
    await waitFor(() => {
      expect(SpreadConfirmation).toHaveBeenCalledOnce();
    });
    const confirmation = latest(SpreadConfirmation);
    expect(latest(BookHistoryControls).unavailable).toBe("Finish the spread confirmation.");
    const occupied = { placements: confirmation.placements, deleting: confirmation.deleting };
    refs.setWorkspaceError(new Error("Read failed"));

    confirmation.onConfirm();
    await waitFor(() => {
      expect(refs.onRefresh).toHaveBeenCalledOnce();
    });
    await settle();

    expect(occupied).toEqual({ placements: 2, deleting: true });
    expect(refs.spreads.execute.mock.lastCall?.[2]).toBe(true);
    expect(refs.spreads.reconcile).not.toHaveBeenCalled();
    const acquisition = deferred<undefined>();
    refs.onRefresh.mockReturnValue(acquisition.promise);
    fireEvent.click(screen.getByRole("button", { name: "Retry workspace" }));
    refs.setWorkspaceError(undefined);
    acquisition.resolve(undefined);
    await waitFor(() => {
      expect(refs.spreads.reconcile).toHaveBeenCalledOnce();
    });
    expect(refs.spreads.execute).toHaveBeenCalledTimes(2);
  });

  it.each([
    { failure: new AlbumRequestError(400, "Invalid action"), retry: "Retry spread action", repeats: true },
    { failure: new AlbumRequestError(409, "Changed composition"), retry: "Reload saved workspace", repeats: false },
    { failure: new AlbumRequestError(500, "Unknown outcome"), retry: "Reload saved workspace", repeats: false },
    { failure: new Error("Lost acknowledgement"), retry: "Reload saved workspace", repeats: false },
  ])("should distinguish safe retry from uncertain spread outcomes for $retry", async ({ failure, retry, repeats }) => {
    const refs = buildRefs();
    refs.spreads.execute.mockRejectedValueOnce(failure).mockResolvedValue({ kind: "part", part: chapter.id });
    renderWorkspace(refs, chapter.id);
    await settle();
    latest(ChapterOutline).onAdd(chapter.id, chapterOpening.id, refs.opener);
    const button = await screen.findByRole("button", { name: retry });

    fireEvent.click(button);
    await settle();

    expect(refs.spreads.execute).toHaveBeenCalledTimes(repeats ? 2 : 1);
    expect(refs.onRefresh).toHaveBeenCalledOnce();
    expect(refs.spreads.reconcile).toHaveBeenCalledOnce();
  });

  it("should cancel confirmation, retry reconciliation independently and fence a mutation after disposal", async () => {
    const refs = buildRefs();
    refs.spreads.execute.mockRejectedValueOnce(new SpreadOccupiedError(3)).mockResolvedValue({ kind: "part", part: chapter.id });
    renderWorkspace(refs, chapter.id);
    await settle();
    latest(ChapterOutline).onDelete(photoSpread.id, refs.opener);
    await waitFor(() => {
      expect(SpreadConfirmation).toHaveBeenCalledOnce();
    });
    latest(SpreadConfirmation).onCancel();
    await settle();
    expect(refs.opener).toHaveFocus();
    const pending = deferred<{ kind: "part"; part: string }>();
    refs.spreads.execute.mockReturnValue(pending.promise);

    latest(ChapterOutline).onAdd(chapter.id, chapterOpening.id, refs.opener);
    cleanup();
    pending.resolve({ kind: "part", part: photoSpread.id });
    await settle();

    expect(refs.onRefresh).not.toHaveBeenCalled();
  });

  it("should forward photo acquisition scope, retained recovery and canvas choices without running their engines", async () => {
    const refs = buildRefs();
    refs.setPhotoError("Read failed");
    refs.setRetained(true);
    renderWorkspace(refs);
    await settle();
    const pane = latest(PhotoPane);

    pane.onRefresh();
    provided(pane.onRetryRefresh)(chapter.id);
    provided(pane.onRetryInventory)(chapter.id);
    provided(pane.onChoosePhoto)(photo.id);
    expect(screen.getByRole("status", { name: "" })).toHaveTextContent("Photo selected");
    fireEvent.click(screen.getByRole("button", { name: "Cancel photo choice" }));
    provided(latest(SpreadCanvas).editing).onChoose({ kind: "move", placementId: "placed" });
    expect(screen.getByRole("status")).toHaveTextContent("Move selected. Choose a destination slot to move or swap.");
    fireEvent.click(screen.getByRole("button", { name: "Cancel photo choice" }));
    fireEvent.click(screen.getByRole("button", { name: "Retry photos" }));
    fireEvent.click(screen.getByRole("button", { name: "Apply retained photo adjustments" }));
    fireEvent.click(screen.getByRole("button", { name: "Discard retained photo adjustments" }));

    expect(refs.onPhotoRefresh.mock.calls).toEqual([[[chapter.id]], [[chapter.id]]]);
    expect(refs.onInventoryRetry).toHaveBeenCalledWith(chapter.id);
    expect(refs.placement.retry).toHaveBeenCalledOnce();
    expect(refs.placement.applyRetained).toHaveBeenCalledOnce();
    expect(refs.placement.discardRetained).toHaveBeenCalledOnce();
    refs.setPhotoSaving(true);
    expect(screen.getByRole("button", { name: "Discard retained photo adjustments" })).toBeDisabled();
  });

  it("should reconcile an acknowledged matching spread but ignore snapshots for another selected identity", async () => {
    const refs = buildRefs();
    const saved = snapshotSpread(chapterOpening);
    refs.setSnapshot(saved);
    renderWorkspace(refs, chapter.id);
    await settle();

    expect(refs.placement.reconcile).toHaveBeenCalledWith(saved);
    expect(latest(ChapterOutline).spreads[1]).toEqual(chapterOpening);
    refs.setSnapshot(snapshotSpread(photoSpread));
    expect(latest(SpreadCanvas).slots).toEqual(chapterOpening.slots);
    expect(refs.placement.reconcile).toHaveBeenCalledOnce();
  });

  it("should acquire saved gear for the selected chapter, report failure and retry on reconnection", async () => {
    const refs = buildRefs();
    vi.mocked(refs.client.gearCatalog).mockRejectedValueOnce(new Error("Gear unavailable")).mockResolvedValue({ cameras: [], lenses: [] });
    renderWorkspace(refs, chapter.id);
    const retry = await screen.findByRole("button", { name: "Read chapter gear" });

    fireEvent.click(retry);
    await waitFor(() => expect(screen.queryByRole("button", { name: "Read chapter gear" })).not.toBeInTheDocument());
    refs.setLiveStatus("interrupted");
    refs.setLiveStatus("live");
    await settle();

    expect(refs.client.chapterGear).toHaveBeenCalledWith(album.id, chapter.id);
    expect(refs.client.gearCatalog).toHaveBeenCalledTimes(3);
    expect(refs.onReady).toHaveBeenCalledOnce();
  });

  it("should keep reader ownership separate from editing, request explicit acquisition and recover disconnected text", async () => {
    const refs = buildRefs();
    refs.setTargetReason("Read only");
    renderWorkspace(refs);
    await settle();
    fireEvent.click(screen.getByRole("button", { name: "Edit spread" }));
    await settle();
    expect(refs.session.select).toHaveBeenLastCalledWith({ kind: "spread", id: opening.id }, true);
    provided(latest(FullscreenReader).onActive)(true);
    await settle();
    expect(refs.session.select).toHaveBeenLastCalledWith(null, false);
    provided(latest(FullscreenReader).onActive)(false);
    refs.setTargetReason(undefined);
    provided(latest(SpreadCanvas).editing?.onEditText)(refs.opener);
    const editor = latest(TextEditor);

    refs.setAvailable(false);
    expect(latest(TextEditor).ownership.maybeAuthority).toBeUndefined();
    expect(latest(TextEditor).ownership.recoveryLabel).toBe("Reconnect browser");
    await provided(editor.ownership.recover)();
    refs.setAvailable(true);
    refs.setTargetReason("Acquire editing ownership");
    expect(latest(TextEditor).ownership.maybeAuthority).toBeUndefined();
    await provided(editor.ownership.recover)();

    expect(refs.session.reconnect).toHaveBeenCalledOnce();
    expect(refs.onRefresh).toHaveBeenCalled();
  });
  it("should project acknowledged placement changes into complementary history operations with resolved identities", async () => {
    const refs = buildRefs();
    renderWorkspace(refs, photoSpread.id);
    await settle();
    const before = snapshotSpread(photoSpread);
    const after = {
      ...before,
      placements: [
        {
          kind: "spread" as const,
          id: "placement",
          photoId: photo.id,
          slotId: photoSlot.id,
          captionCorner: "bottom_right" as const,
          transform: { x: 0, y: 0, zoom: 1, mirrored: false },
        },
      ],
    };
    const command: PlacementEdit = { operation: "place", photoId: photo.id, slotId: photoSlot.id };
    const completed = provided(vi.mocked(createPlacementSession).mock.lastCall?.[3]);
    refs.spreads.resolve.mockReturnValue("resolved");

    completed(before, before, command);
    completed(before, after, command);
    const operations = provided(vi.mocked(createPlacementCompensation).mock.lastCall?.[0]);

    expect(operations.context()).toEqual({ kind: "spread", id: "resolved" });
    expect(operations.label).toBe("Place photo");
    expect(operations.before).toBe(before);
    expect(operations.after).toBe(after);
    expect(operations.client).toBe(refs.client);
    expect(refs.spreads.resolve).toHaveBeenCalledWith(photoSpread.id);
    expect(refs.history.record).toHaveBeenCalledExactlyOnceWith(action);
    refs.setWorkspace({ ...workspace, spreads: [opening, chapterOpening, { ...photoSpread, id: "resolved" }] });
    expect(await operations.authorize()).toBeUndefined();
  });

  it("should use acknowledged cover geometry and retain cover history as an independent target", async () => {
    const refs = buildRefs();
    const before: PlacementSnapshot = {
      kind: "cover",
      placements: [],
      frontSlotId: "front",
      backSlotId: "back",
      spineOwner: "front",
      pageCount: 30,
      geometry: coverGeometries.front,
    };
    const after = { ...before, spineOwner: "back" as const };
    refs.setSnapshot(before);
    renderWorkspace(refs, "cover");
    await settle();
    provided(vi.mocked(createPlacementSession).mock.lastCall?.[3])(before, after, { operation: "spineOwner", owner: "back" });
    const operations = provided(vi.mocked(createPlacementCompensation).mock.lastCall?.[0]);

    expect(latest(FullscreenReader).aspectRatio).toBe(before.geometry.width / before.geometry.height);
    expect(operations.context()).toEqual({ kind: "cover" });
    expect(await operations.authorize()).toBeUndefined();
    expect(window.location.search).toBe("?part=cover");
    expect(operations.label).toBe("Change spine owner");
  });

  it("should acquire missing history targets once, reject persistent absence and fence access lost during the read", async () => {
    const refs = buildRefs();
    renderWorkspace(refs);
    await settle();
    const operations = provided(vi.mocked(createSpreadEditing).mock.lastCall?.[0]);

    expect(await operations.prepare("absent", false)).toBe("The saved spread is no longer available.");
    expect(refs.onRefresh).toHaveBeenCalledOnce();
    const acquisition = deferred<undefined>();
    refs.onRefresh.mockReturnValue(acquisition.promise);
    const preparing = operations.prepare("absent", false);
    refs.setAvailable(false);
    acquisition.resolve(undefined);

    expect(await preparing).toBe("Editing access changed. History cannot replay after access loss.");
    expect(refs.session.select).not.toHaveBeenCalledWith({ kind: "spread", id: "absent" }, true);
    expect(await operations.prepare(opening.id, false)).toBe("Editing access changed. History cannot replay after access loss.");
  });
  it.each([
    { kind: "name", part: null, label: "Rename album", saved: album.title },
    { kind: "synopsis", part: null, label: "Edit synopsis", saved: album.synopsis },
    { kind: "description", part: chapter.id, label: "Edit chapter description", saved: chapter.description },
    { kind: "caption", part: chapter.id, label: "Edit caption", saved: photo.caption },
    { kind: "caption", part: null, label: "Edit caption", saved: photo.caption },
    { kind: "caption", part: "cover", label: "Edit caption", saved: photo.caption },
    { kind: "authors", part: null, label: "Change authors", saved: album.authors },
  ])(
    "should supply complementary reads, writes, comparisons and access preparation for $kind history at $part",
    async ({ kind, part, label, saved }) => {
      const refs = buildRefs();
      vi.mocked(refs.client.saveAuthors).mockResolvedValue(album);
      vi.mocked(refs.client.saveSynopsis).mockResolvedValue(album);
      vi.mocked(refs.client.rename).mockResolvedValue(album);
      renderWorkspace(refs, part);
      await settle();
      if (kind === "authors") provided(latest(AlbumAuthors).onCompleted)(["Cosmin"], ["Mara"]);
      else if (kind === "caption") provided(provided(vi.mocked(createCaptionEditing).mock.lastCall?.[0]).onCompleted)(photo.id, "Before", "After");
      else {
        if (kind === "name") fireEvent.click(screen.getByRole("button", { name: "Edit album name" }));
        else provided(latest(SpreadCanvas).editing?.onEditText)(refs.opener);
        provided(latest(TextEditor).onCompleted)("Before", "After");
      }
      const operations = provided(vi.mocked(createContentCompensation).mock.lastCall?.[0]);

      expect(await operations.read()).toEqual(saved);
      expect(operations.equal(operations.before, operations.before)).toBe(true);
      expect(operations.equal(operations.before, operations.after)).toBe(false);
      expect(await operations.prepare()).toBeUndefined();
      await operations.write(operations.after);

      expect(operations.label).toBe(label);
      expect(refs.onRefresh).toHaveBeenCalledOnce();
      expect(refs.history.record).toHaveBeenCalledExactlyOnceWith(action);
    },
  );

  it("should supply scoped chapter history programs and start a new group after a confirmed access generation changes", async () => {
    const refs = buildRefs();
    renderWorkspace(refs, chapter.id);
    await settle();
    latest(ChapterMetadata).onEdit(refs.opener);
    const dialog = latest(ChapterDialog);
    const details = {
      kind: chapter.kind,
      name: chapter.name,
      countries: chapter.countries,
      start: chapter.start,
      end: chapter.end,
      sourceFolder: chapter.sourceFolder,
    };
    const before = { details, gear: [] };
    const after = { details: { ...details, name: "Osaka" }, gear: [] };
    provided(dialog.onBookSaved)(before, before);
    expect(refs.history.record).not.toHaveBeenCalled();
    provided(dialog.onBookSaved)(before, after);
    const operations = provided(vi.mocked(createChapterCompensation).mock.lastCall?.[0]);
    provided(dialog.onBookSaved)(before, after);
    expect(refs.history.record).toHaveBeenCalledOnce();

    expect(await operations.readDetails()).toEqual(details);
    expect(await operations.readGear()).toEqual([]);
    await operations.saveDetails(after.details);
    await operations.saveGear([]);
    expect(await operations.prepare()).toBeUndefined();
    expect(refs.client.updateChapter).toHaveBeenCalledExactlyOnceWith(album.id, chapter.id, after.details);
    expect(refs.client.saveChapterGear).toHaveBeenCalledExactlyOnceWith(album.id, chapter.id, []);
    vi.mocked(refs.client.chapters).mockResolvedValue([]);
    await expect(operations.readDetails()).rejects.toBeInstanceOf(AlbumRequestError);
    refs.setLossRevision(1);
    provided(dialog.onBookSaved)(before, after);
    expect(createChapterCompensation).toHaveBeenCalledTimes(2);
  });

  it("should project export issue links, keep slot feedback through navigation and prevent feedback from discarding drafts", async () => {
    const refs = buildRefs();
    renderWorkspace(refs);
    await settle();
    const exporting = latest(BookExportControl);
    const issue = {
      code: "unavailable",
      severity: "blocking" as const,
      message: "Saved photo unavailable",
      target: { kind: "slot", spread: photoSpread.id, slot: photoSlot.id },
    };
    const link = provided(exporting.issueLink?.(issue));
    const chapterLink = provided(exporting.issueLink?.({ ...issue, target: { kind: "chapter", chapter: chapter.id } }));
    const coverLink = provided(exporting.issueLink?.({ ...issue, target: { kind: "cover", slot: "front" } }));
    const albumLink = provided(exporting.issueLink?.({ ...issue, target: { kind: "album" } }));

    expect(link.href).toBe("/albums/album?part=photo-spread#photo-frame-photo-slot");
    expect(link.label).toBe("Spread 3 Slot 1");
    link.navigate();
    await settle();
    expect(latest(SpreadCanvas).editing?.highlightedSlot).toBe(photoSlot.id);
    chapterLink.navigate();
    await settle();
    expect(window.location.search).toBe("?part=chapter");
    coverLink.navigate();
    await settle();
    expect(window.location.search).toBe("?part=cover");
    albumLink.navigate();
    await settle();
    expect(window.location.search).toBe("");
    expect(exporting.issueLink?.({ ...issue, target: { kind: "photo", photo: "absent" } })).toBeUndefined();
    latest(AlbumAuthors).onBlock("Retained authors");
    link.navigate();
    expect(window.location.search).toBe("");
    provided(exporting.onDismiss)();
    expect(latest(SpreadCanvas).editing?.highlightedSlot).toBeUndefined();
  });

  it("should clear an invalid selection, retain caption feedback and honor independently supplied loading states", async () => {
    const refs = buildRefs();
    renderWorkspace(refs, "absent");
    await settle();
    expect(refs.placement.clear).toHaveBeenCalled();
    expect(latest(FullscreenReader).unavailable).toBe("Choose an available album part to enter reader mode.");
    fireEvent.click(screen.getByRole("button", { name: "Go to Synopsis" }));
    await settle();
    const caption = buildContentSave<string>();
    caption.setDraft({ original: "Saved", value: "Retained" });
    refs.setCaptionSessions([{ photoId: photo.id, save: caption.save }]);
    const retained = latest(RetainedCaption);
    retained.returnFocus();
    expect(screen.getByRole("region", { name: "Album workspace" })).toHaveFocus();
    expect(retained.focus).toBe(refs.captions.focus);
    const operations = provided(vi.mocked(createCaptionEditing).mock.lastCall?.[0]);
    operations.onBlock(photo.id, "Finish caption");
    expect(latest(PhotoPane).unavailable).toBe("Finish caption");
    operations.onBlock(photo.id, undefined);
    expect(operations.maybeAuthority?.()).toBe(1);
    refs.setAvailable(false);
    expect(operations.maybeAuthority?.()).toBeUndefined();
    refs.setAvailable(true);
    refs.setPhotoError("Photo read failed");
    refs.setPhotoLoading(true);
    expect(screen.getByRole("button", { name: "Retry photos" })).toBeDisabled();
    refs.setPhotoLoading(false);
    refs.setPhotoSaving(true);
    expect(screen.getByRole("button", { name: "Retry photos" })).toBeDisabled();
    refs.setPhotoSaving(false);
    refs.setWorkspaceLoading(true);
    expect(latest(ChapterOutline).unavailable).toBe("Refreshing saved workspace. Please wait.");
    refs.setWorkspaceLoading(false);
    refs.setHistoryMessage("Resolve history");
    refs.setHistoryUnavailable(true);
    expect(latest(BookExportControl).unavailable).toBe("Resolve history");
  });

  it("should fence superseded acquisition and re-confirm another owner's target only after outstanding work is resolved", async () => {
    const refs = buildRefs();
    const selection = deferred<boolean>();
    refs.session.select.mockReturnValueOnce(selection.promise).mockResolvedValue(true);
    renderWorkspace(refs);
    refs.setAuthority(2);
    selection.resolve(true);
    await settle();
    expect(refs.onRefresh).toHaveBeenCalledOnce();
    refs.setOwner("Other browser");
    refs.setOwnershipRevision(1);
    await settle();
    expect(refs.session.select).toHaveBeenLastCalledWith({ kind: "spread", id: opening.id }, true);
    expect(screen.getByText("Other browser")).toBeInTheDocument();
    const acquisitions = refs.session.select.mock.calls.length;
    refs.setTargetPending(true);
    refs.setOwnershipRevision(2);
    await settle();
    expect(refs.session.select).toHaveBeenCalledTimes(acquisitions);
    refs.setTargetPending(false);
    await settle();
    expect(refs.session.select).toHaveBeenCalledTimes(acquisitions + 1);
  });

  it("should preserve native shortcuts and release clean catalog navigation without intercepting another workspace route", async () => {
    const refs = buildRefs();
    renderWorkspace(refs);
    await settle();
    fireEvent.keyDown(document, { key: "Escape" });
    fireEvent.keyDown(document, { key: "ArrowRight", ctrlKey: true });
    fireEvent.keyDown(refs.opener, { key: "ArrowRight", defaultPrevented: true });
    fireEvent.keyDown(document, { key: "ArrowRight" });
    await settle();
    expect(window.location.search).toBe("?part=chapter");
    fireEvent.keyDown(document, { key: "ArrowLeft" });
    await settle();
    expect(window.location.search).toBe("");
    window.history.replaceState(null, "", "/albums/another");
    window.dispatchEvent(new PopStateEvent("popstate"));
    expect(window.location.pathname).toBe("/albums/another");
    window.history.replaceState(null, "", "/albums/album");
    fireEvent.click(screen.getByRole("link", { name: "Back to album catalog" }));
    await settle();
    expect(window.location.pathname).toBe("/");
  });

  it("should preserve failed saved acquisition in delegated content and photo programs and read absent captions as empty text", async () => {
    const refs = buildRefs();
    refs.setWorkspace({
      ...workspace,
      photoInventories: { [chapter.id]: { ...provided(workspace.photoInventories[chapter.id]), photos: [{ ...photo, caption: null }] } },
    });
    renderWorkspace(refs);
    await settle();
    const captions = provided(vi.mocked(createCaptionEditing).mock.lastCall?.[0]);
    const refreshPhotos = provided(vi.mocked(createPlacementSession).mock.lastCall?.[1]);
    const authors = latest(AlbumAuthors);

    expect(await captions.read(photo.id)).toBe("");
    await refreshPhotos();
    expect(authors.maybeAuthority?.()).toBe(0);
    refs.setWorkspaceError(new Error("Acquisition failed"));
    await expect(authors.read()).rejects.toThrow("Saved content acquisition failed");
    await expect(refreshPhotos()).rejects.toThrow("Workspace refresh failed");
    refs.setAvailable(false);
    expect(authors.maybeAuthority?.()).toBeUndefined();
    expect(refs.onRefresh).toHaveBeenCalledTimes(4);
  });

  it.each([false, true])(
    "should acquire a previously absent history target and fence access lost=%s while awaiting its grant",
    async (lostAccess) => {
      const refs = buildRefs();
      refs.setWorkspace({ ...workspace, spreads: [opening, chapterOpening] });
      renderWorkspace(refs);
      await settle();
      const acquisition = deferred<undefined>();
      const grant = deferred<boolean>();
      refs.onRefresh.mockReturnValueOnce(acquisition.promise).mockResolvedValue(undefined);
      refs.session.select.mockResolvedValueOnce(true).mockReturnValueOnce(grant.promise);
      refs.setTargetReason("Acquire saved spread ownership");
      const operations = provided(vi.mocked(createSpreadEditing).mock.lastCall?.[0]);

      const preparing = operations.prepare(photoSpread.id, true);
      refs.setWorkspace(workspace);
      acquisition.resolve(undefined);
      await settle();
      expect(refs.session.select).toHaveBeenLastCalledWith({ kind: "spread", id: photoSpread.id }, true);
      refs.setTargetReason(undefined);
      if (lostAccess) refs.setAvailable(false);
      grant.resolve(true);

      expect(await preparing).toBe(lostAccess ? "Editing access changed. History cannot replay after access loss." : undefined);
      expect(window.location.search).toBe("?part=photo-spread");
    },
  );

  it("should retain an acknowledged spread after failed reconciliation and retry only acquisition and reconciliation", async () => {
    const refs = buildRefs();
    refs.spreads.reconcile.mockImplementationOnce(() => {
      throw new Error("Saved aliases unavailable");
    });
    renderWorkspace(refs, chapter.id);
    await settle();

    latest(ChapterOutline).onAdd(chapter.id, chapterOpening.id, refs.opener);
    const retry = await screen.findByRole("button", { name: "Retry workspace" });
    expect(screen.getByRole("alert")).toHaveTextContent("The request could not be completed. Please try again.");
    expect(screen.getByRole("region", { name: "Album workspace" })).toHaveFocus();
    refs.opener.remove();
    fireEvent.click(retry);
    await settle();

    expect(refs.spreads.execute).toHaveBeenCalledOnce();
    expect(refs.spreads.reconcile).toHaveBeenCalledTimes(2);
    expect(refs.onRefresh).toHaveBeenCalledTimes(2);
    expect(screen.getByRole("region", { name: "Album workspace" })).toHaveFocus();
  });

  it("should preserve an uncertain spread refusal when the saved reload still fails", async () => {
    const refs = buildRefs();
    refs.spreads.execute.mockRejectedValue(new Error("Lost acknowledgement"));
    renderWorkspace(refs, chapter.id);
    await settle();
    latest(ChapterOutline).onAdd(chapter.id, chapterOpening.id, refs.opener);
    const reload = await screen.findByRole("button", { name: "Reload saved workspace" });
    const acquisition = deferred<undefined>();
    refs.onRefresh.mockReturnValue(acquisition.promise);

    fireEvent.click(reload);
    refs.setWorkspaceError(new Error("Read failed"));
    acquisition.resolve(undefined);
    await settle();

    expect(screen.getByText("Spread outcome is uncertain. Reload saved workspace before choosing the action again.")).toBeInTheDocument();
    expect(refs.spreads.execute).toHaveBeenCalledOnce();
    expect(refs.spreads.reconcile).not.toHaveBeenCalled();
  });

  it("should expose chapter availability, delegate thumbnail recovery and omit retained feedback for a still-placed caption", async () => {
    const refs = buildRefs();
    renderWorkspace(refs, chapter.id);
    await settle();
    const metadata = latest(ChapterMetadata);
    expect(latest(CompositionControls).reader).toBe(false);
    expect(latest(CompositionControls).unavailable).toBeUndefined();
    provided(latest(ChapterOutline).onRetry)();
    metadata.onEdit(refs.opener);
    const dialog = latest(ChapterDialog);
    expect(dialog.maybeAuthority?.()).toBe(0);
    refs.setAvailable(false);
    expect(dialog.maybeAuthority?.()).toBeUndefined();
    refs.setAvailable(true);
    dialog.returnFocus();
    expect(refs.opener).toHaveFocus();
    dialog.onClose();
    const caption = buildContentSave<string>();
    caption.setDraft({ original: "Saved", value: "Retained" });
    refs.setPlacements([
      {
        kind: "spread",
        id: "placement",
        slotId: photoSlot.id,
        photoId: photo.id,
        captionCorner: "top_left",
        transform: { x: 0, y: 0, zoom: 1, mirrored: false },
      },
    ]);

    refs.setCaptionSessions([{ photoId: photo.id, save: caption.save }]);

    expect(RetainedCaption).not.toHaveBeenCalled();
    expect(refs.onRefresh).toHaveBeenCalledOnce();
    latest(ChapterMetadata).onEdit(refs.opener);
    latest(ChapterDialog).onReconciled(chapter.id);
    await settle();
    expect(refs.opener).toHaveFocus();
  });

  it("should fence history after editing access is lost while acquiring its target", async () => {
    const refs = buildRefs();
    renderWorkspace(refs);
    await settle();
    const acquisition = deferred<boolean>();
    refs.session.select.mockReturnValue(acquisition.promise);
    const operations = provided(vi.mocked(createSpreadEditing).mock.lastCall?.[0]);

    const preparing = operations.prepare(photoSpread.id, true);
    await settle();
    refs.setAvailable(false);
    acquisition.resolve(true);

    expect(await preparing).toBe("Editing access changed. History cannot replay after access loss.");
    expect(refs.onRefresh).not.toHaveBeenCalled();
  });

  it("should not reconcile an acknowledged spread when its workspace has been disposed during saved acquisition", async () => {
    const refs = buildRefs();
    renderWorkspace(refs, chapter.id);
    await settle();
    const acquisition = deferred<undefined>();
    refs.onRefresh.mockReturnValue(acquisition.promise);

    latest(ChapterOutline).onAdd(chapter.id, chapterOpening.id, refs.opener);
    await waitFor(() => {
      expect(refs.onRefresh).toHaveBeenCalledOnce();
    });
    cleanup();
    acquisition.resolve(undefined);
    await settle();

    expect(refs.spreads.reconcile).not.toHaveBeenCalled();
  });

  it("should ignore a rejected spread acknowledgement after leaving its workspace", async () => {
    const refs = buildRefs();
    renderWorkspace(refs, chapter.id);
    await settle();
    refs.spreads.execute.mockRejectedValue(new Error("Late failed acknowledgement"));

    latest(ChapterOutline).onAdd(chapter.id, chapterOpening.id, refs.opener);
    cleanup();
    await settle();

    expect(refs.onRefresh).not.toHaveBeenCalled();
    expect(refs.spreads.reconcile).not.toHaveBeenCalled();
  });

  it("should avoid a duplicate cover read while photo acquisition is pending and resume from independently supplied readiness", async () => {
    const refs = buildRefs();
    refs.setPhotoLoading(true);
    renderWorkspace(refs, "cover");
    await settle();
    expect(refs.placement.load).not.toHaveBeenCalled();

    refs.setPhotoLoading(false);
    refs.setWorkspace({ ...workspace, spreads: [...workspace.spreads] });
    await settle();

    expect(refs.placement.load).toHaveBeenCalledExactlyOnceWith(album.id, { kind: "cover" });
  });

  it("should not acquire an announced editing target while the user remains in Reader", async () => {
    const refs = buildRefs();
    renderWorkspace(refs);
    await settle();
    provided(latest(FullscreenReader).onActive)(true);
    await settle();
    const acquisitions = refs.session.select.mock.calls.length;
    refs.setOwner("Another browser");

    refs.setOwnershipRevision(1);
    await settle();

    expect(refs.session.select).toHaveBeenCalledTimes(acquisitions);
  });

  it("should block Reader for invalid saved gear, explain pending acquisition and ignore a failed obsolete presentation", async () => {
    const refs = buildRefs();
    vi.mocked(refs.client.chapterGear).mockResolvedValue([{ cameraId: "missing", lensIds: [] }]);
    renderWorkspace(refs, chapter.id);
    await screen.findByRole("button", { name: "Read chapter gear" });
    expect(latest(FullscreenReader).unavailable).toBe("A selected camera is no longer available.");
    let fail!: (failure: Error) => void;
    const acquisition = new Promise<Awaited<ReturnType<typeof refs.client.gearCatalog>>>((_, reject) => {
      fail = reject;
    });
    vi.mocked(refs.client.gearCatalog).mockReturnValueOnce(acquisition);
    fireEvent.click(screen.getByRole("button", { name: "Read chapter gear" }));
    expect(latest(FullscreenReader).unavailable).toBe("Loading saved chapter gear.");

    latest(ChapterOutline).onSelect(null);
    fail(new Error("Obsolete gear read failed"));
    await settle();

    expect(screen.queryByRole("button", { name: "Read chapter gear" })).not.toBeInTheDocument();
    expect(latest(FullscreenReader).unavailable).toBeUndefined();
  });

  it("should preserve a newer ownership acquisition when an older confirmed refresh completes", async () => {
    const refs = buildRefs();
    refs.setTargetReason("Other browser");
    renderWorkspace(refs);
    await settle();
    const refresh = deferred<undefined>();
    const acquisition = deferred<boolean>();
    refs.onRefresh.mockReturnValueOnce(refresh.promise);
    fireEvent.click(screen.getByRole("button", { name: "Edit spread" }));
    await waitFor(() => {
      expect(refs.onRefresh).toHaveBeenCalledOnce();
    });
    refs.setAvailable(false);
    refs.session.select.mockReturnValueOnce(acquisition.promise);
    refs.setAvailable(true);

    refresh.resolve(undefined);
    await settle();
    expect(latest(SpreadCanvas).editing?.unavailable).toBe("Reading saved content and confirming editing ownership.");
    acquisition.resolve(true);
    await settle();

    expect(latest(SpreadCanvas).editing?.unavailable).toBe("Other browser");
  });

  it("should preserve native link and text-entry gestures instead of treating them as catalog departure or spread navigation", async () => {
    const refs = buildRefs();
    renderWorkspace(refs, chapter.id);
    await settle();
    render(() => <input aria-label="Native text entry" />);
    const link = screen.getByRole("link", { name: "Back to album catalog" });
    const acquisitions = refs.session.select.mock.calls.length;

    fireEvent.keyDown(screen.getByRole("textbox", { name: "Native text entry" }), { key: "ArrowRight" });
    fireEvent.click(link, { ctrlKey: true });
    fireEvent(document, new MouseEvent("click", { bubbles: true }));
    link.setAttribute("href", "#chapter");
    fireEvent.click(link);
    await settle();

    expect(window.location.search).toBe("?part=chapter");
    expect(refs.session.select).toHaveBeenCalledTimes(acquisitions);
    latest(TemplateSelector).onChange(chapterOpening.templateId, refs.opener);
    expect(refs.spreads.execute).not.toHaveBeenCalled();
    window.history.replaceState(null, "", "/albums/album?part=cover");
    window.dispatchEvent(new PopStateEvent("popstate"));
    await settle();
    expect(latest(CoverCanvas).title).toBe(album.title);
  });

  it("should retain a saved chapter receipt when its workspace refresh fails and retry acquisition without replaying creation", async () => {
    const refs = buildRefs();
    renderWorkspace(refs);
    await settle();
    latest(ChapterOutline).onCreate(refs.opener);
    const receipt = { id: chapter.id, scheduling: "queued" as const };
    const acquisition = deferred<undefined>();
    refs.onSaved.mockReturnValueOnce(acquisition.promise);

    latest(ChapterDialog).onSaved(receipt);
    refs.setWorkspaceError(new Error("Saved workspace unavailable"));
    acquisition.resolve(undefined);
    await settle();
    expect(screen.getByRole("alert")).toHaveTextContent("Chapter saved, but the workspace could not be refreshed.");
    fireEvent.click(screen.getByRole("button", { name: "Retry workspace" }));
    refs.setWorkspaceError(undefined);
    await settle();

    expect(refs.onSaved).toHaveBeenCalledExactlyOnceWith(receipt);
    expect(refs.onRefresh).toHaveBeenCalledOnce();
    expect(refs.client.createChapter).not.toHaveBeenCalled();
  });
});

function latest<P extends object>(component: (props: P) => JSX.Element): P {
  const call = vi.mocked(component).mock.lastCall;
  if (call === undefined) throw new Error("The collaborator was not presented");
  return provided(runWithOwner(provided(childOwners.get(call[0])), () => ({ ...call[0] })));
}

function recordChildOwner(props: object): null {
  childOwners.set(props, getOwner());
  return null;
}

function provided<T>(value: T | undefined): T {
  if (value === undefined) throw new Error("The required collaborator operation was not supplied");
  return value;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

async function settle() {
  await new Promise((resolve) => {
    setTimeout(resolve, 0);
  });
}

function buildRefs() {
  const [metadata, setMetadata] = createSignal(album);
  const [savedWorkspace, setWorkspace] = createSignal(workspace);
  const [workspaceError, setWorkspaceError] = createSignal<unknown>();
  const [available, setAvailable] = createSignal(true);
  const [lossRevision, setLossRevision] = createSignal(0);
  const [targetReason, setTargetReason] = createSignal<string>();
  const [liveStatus, setLiveStatus] = createSignal<"live" | "interrupted">("live");
  const [workspaceLoading, setWorkspaceLoading] = createSignal(false);
  const [authority, setAuthority] = createSignal(1);
  const [owner, setOwner] = createSignal<string>();
  const [ownershipRevision, setOwnershipRevision] = createSignal(0);
  const [targetPending, setTargetPending] = createSignal(false);
  const [captionSessions, setCaptionSessions] = createSignal<ReturnType<CaptionEditing["sessions"]>>([]);
  const opener = document.createElement("button");
  opener.dataset["workspaceTestOpener"] = "true";
  document.body.append(opener);
  const session = {
    ...editingSession,
    available,
    lossRevision,
    targetReason,
    authority,
    owner,
    ownershipRevision,
    targetPending,
    select: vi.fn<WorkspaceEditingSession["select"]>().mockResolvedValue(true),
    reconnect: vi.fn<WorkspaceEditingSession["reconnect"]>().mockResolvedValue(undefined),
  };
  const client = buildAlbumClient({
    gearCatalog: vi.fn().mockResolvedValue({ cameras: [], lenses: [] }),
    chapterGear: vi.fn().mockResolvedValue([]),
    saveChapterGear: vi.fn().mockResolvedValue(undefined),
    saveDescription: vi.fn().mockResolvedValue(undefined),
    saveCaption: vi.fn().mockResolvedValue(undefined),
    createChapter: vi.fn().mockResolvedValue({ id: chapter.id, scheduling: "queued" }),
    updateChapter: vi.fn().mockResolvedValue({ id: chapter.id, scheduling: "queued" }),
    chapters: vi.fn().mockResolvedValue(workspace.chapters),
  });
  const historyRefs = buildBookHistory();
  const history = historyRefs.history;
  const placementRefs = buildPlacementSession();
  const placement = placementRefs.session;
  const captions = {
    getPhotoSave: vi.fn<CaptionEditing["getPhotoSave"]>().mockImplementation(() => {
      throw new Error("Unexpected child caption acquisition");
    }),
    sessions: captionSessions,
    focus: { requested: vi.fn<CaptionEditing["focus"]["requested"]>().mockReturnValue(undefined), request: vi.fn(), clear: vi.fn() },
  };
  const spreads = {
    resolve: vi.fn<(id: string) => string>().mockReturnValue(photoSpread.id),
    execute: vi.fn<ReturnType<typeof createSpreadEditing>["execute"]>().mockResolvedValue({ kind: "part", part: chapter.id }),
    reconcile: vi.fn<ReturnType<typeof createSpreadEditing>["reconcile"]>(),
  };
  vi.mocked(createBookHistory).mockReturnValue(history);
  vi.mocked(createPlacementSession).mockReturnValue(placement);
  vi.mocked(createCaptionEditing).mockReturnValue(captions);
  vi.mocked(createSpreadEditing).mockReturnValue(spreads);
  vi.mocked(createContentCompensation).mockReturnValue(action);
  vi.mocked(createChapterCompensation).mockReturnValue(action);
  vi.mocked(createPlacementCompensation).mockReturnValue(action);
  return {
    metadata,
    setMetadata,
    savedWorkspace,
    setWorkspace,
    workspaceError,
    setWorkspaceError,
    session,
    setAvailable,
    setLossRevision,
    setTargetReason,
    liveStatus,
    setLiveStatus,
    placement,
    setSnapshot: placementRefs.setSnapshot,
    setPhotoError: placementRefs.setError,
    setRetained: placementRefs.setRetained,
    setNavigationReason: placementRefs.setNavigationReason,
    setPhotoLoading: placementRefs.setLoading,
    setPhotoSaving: placementRefs.setSaving,
    setPlacements: placementRefs.setPlacements,
    setWorkspaceLoading,
    workspaceLoading,
    setAuthority,
    setOwner,
    setOwnershipRevision,
    setTargetPending,
    setCaptionSessions,
    setHistoryUnavailable: historyRefs.setUnavailable,
    setHistoryMessage: historyRefs.setMessage,
    history,
    captions,
    spreads,
    client,
    opener,
    onRefresh: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
    onSaved: vi.fn<Parameters<typeof AlbumWorkspace>[0]["onSaved"]>().mockResolvedValue(undefined),
    onAlbumSaved: vi.fn<(metadata: Album) => void>(),
    onReady: vi.fn(),
    onPhotoRefresh: vi.fn().mockResolvedValue(undefined),
    onInventoryRetry: vi.fn(),
  };
}

function renderWorkspace(refs: ReturnType<typeof buildRefs>, part: string | null = null): void {
  window.history.replaceState(null, "", `/albums/album${part === null ? "" : `?part=${part}`}`);
  render(() => (
    <AlbumWorkspace
      album={refs.metadata()}
      workspace={refs.savedWorkspace()}
      session={refs.session}
      client={refs.client}
      workspaceError={refs.workspaceError()}
      workspaceLoading={refs.workspaceLoading()}
      photoErrors={{}}
      scheduling={{}}
      liveStatus={refs.liveStatus()}
      onRefresh={refs.onRefresh}
      onSaved={refs.onSaved}
      onPhotoRefresh={refs.onPhotoRefresh}
      onInventoryRetry={refs.onInventoryRetry}
      onAlbumSaved={refs.onAlbumSaved}
      onReady={refs.onReady}
    />
  ));
}
