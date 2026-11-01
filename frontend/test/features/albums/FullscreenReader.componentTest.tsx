import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createSignal } from "solid-js";
import { FullscreenReader } from "../../../src/features/albums/workspace/FullscreenReader";

describe("fullscreen reader browser boundary", () => {
  it("should retain the canvas heading and mount the Reader control in the global toolbar", () => {
    const toolbar = document.createElement("div");
    toolbar.setAttribute("role", "toolbar");
    toolbar.setAttribute("aria-label", "Workspace controls");
    document.body.append(toolbar);
    const view = render(() => (
      <FullscreenReader
        aspectRatio={3}
        canPrevious={false}
        canNext={true}
        entryMount={toolbar}
        unavailable={undefined}
        onNavigate={vi.fn()}
        label="Kyoto"
      >
        <div role="img" aria-label="Kyoto composition" />
      </FullscreenReader>
    ));

    const reader = within(toolbar).getByRole("button", { name: "Reader mode" });

    expect(screen.getByRole("heading", { name: "Kyoto" })).toBeInTheDocument();
    expect(reader).toHaveAccessibleName("Reader mode");
    expect(reader).toHaveAttribute("title", "Reader mode");
    expect(reader).toHaveTextContent(/^$/);

    view.unmount();
    toolbar.remove();
  });

  let fullscreen: Element | null;
  const request = vi.fn<() => Promise<void>>();
  const exit = vi.fn<() => Promise<void>>();

  beforeEach(() => {
    fullscreen = null;
    request.mockImplementation(function (this: HTMLElement) {
      fullscreen = document.contains(this) ? this : null;
      document.dispatchEvent(new Event("fullscreenchange"));
      return Promise.resolve();
    });
    exit.mockImplementation(() => {
      fullscreen = null;
      document.dispatchEvent(new Event("fullscreenchange"));
      return Promise.resolve();
    });
    Object.defineProperty(document, "fullscreenElement", { configurable: true, get: () => fullscreen });
    Object.defineProperty(HTMLElement.prototype, "requestFullscreen", { configurable: true, value: request });
    Object.defineProperty(document, "exitFullscreen", { configurable: true, value: exit });
  });
  afterEach(() => {
    vi.restoreAllMocks();
    Reflect.deleteProperty(document, "fullscreenElement");
    Reflect.deleteProperty(HTMLElement.prototype, "requestFullscreen");
    Reflect.deleteProperty(document, "exitFullscreen");
  });

  it("should enter browser fullscreen, navigate by keyboard and controls, and restore Reader focus after Escape or Exit reader", async () => {
    const navigate = vi.fn<(direction: -1 | 1) => void>();
    render(() => (
      <FullscreenReader aspectRatio={3} canPrevious={true} canNext={true} unavailable={undefined} onNavigate={navigate} label="Kyoto">
        <div role="img" aria-label="Persisted canvas" />
      </FullscreenReader>
    ));
    const reader = screen.getByRole("button", { name: "Reader mode" });

    fireEvent.keyDown(screen.getByRole("img", { name: "Persisted canvas" }), { key: "ArrowRight" });

    expect(navigate).not.toHaveBeenCalled();

    fireEvent.click(reader);

    await waitFor(() => expect(screen.getByRole("button", { name: "Exit reader" })).toHaveFocus());

    expect(screen.getByRole("dialog", { name: "Fullscreen reader" })).toBeInTheDocument();

    fireEvent.keyDown(document.activeElement ?? document.body, { key: "ArrowLeft" });
    fireEvent.keyDown(document.activeElement ?? document.body, { key: "ArrowRight" });
    fireEvent.click(screen.getByRole("button", { name: "Previous spread" }));
    fireEvent.click(screen.getByRole("button", { name: "Next spread" }));

    expect(request).toHaveBeenCalledOnce();
    expect(navigate.mock.calls).toEqual([[-1], [1], [-1], [1]]);
    expect(screen.getByRole("img", { name: "Persisted canvas" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Kyoto");

    fireEvent.keyDown(screen.getByRole("button", { name: "Exit reader" }), { key: "Tab" });

    expect(screen.getByRole("button", { name: "Previous spread" })).toHaveFocus();

    fireEvent.keyDown(document.activeElement ?? document.body, { key: "Tab", shiftKey: true });

    expect(screen.getByRole("button", { name: "Exit reader" })).toHaveFocus();

    screen.getByRole("button", { name: "Exit reader" }).blur();
    fireEvent.keyDown(document.body, { key: "Tab", shiftKey: true });

    expect(screen.getByRole("button", { name: "Exit reader" })).toHaveFocus();

    screen.getByRole("button", { name: "Exit reader" }).blur();
    fireEvent.keyDown(document.body, { key: "Tab" });

    expect(screen.getByRole("button", { name: "Previous spread" })).toHaveFocus();

    fireEvent.keyDown(document.activeElement ?? document.body, { key: "Home" });
    fullscreen = null;
    document.dispatchEvent(new Event("fullscreenchange"));

    expect(reader).toHaveFocus();
    expect(screen.queryByRole("button", { name: "Exit reader" })).not.toBeInTheDocument();

    fireEvent.click(reader);
    await screen.findByRole("button", { name: "Exit reader" });
    fireEvent.click(screen.getByRole("button", { name: "Exit reader" }));

    await waitFor(() => expect(reader).toHaveFocus());
    expect(exit).toHaveBeenCalledOnce();

    fireEvent.click(reader);
    await screen.findByRole("button", { name: "Exit reader" });
    fireEvent.keyDown(document.activeElement ?? document.body, { key: "Escape" });

    await waitFor(() => expect(reader).toHaveFocus());
    expect(exit).toHaveBeenCalledTimes(2);
  });

  it.each([
    { canPrevious: false, canNext: true, key: "ArrowLeft", button: "Previous spread" },
    { canPrevious: true, canNext: false, key: "ArrowRight", button: "Next spread" },
  ])("should disable endpoint navigation at $button without explanatory text", async ({ canPrevious, canNext, key, button }) => {
    const navigate = vi.fn();
    render(() => (
      <FullscreenReader
        aspectRatio={3}
        canPrevious={canPrevious}
        canNext={canNext}
        unavailable={undefined}
        onNavigate={navigate}
        label="Current part"
      >
        <div />
      </FullscreenReader>
    ));

    fireEvent.click(screen.getByRole("button", { name: "Reader mode" }));
    const control = await screen.findByRole("button", { name: button });
    fireEvent.keyDown(screen.getByRole("button", { name: "Exit reader" }), { key });
    fireEvent.keyDown(screen.getByRole("button", { name: "Exit reader" }), { key: "Tab" });

    expect(control).toBeDisabled();
    expect(control).not.toHaveAttribute("aria-describedby");
    expect(screen.queryByText(/Cover is the first part|This is the last spread/)).not.toBeInTheDocument();
    expect(navigate).not.toHaveBeenCalled();
  });

  it.each(["unsupported", "rejected", "exit"] as const)(
    "should expose a readable %s fullscreen failure without losing the canvas",
    async (failure) => {
      if (failure === "unsupported") Reflect.deleteProperty(HTMLElement.prototype, "requestFullscreen");
      if (failure === "rejected") request.mockRejectedValue(new Error("denied"));
      if (failure === "exit") exit.mockRejectedValue(new Error("denied"));
      render(() => (
        <FullscreenReader aspectRatio={3} canPrevious={true} canNext={true} unavailable={undefined} onNavigate={vi.fn()} label="Cover">
          <div role="img" aria-label="Saved Cover" />
        </FullscreenReader>
      ));

      fireEvent.click(screen.getByRole("button", { name: "Reader mode" }));
      if (failure === "exit") fireEvent.click(await screen.findByRole("button", { name: "Exit reader" }));

      expect(await screen.findByRole("alert")).toHaveTextContent(/fullscreen/i);
      expect(screen.getByRole("img", { name: "Saved Cover" })).toBeInTheDocument();
      if (failure !== "exit") expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled();
    },
  );

  it("should explain why an unavailable selection cannot enter Reader", () => {
    render(() => (
      <FullscreenReader aspectRatio={3} canPrevious={true} canNext={true} unavailable="Choose an available album part." onNavigate={vi.fn()} label="">
        <div />
      </FullscreenReader>
    ));

    expect(screen.getByRole("button", { name: "Reader mode" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Reader mode" })).toHaveAccessibleDescription("Choose an available album part.");
  });
  it.each([true, false])("should preserve owned Reader return focus across pending acquisition with retainedFocus=%s", async (retainedFocus) => {
    const [unavailable, change] = createSignal<string>();
    render(() => (
      <>
        <FullscreenReader aspectRatio={3} canPrevious={true} canNext={true} unavailable={unavailable()} onNavigate={vi.fn()} label="Kyoto">
          <div role="img" aria-label="Saved book" />
        </FullscreenReader>
        <button type="button">Other control</button>
      </>
    ));
    const reader = screen.getByRole("button", { name: "Reader mode" });
    fireEvent.click(reader);
    await screen.findByRole("button", { name: "Exit reader" });
    change("Loading saved content.");
    fireEvent.click(screen.getByRole("button", { name: "Exit reader" }));
    await waitFor(() => expect(screen.getByText("Loading saved content.")).toHaveFocus());
    const other = screen.getByRole("button", { name: "Other control" });
    if (!retainedFocus) other.focus();
    change(undefined);

    await waitFor(() => expect(retainedFocus ? reader : other).toHaveFocus());
    expect(reader).toBeEnabled();
    expect(screen.getByRole("img", { name: "Saved book" })).toBeInTheDocument();
  });

  it("should prevent duplicate requests while fullscreen entry is pending", async () => {
    let finish: () => void = () => undefined;
    request.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    render(() => (
      <FullscreenReader aspectRatio={3} canPrevious={true} canNext={true} unavailable={undefined} onNavigate={vi.fn()} label="Cover">
        <div />
      </FullscreenReader>
    ));

    fireEvent.click(screen.getByRole("button", { name: "Reader mode" }));

    expect(screen.getByRole("button", { name: "Reader mode" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Reader mode" })).toHaveAccessibleDescription("Entering fullscreen. Please wait.");

    finish();

    await waitFor(() => expect(screen.getByRole("button", { name: "Reader mode" })).toBeEnabled());
  });
});
