import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { createSignal } from "solid-js";
import { createBookHistory, type BookHistory, type HistoryAction } from "../../../src/features/albums/workspace/BookHistory";
import { BookHistoryControls } from "../../../src/features/albums/workspace/BookHistoryControls";

function mount(action?: HistoryAction) {
  let history!: BookHistory;
  const [unavailable, setUnavailable] = createSignal<string>();
  const traverse = vi.fn((direction: "undo" | "redo") => history.traverse(direction));
  const reconcile = vi.fn(() => history.reconcile());
  const view = render(() => {
    history = createBookHistory();
    if (action !== undefined) history.record(action);
    return <BookHistoryControls history={history} unavailable={unavailable()} traverse={traverse} reconcile={reconcile} />;
  });
  return { ...view, history, traverse, reconcile, setUnavailable };
}
const savedAction = (): HistoryAction => ({
  label: "Edit caption",
  apply: vi.fn().mockResolvedValue({ kind: "saved" }),
  reconcile: vi.fn().mockResolvedValue({ kind: "saved" }),
});

describe("book history controls", () => {
  it("should describe empty history and an existing save block", () => {
    const built = mount();
    expect(screen.getByRole("button", { name: "Undo" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Redo" })).toHaveAccessibleDescription("Nothing to redo.");
    built.history.record(savedAction());
    built.setUnavailable("Finish the caption edit.");
    expect(screen.getByRole("button", { name: "Undo: Edit caption" })).toHaveAccessibleDescription("Finish the caption edit.");
    fireEvent.keyDown(document, { key: "z", ctrlKey: true });
    expect(built.traverse).not.toHaveBeenCalled();
  });

  it("should accept a shortcut from focused vector content without treating it as an HTML focus opener", async () => {
    const built = mount(savedAction());
    const graphic = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    graphic.setAttribute("tabindex", "0");
    built.container.append(graphic);
    graphic.focus();
    expect(document.activeElement).toBe(graphic);

    fireEvent.keyDown(graphic, { key: "z", ctrlKey: true });

    await waitFor(() => {
      expect(built.traverse).toHaveBeenCalledExactlyOnceWith("undo");
    });
    expect(built.history.nextRedo()?.label).toBe("Edit caption");
  });

  it("should undo and redo with accessible labels and retain keyboard focus", async () => {
    mount(savedAction());
    const undo = screen.getByRole("button", { name: "Undo: Edit caption" });
    undo.focus();
    fireEvent.click(undo);
    await waitFor(() => expect(screen.getByRole("button", { name: "Redo: Edit caption" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Redo: Edit caption" }));
    await waitFor(() => expect(undo).toBeEnabled());
    expect(screen.getByRole("group", { name: "Book edit history" })).toBeInTheDocument();
  });

  it.each([
    ["z", true, false, false, "undo"],
    ["z", false, true, true, "redo"],
    ["y", true, false, false, "redo"],
  ] as const)("should route %s to the chronological book action", async (key, ctrlKey, metaKey, shiftKey, direction) => {
    const built = mount(savedAction());
    if (direction === "redo") await built.history.traverse("undo");
    fireEvent.keyDown(document, { key, ctrlKey, metaKey, shiftKey });
    await waitFor(() => {
      expect(built.traverse).toHaveBeenCalledWith(direction);
    });
  });

  it.each(["input", "textarea", "select", "dialog", "contenteditable"] as const)("should leave native Undo alone in %s", (kind) => {
    const built = mount(savedAction());
    const element = document.createElement(kind === "contenteditable" ? "div" : kind);
    if (kind === "contenteditable") element.setAttribute("contenteditable", "");
    built.container.append(element);
    expect(fireEvent.keyDown(element, { key: "z", ctrlKey: true, cancelable: true })).toBe(true);
    expect(built.traverse).not.toHaveBeenCalled();
  });

  it.each([
    { key: "z" },
    { key: "z", ctrlKey: true, altKey: true },
    { key: "z", ctrlKey: true, isComposing: true },
    { key: "x", ctrlKey: true },
    { key: "y", ctrlKey: true, shiftKey: true },
  ])("should not intercept a non-history or composing shortcut", (event) => {
    const built = mount(savedAction());
    expect(fireEvent.keyDown(document, { ...event, cancelable: true })).toBe(true);
    expect(built.traverse).not.toHaveBeenCalled();
  });

  it("should respect an already handled shortcut and remove its listener on exit", () => {
    const built = mount(savedAction());
    const event = new KeyboardEvent("keydown", { key: "z", ctrlKey: true, cancelable: true });
    event.preventDefault();
    document.dispatchEvent(event);
    built.unmount();
    fireEvent.keyDown(document, { key: "z", ctrlKey: true });
    expect(built.traverse).not.toHaveBeenCalled();
  });

  it("should disable traversal during a save and expose read-only uncertain recovery", async () => {
    let finish!: (value: { kind: "uncertain"; message: string }) => void;
    const action = savedAction();
    const built = mount({
      ...action,
      apply: () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    });
    fireEvent.click(screen.getByRole("button", { name: "Undo: Edit caption" }));
    expect(screen.getByRole("button", { name: "Undo: Edit caption" })).toHaveAccessibleDescription("Saving the history action. Please wait.");
    finish({ kind: "uncertain", message: "Read saved book." });
    await screen.findByRole("button", { name: "Read saved history action" });
    expect(screen.getByRole("button", { name: "Undo: Edit caption" })).toHaveAccessibleDescription("Read saved content before continuing.");
    const recover = screen.getByRole("button", { name: "Read saved history action" });
    built.setUnavailable("Refreshing the saved workspace.");
    expect(recover).toBeDisabled();
    expect(recover).toHaveAccessibleDescription("Refreshing the saved workspace.");
    built.setUnavailable(undefined);
    fireEvent.click(recover);
    expect(recover).toBeDisabled();
    expect(recover).toHaveAccessibleDescription("Saving the history action. Please wait.");
    await waitFor(() => {
      expect(built.reconcile).toHaveBeenCalledOnce();
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "Redo: Edit caption" })).toBeEnabled());
  });

  it.each(["conflict", "blocked"] as const)("should keep the current book only after explicit recovery from %s", async (kind) => {
    const built = mount({
      ...savedAction(),
      apply: () =>
        Promise.resolve(kind === "blocked" ? { kind, partial: true, message: "Finish compensation." } : { kind, message: "Saved book changed." }),
    });
    fireEvent.click(screen.getByRole("button", { name: "Undo: Edit caption" }));
    const clear = await screen.findByRole("button", { name: "Keep current book and clear history" });
    if (kind === "blocked")
      expect(screen.getByRole("button", { name: "Redo" })).toHaveAccessibleDescription("Finish the interrupted history action first.");
    else
      expect(screen.getByRole("button", { name: "Undo: Edit caption" })).toHaveAccessibleDescription(
        "Resolve the history conflict before continuing.",
      );
    built.setUnavailable("Finishing the saved read.");
    expect(clear).toBeDisabled();
    expect(clear).toHaveAccessibleDescription("Finishing the saved read.");
    built.setUnavailable(undefined);
    fireEvent.click(clear);
    expect(built.history.nextUndo()).toBeUndefined();
    expect(screen.getByRole("status")).toHaveTextContent("Current saved book kept.");
  });
});
