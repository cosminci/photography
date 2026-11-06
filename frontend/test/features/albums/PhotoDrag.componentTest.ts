import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { photoDragType, readPhotoChoice, startPhotoDrag } from "../../../src/features/albums/workspace/PhotoDrag";

describe("rendered photo drag previews", () => {
  it.each(["oops", "null", "[]", '{"kind":"other"}', '{"kind":"photo","photoId":1}'])("should reject foreign drag %s", (value) => {
    expect(readPhotoChoice(value)).toBeUndefined();
  });

  it("should preserve photo and move identities from native drag data", () => {
    expect(readPhotoChoice('{"kind":"photo","photoId":"photo"}')).toEqual({ kind: "photo", photoId: "photo" });
    expect(readPhotoChoice('{"kind":"move","placementId":"placement"}')).toEqual({ kind: "move", placementId: "placement" });
  });
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it.each([false, true])("paints the clipped CSS frame with mirrored %s and a frame-relative hotspot", async (mirrored) => {
    const image = document.createElement("img");
    Object.defineProperties(image, { complete: { value: true }, naturalWidth: { value: 1600 } });
    vi.spyOn(image, "getBoundingClientRect").mockReturnValue(new DOMRect(60, 30, 240.8, 160.6));
    const frame = document.createElement("button");
    vi.spyOn(frame, "getBoundingClientRect").mockReturnValue(new DOMRect(100, 50, 120.4, 80.3));
    const context = { scale: vi.fn(), translate: vi.fn(), drawImage: vi.fn() };
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockImplementation(vi.fn().mockReturnValue(context));
    const transfer = {
      setData: vi.fn(),
      setDragImage: vi.fn<(image: Element, x: number, y: number) => void>(),
      effectAllowed: "none",
    } satisfies Pick<DataTransfer, "setData" | "setDragImage" | "effectAllowed">;
    const event = { dataTransfer: transfer, clientX: 210, clientY: 57, preventDefault: vi.fn() };

    const started = startPhotoDrag(event, { kind: "move", placementId: "placed" }, { image, frame, mirrored });
    const [preview, x, y] = transfer.setDragImage.mock.calls[0] ?? [];
    const dimensions = preview instanceof HTMLCanvasElement ? { width: preview.width, height: preview.height } : undefined;
    const attachedDuringDrag = preview !== undefined && document.body.contains(preview);
    await vi.runAllTimersAsync();
    const attachedAfterCapture = preview !== undefined && document.body.contains(preview);

    expect(started).toBe(true);
    expect(dimensions).toEqual({ width: 120, height: 80 });
    expect(attachedDuringDrag).toBe(true);
    expect(attachedAfterCapture).toBe(false);
    expect(context.scale).toHaveBeenNthCalledWith(1, 120 / 120.4, 80 / 80.3);
    expect(context.translate).toHaveBeenCalledWith(mirrored ? 200.8 : -40, -20);
    expect(context.scale).toHaveBeenNthCalledWith(2, mirrored ? -1 : 1, 1);
    expect(context.drawImage).toHaveBeenCalledWith(image, 0, 0, 240.8, 160.6);
    expect(x).toBeCloseTo((110 * 120) / 120.4);
    expect(y).toBeCloseTo((7 * 80) / 80.3);
    expect(transfer.setData).toHaveBeenCalledWith(photoDragType, '{"kind":"move","placementId":"placed"}');
    expect(event.preventDefault).not.toHaveBeenCalled();
  });

  it.each([
    { name: "missing transfer", transfer: false, image: true, ready: true, naturalWidth: 100, width: 100, height: 80, canvas: true, failure: "" },
    { name: "missing image", transfer: true, image: false, ready: true, naturalWidth: 100, width: 100, height: 80, canvas: true, failure: "" },
    { name: "loading image", transfer: true, image: true, ready: false, naturalWidth: 100, width: 100, height: 80, canvas: true, failure: "" },
    { name: "undecodable image", transfer: true, image: true, ready: true, naturalWidth: 0, width: 100, height: 80, canvas: true, failure: "" },
    { name: "empty width", transfer: true, image: true, ready: true, naturalWidth: 100, width: 0, height: 80, canvas: true, failure: "" },
    { name: "empty height", transfer: true, image: true, ready: true, naturalWidth: 100, width: 100, height: 0, canvas: true, failure: "" },
    { name: "canvas unavailable", transfer: true, image: true, ready: true, naturalWidth: 100, width: 100, height: 80, canvas: false, failure: "" },
    { name: "paint failure", transfer: true, image: true, ready: true, naturalWidth: 100, width: 100, height: 80, canvas: true, failure: "paint" },
    {
      name: "native capture failure",
      transfer: true,
      image: true,
      ready: true,
      naturalWidth: 100,
      width: 100,
      height: 80,
      canvas: true,
      failure: "capture",
    },
  ])("cancels $name without falling back to an enormous native image", async (scenario) => {
    const image = document.createElement("img");
    Object.defineProperties(image, { complete: { value: scenario.ready }, naturalWidth: { value: scenario.naturalWidth } });
    vi.spyOn(image, "getBoundingClientRect").mockReturnValue(new DOMRect(0, 0, 100, 80));
    const frame = document.createElement("button");
    vi.spyOn(frame, "getBoundingClientRect").mockReturnValue(new DOMRect(0, 0, scenario.width, scenario.height));
    const context = { scale: vi.fn(), translate: vi.fn(), drawImage: vi.fn() };
    if (scenario.failure === "paint")
      context.drawImage.mockImplementation(() => {
        throw new Error("paint failed");
      });
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockImplementation(vi.fn().mockReturnValue(scenario.canvas ? context : null));
    const transfer = { setData: vi.fn(), setDragImage: vi.fn(), effectAllowed: "none" } satisfies Pick<
      DataTransfer,
      "setData" | "setDragImage" | "effectAllowed"
    >;
    if (scenario.failure === "capture")
      transfer.setDragImage.mockImplementation(() => {
        throw new Error("capture failed");
      });
    const event = { dataTransfer: scenario.transfer ? transfer : null, clientX: -10, clientY: 900, preventDefault: vi.fn() };

    const started = startPhotoDrag(event, { kind: "photo", photoId: "photo" }, { frame, image: scenario.image ? image : undefined, mirrored: false });
    await vi.runAllTimersAsync();

    expect(started).toBe(false);
    expect(event.preventDefault).toHaveBeenCalledOnce();
    expect(transfer.setData).not.toHaveBeenCalled();
    expect(document.querySelector("canvas")).not.toBeInTheDocument();
  });
});
