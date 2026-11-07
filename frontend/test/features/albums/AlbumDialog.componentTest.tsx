import { cleanup, fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AlbumDialog } from "../../../src/features/albums/dialog/AlbumDialog";
import { AlbumRequestError } from "../../../src/features/albums/model/Album";
import { buildAlbumClient } from "./AlbumClientStub";

describe("AlbumDialog", () => {
  const album = { id: "album", year: 2026, title: "Journey", createdAt: "2026-10-24T12:00:00Z" };
  const animation = vi.fn<() => Promise<void>>();
  const nativeMethods = Object.fromEntries(
    ["showModal", "close", "getAnimations"].map((name) => [name, Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, name)]),
  );

  beforeEach(() => {
    vi.spyOn(Date.prototype, "getFullYear").mockReturnValue(2026);
    animation.mockResolvedValue(undefined);
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
    const create = vi.fn().mockResolvedValue(album);
    const saved = vi.fn().mockResolvedValue(undefined);
    const close = vi.fn();
    render(() => (
      <AlbumDialog client={buildAlbumClient({ create })} action={{ kind: "create" }} onSaved={saved} onClose={close} returnFocus={vi.fn()} />
    ));
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
    const create = vi.fn().mockRejectedValueOnce(new AlbumRequestError(400, "An album already exists for that year")).mockResolvedValueOnce(album);
    const close = vi.fn();
    render(() => (
      <AlbumDialog
        client={buildAlbumClient({ create })}
        action={{ kind: "create" }}
        onSaved={vi.fn().mockResolvedValue(undefined)}
        onClose={close}
        returnFocus={vi.fn()}
      />
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

  it("should require confirmation, protect a pending deletion and retain failed confirmation for retry", async () => {
    let reject!: (error: Error) => void;
    const pending = new Promise<void>((_resolve, failure) => {
      reject = failure;
    });
    const remove = vi.fn().mockReturnValueOnce(pending).mockResolvedValueOnce(undefined);
    const close = vi.fn();
    const saved = vi.fn().mockResolvedValue(undefined);
    render(() => (
      <AlbumDialog client={buildAlbumClient({ remove })} action={{ kind: "delete", album }} onSaved={saved} onClose={close} returnFocus={vi.fn()} />
    ));
    expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
    expect(remove).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Delete permanently" }));

    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Delete permanently" })).toHaveAccessibleDescription("Saving album. Please wait.");
    fireEvent(screen.getByRole("alertdialog"), new Event("cancel", { cancelable: true }));
    expect(close).not.toHaveBeenCalled();
    reject(new Error("Offline"));
    expect(await screen.findByRole("alert")).toHaveTextContent("could not");
    expect(saved).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Delete permanently" }));
    await waitFor(() => {
      expect(close).toHaveBeenCalledOnce();
    });
    expect(remove.mock.calls).toEqual([[album.id], [album.id]]);
    expect(saved).toHaveBeenCalledOnce();
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
    const view = render(() => (
      <AlbumDialog client={buildAlbumClient({ create })} action={{ kind: "create" }} onSaved={vi.fn()} onClose={close} returnFocus={returnFocus} />
    ));

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
