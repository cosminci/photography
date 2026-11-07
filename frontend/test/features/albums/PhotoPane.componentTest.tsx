import { fireEvent, render, screen, within } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PhotoPane, hasProcessingFeedback } from "../../../src/features/albums/workspace/PhotoPane";
import type { PhotoInventory } from "../../../src/features/albums/model/Photo";

describe("photo processing visibility", () => {
  it.each([
    { state: "idle", unsupportedCount: 0, skippedCount: 0, issues: [], expected: false },
    { state: "completed", unsupportedCount: 0, skippedCount: 0, issues: [], expected: false },
    { state: "queued", unsupportedCount: 0, skippedCount: 0, issues: [], expected: true },
    { state: "running", unsupportedCount: 0, skippedCount: 0, issues: [], expected: true },
    { state: "failed", unsupportedCount: 0, skippedCount: 0, issues: [], expected: true },
    { state: "completed", unsupportedCount: 1, skippedCount: 0, issues: [], expected: true },
    { state: "completed", unsupportedCount: 0, skippedCount: 1, issues: [], expected: true },
    {
      state: "completed",
      unsupportedCount: 0,
      skippedCount: 0,
      issues: [{ stage: "cleanup", code: "cleanup_failed", filename: null }],
      expected: true,
    },
  ] as const)("should expose only meaningful $state processing feedback", ({ state, unsupportedCount, skippedCount, issues, expected }) => {
    expect(hasProcessingFeedback({ state, unsupportedCount, skippedCount, issues })).toBe(expected);
  });
});

describe("photo pane browser controls", () => {
  const inventory: PhotoInventory = {
    placedPhotoIds: [],
    photos: [
      {
        id: "photo",
        filename: "Photo.jpg",
        capturedLocal: null,
        width: 1200,
        height: 800,
        caption: null,
        thumbnail: { url: "/thumbnails/key/version.jpg", width: 600, height: 400 },
      },
    ],
    counts: { unplaced: 0, total: 1 },
    processing: {
      state: "failed",
      unsupportedCount: 2,
      skippedCount: 1,
      issues: [
        { stage: "cleanup", code: "cleanup_failed", filename: null },
        { stage: "read", code: "undecodable", filename: "bad.jpg" },
      ],
    },
  };
  const disconnect = vi.fn();
  const notifications: (() => void)[] = [];
  const nativeCapture = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "setPointerCapture");
  beforeEach(() => {
    Object.defineProperty(HTMLElement.prototype, "setPointerCapture", { configurable: true, value: vi.fn() });
    notifications.length = 0;
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe = vi.fn();
        unobserve = vi.fn();
        disconnect = disconnect;
        constructor(receive: ResizeObserverCallback) {
          notifications.push(() => {
            receive([], this);
          });
        }
      },
    );
    vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockImplementation(function (this: HTMLElement) {
      return this.getAttribute("aria-label") === "Photo browser and editor" ? 1000 : 308;
    });
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
      return new DOMRect(0, 0, this.getAttribute("aria-label") === "Photo browser and editor" ? 1000 : 308.5, 0);
    });
  });
  afterEach(() => {
    if (nativeCapture === undefined) Reflect.deleteProperty(HTMLElement.prototype, "setPointerCapture");
    else Object.defineProperty(HTMLElement.prototype, "setPointerCapture", nativeCapture);
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("chooses or drags only authoritative unplaced photos and explains temporary structural exclusion", () => {
    const choose = vi.fn();
    const [reason, setReason] = createSignal<string>();
    const [inventories, setInventories] = createSignal({ chapter: { ...inventory, counts: { unplaced: 1, total: 1 } } });
    render(() => (
      <PhotoPane
        label="Chapter photos"
        chapters={[{ id: "chapter", name: "Kyoto" }]}
        inventories={inventories()}
        inventoryErrors={{}}
        scheduling={{}}
        liveStatus="live"
        onRefresh={vi.fn()}
        onRetryInventory={vi.fn()}
        onRetryRefresh={vi.fn()}
        onChoosePhoto={choose}
        unavailable={reason()}
      >
        <div />
      </PhotoPane>
    ));
    const button = screen.getByRole("button", { name: "Choose Photo.jpg" });
    const image = screen.getByRole("img", { name: "Photo.jpg" });
    Object.defineProperties(image, { complete: { value: true }, naturalWidth: { value: 1600 } });
    vi.spyOn(button, "getBoundingClientRect").mockReturnValue(new DOMRect(0, 0, 120, 80));
    vi.spyOn(image, "getBoundingClientRect").mockReturnValue(new DOMRect(0, 0, 120, 80));
    const context = { scale: vi.fn(), translate: vi.fn(), drawImage: vi.fn() };
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockImplementation(vi.fn().mockReturnValue(context));
    const transfer = { setData: vi.fn(), setDragImage: vi.fn() };

    fireEvent.click(button);
    fireEvent.dragStart(button, { dataTransfer: transfer });
    setReason("Saving spread. Please wait.");
    const unavailablePhoto = screen.getByRole("button", { name: "Choose Photo.jpg", description: "Saving spread. Please wait." });
    setInventories({ chapter: { ...inventory, placedPhotoIds: ["photo"] } });

    expect(choose).toHaveBeenCalledWith("photo");
    expect(transfer.setDragImage).toHaveBeenCalled();
    expect(unavailablePhoto).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Choose Photo.jpg" })).not.toBeInTheDocument();
  });

  it("explains an unready thumbnail preview and retains keyboard selection as an alternative", () => {
    const choose = vi.fn();
    const photos: PhotoInventory = {
      ...inventory,
      counts: { unplaced: 1, total: 1 },
      processing: { state: "idle", unsupportedCount: 0, skippedCount: 0, issues: [] },
    };
    render(() => (
      <PhotoPane
        label="Chapter photos"
        chapters={[{ id: "chapter", name: "Kyoto" }]}
        inventories={{ chapter: photos }}
        inventoryErrors={{}}
        scheduling={{}}
        liveStatus="live"
        onRefresh={vi.fn()}
        onRetryInventory={vi.fn()}
        onRetryRefresh={vi.fn()}
        onChoosePhoto={choose}
      >
        <div />
      </PhotoPane>
    ));
    const button = screen.getByRole("button", { name: "Choose Photo.jpg" });
    vi.spyOn(button, "getBoundingClientRect").mockReturnValue(new DOMRect(0, 0, 120, 80));

    const accepted = fireEvent.dragStart(button, { dataTransfer: { setData: vi.fn(), setDragImage: vi.fn() } });
    const feedback = screen.getByRole("alert").textContent;
    fireEvent.click(button);

    expect(accepted).toBe(false);
    expect(feedback).toContain("Wait for the photo to load, or use keyboard selection");
    expect(choose).toHaveBeenCalledWith("photo");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("should render authoritative counts, complete processing feedback and unchanged lazy image URLs without photo actions", () => {
    const refresh = vi.fn();
    const retryInventory = vi.fn();
    const retryRefresh = vi.fn();
    const [inventories, change] = createSignal({ chapter: inventory });
    const [chapters, changeChapters] = createSignal([{ id: "chapter", name: "Kyoto" }]);
    const view = render(() => (
      <PhotoPane
        label="Chapter photos"
        chapters={chapters()}
        inventories={inventories()}
        inventoryErrors={{ chapter: "Inventory unavailable" }}
        scheduling={{ chapter: "failed" }}
        liveStatus="interrupted"
        onRefresh={refresh}
        onRetryInventory={retryInventory}
        onRetryRefresh={retryRefresh}
      >
        <div>Editor</div>
      </PhotoPane>
    ));
    const image = screen.getByRole("img", { name: "Photo.jpg" });
    const processing = within(screen.getByRole("region", { name: "Processing Kyoto" }));

    expect(screen.getByText("0 unplaced / 1 total")).toBeInTheDocument();
    expect(processing.getByText(/failed.*2 unsupported.*1 skipped/)).toBeInTheDocument();
    fireEvent.click(processing.getByRole("button", { name: "Processing issues for Kyoto" }));
    const disclosure = processing.getByRole("button", { name: "Processing issues for Kyoto" });
    disclosure.focus();
    screen.getByRole("region", { name: "Chapter photos" }).scrollTop = 40;
    changeChapters([{ id: "chapter", name: "Kyoto" }]);

    expect(processing.getByRole("button", { name: "Processing issues for Kyoto" })).toBe(disclosure);
    expect(disclosure).toHaveFocus();
    expect(screen.getByRole("region", { name: "Chapter photos" }).scrollTop).toBe(40);
    expect(processing.getByText("cleanup: cleanup_failed")).toBeInTheDocument();
    expect(processing.getByText("read: undecodable — bad.jpg")).toBeInTheDocument();
    expect(screen.getByText(/Live updates interrupted/)).toBeInTheDocument();
    expect(image).toHaveAttribute("src", inventory.photos[0]?.thumbnail.url);
    expect(image).toHaveAttribute("loading", "lazy");
    fireEvent.click(image);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Refresh photos" }));
    fireEvent.click(screen.getByRole("button", { name: "Retry inventory for Kyoto" }));
    fireEvent.click(screen.getByRole("button", { name: "Retry refresh for Kyoto" }));
    disclosure.focus();
    change({ chapter: { ...inventory, processing: { state: "completed", unsupportedCount: 0, skippedCount: 0, issues: [] } } });

    expect(refresh).toHaveBeenCalledOnce();
    expect(retryInventory).toHaveBeenCalledExactlyOnceWith("chapter");
    expect(retryRefresh).toHaveBeenCalledExactlyOnceWith("chapter");
    expect(screen.getByRole("img", { name: "Photo.jpg" })).toBe(image);
    expect(screen.getByRole("button", { name: "Refresh photos" })).toHaveFocus();
    view.unmount();
    expect(disconnect).toHaveBeenCalled();
  });

  it("should resize by pointer and keyboard, collapse hidden controls out of focus order and restore the chosen width", () => {
    const view = render(() => (
      <PhotoPane
        label="Album photos"
        chapters={[{ id: "chapter", name: "Kyoto" }]}
        inventories={{ chapter: inventory }}
        inventoryErrors={{}}
        scheduling={{}}
        liveStatus="live"
        onRefresh={vi.fn()}
        onRetryInventory={vi.fn()}
        onRetryRefresh={vi.fn()}
      >
        <div>Editor</div>
      </PhotoPane>
    ));
    const grip = screen.getByRole("button", { name: "Collapse photo pane" });

    fireEvent(grip, new MouseEvent("pointerdown", { bubbles: true, clientX: 320, button: 2 }));
    fireEvent.keyDown(grip, { key: "ArrowRight" });
    expect(grip).toHaveAccessibleDescription(/320px/);
    fireEvent.keyDown(grip, { key: "ArrowRight", altKey: true });
    expect(grip).toHaveAccessibleDescription(/336px/);
    fireEvent.keyDown(grip, { key: "ArrowLeft", altKey: true });
    fireEvent.keyDown(grip, { key: "Tab" });
    fireEvent(grip, new MouseEvent("pointerdown", { bubbles: true, clientX: 320, button: 0 }));
    fireEvent(window, new MouseEvent("pointermove", { clientX: 322 }));
    fireEvent(window, new MouseEvent("pointermove", { clientX: 440 }));
    fireEvent(window, new MouseEvent("pointermove", { clientX: 321 }));
    expect(grip).toHaveAccessibleDescription(/321px/);
    fireEvent(window, new MouseEvent("pointermove", { clientX: 440 }));
    fireEvent(window, new MouseEvent("pointerup"));
    fireEvent.click(grip, { detail: 1 });
    expect(grip).toHaveAttribute("aria-expanded", "true");
    expect(grip).toHaveAccessibleDescription(/440px/);
    fireEvent.click(grip);

    expect(screen.queryByRole("button", { name: "Refresh photos" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Expand photo pane" })).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "Expand photo pane" }));
    expect(grip).toHaveAccessibleDescription(/440px/);
    expect(screen.getByRole("button", { name: "Refresh photos" })).toBeInTheDocument();
    fireEvent(grip, new MouseEvent("pointerdown", { bubbles: true, clientX: 440, button: 0 }));
    fireEvent(window, new MouseEvent("pointercancel"));
    view.unmount();
    fireEvent(window, new MouseEvent("pointermove", { clientX: 900 }));
  });

  it.each([
    ["connecting", "Connecting live photo updates"],
    ["catching-up", "Catching up"],
    ["invalid", "Live photo update could not be decoded"],
  ] as const)("should expose %s live state without inventing processing completion", (liveStatus, message) => {
    render(() => (
      <PhotoPane
        label="Album photos"
        chapters={[]}
        inventories={{}}
        inventoryErrors={{}}
        scheduling={{}}
        liveStatus={liveStatus}
        onRefresh={vi.fn()}
        onRetryInventory={vi.fn()}
        onRetryRefresh={vi.fn()}
      >
        <div />
      </PhotoPane>
    ));

    expect(screen.getByText(new RegExp(message))).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Refresh photos" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Refresh photos" })).toHaveAccessibleDescription("Add a chapter to refresh photos.");
  });

  it.each([
    ["pending", "Scheduling photo refresh. Please wait."],
    ["queued", "Refresh photos"],
    ["coalesced", "Refresh photos"],
    ["not-requested", "Refresh photos"],
  ] as const)("should keep routine %s receipts out of the photo explorer", (scheduling, description) => {
    render(() => (
      <PhotoPane
        label="Chapter photos"
        chapters={[{ id: "chapter", name: "Kyoto" }]}
        inventories={{
          chapter: {
            ...inventory,
            processing: { state: "completed", unsupportedCount: 0, skippedCount: 0, issues: [] },
          },
        }}
        inventoryErrors={{}}
        scheduling={{ chapter: scheduling }}
        liveStatus="live"
        onRefresh={vi.fn()}
        onRetryInventory={vi.fn()}
        onRetryRefresh={vi.fn()}
      >
        <div />
      </PhotoPane>
    ));

    expect(screen.getByRole("button", { name: "Refresh photos" }).hasAttribute("disabled")).toBe(scheduling === "pending");
    expect(screen.getByRole("button", { name: "Refresh photos" })).toHaveAccessibleDescription(description);
    expect(screen.getByRole("heading", { name: "Photos" })).toBeInTheDocument();
    expect(screen.getByText("0 unplaced / 1 total")).toBeInTheDocument();
    expect(screen.queryByText(/completed|unsupported|skipped|Refresh request/)).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Processing Kyoto" })).not.toBeInTheDocument();
  });

  it("should measure fractional content bounds, coalesce observer work and cancel its pending frame on exit", () => {
    let frame: FrameRequestCallback | undefined;
    const request = vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      frame = callback;
      return 7;
    });
    const cancel = vi.spyOn(window, "cancelAnimationFrame").mockImplementation(() => undefined);
    const view = render(() => (
      <PhotoPane
        label="Chapter photos"
        chapters={[{ id: "chapter", name: "Kyoto" }]}
        inventories={{ chapter: inventory }}
        inventoryErrors={{}}
        scheduling={{}}
        liveStatus="live"
        onRefresh={vi.fn()}
        onRetryInventory={vi.fn()}
        onRetryRefresh={vi.fn()}
      >
        <div />
      </PhotoPane>
    ));
    const image = screen.getByRole("img", { name: "Photo.jpg" });
    const notify = notifications[0];
    if (notify === undefined) throw new Error("Missing viewport observer");

    expect(image).toHaveStyle({ width: "308.5px" });
    vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(0);
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue(new DOMRect());
    notify();
    notify();
    expect(request).toHaveBeenCalledOnce();
    if (frame === undefined) throw new Error("Missing scheduled measurement");
    frame(0);
    expect(screen.getByRole("img", { name: "Photo.jpg" })).toBe(image);
    expect(image).toHaveStyle({ width: "308.5px" });
    notify();
    view.unmount();

    expect(cancel).toHaveBeenCalledExactlyOnceWith(7);
  });

  it("should retain focus on the grip when a retry starts while another chapter disables Refresh", () => {
    const retryInventory = vi.fn();
    const retryRefresh = vi.fn();
    const empty = { ...inventory, photos: [], counts: { unplaced: 0, total: 0 } };
    render(() => (
      <PhotoPane
        label="Album photos"
        chapters={[
          { id: "chapter", name: "Kyoto" },
          { id: "home", name: "Home" },
        ]}
        inventories={{ chapter: inventory, home: empty }}
        inventoryErrors={{ chapter: "Unavailable inventory" }}
        scheduling={{ chapter: "failed", home: "pending" }}
        liveStatus="live"
        onRefresh={vi.fn()}
        onRetryInventory={retryInventory}
        onRetryRefresh={retryRefresh}
      >
        <div />
      </PhotoPane>
    ));
    const retry = screen.getByRole("button", { name: "Retry inventory for Kyoto" });
    retry.focus();

    fireEvent.click(retry);

    expect(screen.getByRole("button", { name: "Collapse photo pane" })).toHaveFocus();
    const schedule = screen.getByRole("button", { name: "Retry refresh for Kyoto" });
    schedule.focus();
    fireEvent.click(schedule);
    expect(screen.getByRole("button", { name: "Collapse photo pane" })).toHaveFocus();
    expect(retryInventory).toHaveBeenCalledExactlyOnceWith("chapter");
    expect(retryRefresh).toHaveBeenCalledExactlyOnceWith("chapter");
  });
});
