import { fireEvent, render, screen } from "@solidjs/testing-library";
import { Show, createRoot, createSignal, untrack } from "solid-js";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PhotoSlot } from "../../../src/features/albums/workspace/PhotoSlot";
import { createPlacementSession } from "../../../src/features/albums/workspace/PlacementSession";
import type { PlacementSnapshot, SpreadSnapshot } from "../../../src/features/albums/model/Placement";
import { photoDragType, type PhotoChoice } from "../../../src/features/albums/workspace/PhotoDrag";
import { createCaptionEditing } from "../../../src/features/albums/workspace/PhotoCaption";

describe("on-slot photo editing", () => {
  it("should bind caption writes to the current photo after replacement in the same slot", async () => {
    const replacement = { ...photo, id: "replacement", filename: "Replacement.jpg", caption: "Replacement caption" };
    const save = vi.fn().mockResolvedValue(undefined);
    const captions = createRoot(() => createCaptionEditing({ save, read: vi.fn().mockResolvedValue("Changed replacement"), onBlock: vi.fn() }));
    const session = createRoot(() =>
      createPlacementSession({ readPlacements: vi.fn().mockResolvedValue(initial), editPlacements: vi.fn() }, vi.fn()),
    );
    await session.load("album", { kind: "spread", id: "spread" });
    render(() => (
      <PhotoSlot
        slotId="s"
        label="Photo slot 1"
        width={200}
        height={200}
        placement={session.placements()[0]}
        photo={session.placements()[0]?.photoId === photo.id ? photo : replacement}
        session={session}
        choice={undefined}
        onChoose={vi.fn()}
        unavailable={undefined}
        reader={false}
        captions={captions}
      />
    ));
    session.reconcile({ ...initial, placements: initial.placements.map((row) => ({ ...row, photoId: replacement.id })) });
    fireEvent.click(screen.getByRole("button", { name: "Edit caption for Replacement.jpg" }));
    const editor = screen.getByRole("textbox", { name: "Caption for Replacement.jpg" });
    editor.textContent = "Changed replacement";
    Object.defineProperty(editor, "innerText", { configurable: true, value: "Changed replacement" });
    fireEvent.input(editor);
    fireEvent.blur(editor);
    await vi.waitUntil(() => screen.queryByRole("textbox") === null);

    expect(save).toHaveBeenCalledExactlyOnceWith(replacement.id, "Changed replacement");
    expect(session.placements()[0]).toEqual({ ...initial.placements[0], photoId: replacement.id });
  });
  it("should retain a photo-owned draft when its slot is unmounted and restored", async () => {
    const save = vi.fn();
    const captions = createRoot(() => createCaptionEditing({ save, read: vi.fn(), onBlock: vi.fn() }));
    const session = createRoot(() =>
      createPlacementSession({ readPlacements: vi.fn().mockResolvedValue(initial), editPlacements: vi.fn() }, vi.fn()),
    );
    await session.load("album", { kind: "spread", id: "spread" });
    const [visible, setVisible] = createSignal(true);
    render(() => (
      <Show when={visible()}>
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
          captions={captions}
        />
      </Show>
    ));
    fireEvent.click(screen.getByRole("button", { name: "Add caption for Mountains.jpg" }));
    const editor = screen.getByRole("textbox", { name: "Caption for Mountains.jpg" });
    editor.textContent = "Draft";
    Object.defineProperty(editor, "innerText", { configurable: true, value: "Draft" });
    fireEvent.input(editor);
    setVisible(false);
    setVisible(true);

    expect(screen.getByRole("textbox", { name: "Caption for Mountains.jpg" }).textContent).toBe("Draft");
    expect(save).not.toHaveBeenCalled();
    fireEvent.keyDown(screen.getByRole("textbox", { name: "Caption for Mountains.jpg" }), { key: "Escape" });
    expect(screen.getByRole("button", { name: "Add caption for Mountains.jpg" })).toBeInTheDocument();
  });
  it.each([
    { trigger: "pointer departure", text: "  山\n\n ", original: "Saved", escape: false, writes: 1 },
    { trigger: "blur", text: "", original: "Saved", escape: false, writes: 1 },
    { trigger: "pointer departure", text: "Changed", original: "Saved", escape: true, writes: 0 },
    { trigger: "blur", text: "Saved", original: "Saved", escape: false, writes: 0 },
    { trigger: "pointer departure", text: "", original: "", escape: false, writes: 0 },
  ])("should commit caption once on $trigger with text=$text and Escape=$escape", async ({ trigger, text, original, escape, writes }) => {
    const save = vi.fn().mockResolvedValue(undefined);
    const read = vi.fn<(id: string) => Promise<string>>().mockResolvedValue(text);
    const block = vi.fn();
    const session = createRoot(() =>
      createPlacementSession({ readPlacements: vi.fn().mockResolvedValue(initial), editPlacements: vi.fn() }, vi.fn()),
    );
    await session.load("album", { kind: "spread", id: "spread" });
    const [caption, setCaption] = createSignal(original);
    const captions = createRoot(() =>
      createCaptionEditing({
        save,
        read: async (id) => {
          const saved = await read(id);
          setCaption(saved);
          return saved;
        },
        onBlock: block,
      }),
    );
    render(() => (
      <PhotoSlot
        slotId="s"
        label="Photo slot 1"
        width={200}
        height={200}
        placement={session.placements()[0]}
        photo={{ ...photo, caption: caption() }}
        session={session}
        choice={undefined}
        onChoose={vi.fn()}
        unavailable={undefined}
        reader={false}
        captions={captions}
      />
    ));
    const label = screen.getByRole("button", { name: `${original === "" ? "Add" : "Edit"} caption for Mountains.jpg` });
    fireEvent.keyDown(label, { key: "Enter" });
    const editor = screen.getByRole("textbox", { name: "Caption for Mountains.jpg" });
    expect(editor).toHaveFocus();
    editor.textContent = text;
    Object.defineProperty(editor, "innerText", { configurable: true, value: text });
    fireEvent.input(editor);
    expect(save).not.toHaveBeenCalled();
    if (escape) fireEvent.keyDown(editor, { key: "Escape" });
    if (trigger === "blur") fireEvent.blur(editor);
    else fireEvent.pointerLeave(editor);
    fireEvent.blur(editor);
    fireEvent.pointerLeave(editor);
    await vi.waitUntil(() => screen.queryByRole("textbox", { name: "Caption for Mountains.jpg" }) === null);

    expect(save).toHaveBeenCalledTimes(writes);
    if (writes === 1) {
      expect(save).toHaveBeenCalledWith(photo.id, text);
      expect(read).toHaveBeenCalledExactlyOnceWith(photo.id);
    } else expect(read).not.toHaveBeenCalled();
    expect(session.placements()[0]).toEqual(initial.placements[0]);
    expect(screen.getByRole("button", { name: `${!escape && text === "" ? "Add" : "Edit"} caption for Mountains.jpg` }).textContent).toBe(
      escape ? original : text === "" ? "Add caption" : text,
    );
  });
  it.each([
    { selected: "top_right", alternative: "top_left" },
    { selected: "bottom_left", alternative: "top_right" },
    { selected: "bottom_right", alternative: "bottom_left" },
    { selected: "top_left", alternative: "bottom_right" },
  ] as const)(
    "should offer three alternatives to $selected and save $alternative without changing text or transforms",
    async ({ selected, alternative }) => {
      const placed = { ...initial, placements: initial.placements.map((row) => ({ ...row, captionCorner: selected })) };
      const saved = { ...initial, placements: initial.placements.map((row) => ({ ...row, captionCorner: alternative })) };
      const edits = vi.fn().mockResolvedValue(saved);
      const session = createRoot(() => createPlacementSession({ readPlacements: vi.fn().mockResolvedValue(placed), editPlacements: edits }, vi.fn()));
      await session.load("album", { kind: "spread", id: "spread" });
      const captions = createRoot(() => createCaptionEditing({ save: vi.fn(), read: vi.fn(), onBlock: vi.fn() }));
      render(() => (
        <PhotoSlot
          slotId="s"
          label="Photo slot 1"
          width={200}
          height={200}
          placement={session.placements()[0]}
          photo={{ ...photo, caption: "Saved caption" }}
          session={session}
          choice={undefined}
          onChoose={vi.fn()}
          unavailable={undefined}
          reader={false}
          captions={captions}
        />
      ));

      expect(screen.getByRole("button", { name: "Edit caption for Mountains.jpg" })).toHaveTextContent("Saved caption");
      expect(screen.queryByRole("button", { name: `Caption ${selected.replace("_", " ")}` })).not.toBeInTheDocument();
      fireEvent.click(screen.getByRole("button", { name: `Caption ${alternative.replace("_", " ")}` }));
      await vi.waitUntil(() => !session.saving());

      expect(edits).toHaveBeenCalledWith(
        "album",
        { kind: "spread", id: "spread" },
        { operation: "captionCorner", placementId: "p", captionCorner: alternative },
      );
      expect(session.snapshot()).toEqual(saved);
      expect(screen.getByRole("button", { name: "Edit caption for Mountains.jpg" })).toHaveTextContent("Saved caption");
    },
  );
  it("should keep captions read-only in Reader, guard unavailable edits and preserve unchanged drafts against live text changes", async () => {
    const save = vi.fn().mockResolvedValue(undefined);
    const captions = createRoot(() => createCaptionEditing({ save, read: vi.fn(), onBlock: vi.fn() }));
    const session = createRoot(() =>
      createPlacementSession({ readPlacements: vi.fn().mockResolvedValue(initial), editPlacements: vi.fn() }, vi.fn()),
    );
    await session.load("album", { kind: "spread", id: "spread" });
    const [reader, setReader] = createSignal(true);
    const [unavailable, setUnavailable] = createSignal<string | undefined>();
    const [text, setText] = createSignal("Saved");
    render(() => (
      <PhotoSlot
        slotId="s"
        label="Photo slot 1"
        width={200}
        height={200}
        placement={session.placements()[0]}
        photo={{ ...photo, caption: text() }}
        session={session}
        choice={undefined}
        onChoose={vi.fn()}
        unavailable={unavailable()}
        reader={reader()}
        captions={captions}
      />
    ));
    expect(screen.queryByRole("button", { name: "Edit caption for Mountains.jpg" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByText("Saved"));
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    setReader(false);
    setUnavailable("Saving another resource.");
    const label = screen.getByRole("button", { name: "Edit caption for Mountains.jpg" });
    fireEvent.click(label);
    expect(label).toHaveAttribute("aria-disabled", "true");
    expect(label).toHaveAccessibleDescription("Saving another resource.");
    setUnavailable(undefined);
    fireEvent.keyDown(label, { key: "Tab" });
    fireEvent.keyDown(label, { key: " " });
    const editor = screen.getByRole("textbox", { name: "Caption for Mountains.jpg" });
    fireEvent.click(editor);
    fireEvent.keyDown(editor, { key: "ArrowLeft" });
    setText("New authoritative caption");
    fireEvent.pointerLeave(editor);

    expect(save).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Edit caption for Mountains.jpg" })).toHaveTextContent("New authoritative caption");
    setText("");
    setReader(true);
    expect(screen.queryByText("Add caption")).not.toBeInTheDocument();
  });

  it("should retain rejected caption text and ignore Escape while awaiting acknowledgement", async () => {
    let acknowledge!: () => void;
    const pending = new Promise<void>((resolve) => {
      acknowledge = resolve;
    });
    const save = vi.fn().mockRejectedValueOnce(new Error("uncertain")).mockReturnValueOnce(pending);
    const read = vi.fn().mockResolvedValue("Original");
    const captions = createRoot(() => createCaptionEditing({ save, read, onBlock: vi.fn() }));
    const session = createRoot(() =>
      createPlacementSession({ readPlacements: vi.fn().mockResolvedValue(initial), editPlacements: vi.fn() }, vi.fn()),
    );
    await session.load("album", { kind: "spread", id: "spread" });
    render(() => (
      <PhotoSlot
        slotId="s"
        label="Photo slot 1"
        width={200}
        height={200}
        placement={session.placements()[0]}
        photo={{ ...photo, caption: "Original" }}
        session={session}
        choice={undefined}
        onChoose={vi.fn()}
        unavailable={undefined}
        reader={false}
        captions={captions}
      />
    ));
    fireEvent.click(screen.getByRole("button", { name: "Edit caption for Mountains.jpg" }));
    const editor = screen.getByRole("textbox", { name: "Caption for Mountains.jpg" });
    editor.textContent = "Draft";
    Object.defineProperty(editor, "innerText", { configurable: true, value: "Draft" });
    fireEvent.input(editor);
    fireEvent.blur(editor);
    fireEvent.click(await screen.findByRole("button", { name: "Read saved caption" }));
    await screen.findByRole("button", { name: "Retry caption" });
    fireEvent.click(screen.getByRole("button", { name: "Retry caption" }));
    fireEvent.keyDown(editor, { key: "Escape" });
    expect(editor).toBeInTheDocument();
    expect(editor).toHaveAttribute("contenteditable", "false");
    acknowledge();
    await vi.waitUntil(() => screen.queryByRole("textbox") === null);
    expect(save).toHaveBeenCalledTimes(2);
  });
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
