import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { afterEach, describe, expect, it, vi } from "vitest";
import { BookExportControl } from "../../../src/features/albums/workspace/BookExportControl";
import { BookExportBlocked, type BookDownload } from "../../../src/features/albums/model/BookExport";

describe("saved-book InDesign export", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("should explain unavailable export without acquiring or saving content", () => {
    const exportBook = vi.fn();
    render(() => <BookExportControl album="book" year={2025} client={{ exportBook }} unavailable="Wait for the saved edit." />);

    expect(screen.getByRole("button", { name: "Export for InDesign" })).toBeDisabled();
    expect(screen.getByRole("button")).toHaveAccessibleDescription(/Wait for the saved edit/);
    expect(screen.getByRole("button")).toHaveAttribute("title", "Wait for the saved edit.");
    expect(exportBook).not.toHaveBeenCalled();
    expect(screen.queryByText("Exports saved content only; unsaved drafts stay in the editor.")).not.toBeInTheDocument();
  });

  it("should prevent duplicate pending export and download one JSON for interior and cover", async () => {
    let finish!: (download: BookDownload) => void;
    const exportBook = vi.fn().mockReturnValue(
      new Promise<BookDownload>((resolve) => {
        finish = resolve;
      }),
    );
    const createObjectURL = vi.fn().mockReturnValue("blob:book");
    const revokeObjectURL = vi.fn();
    class DownloadURL extends URL {
      static override createObjectURL = createObjectURL;
      static override revokeObjectURL = revokeObjectURL;
    }
    vi.stubGlobal("URL", DownloadURL);
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    render(() => <BookExportControl album="book" year={2025} client={{ exportBook }} unavailable={undefined} />);

    fireEvent.click(screen.getByRole("button"));
    fireEvent.click(screen.getByRole("button"));
    expect(screen.getByRole("button")).toBeDisabled();
    expect(screen.getByRole("button")).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("status")).toHaveTextContent("Validating");
    finish({ content: '{"formatVersion":1,"interior":{},"cover":{}}', warnings: [] });

    await waitFor(() => expect(screen.getByRole("button")).toBeEnabled());
    expect(screen.getByRole("button")).toHaveAttribute("aria-busy", "false");
    expect(exportBook).toHaveBeenCalledExactlyOnceWith("book");
    expect(createObjectURL).toHaveBeenCalledExactlyOnceWith(expect.any(Blob));
    expect(click).toHaveBeenCalledOnce();
    expect(click.mock.instances[0]).toHaveAttribute("download", "album-2025-indesign.json");
    expect(revokeObjectURL).toHaveBeenCalledExactlyOnceWith("blob:book");
    expect(screen.getByRole("status")).toHaveTextContent("same JSON for the interior and cover");
  });

  it("should display scoped blocking issues and retry saved acquisition after failure", async () => {
    const exportBook = vi
      .fn()
      .mockRejectedValueOnce(
        new BookExportBlocked([{ code: "empty_description", severity: "blocking", target: { kind: "chapter", chapter: "coast" } }]),
      )
      .mockRejectedValueOnce(new Error("Source unavailable"));
    render(() => <BookExportControl album="book" year={2025} client={{ exportBook }} unavailable={undefined} />);

    fireEvent.click(screen.getByRole("button"));
    await screen.findByText(/empty description.*coast/);
    expect(screen.getByRole("button")).toBeEnabled();
    fireEvent.click(screen.getByRole("button"));
    await screen.findByText("The request could not be completed. Please try again.");

    expect(exportBook).toHaveBeenNthCalledWith(1, "book");
    expect(exportBook).toHaveBeenNthCalledWith(2, "book");
    expect(screen.queryByText(/empty description.*coast/)).not.toBeInTheDocument();
  });

  it("should retain keyboard focus through validation and report nonblocking print warnings", async () => {
    let finish!: (download: BookDownload) => void;
    const exportBook = vi.fn().mockReturnValue(
      new Promise<BookDownload>((resolve) => {
        finish = resolve;
      }),
    );
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:book");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    render(() => <BookExportControl album="book" year={2025} client={{ exportBook }} unavailable={undefined} />);

    const button = screen.getByRole("button");
    button.focus();
    fireEvent.click(button);
    expect(screen.getByRole("status")).toHaveFocus();
    finish({
      content: "{}",
      warnings: [{ code: "low_resolution", severity: "warning", target: { kind: "slot", spread: "opening", slot: "portrait" } }],
    });

    await screen.findByText(/warning: low resolution.*opening.*portrait/);
    expect(button).toHaveFocus();
  });

  it.each(["success", "failure"] as const)("should ignore late %s after leaving the album", async (outcome) => {
    let finish!: (download: BookDownload) => void;
    let reject!: (failure: Error) => void;
    const exportBook = vi.fn().mockReturnValue(
      new Promise<BookDownload>((resolve, fail) => {
        finish = resolve;
        reject = fail;
      }),
    );
    const createObjectURL = vi.spyOn(URL, "createObjectURL");
    const { unmount } = render(() => <BookExportControl album="book" year={2025} client={{ exportBook }} unavailable={undefined} />);

    fireEvent.click(screen.getByRole("button"));
    unmount();
    if (outcome === "success") finish({ content: "{}", warnings: [] });
    else reject(new Error("Late failure"));
    await Promise.resolve();

    expect(createObjectURL).not.toHaveBeenCalled();
  });
});
