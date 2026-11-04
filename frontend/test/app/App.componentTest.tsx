import { coverGeometries } from "../features/albums/CoverGeometry";
import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { App } from "../../src/app/App";
import { readAppConfig } from "../../src/app/AppConfig";
import { editingSession } from "../features/editing/EditingSessionStub";
import { AlbumRequestError, type Album, type AlbumClient, type AlbumPage, type AlbumSummary } from "../../src/features/albums/model/Album";
import type { PlacementContext, PlacementSnapshot } from "../../src/features/albums/model/Placement";
import type { AlbumPhotoEvents, PhotoEvent } from "../../src/features/albums/model/Photo";
import type { WorkspaceEditingSession } from "../../src/features/editing/EditorSession";

const events: AlbumPhotoEvents = {
  subscribe: (_id, receive) => {
    queueMicrotask(() => {
      receive({ kind: "subscribed" });
    });
    return vi.fn();
  },
};

const firstAlbum: AlbumSummary = {
  id: "00000000-0000-0000-0000-000000000001",
  year: 2026,
  title: "Journey of the Shepherds 2026",
  createdAt: "2026-10-24T12:00:00Z",
};
const firstPage: AlbumPage = {
  albums: [
    firstAlbum,
    ...Array.from({ length: 5 }, (_, index) => ({
      ...firstAlbum,
      id: `00000000-0000-0000-0000-${String(index + 2).padStart(12, "0")}`,
      year: 2027 + index,
      title: `Journey of the Shepherds ${String(2027 + index)}`,
    })),
  ],
  page: 1,
  pageSize: 6,
  totalAlbums: 7,
  totalPages: 2,
};
const lastAlbum = { ...firstAlbum, id: "00000000-0000-0000-0000-000000000007", year: 2032, title: "Journey of the Shepherds 2032" };
const secondPage = { ...firstPage, albums: [lastAlbum], page: 2 };
const nativeDialogMethods = {
  showModal: Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "showModal"),
  close: Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "close"),
  getAnimations: Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "getAnimations"),
};
const dialogAnimationFinished = vi.fn<() => Promise<void>>().mockResolvedValue(undefined);
const browserYear = vi.spyOn(Date.prototype, "getFullYear").mockReturnValue(2026);

describe("album dashboard and open", () => {
  it("should carry the configured history limit into the opened workspace", async () => {
    let album: Album = { ...firstAlbum, synopsis: "", authors: ["Cosmin", "Mara", "Iacob"] };
    const client = buildClient({
      open: vi.fn(() => Promise.resolve(album)),
      saveAuthors: vi.fn<AlbumClient["saveAuthors"]>((_id, authors) => {
        album = { ...album, authors };
        return Promise.resolve(album);
      }),
    });
    const config = readAppConfig({ VITE_BOOK_HISTORY_LIMIT: "1" });
    window.history.replaceState(null, "", `/albums/${firstAlbum.id}`);
    render(() => <App session={editingSession} client={client} events={events} historyLimit={config.historyLimit} />);
    const mara = await screen.findByRole("button", { name: "Mara" });
    await waitFor(() => expect(mara).toBeEnabled());
    fireEvent.click(mara);
    await waitFor(() => expect(mara).toHaveAttribute("aria-pressed", "false"));
    const iacob = screen.getByRole("button", { name: "Iacob" });
    await waitFor(() => expect(iacob).toBeEnabled());
    fireEvent.click(iacob);
    await waitFor(() => expect(iacob).toHaveAttribute("aria-pressed", "false"));
    const undo = screen.getByRole("button", { name: "Undo: Change authors" });
    await waitFor(() => expect(undo).toBeEnabled());
    fireEvent.click(undo);

    await waitFor(() => expect(screen.getByRole("button", { name: "Redo: Change authors" })).toBeEnabled());
    expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled();
    expect(iacob).toHaveAttribute("aria-pressed", "true");
    expect(mara).toHaveAttribute("aria-pressed", "false");
    expect(client.saveAuthors).toHaveBeenLastCalledWith(firstAlbum.id, ["Cosmin", "Iacob"]);
  });

  it("should retain the catalog until album acquisition and initial ownership settle, then focus the workspace", async () => {
    let admit!: () => void;
    const reading = new Promise<void>((resolve) => {
      admit = resolve;
    });
    let confirm!: (value: boolean) => void;
    const confirming = new Promise<boolean>((resolve) => {
      confirm = resolve;
    });
    const select = vi.fn<WorkspaceEditingSession["select"]>((target) => (target === null ? Promise.resolve(true) : confirming));
    const base = buildClient();
    const client = buildClient({
      open: vi.fn(async (id: string, signal?: AbortSignal) => {
        await reading;
        return base.open(id, signal);
      }),
    });
    render(() => <App session={{ ...editingSession, select }} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("link", { name: firstAlbum.title }));

    expect(await screen.findByRole("status", { name: "Opening album" })).toBeVisible();
    expect(screen.getByRole("heading", { name: "Album dashboard" })).toBeVisible();
    expect(screen.queryByRole("status", { name: "Loading album" })).not.toBeInTheDocument();
    admit();
    await waitFor(() => {
      expect(select).toHaveBeenCalledWith(expect.objectContaining({ kind: "spread" }), false);
    });
    expect(screen.getByRole("heading", { name: "Album dashboard" })).toBeVisible();
    expect(screen.getByRole("status", { name: "Opening album" })).toBeVisible();
    confirm(true);

    expect(await screen.findByRole("heading", { level: 1, name: firstAlbum.title })).toBeVisible();
    expect(screen.queryByRole("heading", { name: "Album dashboard" })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("main")).toHaveFocus());
  });

  it("should reveal retryable opening failures instead of leaving the catalog busy", async () => {
    const client = buildClient({ open: vi.fn().mockRejectedValue(new Error("offline")) });
    render(() => <App session={editingSession} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("link", { name: firstAlbum.title }));
    expect(await screen.findByRole("button", { name: "Retry album" })).toBeVisible();
    expect(screen.queryByRole("status", { name: "Opening album" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Album dashboard" })).not.toBeInTheDocument();
  });

  it("should preserve the loaded document across catalog navigation and keep draft resolution ahead of history departure", async () => {
    const select = vi.fn().mockResolvedValue(true);
    const documentBefore = document;
    const client = buildClient({
      spread: vi.fn().mockResolvedValue({
        id: "opening",
        bleed: { state: "disabled" as const, available: true },
        templateId: "layout",
        chapter: null,
        kind: "album_start",
        placements: [],
        slots: [{ id: "text-slot", kind: "text", x: 50, y: 4, width: 48, height: 92 }],
      }),
    });
    render(() => <App session={{ ...editingSession, select }} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("link", { name: firstAlbum.title }));
    await screen.findByRole("heading", { level: 1, name: firstAlbum.title });
    expect(document).toBe(documentBefore);
    fireEvent.click(screen.getByRole("button", { name: "Edit synopsis" }));
    const editor = screen.getByRole("textbox", { name: "Synopsis" });
    fireEvent.input(editor, { target: { value: "Retained text" } });
    window.history.pushState(null, "", "/");
    window.dispatchEvent(new PopStateEvent("popstate"));
    await waitFor(() => {
      expect(window.location.pathname).toBe(`/albums/${firstAlbum.id}`);
    });
    expect(editor).toHaveValue("Retained text");
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    fireEvent.click(screen.getByRole("link", { name: "Album Layout Studio" }));
    await screen.findByRole("heading", { name: "Album dashboard" });
    expect(select).toHaveBeenLastCalledWith(null);
    expect(window.location.pathname).toBe("/");
    expect(document).toBe(documentBefore);
  });

  beforeAll(() => {
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe = vi.fn();
        disconnect = vi.fn();
      },
    );
    HTMLDialogElement.prototype.showModal = function () {
      this.setAttribute("open", "");
    };
    HTMLDialogElement.prototype.close = function () {
      this.removeAttribute("open");
    };
    Object.defineProperty(HTMLDialogElement.prototype, "getAnimations", {
      configurable: true,
      value: () => [{ finished: dialogAnimationFinished() }],
    });
  });
  afterAll(() => {
    vi.unstubAllGlobals();
    browserYear.mockRestore();
    for (const [name, maybeDescriptor] of Object.entries(nativeDialogMethods)) {
      if (maybeDescriptor === undefined) Reflect.deleteProperty(HTMLDialogElement.prototype, name);
      else Object.defineProperty(HTMLDialogElement.prototype, name, maybeDescriptor);
    }
  });
  it("should show persisted albums and preserve them while the next page loads", async () => {
    let resolveNext: (page: AlbumPage) => void = () => {
      throw new Error("Next page was not requested");
    };
    const nextPage = new Promise<AlbumPage>((resolve) => {
      resolveNext = resolve;
    });
    const client = buildClient({ list: vi.fn().mockResolvedValueOnce(firstPage).mockReturnValueOnce(nextPage) });

    render(() => <App session={editingSession} client={client} events={events} />);

    expect(await screen.findByRole("link", { name: firstAlbum.title })).toHaveAttribute("href", `/albums/${firstAlbum.id}`);
    expect(screen.queryByRole("region", { name: "Album start layouts" })).not.toBeInTheDocument();
    expect(screen.getByRole("banner")).toContainElement(screen.getByRole("link", { name: "Album Layout Studio" }));
    expect(screen.getByRole("heading", { name: "Album dashboard" })).toBeInTheDocument();
    expect(screen.getByRole("main")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: `Rename ${firstAlbum.title}` })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(screen.getByRole("link", { name: firstAlbum.title })).toBeInTheDocument();
    resolveNext(secondPage);
    expect(await screen.findByText("Page 2")).toBeInTheDocument();
  });

  it("should ask for the browser year before creating an album, restore focus and keep opening separate", async () => {
    const createdAlbum = { ...lastAlbum, year: 2040, title: "Journey of the Shepherds 2040" };
    const client = buildClient({
      create: vi.fn().mockResolvedValue(createdAlbum),
      list: vi
        .fn()
        .mockResolvedValueOnce(firstPage)
        .mockResolvedValueOnce({ ...firstPage, albums: [createdAlbum, ...firstPage.albums.slice(0, 5)], totalAlbums: 8 }),
    });
    render(() => <App session={editingSession} client={client} events={events} />);
    const add = screen.getByRole("button", { name: "Add album" });
    add.focus();

    fireEvent.click(add);
    const dialog = screen.getByRole("dialog", { name: "Create an album" });
    expect(within(dialog).getByRole("textbox", { name: "Album year" })).toHaveValue("2026");
    expect(client.create).not.toHaveBeenCalled();
    fireEvent.input(within(dialog).getByRole("textbox", { name: "Album year" }), { target: { value: " 2040 " } });
    fireEvent.submit(within(dialog).getByRole("form", { name: "Album details" }));

    await waitFor(() => {
      expect(client.create).toHaveBeenCalledWith(2040);
    });
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(add).toHaveFocus();
    expect(window.location.pathname).toBe("/");
    expect(await screen.findByRole("link", { name: createdAlbum.title })).toBeInTheDocument();
  });

  it("should retain a duplicate year with readable feedback and delete only after explicit confirmation", async () => {
    const client = buildClient({ create: vi.fn().mockRejectedValue(new AlbumRequestError(400, "An album already exists for that year")) });
    render(() => <App session={editingSession} client={client} events={events} />);
    await screen.findByRole("link", { name: firstAlbum.title });

    fireEvent.click(screen.getByRole("button", { name: "Add album" }));
    const input = screen.getByRole("textbox", { name: "Album year" });
    fireEvent.input(input, { target: { value: "2026" } });
    fireEvent.submit(screen.getByRole("form", { name: "Album details" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("An album already exists for that year");
    expect(input).toHaveValue("2026");
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    fireEvent.click(screen.getByRole("button", { name: `Delete ${firstAlbum.title}` }));
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => {
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    });
    expect(client.remove).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: `Delete ${firstAlbum.title}` }));
    fireEvent.click(screen.getByRole("button", { name: "Delete permanently" }));

    await waitFor(() => {
      expect(client.remove).toHaveBeenCalledWith(firstAlbum.id);
    });
  });

  it("should open a directly addressed album workspace read-only", async () => {
    window.history.replaceState(null, "", `/albums/${firstAlbum.id}`);
    const client = buildClient();

    render(() => <App session={editingSession} client={client} events={events} />);

    expect(await screen.findByRole("heading", { level: 1, name: firstAlbum.title })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Synopsis composition" })).toBeInTheDocument();
    expect(screen.queryByText("Read-only album")).not.toBeInTheDocument();
    expect(screen.queryByText(firstAlbum.createdAt)).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to album catalog" })).toHaveAttribute("href", "/");
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(client.open).toHaveBeenCalledWith(firstAlbum.id, expect.any(AbortSignal));
  });

  it("should distinguish initial loading, retryable list failure and successful empty albums", async () => {
    const client = buildClient({
      list: vi
        .fn()
        .mockRejectedValueOnce(new Error("Offline"))
        .mockResolvedValueOnce({ albums: [], page: 1, pageSize: 6, totalAlbums: 0, totalPages: 0 }),
    });

    render(() => <App session={editingSession} client={client} events={events} />);

    expect(screen.getByRole("status", { name: "Loading albums" })).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent("Albums could not be loaded");
    expect(screen.queryByRole("heading", { name: "No albums yet" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry albums" }));
    await waitFor(() => {
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });
    expect(screen.queryByRole("navigation", { name: "Album pages" })).not.toBeInTheDocument();
    expect(screen.queryByText("No albums yet")).not.toBeInTheDocument();
  });

  it("should leave an empty dashboard blank with one Add action and restore its focus on close and create", async () => {
    const client = buildClient({
      list: vi
        .fn()
        .mockResolvedValueOnce({ albums: [], page: 1, pageSize: 6, totalAlbums: 0, totalPages: 0 })
        .mockResolvedValueOnce({ albums: [firstAlbum], page: 1, pageSize: 6, totalAlbums: 1, totalPages: 1 }),
    });
    render(() => <App session={editingSession} client={client} events={events} />);
    await waitFor(() => {
      expect(screen.queryByRole("status", { name: "Loading albums" })).not.toBeInTheDocument();
    });
    const firstAlbumAction = screen.getByRole("button", { name: "Add album" });
    expect(screen.getAllByRole("button")).toEqual([firstAlbumAction]);
    expect(screen.queryByText("No albums yet")).not.toBeInTheDocument();

    fireEvent.click(firstAlbumAction);
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(screen.getByRole("status")).toHaveTextContent("Closing album dialog.");
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(firstAlbumAction).toHaveFocus();
    fireEvent.click(firstAlbumAction);
    fireEvent.input(screen.getByRole("textbox", { name: "Album year" }), { target: { value: "2026" } });
    fireEvent.submit(screen.getByRole("form", { name: "Album details" }));

    expect(await screen.findByRole("link", { name: firstAlbum.title })).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(screen.getByRole("button", { name: "Add album" })).toHaveFocus();
  });

  it("should retain an invalid year and allow correction before creating", async () => {
    const createdAlbum = { ...firstAlbum, year: 2040, title: "Journey of the Shepherds 2040" };
    const client = buildClient({
      create: vi.fn().mockResolvedValue(createdAlbum),
      list: vi
        .fn()
        .mockResolvedValueOnce(firstPage)
        .mockResolvedValueOnce({ ...firstPage, albums: [createdAlbum, ...firstPage.albums.slice(0, 5)] }),
    });
    render(() => <App session={editingSession} client={client} events={events} />);
    const add = screen.getByRole("button", { name: "Add album" });
    add.focus();

    fireEvent.click(add);
    const input = screen.getByRole("textbox", { name: "Album year" });
    expect(input).toHaveFocus();
    fireEvent.input(input, { target: { value: "   " } });
    fireEvent.submit(screen.getByRole("form", { name: "Album details" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Choose a year from 1900 to 2100.");
    expect(input).toHaveValue("   ");
    expect(client.create).not.toHaveBeenCalled();
    fireEvent.input(input, { target: { value: " 2040 " } });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    fireEvent.submit(screen.getByRole("form", { name: "Album details" }));

    await waitFor(() => {
      expect(client.create).toHaveBeenCalledWith(2040);
    });
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(add).toHaveFocus();
  });

  it("should preserve rename input on failure, save a correction and restore focus", async () => {
    const renamedAlbum = { ...firstAlbum, title: "Asian capitals" };
    const client = buildClient({
      rename: vi.fn().mockRejectedValueOnce(new Error("Offline")).mockResolvedValueOnce(renamedAlbum),
      list: vi
        .fn()
        .mockResolvedValueOnce(firstPage)
        .mockResolvedValueOnce({ ...firstPage, albums: [renamedAlbum, ...firstPage.albums.slice(1)] }),
    });
    render(() => <App session={editingSession} client={client} events={events} />);
    const rename = await screen.findByRole("button", { name: `Rename ${firstAlbum.title}` });

    fireEvent.click(rename);
    const input = screen.getByRole("textbox", { name: "Album name" });
    expect(input).toHaveFocus();
    expect(input).toHaveValue(firstAlbum.title);
    fireEvent.input(input, { target: { value: "   " } });
    fireEvent.submit(screen.getByRole("form", { name: "Album details" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Enter an album title.");
    expect(client.rename).not.toHaveBeenCalled();
    fireEvent.input(input, { target: { value: "Asian capitals" } });
    fireEvent.submit(screen.getByRole("form", { name: "Album details" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("could not");
    expect(input).toHaveValue("Asian capitals");
    fireEvent.input(input, { target: { value: "  Asian capitals  " } });
    fireEvent.submit(screen.getByRole("form", { name: "Album details" }));

    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(client.rename).toHaveBeenLastCalledWith(firstAlbum.id, renamedAlbum.title);
    expect(await screen.findByRole("button", { name: `Rename ${renamedAlbum.title}` })).toHaveFocus();
    expect(screen.getByRole("button", { name: `Rename ${renamedAlbum.title}` })).toBe(rename);
  });

  it("should protect a pending delete, preserve confirmation on failure and fall back after the last page disappears", async () => {
    let rejectDelete: (error: Error) => void = () => {
      throw new Error("Delete was not requested");
    };
    const pendingDelete = new Promise<void>((_resolve, reject) => {
      rejectDelete = reject;
    });
    const client = buildClient({
      list: vi
        .fn()
        .mockResolvedValueOnce(firstPage)
        .mockResolvedValueOnce(secondPage)
        .mockResolvedValueOnce({ ...firstPage, totalAlbums: 6, totalPages: 1 }),
      remove: vi.fn().mockReturnValueOnce(pendingDelete).mockResolvedValueOnce(undefined),
    });
    render(() => <App session={editingSession} client={client} events={events} />);
    await screen.findByRole("link", { name: firstAlbum.title });

    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText("Page 2");
    fireEvent.click(screen.getByRole("button", { name: `Delete ${lastAlbum.title}` }));
    expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "Delete permanently" }));
    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Delete permanently" })).toHaveAccessibleDescription("Saving album. Please wait.");
    fireEvent(screen.getByRole("alertdialog"), new Event("cancel", { cancelable: true }));
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    rejectDelete(new Error("Offline"));
    expect(await screen.findByRole("alert")).toHaveTextContent("could not");
    fireEvent.click(screen.getByRole("button", { name: "Delete permanently" }));

    expect(await screen.findByText("Page 1")).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    });
    expect(screen.getByRole("button", { name: "Add album" })).toHaveFocus();
  });

  it.each(["finished", "cancelled"])("should return to a previous page and dismiss a popup after its animation is %s", async (animationOutcome) => {
    const client = buildClient({ list: vi.fn().mockResolvedValueOnce(firstPage).mockResolvedValueOnce(secondPage).mockResolvedValueOnce(firstPage) });
    if (animationOutcome === "cancelled") dialogAnimationFinished.mockRejectedValueOnce(new DOMException("Animation cancelled", "AbortError"));
    render(() => <App session={editingSession} client={client} events={events} />);
    await screen.findByRole("link", { name: firstAlbum.title });

    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText("Page 2");
    fireEvent.click(screen.getByRole("button", { name: "Previous" }));
    await screen.findByText("Page 1");
    fireEvent.click(screen.getByRole("button", { name: "Add album" }));
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));

    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(screen.getByRole("button", { name: "Add album" })).toHaveFocus();
  });

  it.each([
    [404, "open"],
    [500, "open"],
    [404, "chapters"],
    [500, "chapters"],
  ] as const)("should distinguish missing album from retryable HTTP %i at %s and never show partial metadata", async (status, operation) => {
    window.history.replaceState(null, "", `/albums/${firstAlbum.id}`);
    const client = buildClient({
      [operation]: vi
        .fn()
        .mockRejectedValueOnce(new AlbumRequestError(status, "Request failed"))
        .mockResolvedValueOnce(operation === "open" ? { ...firstAlbum, synopsis: "After retry.", authors: ["Cosmin", "Mara", "Iacob"] } : []),
    });

    render(() => <App session={editingSession} client={client} events={events} />);

    expect(screen.getByRole("status", { name: "Loading album" })).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent(status === 404 ? "could not be found" : "could not be loaded");
    expect(screen.queryByRole("heading", { level: 1, name: firstAlbum.title })).not.toBeInTheDocument();
    if (status === 500) {
      fireEvent.click(screen.getByRole("button", { name: "Retry album" }));
      expect(await screen.findByRole("heading", { level: 1, name: firstAlbum.title })).toBeInTheDocument();
    } else {
      expect(screen.queryByRole("button", { name: "Retry album" })).not.toBeInTheDocument();
    }
  });
  it.each(["interrupted", "invalid"] as const)("should report an initial %s stream and recover by reading existing workspace state", async (kind) => {
    window.history.replaceState(null, "", `/albums/${firstAlbum.id}`);
    let receive: (event: PhotoEvent) => void = () => {
      throw new Error("Not subscribed");
    };
    const liveEvents: AlbumPhotoEvents = {
      subscribe: (_id, listener) => {
        receive = listener;
        return vi.fn();
      },
    };
    const client = buildClient();
    render(() => <App session={editingSession} client={client} events={liveEvents} />);

    receive({ kind });
    expect(screen.getByRole("alert")).toHaveTextContent(
      kind === "invalid" ? "Live photo update could not be decoded" : "Live photo updates interrupted",
    );
    if (kind === "invalid") {
      expect(screen.getByRole("link", { name: "Reopen album" })).toHaveAttribute("href", window.location.href);
      expect(screen.queryByRole("status", { name: "Loading album" })).not.toBeInTheDocument();
    }
    expect(client.chapters).not.toHaveBeenCalled();
    receive({ kind: "subscribed" });
    await screen.findByRole("heading", { level: 1, name: firstAlbum.title });

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(client.chapters).toHaveBeenCalledOnce();
    expect(client.refreshPhotos).not.toHaveBeenCalled();
  });
});

function buildClient(overrides: Partial<AlbumClient> = {}): AlbumClient {
  return {
    photo: vi.fn().mockRejectedValue(new Error("Unexpected individual photo read")),
    gearCatalog: vi.fn().mockResolvedValue({ cameras: [], lenses: [] }),
    chapterGear: vi.fn().mockResolvedValue([]),
    saveChapterGear: vi.fn(),
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
              layout: { kind: "album_start", bleed: { state: "disabled" as const, available: true }, templateId: "layout", slots: [] },
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
              layout: { kind: "album_start", bleed: { state: "disabled" as const, available: true }, templateId: "layout", slots: [] },
            },
      ),
    ),
    list: vi.fn().mockResolvedValue(firstPage),
    create: vi.fn().mockResolvedValue(firstAlbum),
    rename: vi.fn().mockResolvedValue(firstAlbum),
    open: vi.fn().mockResolvedValue({ ...firstAlbum, synopsis: "The sea at dawn.", authors: ["Cosmin", "Mara", "Iacob"] }),
    saveSynopsis: vi.fn(),
    saveAuthors: vi.fn(),
    saveDescription: vi.fn(),
    saveCaption: vi.fn(),
    remove: vi.fn().mockResolvedValue(undefined),
    chapters: vi.fn().mockResolvedValue([]),
    composition: vi.fn().mockResolvedValue([{ id: "opening", chapter: null }]),
    spread: vi.fn().mockResolvedValue({
      id: "opening",
      bleed: { state: "disabled" as const, available: true },
      templateId: "layout",
      chapter: null,
      kind: "album_start",
      slots: [],
      placements: [],
    }),
    countries: vi.fn().mockResolvedValue([]),
    photoSource: vi.fn().mockResolvedValue("/photos"),
    createChapter: vi.fn(),
    updateChapter: vi.fn(),
    chapterPhotos: vi.fn(),
    refreshPhotos: vi.fn(),
    templates: vi.fn().mockResolvedValue([]),
    addSpread: vi.fn(),
    replaceChapterComposition: vi.fn(),
    changeSpreadTemplate: vi.fn(),
    deleteSpread: vi.fn(),
    ...overrides,
  };
}
