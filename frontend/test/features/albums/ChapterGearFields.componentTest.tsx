import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { createSignal, untrack } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { ChapterGearFields } from "../../../src/features/albums/workspace/ChapterGearFields";
import type { Camera, CameraSelection, GearCatalog, Lens } from "../../../src/features/albums/model/Gear";

const first: Camera = { id: "first", name: "First body", code: "first", kind: "interchangeable_lens", mount: "sony_e", sensorFormat: "aps_c" };
const second: Camera = { ...first, id: "second", name: "Second body", code: "second" };
const fixed: Camera = { id: "phone", name: "Phone", code: "phone", kind: "fixed_lens" };
const lens: Lens = { id: "lens", name: "Shared lens", code: "lens", mount: "sony_e", coverage: "full_frame" };
const catalog: GearCatalog = { cameras: [first, second, fixed], lenses: [lens] };

describe("chapter gear fields", () => {
  it("should share compatible lenses per body, hide exhausted selectors and restore meaningful keyboard focus", async () => {
    const [selection, change] = createSignal<readonly CameraSelection[]>([]);
    const manage = vi.fn();
    render(() => (
      <>
        <ChapterGearFields catalog={catalog} selected={selection()} disabled={false} onChange={change} onManage={manage} />
        <p id="chapter-feedback">Choose compatible gear.</p>
      </>
    ));
    expect(screen.getByRole("button", { name: "Manage catalog" })).toHaveAttribute("title", "Manage catalog");
    expect(screen.getByRole("button", { name: "Manage catalog" })).not.toHaveTextContent("Manage catalog");
    fireEvent.change(screen.getByRole("combobox", { name: "Add camera" }), { target: { value: "" } });
    expect(untrack(selection)).toEqual([]);
    fireEvent.change(screen.getByRole("combobox", { name: "Add camera" }), { target: { value: first.id } });
    await waitFor(() => expect(screen.getByRole("group", { name: "First body gear" })).toHaveFocus());
    fireEvent.change(screen.getByRole("combobox", { name: "Add camera" }), { target: { value: second.id } });
    fireEvent.change(screen.getByRole("combobox", { name: "Add camera" }), { target: { value: fixed.id } });
    expect(screen.queryByRole("combobox", { name: "Add camera" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole("combobox", { name: "Add lens for First body" }), { target: { value: "" } });
    fireEvent.change(screen.getByRole("combobox", { name: "Add lens for First body" }), { target: { value: lens.id } });
    await waitFor(() => expect(screen.getByRole("group", { name: "First body gear" })).toHaveFocus());
    expect(screen.queryByRole("combobox", { name: "Add lens for First body" })).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Add lens for Second body" })).toBeEnabled();
    fireEvent.change(screen.getByRole("combobox", { name: "Add lens for Second body" }), { target: { value: lens.id } });

    expect(untrack(selection)).toEqual([
      { cameraId: first.id, lensIds: [lens.id] },
      { cameraId: second.id, lensIds: [lens.id] },
      { cameraId: fixed.id, lensIds: [] },
    ]);
    fireEvent.click(within(screen.getByRole("group", { name: "First body gear" })).getByRole("button", { name: "Remove Shared lens from chapter" }));
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Add lens for First body" })).toHaveFocus());
    expect(untrack(selection)[1]).toEqual({ cameraId: second.id, lensIds: [lens.id] });
    fireEvent.click(screen.getByRole("button", { name: "Remove Phone from chapter" }));
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Add camera" })).toHaveFocus());
    fireEvent.click(screen.getByRole("button", { name: "Manage catalog" }));
    expect(manage).toHaveBeenCalledExactlyOnceWith(screen.getByRole("button", { name: "Manage catalog" }));
  });
  it("should retain focus on a lens selector while compatible choices remain and explain an empty compatible catalog", async () => {
    const [selection, change] = createSignal<readonly CameraSelection[]>([{ cameraId: first.id, lensIds: [] }]);
    const otherLens = { ...lens, id: "other", name: "Other lens" };
    const [current, updateCatalog] = createSignal<GearCatalog>({ cameras: [first], lenses: [lens, otherLens] });
    render(() => <ChapterGearFields catalog={current()} selected={selection()} disabled={false} onChange={change} onManage={vi.fn()} />);
    fireEvent.change(screen.getByRole("combobox", { name: "Add lens for First body" }), { target: { value: lens.id } });
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Add lens for First body" })).toHaveFocus());
    updateCatalog({ cameras: [first], lenses: [] });
    expect(screen.getByText("No compatible lenses in the catalog.")).toBeInTheDocument();
    expect(screen.getByText("Lens no longer available")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Remove lens from chapter" }));
    await waitFor(() => expect(screen.getByRole("group", { name: "First body gear" })).toHaveFocus());
    updateCatalog({ cameras: [], lenses: [] });
    expect(screen.getByRole("group", { name: "Unavailable camera gear" })).toHaveTextContent("Camera no longer available");
    fireEvent.click(screen.getByRole("button", { name: "Remove camera from chapter" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Manage catalog" })).toHaveFocus());
    expect(untrack(selection)).toEqual([]);
  });
  it("should disable gear controls with the associated pending explanation", () => {
    render(() => (
      <>
        <ChapterGearFields
          catalog={catalog}
          selected={[{ cameraId: first.id, lensIds: [lens.id] }]}
          disabled={true}
          onChange={vi.fn()}
          onManage={vi.fn()}
        />
        <p id="chapter-feedback">Saving gear. Please wait.</p>
      </>
    ));
    expect(screen.getByRole("combobox", { name: "Add camera" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Manage catalog" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Remove Shared lens from chapter" })).toBeDisabled();
    expect(screen.getByRole("group", { name: "Gear" })).toHaveAccessibleDescription("Saving gear. Please wait.");
  });
});
