import { coverGeometries } from "./CoverGeometry";
import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { createRoot, createSignal } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { CompositionControls } from "../../../src/features/albums/workspace/CompositionControls";
import { createPlacementSession } from "../../../src/features/albums/workspace/PlacementSession";
import type { CoverSnapshot, SpreadSnapshot } from "../../../src/features/albums/model/Placement";

const spread: SpreadSnapshot = {
  kind: "spread",
  id: "spread",
  placements: [],
  layout: { kind: "photos_only", templateId: "template", slots: [], bleed: { state: "disabled", available: true } },
};
const cover: CoverSnapshot = {
  kind: "cover",
  frontSlotId: "front",
  backSlotId: "back",
  pageCount: 30,
  geometry: coverGeometries.front,
  spineOwner: "front",
  placements: [],
};

describe("saved composition controls", () => {
  it.each(["another control", "Reader"] as const)("should not steal focus after the user moves to %s during acknowledgement", async (destination) => {
    const [reader, setReader] = createRoot(() => {
      return createSignal(false);
    });
    let acknowledge!: (value: SpreadSnapshot) => void;
    const edit = vi.fn().mockImplementation(
      () =>
        new Promise<SpreadSnapshot>((resolve) => {
          acknowledge = resolve;
        }),
    );
    const session = createRoot(() => createPlacementSession({ readPlacements: vi.fn(), editPlacements: edit }, vi.fn()));
    session.selectSpread("album", spread);
    render(() => (
      <>
        <CompositionControls session={session} unavailable={undefined} reader={reader()} />
        <button type="button">Continue viewing</button>
      </>
    ));
    const toggle = screen.getByRole("checkbox", { name: "Spread bleed" });
    toggle.focus();
    fireEvent.click(toggle);
    const next = screen.getByRole("button", { name: "Continue viewing" });
    next.focus();
    acknowledge({ ...spread, layout: { ...spread.layout, bleed: { state: "enabled", available: true } } });
    if (destination === "Reader") setReader(true);
    await waitFor(() => {
      expect(session.unavailable()).toBeUndefined();
    });
    expect(next).toHaveFocus();
  });
  it("should preserve nonstandard saved bleed and explain why its toggle is disabled", () => {
    const mixed: SpreadSnapshot = {
      ...spread,
      layout: {
        ...spread.layout,
        bleed: { state: "mixed", available: true },
        slots: [
          {
            id: "outer",
            kind: "photo",
            x: 0,
            y: 0,
            width: 50,
            height: 100,
            presentation: {
              bleed: ["top"],
              contentBox: { x: 7, y: 7, width: 36, height: 86 },
              visibleBox: { x: 7, y: 0, width: 36, height: 93 },
            },
          },
        ],
      },
    };
    const read = vi.fn();
    const edit = vi.fn();
    const refresh = vi.fn();
    const session = createRoot(() => createPlacementSession({ readPlacements: read, editPlacements: edit }, refresh));
    session.selectSpread("album", mixed);
    render(() => <CompositionControls session={session} unavailable={undefined} reader={false} />);
    const toggle = screen.getByRole("checkbox", { name: "Spread bleed" });

    expect(toggle).toHaveAttribute("aria-checked", "mixed");
    expect(toggle).toBeDisabled();
    expect(toggle).toHaveAccessibleDescription("This spread has nonstandard bleed. The bleed toggle is unavailable.");
    fireEvent.click(toggle);

    expect(session.snapshot()).toBe(mixed);
    expect(read).not.toHaveBeenCalled();
    expect(edit).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
    session.selectSpread("album", spread);
    expect(screen.getByRole("checkbox", { name: "Spread bleed" })).toBeEnabled();
    expect(screen.getByRole("checkbox", { name: "Spread bleed" })).toHaveAttribute("aria-checked", "false");
  });

  it.each(["disabled", "enabled"] as const)("should toggle standard %s bleed and preserve focus while its save is pending", async (state) => {
    let acknowledge!: (value: SpreadSnapshot) => void;
    const enabled = state === "disabled";
    const saved = {
      ...spread,
      layout: { ...spread.layout, bleed: { state: enabled ? ("enabled" as const) : ("disabled" as const), available: true } },
    };
    const edit = vi.fn().mockImplementation(
      () =>
        new Promise<SpreadSnapshot>((resolve) => {
          acknowledge = resolve;
        }),
    );
    const session = createRoot(() => createPlacementSession({ readPlacements: vi.fn(), editPlacements: edit }, vi.fn()));
    session.selectSpread("album", { ...spread, layout: { ...spread.layout, bleed: { state, available: true } } });
    render(() => <CompositionControls session={session} unavailable={undefined} reader={false} />);
    const toggle = screen.getByRole("checkbox", { name: "Spread bleed" });
    expect(toggle).toHaveAttribute("aria-checked", String(!enabled));
    expect(toggle).toHaveAttribute("title", `Spread bleed: ${state}`);
    expect(toggle).toHaveTextContent("");
    expect(toggle.textContent).toBe("");
    expect(edit).not.toHaveBeenCalled();

    toggle.focus();
    fireEvent.click(toggle);
    expect(edit).toHaveBeenCalledWith("album", { kind: "spread", id: "spread" }, { operation: "bleed", enabled });
    expect(toggle).toBeDisabled();
    expect(toggle).toHaveAccessibleDescription("Saving photos. Please wait.");
    document.body.tabIndex = -1;
    document.body.focus();
    document.body.removeAttribute("tabindex");
    expect(toggle).not.toHaveFocus();
    acknowledge(saved);
    await waitFor(() => expect(toggle).toBeEnabled());
    expect(toggle).toHaveAttribute("aria-checked", String(enabled));
    expect(toggle).toHaveFocus();
  });

  it("should reject an unavailable spread and expose ownership feedback without acquiring access", () => {
    const session = createRoot(() => createPlacementSession({ readPlacements: vi.fn(), editPlacements: vi.fn() }, vi.fn()));
    session.selectSpread("album", { ...spread, layout: { ...spread.layout, bleed: { state: "disabled", available: false } } });
    render(() => <CompositionControls session={session} unavailable={undefined} reader={false} />);
    expect(screen.getByRole("checkbox", { name: "Spread bleed" })).toHaveAccessibleDescription("This spread has no eligible outer photo slots.");
    expect(screen.getByRole("checkbox", { name: "Spread bleed" })).toBeDisabled();
  });

  it("should persist spine ownership through the existing composition session and hide editing controls in Reader", async () => {
    const edit = vi.fn().mockResolvedValue({ ...cover, pageCount: 30, geometry: coverGeometries.back, spineOwner: "back" });
    const session = createRoot(() => createPlacementSession({ readPlacements: vi.fn().mockResolvedValue(cover), editPlacements: edit }, vi.fn()));
    await session.load("album", { kind: "cover" });
    const view = render(() => <CompositionControls session={session} unavailable={undefined} reader={false} />);
    const owner = screen.getByRole("combobox", { name: "Spine/gutter owner" });
    fireEvent.change(owner, { target: { value: "back" } });
    await waitFor(() => expect(owner).toHaveValue("back"));
    expect(edit).toHaveBeenCalledWith("album", { kind: "cover" }, { operation: "spineOwner", owner: "back" });
    view.unmount();
    render(() => <CompositionControls session={session} unavailable="Another editor owns this cover." reader={true} />);
    expect(screen.queryByRole("combobox", { name: "Spine/gutter owner" })).not.toBeInTheDocument();
  });
});
