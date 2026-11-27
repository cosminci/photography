import { fireEvent, render, screen } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PhotoPane } from "../../../src/features/albums/workspace/PhotoPane";
import type { PhotoInventory } from "../../../src/features/albums/model/Photo";

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

  it("disables photo placement without a visible status banner while the spread is read-only", () => {
    render(() => (
      <PhotoPane
        label="Chapter photos"
        chapters={[{ id: "chapter", name: "Kyoto" }]}
        inventories={{ chapter: inventory }}
        inventoryErrors={{}}
        scheduling={{}}
        liveStatus="live"
        onRefresh={vi.fn()}
        onRetryInventory={vi.fn()}
        unavailable="Confirming editing ownership."
        quietUnavailable
      >
        <div />
      </PhotoPane>
    ));

    const photo = screen.getByRole("button", { name: "Choose Photo.jpg" });
    expect(photo).toBeDisabled();
    expect(photo).toHaveAccessibleDescription("Read only.");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("keeps import diagnostics out of the pane and retries failed inventory reads from Refresh", () => {
    const refresh = vi.fn();
    const retryInventory = vi.fn();
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
      >
        <div>Editor</div>
      </PhotoPane>
    ));
    const image = screen.getByRole("img", { name: "Photo.jpg" });
    const button = screen.getByRole("button", { name: "Refresh photos" });

    expect(screen.getByText("0 unplaced / 1 total")).toBeInTheDocument();
    button.focus();
    screen.getByRole("region", { name: "Chapter photos" }).scrollTop = 40;
    changeChapters([{ id: "chapter", name: "Kyoto" }]);

    expect(button).toHaveFocus();
    expect(screen.getByRole("region", { name: "Chapter photos" }).scrollTop).toBe(40);
    expect(screen.queryByText(/Kyoto photos|unsupported|skipped|bad\.jpg|Inventory unavailable|interrupted/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(image).toHaveAttribute("src", inventory.photos[0]?.thumbnail.url);
    expect(image).toHaveAttribute("loading", "lazy");
    fireEvent.click(image);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    fireEvent.click(button);
    change({ chapter: { ...inventory, processing: { state: "completed", unsupportedCount: 0, skippedCount: 0, issues: [] } } });

    expect(refresh).toHaveBeenCalledOnce();
    expect(retryInventory).toHaveBeenCalledExactlyOnceWith("chapter");
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

  it.each(["connecting", "catching-up", "interrupted", "invalid"] as const)("only alerts on invalid live updates, not %s recovery", (liveStatus) => {
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
      >
        <div />
      </PhotoPane>
    ));

    if (liveStatus === "invalid") expect(screen.getByRole("alert")).toHaveTextContent("Reopen the album");
    else expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Refresh photos" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Refresh photos" })).toHaveAccessibleDescription("Add a chapter to refresh photos.");
  });

  it.each([
    ["pending", "Processing photos…"],
    ["queued", "Processing photos…"],
    ["coalesced", "Processing photos…"],
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
      >
        <div />
      </PhotoPane>
    ));

    expect(screen.getByRole("button", { name: "Refresh photos" }).getAttribute("aria-disabled")).toBe(
      description === "Processing photos…" ? "true" : null,
    );
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

  it("keeps Refresh busy until all displayed chapters finish, but allows recovery from stale activity", () => {
    const retryInventory = vi.fn();
    const refresh = vi.fn();
    const [state, setState] = createSignal<PhotoInventory["processing"]["state"]>("queued");
    const [other, setOther] = createSignal<PhotoInventory["processing"]["state"]>("running");
    const [failedRead, setFailedRead] = createSignal(false);
    render(() => (
      <PhotoPane
        label="Album photos"
        chapters={[
          { id: "chapter", name: "Kyoto" },
          { id: "home", name: "Home" },
        ]}
        inventories={{
          chapter: { ...inventory, processing: { ...inventory.processing, state: state() } },
          home: { ...inventory, photos: [], processing: { ...inventory.processing, state: other() } },
        }}
        inventoryErrors={failedRead() ? { home: "Unavailable inventory" } : {}}
        scheduling={{}}
        liveStatus="live"
        onRefresh={refresh}
        onRetryInventory={retryInventory}
      >
        <div />
      </PhotoPane>
    ));
    const button = screen.getByRole("button", { name: "Refresh photos" });
    button.focus();
    expect(button).toHaveAttribute("aria-disabled", "true");
    expect(button).toHaveAttribute("title", "Processing photos…");
    expect(button).toHaveAccessibleDescription("Processing photos…");
    expect(screen.queryByText(/Kyoto photos|Home photos|^running$|^queued$/)).not.toBeInTheDocument();
    fireEvent.click(button);
    expect(refresh).not.toHaveBeenCalled();

    setState("running");
    setOther("completed");
    expect(button).toHaveAttribute("aria-disabled", "true");
    setState("failed");
    expect(button).not.toHaveAttribute("aria-disabled");
    expect(button).toHaveFocus();

    setOther("running");
    setFailedRead(true);
    expect(button).not.toHaveAttribute("aria-disabled");
    fireEvent.click(button);
    expect(retryInventory).toHaveBeenCalledExactlyOnceWith("home");
    expect(refresh).toHaveBeenCalledOnce();
  });
});
