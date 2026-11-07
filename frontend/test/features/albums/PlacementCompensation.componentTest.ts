import { coverGeometries } from "./CoverGeometry";
import { describe, expect, it, vi } from "vitest";
import {
  createPlacementCompensation,
  planPlacementCompensation,
  sameComposition,
} from "../../../src/features/albums/workspace/PlacementCompensation";
import type { CoverSnapshot, PlacementSnapshot, SpreadSnapshot } from "../../../src/features/albums/model/Placement";
import { AlbumRequestError } from "../../../src/features/albums/model/Album";

const transform = { x: 12, y: -3, zoom: 2, mirrored: true };
const empty: SpreadSnapshot = {
  kind: "spread",
  id: "spread",
  layout: {
    kind: "photos_only",
    templateId: "template",
    bleed: { state: "disabled", available: true },
    slots: [
      { kind: "photo", id: "left", x: 0, y: 0, width: 50, height: 100 },
      { kind: "photo", id: "right", x: 50, y: 0, width: 50, height: 100 },
    ],
  },
  placements: [],
};
const populated: SpreadSnapshot = {
  ...empty,
  placements: [{ kind: "spread", id: "old-placement", slotId: "left", photoId: "photo", transform, captionCorner: "top_left" }],
};
const newlyPlaced: SpreadSnapshot = {
  ...populated,
  placements: populated.placements.map((row) => ({
    ...row,
    id: "new-placement",
    transform: { x: 0, y: 0, zoom: 1, mirrored: false },
    captionCorner: "bottom_right",
  })),
};
const framed: SpreadSnapshot = { ...newlyPlaced, placements: newlyPlaced.placements.map((row) => ({ ...row, transform })) };
const restored: SpreadSnapshot = { ...framed, placements: framed.placements.map((row) => ({ ...row, captionCorner: "top_left" })) };
const cover: CoverSnapshot = {
  kind: "cover",
  frontSlotId: "front",
  backSlotId: "back",
  pageCount: 30,
  geometry: coverGeometries.front,
  spineOwner: "front",
  placements: [],
};
function build(before: PlacementSnapshot = populated, after: PlacementSnapshot = empty) {
  const read = vi.fn().mockResolvedValue(after);
  const edit = vi.fn().mockRejectedValue(new Error("Lost response"));
  const authorize = vi.fn().mockResolvedValue(undefined);
  const templates = vi.fn().mockResolvedValue([{ id: "template", name: "Original", slots: empty.layout.slots }]);
  const changeSpreadTemplate = vi.fn().mockResolvedValue(undefined);
  const action = createPlacementCompensation({
    album: "album",
    label: "Edit composition",
    before,
    after,
    context: () => ({ kind: "spread", id: "spread" }),
    authorize,
    client: { readPlacements: read, editPlacements: edit, templates, changeSpreadTemplate },
  });
  return { action, read, edit, authorize, templates, changeSpreadTemplate };
}

describe("placement compensating commands", () => {
  it("should place a missing photograph without removing an unaffected photograph", () => {
    const first = populated.placements[0];
    if (first === undefined) throw new Error("Missing photograph.");
    const fresh = { ...first, transform: { x: 0, y: 0, zoom: 1, mirrored: false }, captionCorner: "bottom_right" as const };
    const retained = { ...fresh, id: "retained", photoId: "retained-photo", slotId: "right" };
    const current = { ...empty, placements: [retained] };
    const target = { ...empty, placements: [retained, fresh] };

    const step = planPlacementCompensation(current, target);

    expect(step).toMatchObject({ edit: { operation: "place", photoId: "photo", slotId: "left" } });
    if (typeof step === "string") throw new Error(step);
    expect(sameComposition(step.after, target)).toBe(true);
  });

  it("should preserve a completed Undo when access prevents Redo", async () => {
    const built = build(empty, populated);
    built.edit.mockResolvedValue(empty);
    expect(await built.action.apply("undo")).toEqual({ kind: "saved" });
    built.authorize.mockResolvedValue("The cover is view-only.");

    expect(await built.action.apply("redo")).toEqual({ kind: "blocked", message: "The cover is view-only." });
    expect(built.edit).toHaveBeenCalledOnce();
  });

  it("should compensate cover spine ownership while keeping both saved cover photographs", async () => {
    const front = {
      ...cover,
      placements: [
        { kind: "cover" as const, id: "front-photo", photoId: "front-photo", slotId: cover.frontSlotId, transform },
        { kind: "cover" as const, id: "back-photo", photoId: "back-photo", slotId: cover.backSlotId, transform },
      ],
    };
    const back = { ...front, pageCount: 30, geometry: coverGeometries.back, spineOwner: "back" as const };
    const edit = vi.fn().mockResolvedValue(back);
    const action = createPlacementCompensation({
      album: "album",
      label: "Change spine owner",
      before: back,
      after: front,
      context: () => ({ kind: "cover" }),
      authorize: () => Promise.resolve(undefined),
      client: { readPlacements: () => Promise.resolve(front), editPlacements: edit },
    });

    expect(await action.apply("undo")).toEqual({ kind: "saved" });
    expect(edit).toHaveBeenCalledExactlyOnceWith("album", { kind: "cover" }, { operation: "spineOwner", owner: "back" });
  });

  it("should reconcile a lost template response after it retained one photo, reset framing and dropped an unmatched photo", async () => {
    const first = populated.placements[0];
    if (first === undefined) throw new Error("Missing photograph.");
    const current: SpreadSnapshot = {
      ...populated,
      layout: { ...empty.layout, templateId: "two-photos" },
      placements: [first, { ...first, id: "second-placement", photoId: "second-photo", slotId: "right" }],
    };
    const recovered: SpreadSnapshot = {
      ...empty,
      layout: { ...empty.layout, slots: empty.layout.slots.slice(0, 1) },
      placements: [{ ...first, transform: { x: 0, y: 0, zoom: 1, mirrored: false } }],
    };
    const built = build(recovered, current);
    built.templates.mockResolvedValue([{ id: "template", name: "One photograph", slots: recovered.layout.slots }]);
    built.changeSpreadTemplate.mockRejectedValue(new Error("Lost response"));

    expect(await built.action.apply("undo")).toMatchObject({ kind: "uncertain" });
    built.read.mockResolvedValue(recovered);
    expect(await built.action.reconcile("undo")).toEqual({ kind: "saved" });
    expect(built.changeSpreadTemplate).toHaveBeenCalledOnce();
    expect(built.edit).not.toHaveBeenCalled();
  });

  it("should stop photo restoration if the acknowledged template read has incompatible geometry", async () => {
    const changed: SpreadSnapshot = { ...empty, layout: { ...empty.layout, templateId: "other", slots: [] } };
    const built = build(populated, changed);

    expect(await built.action.apply("undo")).toMatchObject({ kind: "blocked", message: /layout must be recreated/ });
    expect(built.edit).not.toHaveBeenCalled();
  });

  it.each(["move", "adjust", "captionCorner"] as const)("should preserve unaffected photographs while planning %s", (operation) => {
    const first = populated.placements[0];
    if (first === undefined) throw new Error("Missing photograph.");
    const slots = [0, 1, 2].map((index) => ({
      id: `slot-${String(index)}`,
      kind: "photo" as const,
      x: index * 30,
      y: 0,
      width: 30,
      height: 100,
    }));
    const target: SpreadSnapshot = {
      ...empty,
      layout: { ...empty.layout, slots },
      placements: slots.map((slot, index) => ({ ...first, id: `placement-${String(index)}`, photoId: `photo-${String(index)}`, slotId: slot.id })),
    };
    const current: SpreadSnapshot = {
      ...target,
      placements: target.placements.map((row, index) =>
        operation === "move" && index < 2
          ? { ...row, slotId: `slot-${String(1 - index)}` }
          : index !== 0
            ? row
            : operation === "adjust"
              ? { ...row, transform: { ...row.transform, zoom: 1 } }
              : { ...row, captionCorner: "bottom_right" },
      ),
    };

    const step = planPlacementCompensation(current, target);

    expect(step).toMatchObject({ edit: { operation, placementId: "placement-0" } });
    if (typeof step === "string") throw new Error(step);
    expect(sameComposition(step.after, target)).toBe(true);
  });

  it("should reject planning bleed across incompatible slot geometry and leave a no-op composition untouched", () => {
    const other = { ...empty, layout: { ...empty.layout, slots: [] } };
    expect(() => planPlacementCompensation(empty, other)).toThrow("The saved layout slot is unavailable.");
    expect(planPlacementCompensation(empty, empty)).toEqual(expect.any(String));
  });

  it("should stop before any command when ownership is unavailable", async () => {
    const built = build();
    built.authorize.mockResolvedValue("Choose Edit spread.");
    expect(await built.action.apply("undo")).toMatchObject({ kind: "blocked", message: "Choose Edit spread." });
    expect(built.read).not.toHaveBeenCalled();
  });

  it("should stop when ownership disappears after the saved composition read", async () => {
    const built = build();
    built.authorize.mockResolvedValueOnce(undefined).mockResolvedValue("Ownership lost.");
    expect(await built.action.apply("undo")).toMatchObject({ kind: "blocked", message: "Ownership lost." });
    expect(built.edit).not.toHaveBeenCalled();
  });

  it("should prevent replay of an uncertain command and accept an unchanged read without writing", async () => {
    const built = build();
    expect(await built.action.apply("undo")).toMatchObject({ kind: "uncertain" });
    expect(await built.action.apply("undo")).toMatchObject({ kind: "uncertain" });
    expect(await built.action.reconcile("undo")).toEqual({ kind: "unchanged" });
    expect(built.edit).toHaveBeenCalledOnce();
    built.read.mockResolvedValue(populated);
    expect(await built.action.reconcile("undo")).toMatchObject({ kind: "conflict" });
  });

  it("should accept only the predicted uncertain outcome even when placement identities regenerate", async () => {
    const desired: SpreadSnapshot = {
      ...populated,
      placements: populated.placements.map((row) => ({
        ...row,
        transform: { x: 0, y: 0, zoom: 1, mirrored: false },
        captionCorner: "bottom_right",
      })),
    };
    const built = build(desired);
    expect(await built.action.apply("undo")).toMatchObject({ kind: "uncertain" });
    built.read.mockResolvedValue({ ...desired, placements: desired.placements.map((row) => ({ ...row, id: "regenerated" })) });
    expect(await built.action.reconcile("undo")).toEqual({ kind: "saved" });
    expect(built.edit).toHaveBeenCalledOnce();
  });

  it("should expose a rejected or non-progressing acknowledgement without looping", async () => {
    const built = build();
    built.edit.mockRejectedValueOnce(new AlbumRequestError(422, "Photo unavailable."));
    expect(await built.action.apply("undo")).toMatchObject({ kind: "blocked", message: "Photo unavailable." });
    built.edit.mockResolvedValue(empty);
    expect(await built.action.apply("undo")).toMatchObject({ kind: "conflict" });
    expect(built.edit).toHaveBeenCalledTimes(2);
  });

  it("should reject compensation across a cover/interior context change", async () => {
    const built = build(cover);
    expect(await built.action.apply("undo")).toMatchObject({ kind: "blocked", message: "The composition context changed." });
  });

  it.each(["missing", "geometry", "lost-access", "rejection", "uncertain"] as const)(
    "should retain a %s template restoration without unsafe photo commands",
    async (failure) => {
      const changed: SpreadSnapshot = { ...empty, layout: { ...empty.layout, templateId: "other" } };
      const built = build(empty, changed);
      if (failure === "missing") built.templates.mockResolvedValue([]);
      if (failure === "geometry") built.templates.mockResolvedValue([{ id: "template", name: "Original", slots: [] }]);
      if (failure === "lost-access") built.authorize.mockResolvedValueOnce(undefined).mockResolvedValue("Ownership lost.");
      if (failure === "rejection") built.changeSpreadTemplate.mockRejectedValue(new AlbumRequestError(422, "Template unavailable."));
      if (failure === "uncertain") built.changeSpreadTemplate.mockRejectedValue(new Error("Lost response"));
      expect(await built.action.apply("undo")).toMatchObject({ kind: failure === "uncertain" ? "uncertain" : "blocked" });
      expect(built.edit).not.toHaveBeenCalled();
      if (failure === "uncertain") {
        built.read.mockResolvedValue(empty);
        expect(await built.action.reconcile("undo")).toEqual({ kind: "saved" });
      }
    },
  );

  it("should require available template editing and the correct spread target", async () => {
    const changed: SpreadSnapshot = { ...empty, layout: { ...empty.layout, templateId: "other" } };
    const client = { readPlacements: () => Promise.resolve(changed), editPlacements: vi.fn() };
    const options = { album: "album", label: "Template", before: empty, after: changed, authorize: () => Promise.resolve(undefined) };
    const missing = createPlacementCompensation({ ...options, context: () => ({ kind: "spread", id: "spread" }), client });
    expect(await missing.apply("undo")).toMatchObject({ kind: "blocked", message: "Template editing is unavailable." });
    const wrongTarget = createPlacementCompensation({
      ...options,
      context: () => ({ kind: "cover" }),
      client: {
        ...client,
        templates: () => Promise.resolve([{ id: "template", name: "Original", slots: empty.layout.slots }]),
        changeSpreadTemplate: vi.fn(),
      },
    });
    expect(await wrongTarget.apply("undo")).toMatchObject({ kind: "blocked", message: "Choose an interior spread." });
  });

  it("should swap photos by visible slot geometry without requiring the old slot identities", () => {
    const first = populated.placements[0];
    if (first === undefined) throw new Error("Missing photo fixture.");
    const second = { ...first, id: "second", photoId: "second-photo", slotId: "right" };
    const target = { ...populated, placements: [first, second] };
    const current = {
      ...target,
      placements: [
        { ...first, slotId: "right" },
        { ...second, slotId: "left" },
      ],
    };
    const step = planPlacementCompensation(current, target);
    expect(step).toMatchObject({ edit: { operation: "move", placementId: first.id, slotId: "left" } });
    if (typeof step === "string") throw new Error(step);
    expect(sameComposition(step.after, target)).toBe(true);
  });

  it("should restore cover photos, framing and spine independently of generated slot identities", () => {
    const target: CoverSnapshot = {
      ...cover,
      frontSlotId: "old-front",
      placements: [{ kind: "cover", id: "old", slotId: "old-front", photoId: "photo", transform }],
    };
    const step = planPlacementCompensation(cover, target);
    expect(step).toMatchObject({ edit: { operation: "place", photoId: "photo", slotId: "front" } });
    const spine = planPlacementCompensation(cover, { ...cover, pageCount: 30, geometry: coverGeometries.back, spineOwner: "back" });
    expect(spine).toMatchObject({ edit: { operation: "spineOwner", owner: "back" } });
    const back = { ...target, placements: target.placements.map((row) => ({ ...row, slotId: target.backSlotId })) };
    expect(sameComposition(target, back)).toBe(false);
    expect(planPlacementCompensation(cover, cover)).toEqual(expect.any(String));
    expect(planPlacementCompensation(cover, populated)).toEqual(expect.any(String));
  });

  it("should restore saved bleed directions through the boolean API without altering template boxes", async () => {
    const enabled: SpreadSnapshot = {
      ...empty,
      layout: {
        ...empty.layout,
        bleed: { state: "enabled", available: true },
        slots: empty.layout.slots.map((slot) => ({ ...slot, presentation: { bleed: ["top", "bottom"], contentBox: slot, visibleBox: slot } })),
      },
    };
    const built = build(enabled);
    built.edit.mockResolvedValue(enabled);
    expect(await built.action.apply("undo")).toEqual({ kind: "saved" });
    expect(built.edit).toHaveBeenCalledWith("album", { kind: "spread", id: "spread" }, { operation: "bleed", enabled: true });
    const clearing = build(empty, enabled);
    clearing.edit.mockResolvedValue(empty);
    expect(await clearing.action.apply("undo")).toEqual({ kind: "saved" });
    expect(clearing.edit).toHaveBeenCalledWith("album", { kind: "spread", id: "spread" }, { operation: "bleed", enabled: false });
  });
  it("should change back to the saved template and follow newly generated slot and placement identities", async () => {
    const after: SpreadSnapshot = { ...empty, layout: { ...empty.layout, templateId: "other", slots: [] } };
    const remapped = { ...empty, layout: { ...empty.layout, slots: empty.layout.slots.map((slot) => ({ ...slot, id: `new-${slot.id}` })) } };
    const placed = { ...remapped, placements: newlyPlaced.placements.map((row) => ({ ...row, slotId: "new-left" })) };
    const adjusted = { ...remapped, placements: framed.placements.map((row) => ({ ...row, slotId: "new-left" })) };
    const completed = { ...remapped, placements: restored.placements.map((row) => ({ ...row, slotId: "new-left" })) };
    const read = vi.fn().mockResolvedValueOnce(after).mockResolvedValueOnce(remapped);
    const changeTemplate = vi.fn().mockResolvedValue(undefined);
    const edit = vi.fn().mockResolvedValueOnce(placed).mockResolvedValueOnce(adjusted).mockResolvedValueOnce(completed);
    const action = createPlacementCompensation({
      album: "album",
      label: "Change template",
      before: populated,
      after,
      context: () => ({ kind: "spread", id: "spread" }),
      authorize: () => Promise.resolve(undefined),
      client: {
        readPlacements: read,
        editPlacements: edit,
        changeSpreadTemplate: changeTemplate,
        templates: () => Promise.resolve([{ id: "template", name: "Original", slots: empty.layout.slots }]),
      },
    });

    expect(await action.apply("undo")).toEqual({ kind: "saved" });
    expect(changeTemplate).toHaveBeenCalledExactlyOnceWith("album", "spread", "template", true);
    expect(edit).toHaveBeenCalledTimes(3);
    expect(edit).toHaveBeenNthCalledWith(1, "album", { kind: "spread", id: "spread" }, { operation: "place", photoId: "photo", slotId: "new-left" });
    expect(edit).toHaveBeenNthCalledWith(
      2,
      "album",
      { kind: "spread", id: "spread" },
      { operation: "adjust", placementId: "new-placement", transform },
    );
    expect(edit).toHaveBeenNthCalledWith(
      3,
      "album",
      { kind: "spread", id: "spread" },
      { operation: "captionCorner", placementId: "new-placement", captionCorner: "top_left" },
    );
  });

  it("should recreate a removed photograph and restore its framing and corner through ordinary commands", async () => {
    const read = vi.fn().mockResolvedValueOnce(empty).mockResolvedValueOnce(restored);
    const edit = vi
      .fn()
      .mockResolvedValueOnce(newlyPlaced)
      .mockResolvedValueOnce(framed)
      .mockResolvedValueOnce(restored)
      .mockResolvedValueOnce(empty);
    const action = createPlacementCompensation({
      album: "album",
      label: "Remove photo",
      before: populated,
      after: empty,
      context: () => ({ kind: "spread", id: "spread" }),
      authorize: vi.fn().mockResolvedValue(undefined),
      client: { readPlacements: read, editPlacements: edit },
    });

    expect(await action.apply("undo")).toEqual({ kind: "saved" });
    expect(edit).toHaveBeenCalledTimes(3);
    expect(edit).toHaveBeenNthCalledWith(1, "album", { kind: "spread", id: "spread" }, { operation: "place", photoId: "photo", slotId: "left" });
    expect(edit).toHaveBeenNthCalledWith(
      2,
      "album",
      { kind: "spread", id: "spread" },
      { operation: "adjust", placementId: "new-placement", transform },
    );
    expect(edit).toHaveBeenNthCalledWith(
      3,
      "album",
      { kind: "spread", id: "spread" },
      { operation: "captionCorner", placementId: "new-placement", captionCorner: "top_left" },
    );
    expect(await action.apply("redo")).toEqual({ kind: "saved" });
    expect(edit).toHaveBeenLastCalledWith("album", { kind: "spread", id: "spread" }, { operation: "remove", placementId: "new-placement" });
  });

  it("should retain acknowledged steps when a later request is rejected and retry only unfinished compensation", async () => {
    const read = vi.fn().mockResolvedValueOnce(empty).mockResolvedValueOnce(newlyPlaced);
    const edit = vi
      .fn()
      .mockResolvedValueOnce(newlyPlaced)
      .mockRejectedValueOnce(new AlbumRequestError(409, "Editing ownership unavailable."))
      .mockResolvedValueOnce(framed)
      .mockResolvedValueOnce(restored);
    const action = createPlacementCompensation({
      album: "album",
      label: "Remove photo",
      before: populated,
      after: empty,
      context: () => ({ kind: "spread", id: "spread" }),
      authorize: vi.fn().mockResolvedValue(undefined),
      client: { readPlacements: read, editPlacements: edit },
    });

    expect(await action.apply("undo")).toEqual({ kind: "blocked", message: "Editing ownership unavailable.", partial: true });
    expect(edit).toHaveBeenCalledTimes(2);
    expect(await action.apply("undo")).toEqual({ kind: "saved" });
    expect(edit).toHaveBeenCalledTimes(4);
    expect(edit).toHaveBeenNthCalledWith(1, "album", { kind: "spread", id: "spread" }, { operation: "place", photoId: "photo", slotId: "left" });
    expect(edit).toHaveBeenNthCalledWith(
      2,
      "album",
      { kind: "spread", id: "spread" },
      { operation: "adjust", placementId: "new-placement", transform },
    );
    expect(edit).toHaveBeenNthCalledWith(
      3,
      "album",
      { kind: "spread", id: "spread" },
      { operation: "adjust", placementId: "new-placement", transform },
    );
    expect(edit).toHaveBeenNthCalledWith(
      4,
      "album",
      { kind: "spread", id: "spread" },
      { operation: "captionCorner", placementId: "new-placement", captionCorner: "top_left" },
    );
  });

  it("should reconcile a lost placement acknowledgement using reads without placing the photo twice", async () => {
    const read = vi.fn().mockResolvedValueOnce(empty).mockResolvedValueOnce(newlyPlaced);
    const edit = vi.fn().mockRejectedValue(new Error("Lost acknowledgement"));
    const action = createPlacementCompensation({
      album: "album",
      label: "Remove photo",
      before: populated,
      after: empty,
      context: () => ({ kind: "spread", id: "spread" }),
      authorize: vi.fn().mockResolvedValue(undefined),
      client: { readPlacements: read, editPlacements: edit },
    });

    expect(await action.apply("undo")).toMatchObject({ kind: "uncertain" });
    expect(await action.reconcile("undo")).toEqual({ kind: "unchanged" });
    expect(edit).toHaveBeenCalledOnce();
  });

  it("should block an intervening saved change without issuing compensation", async () => {
    const edit = vi.fn();
    const changed = { ...populated, placements: populated.placements.map((row) => ({ ...row, photoId: "someone-elses-photo" })) };
    const action = createPlacementCompensation({
      album: "album",
      label: "Remove photo",
      before: populated,
      after: empty,
      context: () => ({ kind: "spread", id: "spread" }),
      authorize: vi.fn().mockResolvedValue(undefined),
      client: { readPlacements: vi.fn().mockResolvedValue(changed), editPlacements: edit },
    });

    expect(await action.apply("undo")).toMatchObject({ kind: "conflict" });
    expect(edit).not.toHaveBeenCalled();
  });
});
