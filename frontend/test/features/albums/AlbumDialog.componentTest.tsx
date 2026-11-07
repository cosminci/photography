import { cleanup, fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AlbumDialog } from "../../../src/features/albums/dialog/AlbumDialog";
import { AcknowledgedWriteError, AlbumRequestError } from "../../../src/features/albums/model/Album";
import { buildAlbumClient } from "./AlbumClientStub";

describe("AlbumDialog", () => {
  const album = { id: "album", year: 2026, title: "Journey", createdAt: "2026-10-24T12:00:00Z" };
  const animation = vi.fn<() => Promise<void>>();
  const nativeMethods = Object.fromEntries(
    ["showModal", "close", "getAnimations"].map((name) => [name, Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, name)]),
  );

  beforeEach(() => {
    vi.spyOn(Date.prototype, "getFullYear").mockReturnValue(2026);
    animation.mockClear().mockResolvedValue(undefined);
    HTMLDialogElement.prototype.showModal = function () {
      this.setAttribute("open", "");
    };
    HTMLDialogElement.prototype.close = function () {
      this.removeAttribute("open");
    };
    Object.defineProperty(HTMLDialogElement.prototype, "getAnimations", { configurable: true, value: () => [{ finished: animation() }] });
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    for (const [name, descriptor] of Object.entries(nativeMethods)) {
      if (descriptor === undefined) Reflect.deleteProperty(HTMLDialogElement.prototype, name);
      else Object.defineProperty(HTMLDialogElement.prototype, name, descriptor);
    }
  });

  it("should start with the browser year, retain invalid input and create only after correction", async () => {
    const create = vi.fn().mockResolvedValue({ ...album, year: 2040 });
    const saved = vi.fn().mockResolvedValue(undefined);
    const close = vi.fn();
    render(() => <AlbumDialog client={buildAlbumClient({ create })} onSaved={saved} onClose={close} returnFocus={vi.fn()} />);
    const input = screen.getByRole("textbox", { name: "Album year" });
    expect(input).toHaveValue("2026");
    expect(input).toHaveFocus();

    fireEvent.input(input, { target: { value: "   " } });
    fireEvent.submit(screen.getByRole("form", { name: "Album details" }));

    expect(screen.getByRole("alert")).toHaveTextContent("Choose a year from 1900 to 2100.");
    expect(input).toHaveValue("   ");
    expect(create).not.toHaveBeenCalled();
    fireEvent.input(input, { target: { value: " 2040 " } });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    fireEvent.submit(screen.getByRole("form", { name: "Album details" }));
    await waitFor(() => {
      expect(close).toHaveBeenCalledOnce();
    });
    expect(create).toHaveBeenCalledExactlyOnceWith(2040);
    expect(saved).toHaveBeenCalledOnce();
  });

  it("should retain a duplicate year with readable feedback and permit correction", async () => {
    const create = vi
      .fn()
      .mockRejectedValueOnce(new AlbumRequestError(400, "An album already exists for that year"))
      .mockResolvedValueOnce({ ...album, year: 2040 });
    const close = vi.fn();
    render(() => (
      <AlbumDialog client={buildAlbumClient({ create })} onSaved={vi.fn().mockResolvedValue(undefined)} onClose={close} returnFocus={vi.fn()} />
    ));
    const input = screen.getByRole("textbox", { name: "Album year" });

    fireEvent.submit(screen.getByRole("form", { name: "Album details" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("An album already exists for that year");
    expect(input).toHaveValue("2026");
    expect(close).not.toHaveBeenCalled();
    fireEvent.input(input, { target: { value: "2040" } });
    fireEvent.submit(screen.getByRole("form", { name: "Album details" }));
    await waitFor(() => {
      expect(close).toHaveBeenCalledOnce();
    });
    expect(create.mock.calls).toEqual([[2026], [2040]]);
  });

  it("should protect a pending creation and retain a rejected year for correction", async () => {
    let reject!: (error: Error) => void;
    const pending = new Promise<void>((_resolve, failure) => {
      reject = failure;
    });
    const create = vi.fn().mockReturnValueOnce(pending).mockResolvedValueOnce(album);
    const close = vi.fn();
    const saved = vi.fn().mockResolvedValue(undefined);
    render(() => <AlbumDialog client={buildAlbumClient({ create })} onSaved={saved} onClose={close} returnFocus={vi.fn()} />);
    expect(screen.getByRole("textbox", { name: "Album year" })).toHaveFocus();
    expect(create).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Create" }));

    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Create" })).toHaveAccessibleDescription("Saving content. Please wait.");
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    expect(close).not.toHaveBeenCalled();
    reject(new AlbumRequestError(400, "An album already exists for that year"));
    expect(await screen.findByRole("alert")).toHaveTextContent("already exists");
    expect(saved).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    await waitFor(() => {
      expect(close).toHaveBeenCalledOnce();
    });
    expect(create.mock.calls).toEqual([[2026], [2026]]);
    expect(saved).toHaveBeenCalledOnce();
  });

  it.each([
    { outcome: "acknowledged", failure: new AcknowledgedWriteError("Unreadable saved response") },
    { outcome: "uncertain", failure: new Error("Lost response") },
  ])("should recover an $outcome creation with catalog reads and never repeat the write", async ({ outcome, failure }) => {
    const create = vi.fn().mockRejectedValue(failure);
    const list = vi
      .fn()
      .mockRejectedValueOnce(new Error("Offline"))
      .mockResolvedValue({
        albums: [album],
        page: 1,
        totalPages: 1,
      });
    const close = vi.fn();
    render(() => <AlbumDialog client={buildAlbumClient({ create, list })} onSaved={vi.fn()} onClose={close} returnFocus={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    await screen.findByRole("button", { name: "Read saved album" });
    if (outcome === "uncertain") fireEvent.click(screen.getByRole("button", { name: "Read saved album" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Saved content could not be read"));
    expect(screen.getByRole("button", { name: "Create" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Read saved album" }));

    await waitFor(() => {
      expect(close).toHaveBeenCalledOnce();
    });
    expect(create).toHaveBeenCalledExactlyOnceWith(2026);
    expect(list.mock.calls).toEqual([[1], [1]]);
  });

  it("should search all saved catalog pages before allowing another creation", async () => {
    const create = vi.fn().mockRejectedValue(new Error("Lost response"));
    const list = vi
      .fn()
      .mockResolvedValueOnce({ albums: [], page: 1, totalPages: 2 })
      .mockResolvedValueOnce({
        albums: [album],
        page: 2,
        totalPages: 2,
      });
    const close = vi.fn();
    render(() => <AlbumDialog client={buildAlbumClient({ create, list })} onSaved={vi.fn()} onClose={close} returnFocus={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    fireEvent.click(await screen.findByRole("button", { name: "Read saved album" }));

    await waitFor(() => {
      expect(close).toHaveBeenCalledOnce();
    });
    expect(list.mock.calls).toEqual([[1], [2]]);
    expect(create).toHaveBeenCalledOnce();
  });

  it("should retain an acknowledged creation when the saved album is missing and retry only catalog reads", async () => {
    const create = vi.fn().mockRejectedValue(new AcknowledgedWriteError("Unreadable saved response"));
    const list = vi
      .fn()
      .mockResolvedValueOnce({ albums: [], page: 1, totalPages: 0 })
      .mockResolvedValueOnce({
        albums: [album],
        page: 1,
        totalPages: 1,
      });
    const close = vi.fn();
    render(() => <AlbumDialog client={buildAlbumClient({ create, list })} onSaved={vi.fn()} onClose={close} returnFocus={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("The saved album could not be found.");
    });
    expect(screen.getByRole("button", { name: "Create" })).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "Album year" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Read saved album" }));

    await waitFor(() => {
      expect(close).toHaveBeenCalledOnce();
    });
    expect(create).toHaveBeenCalledOnce();
    expect(list.mock.calls).toEqual([[1], [1]]);
  });

  it.each([
    { stage: "creation", outcome: "saved" },
    { stage: "publication", outcome: "saved" },
    { stage: "publication", outcome: "failed" },
    { stage: "closing", outcome: "saved" },
  ])("should not publish or close a disposed dialog after $stage finishes as $outcome", async ({ stage, outcome }) => {
    let finish!: () => void;
    let fail!: (failure: Error) => void;
    const pending = new Promise<void>((resolve, reject) => {
      finish = resolve;
      fail = reject;
    });
    const create = vi.fn().mockReturnValue(stage === "creation" ? pending.then(() => album) : Promise.resolve(album));
    const saved = vi.fn().mockReturnValue(stage === "publication" ? pending : Promise.resolve());
    const close = vi.fn();
    if (stage === "closing") animation.mockReturnValueOnce(pending);
    const view = render(() => <AlbumDialog client={buildAlbumClient({ create })} onSaved={saved} onClose={close} returnFocus={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    if (stage === "publication")
      await waitFor(() => {
        expect(saved).toHaveBeenCalledOnce();
      });
    if (stage === "closing")
      await waitFor(() => {
        expect(animation).toHaveBeenCalledOnce();
      });

    view.unmount();
    if (outcome === "failed") fail(new Error("Offline"));
    else finish();
    await pending.catch(() => undefined);
    await new Promise<void>((resolve) => setTimeout(resolve, 0));

    expect(create).toHaveBeenCalledOnce();
    expect(saved).toHaveBeenCalledTimes(stage === "creation" ? 0 : 1);
    expect(close).not.toHaveBeenCalled();
  });

  it("should allow correcting an uncertain creation only after the saved catalog confirms its absence", async () => {
    const create = vi
      .fn()
      .mockRejectedValueOnce(new Error("Lost response"))
      .mockResolvedValueOnce({ ...album, year: 2040 });
    const list = vi.fn().mockResolvedValue({ albums: [], page: 1, totalPages: 0 });
    const close = vi.fn();
    render(() => <AlbumDialog client={buildAlbumClient({ create, list })} onSaved={vi.fn()} onClose={close} returnFocus={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    fireEvent.click(await screen.findByRole("button", { name: "Read saved album" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Create" })).toBeEnabled());
    fireEvent.input(screen.getByRole("textbox", { name: "Album year" }), { target: { value: "2040" } });
    fireEvent.click(screen.getByRole("button", { name: "Create" }));

    await waitFor(() => {
      expect(close).toHaveBeenCalledOnce();
    });
    expect(create.mock.calls).toEqual([[2026], [2040]]);
    expect(list).toHaveBeenCalledExactlyOnceWith(1);
  });

  it("should retry catalog publication without creating the acknowledged album again", async () => {
    const create = vi.fn().mockResolvedValue(album);
    const saved = vi.fn().mockRejectedValueOnce(new Error("Offline")).mockResolvedValueOnce(undefined);
    const close = vi.fn();
    render(() => <AlbumDialog client={buildAlbumClient({ create })} onSaved={saved} onClose={close} returnFocus={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    await screen.findByRole("alert");
    fireEvent.click(screen.getByRole("button", { name: "Refresh albums" }));

    await waitFor(() => {
      expect(close).toHaveBeenCalledOnce();
    });
    expect(create).toHaveBeenCalledOnce();
    expect(saved).toHaveBeenCalledTimes(2);
  });

  it.each([
    { dismissal: "Close", cancelled: false },
    { dismissal: "Cancel", cancelled: false },
    { dismissal: "native", cancelled: true },
  ])("should finish $dismissal dismissal and restore focus even when animation cancellation is $cancelled", async ({ dismissal, cancelled }) => {
    if (cancelled) animation.mockRejectedValueOnce(new DOMException("Animation cancelled", "AbortError"));
    const close = vi.fn();
    const returnFocus = vi.fn();
    const create = vi.fn();
    const view = render(() => <AlbumDialog client={buildAlbumClient({ create })} onSaved={vi.fn()} onClose={close} returnFocus={returnFocus} />);

    if (dismissal === "native") fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    else fireEvent.click(screen.getByRole("button", { name: dismissal }));

    expect(screen.getByRole("status")).toHaveTextContent("Closing album dialog.");
    await waitFor(() => {
      expect(close).toHaveBeenCalledOnce();
    });
    expect(create).not.toHaveBeenCalled();
    view.unmount();
    expect(returnFocus).toHaveBeenCalledOnce();
  });
});
