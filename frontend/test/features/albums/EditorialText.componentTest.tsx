import { render, screen, waitFor } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { EditorialText } from "../../../src/features/albums/workspace/EditorialText";

describe("editorial text fitting", () => {
  it.each([60, 150])("should preserve or fit %i-point content within the measured slot and refit on resize", async (height) => {
    const nativeHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "clientHeight");
    const nativeScroll = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "scrollHeight");
    let observed: Element | undefined;
    let resize!: () => void;
    const disconnect = vi.fn();
    const observer = vi.fn().mockImplementation(function (callback: () => void) {
      resize = callback;
      return {
        observe: (element: Element) => {
          observed = element;
        },
        disconnect,
      };
    });
    vi.stubGlobal("ResizeObserver", observer);
    Object.defineProperty(HTMLElement.prototype, "clientHeight", {
      configurable: true,
      get() {
        return 100;
      },
    });
    Object.defineProperty(HTMLElement.prototype, "scrollHeight", {
      configurable: true,
      get() {
        const scale = observed instanceof HTMLElement ? Number(observed.style.getPropertyValue("--editorial-scale")) || 1 : 1;
        return height * scale;
      },
    });
    const [text, change] = createSignal("  山\n\n ");
    const view = render(() => <EditorialText title="Kyoto" text={text()} gear="Body w/ Lens" synopsis={false} textWidth={undefined} />);
    const box = screen.getByRole("group", { name: "Chapter text" });
    box.style.padding = "0px";
    try {
      resize();
      await waitFor(() => {
        const scale = Number(box.style.getPropertyValue("--editorial-scale"));
        expect(scale).toBeGreaterThan(0);
        expect(height * scale).toBeLessThanOrEqual(100);
        expect(scale).toBeCloseTo(height <= 100 ? 1 : 100 / height, 4);
      });
      change("Revised text");
      await waitFor(() => expect(box).toHaveTextContent("Revised text"));
      expect(screen.getByRole("group", { name: "Chapter gear" })).toHaveTextContent("Body w/ Lens");
    } finally {
      view.unmount();
      vi.unstubAllGlobals();
      if (nativeHeight === undefined) Reflect.deleteProperty(HTMLElement.prototype, "clientHeight");
      else Object.defineProperty(HTMLElement.prototype, "clientHeight", nativeHeight);
      if (nativeScroll === undefined) Reflect.deleteProperty(HTMLElement.prototype, "scrollHeight");
      else Object.defineProperty(HTMLElement.prototype, "scrollHeight", nativeScroll);
    }
    expect(disconnect).toHaveBeenCalledOnce();
  });
  it("should retain synopsis content when ResizeObserver is unavailable and no space is measured", async () => {
    vi.stubGlobal("ResizeObserver", undefined);
    const view = render(() => <EditorialText title="Album" text="Saved synopsis" gear={undefined} synopsis={true} textWidth={80} />);
    await screen.findByRole("group", { name: "Synopsis text" });
    expect(screen.getByText("Saved synopsis")).toBeInTheDocument();
    view.unmount();
    vi.unstubAllGlobals();
  });
});
