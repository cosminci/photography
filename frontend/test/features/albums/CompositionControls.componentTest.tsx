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
  layout: { kind: "photos_only", templateId: "template", slots: [], bleed: { state: "mixed", available: true } },
};
const cover: CoverSnapshot = { kind: "cover", frontSlotId: "front", backSlotId: "back", spineOwner: "front", placements: [] };

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
  it("should show mixed without writing and preserve focus while acknowledged bleed saves are pending", async () => {
    let acknowledge!: (value: SpreadSnapshot) => void;
    const saved = { ...spread, layout: { ...spread.layout, bleed: { state: "enabled" as const, available: true } } };
    const edit = vi.fn().mockImplementation(
      () =>
        new Promise<SpreadSnapshot>((resolve) => {
          acknowledge = resolve;
        }),
    );
    const session = createRoot(() => createPlacementSession({ readPlacements: vi.fn(), editPlacements: edit }, vi.fn()));
    session.selectSpread("album", spread);
    render(() => <CompositionControls session={session} unavailable={undefined} reader={false} />);
    const toggle = screen.getByRole("checkbox", { name: "Spread bleed" });
    expect(toggle).toHaveAttribute("aria-checked", "mixed");
    expect(edit).not.toHaveBeenCalled();

    toggle.focus();
    fireEvent.click(toggle);
    expect(edit).toHaveBeenCalledWith("album", { kind: "spread", id: "spread" }, { operation: "bleed", enabled: true });
    expect(toggle).toBeDisabled();
    expect(toggle).toHaveAccessibleDescription("Saving photos. Please wait.");
    document.body.tabIndex = -1;
    document.body.focus();
    document.body.removeAttribute("tabindex");
    expect(toggle).not.toHaveFocus();
    acknowledge(saved);
    await waitFor(() => expect(toggle).toBeEnabled());
    expect(toggle).toHaveAttribute("aria-checked", "true");
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
    const edit = vi.fn().mockResolvedValue({ ...cover, spineOwner: "back" });
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
