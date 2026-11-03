import { AlbumRequestError, type AlbumClient } from "../model/Album";
import type { Placement, PlacementContext, PlacementEdit, PlacementSnapshot, SpreadSnapshot } from "../model/Placement";
import type { Slot } from "../model/Layout";
import type { SpreadTemplate } from "../model/Workspace";
import type { HistoryAction, HistoryDirection, HistoryOutcome } from "./BookHistory";
import { sameTransform } from "./PlacementSession";

export interface CompensationPlacementClient {
  readPlacements: (album: string, context: PlacementContext) => Promise<PlacementSnapshot>;
  editPlacements: (album: string, context: PlacementContext, edit: PlacementEdit) => Promise<PlacementSnapshot>;
}

export function createPlacementCompensation(operations: {
  album: string;
  label: string;
  before: PlacementSnapshot;
  after: PlacementSnapshot;
  context: () => PlacementContext;
  authorize: () => Promise<string | undefined>;
  client: Partial<Pick<AlbumClient, "templates" | "changeSpreadTemplate">> & CompensationPlacementClient;
}): HistoryAction {
  let acknowledged = operations.after;
  let maybeUncertain: { before: PlacementSnapshot; after: PlacementSnapshot } | undefined;
  const desired = (direction: HistoryDirection) => (direction === "undo" ? operations.before : operations.after);
  function blocked(direction: HistoryDirection, message: string): HistoryOutcome {
    const original = direction === "undo" ? operations.after : operations.before;
    return sameComposition(acknowledged, original) ? { kind: "blocked", message } : { kind: "blocked", message, partial: true };
  }

  async function read() {
    return operations.client.readPlacements(operations.album, operations.context());
  }

  async function apply(direction: HistoryDirection): Promise<HistoryOutcome> {
    if (maybeUncertain !== undefined) return { kind: "uncertain", message: "Read saved photos before retrying compensation." };
    const reason = await operations.authorize();
    if (reason !== undefined) return blocked(direction, reason);
    let saved = await read();
    if (!sameComposition(saved, acknowledged)) return { kind: "conflict", message: "Saved composition changed. This action cannot overwrite it." };
    const target = desired(direction);
    if (saved.kind !== target.kind) return blocked(direction, "The composition context changed.");
    if (saved.kind === "spread" && target.kind === "spread") {
      if (!sameRawLayout(saved, target)) {
        const client = operations.client;
        if (client.templates === undefined || client.changeSpreadTemplate === undefined)
          return blocked(direction, "Template editing is unavailable.");
        const templates = await client.templates(target.layout.kind);
        const template = templates.find((candidate) => candidate.id === target.layout.templateId);
        if (template === undefined || !sameBoxes(template.slots, target.layout.slots))
          return blocked(direction, "The saved geometry does not match an available template. No compensation was applied.");
        const context = operations.context();
        if (context.kind !== "spread") return blocked(direction, "Choose an interior spread.");
        const lostAccess = await operations.authorize();
        if (lostAccess !== undefined) return blocked(direction, lostAccess);
        maybeUncertain = { before: saved, after: projectTemplate(saved, template) };
        try {
          await client.changeSpreadTemplate(operations.album, context.id, template.id, true);
          saved = await read();
          acknowledged = saved;
          maybeUncertain = undefined;
        } catch (failure) {
          if (failure instanceof AlbumRequestError && failure.status < 500) {
            maybeUncertain = undefined;
            return blocked(direction, failure.message);
          }
          return { kind: "uncertain", message: "Template compensation could not be confirmed. Read saved photos before continuing." };
        }
      }
    }
    while (!sameComposition(saved, target)) {
      const step = planPlacementCompensation(saved, target);
      if (typeof step === "string") return blocked(direction, step);
      const maybeReason = await operations.authorize();
      if (maybeReason !== undefined) return blocked(direction, maybeReason);
      maybeUncertain = { before: saved, after: step.after };
      try {
        saved = await operations.client.editPlacements(operations.album, operations.context(), step.edit);
        acknowledged = saved;
        maybeUncertain = undefined;
        if (!sameComposition(saved, step.after))
          return { kind: "conflict", message: "The acknowledged composition differs from the requested compensation. Review the saved book." };
      } catch (failure) {
        if (failure instanceof AlbumRequestError && failure.status < 500) {
          maybeUncertain = undefined;
          return blocked(direction, failure.message);
        }
        return { kind: "uncertain", message: "Compensation outcome is uncertain. Read saved photos before continuing." };
      }
    }
    return { kind: "saved" };
  }

  async function reconcile(direction: HistoryDirection): Promise<HistoryOutcome> {
    const saved = await read();
    const matchesKnownState =
      maybeUncertain === undefined
        ? sameComposition(saved, acknowledged)
        : sameComposition(saved, maybeUncertain.before) || sameComposition(saved, maybeUncertain.after);
    if (!matchesKnownState) return { kind: "conflict", message: "Saved photos differ from both known compensation outcomes." };
    acknowledged = saved;
    maybeUncertain = undefined;
    return { kind: sameComposition(saved, desired(direction)) ? "saved" : "unchanged" };
  }

  return { label: operations.label, apply, reconcile };
}

export function sameComposition(left: PlacementSnapshot, right: PlacementSnapshot): boolean {
  return JSON.stringify(describeComposition(left)) === JSON.stringify(describeComposition(right));
}

function describeComposition(snapshot: PlacementSnapshot) {
  const placements = snapshot.placements
    .map((row) => ({
      slot: locateSlot(snapshot, row.slotId),
      photo: row.photoId,
      x: row.transform.x,
      y: row.transform.y,
      zoom: row.transform.zoom,
      mirrored: row.transform.mirrored,
      corner: row.kind === "spread" ? row.captionCorner : undefined,
    }))
    .toSorted((left, right) => left.slot.localeCompare(right.slot));
  return snapshot.kind === "cover"
    ? { kind: snapshot.kind, spine: snapshot.spineOwner, placements }
    : {
        kind: snapshot.kind,
        layout: snapshot.layout.kind,
        template: snapshot.layout.templateId,
        slots: snapshot.layout.slots
          .map((slot) => ({ box: describeSlot(slot), bleed: [...(slot.presentation?.bleed ?? [])].sort() }))
          .toSorted((left, right) => left.box.localeCompare(right.box)),
        placements,
      };
}

function describeSlot(slot: Slot) {
  return `${slot.kind}:${String(slot.x)}:${String(slot.y)}:${String(slot.width)}:${String(slot.height)}`;
}

function sameBoxes(left: readonly Omit<Slot, "id">[], right: readonly Omit<Slot, "id">[]) {
  const describe = (slots: readonly Omit<Slot, "id">[]) => slots.map((slot) => describeSlot({ ...slot, id: "" })).sort();
  return JSON.stringify(describe(left)) === JSON.stringify(describe(right));
}

function sameRawLayout(left: SpreadSnapshot, right: SpreadSnapshot) {
  return (
    left.layout.kind === right.layout.kind && left.layout.templateId === right.layout.templateId && sameBoxes(left.layout.slots, right.layout.slots)
  );
}

function projectTemplate(current: SpreadSnapshot, template: SpreadTemplate): SpreadSnapshot {
  const slots = template.slots.map((slot, index) => ({ ...slot, id: `unacknowledged-slot-${String(index)}` }));
  const previous = current.layout.slots.filter((slot) => slot.kind === "photo");
  const photos = slots.filter((slot) => slot.kind === "photo");
  const placements = current.placements.flatMap((row) => {
    const position = previous.findIndex((slot) => slot.id === row.slotId);
    const slot = photos[position];
    return slot === undefined ? [] : [{ ...row, slotId: slot.id, transform: { x: 0, y: 0, zoom: 1, mirrored: false } }];
  });
  return {
    ...current,
    layout: { kind: current.layout.kind, templateId: template.id, slots, bleed: { state: "disabled", available: current.layout.bleed.available } },
    placements,
  };
}

function locateSlot(snapshot: PlacementSnapshot, slotId: string): string {
  if (snapshot.kind === "cover") return slotId === snapshot.frontSlotId ? "front" : "back";
  const slot = snapshot.layout.slots.find((candidate) => candidate.id === slotId);
  /* v8 ignore next -- Authoritative spread snapshots reference their own persisted slots. */
  if (slot === undefined) throw new Error("The saved placement has no matching slot.");
  return describeSlot(slot);
}

function findCurrentSlot(current: PlacementSnapshot, target: PlacementSnapshot, slotId: string) {
  if (current.kind === "cover" && target.kind === "cover") return slotId === target.frontSlotId ? current.frontSlotId : current.backSlotId;
  if (current.kind !== "spread" || target.kind !== "spread") return undefined;
  const box = locateSlot(target, slotId);
  return current.layout.slots.find((slot) => describeSlot(slot) === box)?.id;
}

export function planPlacementCompensation(
  current: PlacementSnapshot,
  target: PlacementSnapshot,
): { edit: PlacementEdit; after: PlacementSnapshot } | string {
  const unwanted = current.placements.find((row) => !target.placements.some((wanted) => wanted.photoId === row.photoId));
  if (unwanted !== undefined) {
    const edit = { operation: "remove", placementId: unwanted.id } as const;
    return {
      edit,
      after: replacePlacements(
        current,
        current.placements.filter((row) => row.id !== unwanted.id),
      ),
    };
  }
  for (const wanted of target.placements) {
    const slotId = findCurrentSlot(current, target, wanted.slotId);
    if (slotId === undefined) return "The saved layout must be recreated before restoring its photographs.";
    const placed = current.placements.find((row) => row.photoId === wanted.photoId);
    if (placed === undefined) {
      const edit = { operation: "place", photoId: wanted.photoId, slotId } as const;
      const fields = {
        id: "unacknowledged-placement",
        slotId,
        photoId: wanted.photoId,
        transform: { x: 0, y: 0, zoom: 1, mirrored: false },
      };
      const fresh: Placement = current.kind === "cover" ? { kind: "cover", ...fields } : { kind: "spread", ...fields, captionCorner: "bottom_right" };
      return { edit, after: replacePlacements(current, [...current.placements.filter((row) => row.slotId !== slotId), fresh]) };
    }
    if (placed.slotId !== slotId) {
      const edit = { operation: "move", placementId: placed.id, slotId } as const;
      return {
        edit,
        after: replacePlacements(
          current,
          current.placements.map((row) =>
            row.id === placed.id ? { ...row, slotId } : row.slotId === slotId ? { ...row, slotId: placed.slotId } : row,
          ),
        ),
      };
    }
    if (!sameTransform(placed.transform, wanted.transform)) {
      const edit = { operation: "adjust", placementId: placed.id, transform: wanted.transform } as const;
      return {
        edit,
        after: replacePlacements(
          current,
          current.placements.map((row) => (row.id === placed.id ? { ...row, transform: wanted.transform } : row)),
        ),
      };
    }
    if (placed.kind === "spread" && wanted.kind === "spread" && placed.captionCorner !== wanted.captionCorner) {
      const edit = { operation: "captionCorner", placementId: placed.id, captionCorner: wanted.captionCorner } as const;
      return {
        edit,
        after: replacePlacements(
          current,
          current.placements.map((row) => (row.kind === "spread" && row.id === placed.id ? { ...row, captionCorner: wanted.captionCorner } : row)),
        ),
      };
    }
  }
  if (current.kind === "cover" && target.kind === "cover" && current.spineOwner !== target.spineOwner)
    return { edit: { operation: "spineOwner", owner: target.spineOwner }, after: { ...current, spineOwner: target.spineOwner } };
  if (current.kind === "spread" && target.kind === "spread" && target.layout.bleed.state !== "mixed") {
    const slots = current.layout.slots.map((slot) => {
      const desired = target.layout.slots.find((candidate) => describeSlot(candidate) === describeSlot(slot));
      if (desired === undefined) throw new Error("The saved layout slot is unavailable.");
      return { ...desired, id: slot.id };
    });
    const after = { ...current, layout: { ...current.layout, slots, bleed: target.layout.bleed } };
    if (!sameComposition(current, after)) return { edit: { operation: "bleed", enabled: target.layout.bleed.state === "enabled" }, after };
  }
  return "This composition cannot yet be recreated by the available editing commands.";
}

function replacePlacements(snapshot: PlacementSnapshot, placements: readonly Placement[]): PlacementSnapshot {
  /* v8 ignore next -- Every private caller preserves row kind or constructs it from snapshot.kind. */
  if (placements.some((row) => row.kind !== snapshot.kind)) throw new Error("Compensation cannot change the placement context.");
  if (snapshot.kind === "cover") return { ...snapshot, placements: placements.filter((row) => row.kind === "cover") };
  return { ...snapshot, placements: placements.filter((row) => row.kind === "spread") };
}
