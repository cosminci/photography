import type {
  CoverContext,
  CoverPlacement,
  CoverSnapshot,
  CoverGeometry,
  CoverPhotoFrame,
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
import type { Slot, SlotBox, SpreadEdge, SpreadLayout } from "../model/Layout";

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
    const state = fields["bleedState"];
    const available = fields["bleedAvailable"];
    if ((state !== "disabled" && state !== "enabled" && state !== "mixed") || typeof available !== "boolean")
      throw new Error("Invalid spread bleed state");
    const layout: SpreadLayout = {
      kind,
      templateId: readText(fields["templateId"]),
      slots: readArray(fields["slots"]).map(readSavedSlot),
      bleed: { state, available },
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
  const pageCount = readNumber(fields["pageCount"]);
  if (!Number.isSafeInteger(pageCount) || pageCount < 0 || pageCount % 2 !== 0) throw new Error("Invalid cover page count");
  return { kind: "cover", placements, frontSlotId, backSlotId, spineOwner, pageCount, geometry: readCoverGeometry(fields["geometry"]) };
}

function readCoverGeometry(value: unknown): CoverGeometry {
  const fields = readRecord(value);
  const width = readNumber(fields["width"]);
  const height = readNumber(fields["height"]);
  const spineStart = readNumber(fields["spineStart"]);
  const spineWidth = readNumber(fields["spineWidth"]);
  if (width <= 0 || height <= 0 || spineStart <= 0 || spineWidth <= 0 || spineStart + spineWidth >= width) throw new Error("Invalid cover geometry");
  return { width, height, spineStart, spineWidth, front: readCoverFrame(fields["front"]), back: readCoverFrame(fields["back"]) };
}

function readCoverFrame(value: unknown): CoverPhotoFrame {
  const fields = readRecord(value);
  const frame = { x: readNumber(fields["x"]), width: readNumber(fields["width"]), height: readNumber(fields["height"]) };
  if (frame.x < 0 || frame.width <= 0 || frame.height <= 0) throw new Error("Invalid cover photo frame");
  return frame;
}

function readSavedSlot(value: unknown): Slot {
  const slot = readSlot(value);
  if (slot.kind === "text") return slot;
  const fields = readRecord(value);
  const bleed = readArray(fields["bleed"]).map(readEdge);
  if (new Set(bleed).size !== bleed.length) throw new Error("Duplicate saved bleed direction");
  return {
    ...slot,
    presentation: { bleed, contentBox: readBox(fields["contentBox"]), visibleBox: readBox(fields["visibleBox"]) },
  };
}

function readBox(value: unknown): SlotBox {
  const fields = readRecord(value);
  const box = { x: readNumber(fields["x"]), y: readNumber(fields["y"]), width: readNumber(fields["width"]), height: readNumber(fields["height"]) };
  if (box.x < 0 || box.y < 0 || box.width <= 0 || box.height <= 0) throw new Error("Invalid saved photo box");
  return box;
}

function readEdge(value: unknown): SpreadEdge {
  if (value !== "top" && value !== "bottom" && value !== "left" && value !== "right") throw new Error("Invalid saved bleed direction");
  return value;
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
