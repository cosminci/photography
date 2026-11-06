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
    expect(screen.queryByRole("img", { name: "Export in progress" })).not.toBeInTheDocument();
    expect(screen.getByText("Id")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Validating saved book and preparing export.");
    finish({ content: '{"formatVersion":1,"interior":{},"cover":{}}', warnings: [] });

    await waitFor(() => expect(screen.getByRole("button")).toBeEnabled());
    expect(screen.getByRole("button")).toHaveAttribute("aria-busy", "false");
    expect(screen.queryByRole("img", { name: "Export in progress" })).not.toBeInTheDocument();
    expect(screen.getByText("Id")).toBeInTheDocument();
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
        new BookExportBlocked([
          {
            code: "empty_description",
            severity: "blocking",
            target: { kind: "chapter", chapter: "coast" },
            message: "Coast: chapter description is empty.",
          },
        ]),
      )
      .mockRejectedValueOnce(new Error("Source unavailable"));
    render(() => <BookExportControl album="book" year={2025} client={{ exportBook }} unavailable={undefined} />);

    fireEvent.click(screen.getByRole("button"));
    await screen.findByText("Cannot export: Coast: chapter description is empty.");
    expect(screen.getByRole("button", { name: "Export for InDesign" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Export for InDesign" }));
    await screen.findByText("The request could not be completed. Please try again.");

    expect(exportBook).toHaveBeenNthCalledWith(1, "book");
    expect(exportBook).toHaveBeenNthCalledWith(2, "book");
    expect(screen.queryByText("Cannot export: Coast: chapter description is empty.")).not.toBeInTheDocument();
  });

  it("should retain keyboard focus through validation and report nonblocking print warnings", async () => {
    let finish!: (download: BookDownload) => void;
    const exportBook = vi.fn().mockReturnValue(
      new Promise<BookDownload>((resolve) => {
        finish = resolve;
      }),
    );
    const createObjectURL = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:book");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    render(() => <BookExportControl album="book" year={2025} client={{ exportBook }} unavailable={undefined} />);

    const button = screen.getByRole("button");
    button.focus();
    fireEvent.click(button);
    expect(screen.getByRole("status")).toHaveFocus();
    finish({
      content: "{}",
      warnings: [
        {
          code: "low_resolution",
          severity: "warning",
          target: { kind: "slot", spread: "opening", slot: "portrait" },
          message: "Hội An - spread 12, photo 2: IMG_4321.jpg is 242 DPI (recommended 300 DPI).",
        },
      ],
    });

    await screen.findByText(/Low-resolution photos:.*Hội An - spread 12, photo 2: IMG_4321.jpg is 242 DPI/);
    expect(screen.queryByText(/opening.*portrait/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirm export" })).toHaveFocus();
    expect(createObjectURL).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Dismiss export warnings" }));
    expect(screen.queryByText(/Low-resolution photos:/)).not.toBeInTheDocument();
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

  it("should require low-resolution confirmation then revalidate and download only the fresh saved book", async () => {
    const onDismiss = vi.fn();
    const warnings = [
      {
        code: "low_resolution",
        severity: "warning" as const,
        target: { kind: "slot", spread: "opening", slot: "portrait" },
        message: "one.jpg is 242 DPI (recommended 300 DPI).",
      },
    ];
    let finish!: (download: BookDownload) => void;
    const exportBook = vi
      .fn()
      .mockResolvedValueOnce({ content: '{"saved":"before review"}', warnings })
      .mockReturnValueOnce(
        new Promise<BookDownload>((resolve) => {
          finish = resolve;
        }),
      );
    const createObjectURL = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:fresh-book");
    const revokeObjectURL = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    render(() => <BookExportControl album="book" year={2025} client={{ exportBook }} unavailable={undefined} onDismiss={onDismiss} />);
    const button = screen.getByRole("button", { name: "Export for InDesign" });

    fireEvent.click(button);
    const confirm = await screen.findByRole("button", { name: "Confirm export" });
    expect(screen.queryByText("Continue with export?")).not.toBeInTheDocument();
    expect(screen.getByText("|")).toBeVisible();
    expect(click).not.toHaveBeenCalled();
    expect(createObjectURL).not.toHaveBeenCalled();
    confirm.focus();
    fireEvent.click(confirm);
    fireEvent.click(button);
    expect(button).toBeDisabled();
    expect(screen.getByText("Id")).toBeInTheDocument();
    expect(exportBook).toHaveBeenCalledTimes(2);
    finish({ content: '{"saved":"fresh after reviewing photographs"}', warnings });

    await waitFor(() => expect(button).toBeEnabled());
    expect(click).toHaveBeenCalledOnce();
    expect(createObjectURL.mock.calls[0]?.[0]).toHaveProperty("size", new Blob(['{"saved":"fresh after reviewing photographs"}']).size);
    expect(revokeObjectURL).toHaveBeenCalledExactlyOnceWith("blob:fresh-book");
    expect(screen.queryByRole("button", { name: "Confirm export" })).not.toBeInTheDocument();
    expect(screen.queryByText(/Low-resolution photos:/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Dismiss export warnings" })).not.toBeInTheDocument();
    expect(screen.queryByText(/Saved book exported/)).not.toBeInTheDocument();
    expect(onDismiss).toHaveBeenCalledOnce();
    expect(button).toHaveFocus();
  });

  it("should preserve native modified link clicks and navigate only a normal activation", async () => {
    const navigate = vi.fn();
    const exportBook = vi
      .fn()
      .mockRejectedValue(
        new BookExportBlocked([
          { code: "composition_notice", severity: "warning", target: { kind: "album" }, message: "Check the saved composition." },
        ]),
      );
    render(() => (
      <BookExportControl
        album="book"
        year={2025}
        client={{ exportBook }}
        unavailable={undefined}
        issueLink={() => ({ label: "Album", href: "#", navigate })}
      />
    ));
    fireEvent.click(screen.getByRole("button", { name: "Export for InDesign" }));
    const link = await screen.findByRole("link", { name: "Check the saved composition." });
    expect(screen.getByText(/Warning:/)).toHaveTextContent("Warning: Check the saved composition.");
    for (const modifiers of [{ button: 1 }, { altKey: true }, { ctrlKey: true }, { metaKey: true }, { shiftKey: true }]) {
      fireEvent.click(link, modifiers);
    }
    expect(navigate).not.toHaveBeenCalled();
    fireEvent.click(link);
    expect(navigate).toHaveBeenCalledOnce();
  });
});
