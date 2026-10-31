import { fireEvent, render, screen } from "@solidjs/testing-library";
import { createRoot, createSignal, untrack } from "solid-js";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PhotoSlot } from "../../../src/features/albums/workspace/PhotoSlot";
import { createPlacementSession } from "../../../src/features/albums/workspace/PlacementSession";
import type { PlacementSnapshot, SpreadSnapshot } from "../../../src/features/albums/model/Placement";
import { photoDragType, type PhotoChoice } from "../../../src/features/albums/workspace/PhotoDrag";

describe("on-slot photo editing", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });
  const photo = {
    id: "photo",
    filename: "Mountains.jpg",
    width: 400,
    height: 200,
    capturedLocal: null,
    caption: null,
    thumbnail: { url: "/photo.jpg", width: 400, height: 200 },
  };
  const initial: SpreadSnapshot = {
    kind: "spread",
    id: "spread",
    layout: { kind: "photos_only", templateId: "layout", slots: [{ id: "s", kind: "photo", x: 0, y: 0, width: 100, height: 100 }] },
    placements: [
      { kind: "spread", captionCorner: "top_left", id: "p", slotId: "s", photoId: "photo", transform: { x: 0, y: 0, zoom: 2, mirrored: false } },
    ],
  };

  it.each([false, true])("lets mirror supersede an unfocused wheel burst with slow acknowledgement %s", async (slow) => {
    vi.useFakeTimers();
    try {
      let acknowledge!: (value: PlacementSnapshot) => void;
      const acknowledgement = new Promise<PlacementSnapshot>((resolve) => {
        acknowledge = resolve;
      });
      const transform = { x: 0, y: 0, zoom: 2.2, mirrored: true };
      const saved = { ...initial, placements: initial.placements.map((row) => ({ ...row, transform })) };
      const edits = vi.fn().mockImplementation(() => (slow ? acknowledgement : Promise.resolve(saved)));
      const session = createRoot(() =>
        createPlacementSession({ readPlacements: vi.fn().mockResolvedValue(initial), editPlacements: edits }, vi.fn()),
      );
      await session.load("album", { kind: "spread", id: "spread" });
      render(() => (
        <PhotoSlot
          slotId="s"
          label="Photo slot 1"
          width={200}
          height={200}
          placement={session.placements()[0]}
          photo={photo}
          session={session}
          choice={undefined}
          onChoose={vi.fn()}
          unavailable={undefined}
          reader={false}
        />
      ));
      const slot = screen.getByRole("button", { name: "Photo slot 1: Mountains.jpg" });

      fireEvent.wheel(slot, { deltaY: -100 });
      fireEvent.click(screen.getByRole("button", { name: "Mirror photo in Photo slot 1" }));
      await vi.advanceTimersByTimeAsync(350);
      acknowledge(saved);
      await vi.advanceTimersByTimeAsync(350);
      const expectedEdit = { operation: "adjust", placementId: "p", transform };

      expect(edits).toHaveBeenCalledTimes(1);
      expect(edits).toHaveBeenCalledWith("album", { kind: "spread", id: "spread" }, expectedEdit);
      expect(session.placements()[0]?.transform).toEqual(transform);
    } finally {
      vi.useRealTimers();
    }
  });

  it("previews wheel and keyboard bursts locally, then saves completed input and mirrors through compact accessible actions", async () => {
    const edits = vi
      .fn()
      .mockImplementation((_album, _context, edit: { transform: (typeof initial.placements)[number]["transform"] }) =>
        Promise.resolve({ ...initial, placements: initial.placements.map((row) => ({ ...row, transform: edit.transform })) }),
      );
    const session = createRoot(() => createPlacementSession({ readPlacements: vi.fn().mockResolvedValue(initial), editPlacements: edits }, vi.fn()));
    await session.load("album", { kind: "spread", id: "spread" });
    render(() => (
      <PhotoSlot
        slotId="s"
        label="Photo slot 1"
        width={200}
        height={200}
        placement={session.placements()[0]}
        photo={photo}
        session={session}
        choice={undefined}
        onChoose={vi.fn()}
        unavailable={undefined}
        reader={false}
      />
    ));
    const slot = screen.getByRole("button", { name: "Photo slot 1: Mountains.jpg" });

    fireEvent.wheel(slot, { deltaY: -100 });
    fireEvent.keyDown(slot, { key: "ArrowRight" });
    fireEvent.keyDown(slot, { key: "+" });
    const writesDuringInput = edits.mock.calls.length;
    fireEvent.blur(slot);
    await vi.waitUntil(() => !session.saving());
    const completedInputWrites = edits.mock.calls.length;
    fireEvent.click(screen.getByRole("button", { name: "Mirror photo in Photo slot 1" }));
    await vi.waitUntil(() => !session.saving());

    expect(writesDuringInput).toBe(0);
    expect(completedInputWrites).toBe(1);
    expect(edits).toHaveBeenCalledTimes(2);
    expect(session.placements()[0]?.transform.mirrored).toBe(true);
    expect(screen.queryByRole("slider")).not.toBeInTheDocument();
  });

  it("pans without per-event writes, restores pointer cancellation, then saves only on release", async () => {
    const edit = vi.fn().mockResolvedValue(initial);
    const session = createRoot(() => createPlacementSession({ readPlacements: vi.fn().mockResolvedValue(initial), editPlacements: edit }, vi.fn()));
    await session.load("album", { kind: "spread", id: "spread" });
    render(() => (
      <PhotoSlot
        slotId="s"
        label="Photo slot 1"
        width={200}
        height={200}
        placement={session.placements()[0]}
        photo={photo}
        session={session}
        choice={undefined}
        onChoose={vi.fn()}
        unavailable={undefined}
        reader={false}
      />
    ));
    const slot = screen.getByRole("button", { name: "Photo slot 1: Mountains.jpg" });
    Object.defineProperty(slot, "setPointerCapture", { value: vi.fn() });
    vi.spyOn(slot, "getBoundingClientRect").mockReturnValue({
      width: 200,
      height: 200,
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      bottom: 200,
      right: 200,
      toJSON: () => ({}),
    });

    fireEvent(slot, new MouseEvent("pointerdown", { clientX: 10, clientY: 10, button: 0, bubbles: true }));
    fireEvent(slot, new MouseEvent("pointermove", { clientX: 30, clientY: 40, bubbles: true }));
    const pannedTransform = session.placements()[0]?.transform;
    const writesDuringPan = edit.mock.calls.length;
    fireEvent(slot, new MouseEvent("pointercancel", { bubbles: true }));
    const cancelledTransform = session.placements()[0]?.transform;
    fireEvent(slot, new MouseEvent("pointerdown", { clientX: 10, clientY: 10, button: 0, bubbles: true }));
    fireEvent(slot, new MouseEvent("pointermove", { clientX: 50, clientY: 60, bubbles: true }));
    fireEvent(slot, new MouseEvent("pointerup", { bubbles: true }));
    await vi.waitUntil(() => !session.saving());

    expect(pannedTransform?.x).toBe(20);
    expect(writesDuringPan).toBe(0);
    expect(cancelledTransform).toEqual(initial.placements[0]?.transform);
    expect(edit).toHaveBeenCalledTimes(1);
    expect(edit.mock.calls[0]?.[2]).toEqual({ operation: "adjust", placementId: "p", transform: { x: 40, y: 50, zoom: 2, mirrored: false } });
  });

  it("arms keyboard moves only for keyboard activation and accepts native photo drops", async () => {
    const edit = vi.fn().mockResolvedValue(initial);
    const chosen = vi.fn();
    const session = createRoot(() => createPlacementSession({ readPlacements: vi.fn().mockResolvedValue(initial), editPlacements: edit }, vi.fn()));
    await session.load("album", { kind: "spread", id: "spread" });
    const [choice, setChoice] = createSignal<PhotoChoice>();
    render(() => (
      <PhotoSlot
        slotId="s"
        label="Photo slot 1"
        width={200}
        height={200}
        placement={session.placements()[0]}
        photo={photo}
        session={session}
        choice={choice()}
        onChoose={(next) => {
          setChoice(next);
          chosen(next);
        }}
        unavailable={undefined}
        reader={false}
      />
    ));
    const hand = screen.getByRole("button", { name: "Move photo from Photo slot 1" });
    const slot = screen.getByRole("button", { name: "Photo slot 1: Mountains.jpg" });
    const image = screen.getByRole("img", { name: "Mountains.jpg" });
    Object.defineProperties(image, { complete: { value: true }, naturalWidth: { value: 1600 } });
    vi.spyOn(slot, "getBoundingClientRect").mockReturnValue(new DOMRect(0, 0, 200, 200));
    vi.spyOn(image, "getBoundingClientRect").mockReturnValue(new DOMRect(-300, -100, 800, 400));
    const context = { scale: vi.fn(), translate: vi.fn(), drawImage: vi.fn() };
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockImplementation(vi.fn().mockReturnValue(context));
    const dataTransfer = { setData: vi.fn(), setDragImage: vi.fn(), effectAllowed: "none", types: [photoDragType] };

    fireEvent.click(hand, { detail: 1 });
    const choicesAfterMouseClick = chosen.mock.calls.length;
    fireEvent.dragStart(hand, { dataTransfer });
    fireEvent.dragOver(slot, { dataTransfer });
    fireEvent.dragOver(slot, { dataTransfer: { types: ["text/plain"] } });
    fireEvent.click(hand, { detail: 0 });
    const keyboardChoice = untrack(choice);
    fireEvent.click(slot);
    await vi.waitUntil(() => !session.saving());
    fireEvent.drop(slot, { dataTransfer: { getData: () => JSON.stringify({ kind: "photo", photoId: "new" }) } });
    await vi.waitUntil(() => !session.saving());

    expect(choicesAfterMouseClick).toBe(0);
    expect(keyboardChoice).toEqual({ kind: "move", placementId: "p" });
    expect(dataTransfer.setData).toHaveBeenCalledWith(photoDragType, JSON.stringify({ kind: "move", placementId: "p" }));
    expect(edit).toHaveBeenCalledTimes(2);
    expect(edit.mock.calls[1]?.[2]).toEqual({ operation: "place", photoId: "new", slotId: "s" });
  });

  it("supports removal, ignores incomplete gestures and leaves non-adjustment keys to native controls", async () => {
    const edit = vi.fn().mockResolvedValue(initial);
    const session = createRoot(() => createPlacementSession({ readPlacements: vi.fn().mockResolvedValue(initial), editPlacements: edit }, vi.fn()));
    await session.load("album", { kind: "spread", id: "spread" });
    const view = render(() => (
      <PhotoSlot
        slotId="s"
        label="Photo slot 1"
        width={200}
        height={200}
        placement={session.placements()[0]}
        photo={photo}
        session={session}
        choice={undefined}
        onChoose={vi.fn()}
        unavailable={undefined}
        reader={false}
      />
    ));
    const slot = screen.getByRole("button", { name: "Photo slot 1: Mountains.jpg" });

    fireEvent.click(slot);
    fireEvent.keyDown(slot, { key: "Tab" });
    fireEvent.keyDown(slot, { key: "ArrowRight", altKey: true });
    fireEvent(slot, new MouseEvent("pointermove", { bubbles: true }));
    fireEvent(slot, new MouseEvent("pointerup", { bubbles: true }));
    fireEvent(slot, new MouseEvent("pointercancel", { bubbles: true }));
    fireEvent(slot, new MouseEvent("pointerdown", { button: 2, bubbles: true }));
    fireEvent.drop(slot);
    fireEvent.click(screen.getByRole("button", { name: "Remove photo from Photo slot 1" }));
    await vi.waitUntil(() => !session.saving());
    view.unmount();

    expect(edit).toHaveBeenCalledTimes(1);
    expect(edit.mock.calls[0]?.[2]).toEqual({ operation: "remove", placementId: "p" });
  });

  it("keeps unavailable metadata closed to adjustment and cancels its pending burst on teardown", async () => {
    const edit = vi.fn().mockResolvedValue(initial);
    const session = createRoot(() => createPlacementSession({ readPlacements: vi.fn().mockResolvedValue(initial), editPlacements: edit }, vi.fn()));
    await session.load("album", { kind: "spread", id: "spread" });
    const [available, setAvailable] = createSignal(false);
    const view = render(() => (
      <PhotoSlot
        slotId="s"
        label="Photo slot 1"
        width={200}
        height={200}
        placement={session.placements()[0]}
        photo={available() ? photo : undefined}
        session={session}
        choice={undefined}
        onChoose={vi.fn()}
        unavailable={undefined}
        reader={false}
      />
    ));
    const slot = screen.getByRole("button", { name: "Photo slot 1: empty" });

    fireEvent.keyDown(slot, { key: "+" });
    fireEvent.dragStart(screen.getByRole("button", { name: "Move photo from Photo slot 1" }));
    const dragFeedback = screen.getByRole("alert").textContent;
    setAvailable(true);
    fireEvent.wheel(slot, { deltaY: 10 });
    view.unmount();
    await new Promise((resolve) => setTimeout(resolve, 300));

    expect(edit).not.toHaveBeenCalled();
    expect(dragFeedback).toContain("Wait for the photo to load, or use keyboard selection");
  });

  it("starts pan from the bounded destination crop and restores the original retained transform on cancellation", async () => {
    const retained = { ...initial.placements[0], id: "p", slotId: "s", photoId: "photo", transform: { x: 900, y: 0, zoom: 2, mirrored: false } };
    const edit = vi.fn();
    const session = createRoot(() =>
      createPlacementSession({ readPlacements: vi.fn().mockResolvedValue({ ...initial, placements: [retained] }), editPlacements: edit }, vi.fn()),
    );
    await session.load("album", { kind: "spread", id: "spread" });
    render(() => (
      <PhotoSlot
        slotId="s"
        label="Photo slot 1"
        width={200}
        height={200}
        placement={session.placements()[0]}
        photo={photo}
        session={session}
        choice={undefined}
        onChoose={vi.fn()}
        unavailable={undefined}
        reader={false}
      />
    ));
    const slot = screen.getByRole("button", { name: "Photo slot 1: Mountains.jpg" });
    Object.defineProperty(slot, "setPointerCapture", { value: vi.fn() });
    vi.spyOn(slot, "getBoundingClientRect").mockReturnValue(new DOMRect(0, 0, 200, 200));

    fireEvent(slot, new MouseEvent("pointerdown", { clientX: 30, clientY: 10, button: 0, bubbles: true }));
    fireEvent(slot, new MouseEvent("pointermove", { clientX: 10, clientY: 10, bubbles: true }));
    const boundedPan = session.placements()[0]?.transform.x;
    fireEvent(slot, new MouseEvent("pointercancel", { bubbles: true }));

    expect(boundedPan).toBe(280);
    expect(session.placements()[0]?.transform).toEqual(retained.transform);
    expect(edit).not.toHaveBeenCalled();
  });

  it("keeps empty slots available as destinations without adjustment gestures", async () => {
    const edit = vi.fn();
    const session = createRoot(() =>
      createPlacementSession({ readPlacements: vi.fn().mockResolvedValue({ ...initial, placements: [] }), editPlacements: edit }, vi.fn()),
    );
    await session.load("album", { kind: "spread", id: "spread" });
    render(() => (
      <PhotoSlot
        slotId="s"
        label="Photo slot 1"
        width={200}
        height={200}
        placement={undefined}
        photo={undefined}
        session={session}
        choice={undefined}
        onChoose={vi.fn()}
        unavailable={undefined}
        reader={false}
      />
    ));
    const slot = screen.getByRole("button", { name: "Photo slot 1: empty" });

    fireEvent.wheel(slot, { deltaY: 10 });
    fireEvent.keyDown(slot, { key: "+" });
    fireEvent.pointerDown(slot);

    expect(edit).not.toHaveBeenCalled();
  });
});
