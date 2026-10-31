import type {
  CoverContext,
  CoverPlacement,
  CoverSnapshot,
  Placement,
  PlacementContext,
  PlacementSnapshot,
  PhotoTransform,
  SpreadContext,
  SpreadPlacement,
  SpreadSnapshot,
} from "../model/Placement";
import { readArray, readNumber, readRecord, readText } from "./Wire";
import { readSlot } from "./WorkspaceWire";
import type { Spread } from "../model/Workspace";
import type { SpreadLayout } from "../model/Layout";

export function readSpread(value: unknown): Spread {
  const fields = readRecord(value);
  const id = readText(fields["id"]);
  const saved = readPlacementSnapshot(value, { kind: "spread", id });
  const kind = saved.layout.kind;
  const geometry = { id, templateId: saved.layout.templateId, slots: saved.layout.slots, placements: saved.placements };
  if (kind !== "album_start") return { ...geometry, kind, chapter: readText(fields["chapter"]) };
  if (fields["chapter"] !== null) throw new Error("An album opening cannot belong to a chapter");
  return { ...geometry, kind, chapter: null };
}

export function readPlacementSnapshot(value: unknown, context: CoverContext): CoverSnapshot;
export function readPlacementSnapshot(value: unknown, context: SpreadContext): SpreadSnapshot;
export function readPlacementSnapshot(value: unknown, context: PlacementContext): PlacementSnapshot;
export function readPlacementSnapshot(value: unknown, context: PlacementContext): PlacementSnapshot {
  const fields = readRecord(value);
  const rows = readArray(fields["placements"]);
  if (context.kind === "spread") {
    if (readText(fields["id"]) !== context.id) throw new Error("Unexpected spread identity");
    const placements = rows.map(readSpreadPlacement);
    requireUniquePlacements(placements);
    const kind = fields["kind"];
    if (kind !== "album_start" && kind !== "chapter_start" && kind !== "photos_only") throw new Error("Unknown spread kind");
    const layout: SpreadLayout = {
      kind,
      templateId: readText(fields["templateId"]),
      slots: readArray(fields["slots"]).map(readSlot),
    };
    const photoSlots = new Set(layout.slots.filter((slot) => slot.kind === "photo").map((slot) => slot.id));
    if (new Set(layout.slots.map((slot) => slot.id)).size !== layout.slots.length || placements.some((row) => !photoSlots.has(row.slotId)))
      throw new Error("Invalid spread placement slot");
    return { kind: "spread", id: context.id, placements, layout };
  }
  const placements = rows.map(readCoverPlacement);
  requireUniquePlacements(placements);
  const frontSlotId = readText(fields["frontSlotId"]);
  const backSlotId = readText(fields["backSlotId"]);
  const spineOwner = fields["spineOwner"];
  if (
    frontSlotId === backSlotId ||
    (spineOwner !== "front" && spineOwner !== "back") ||
    placements.some((row) => row.slotId !== frontSlotId && row.slotId !== backSlotId)
  )
    throw new Error("Invalid cover composition");
  return { kind: "cover", placements, frontSlotId, backSlotId, spineOwner };
}

export function readTransform(value: unknown): PhotoTransform {
  const fields = readRecord(value);
  const x = readNumber(fields["x"]);
  const y = readNumber(fields["y"]);
  const zoom = readNumber(fields["zoom"]);
  const mirrored = fields["mirrored"];
  if (zoom < 1 || zoom > 5 || typeof mirrored !== "boolean") throw new Error("Invalid photo transform");
  return { x, y, zoom, mirrored };
}

function readSpreadPlacement(value: unknown): SpreadPlacement {
  const row = readRecord(value);
  const fields = readPlacementFields(row);
  const captionCorner = readText(row["captionCorner"]);
  if (captionCorner !== "top_left" && captionCorner !== "top_right" && captionCorner !== "bottom_left" && captionCorner !== "bottom_right")
    throw new Error("Invalid caption corner");
  return { kind: "spread", ...fields, captionCorner };
}

function readCoverPlacement(value: unknown): CoverPlacement {
  return { kind: "cover", ...readPlacementFields(readRecord(value)) };
}

function readPlacementFields(row: Record<string, unknown>) {
  return {
    id: readText(row["id"]),
    slotId: readText(row["slotId"]),
    photoId: readText(row["photoId"]),
    transform: readTransform(row["transform"]),
  };
}

function requireUniquePlacements(placements: readonly Placement[]) {
  if (
    new Set(placements.map((row) => row.id)).size !== placements.length ||
    new Set(placements.map((row) => row.slotId)).size !== placements.length ||
    new Set(placements.map((row) => row.photoId)).size !== placements.length
  )
    throw new Error("Duplicate placement");
}
