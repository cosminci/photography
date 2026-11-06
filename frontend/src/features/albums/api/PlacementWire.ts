import {
  array,
  boolean,
  check,
  gtValue,
  literal,
  maxValue,
  minValue,
  multipleOf,
  object,
  parse,
  picklist,
  pipe,
  safeInteger,
  string,
  transform,
  variant,
} from "valibot";
import type { CoverContext, CoverSnapshot, Placement, PlacementContext, PlacementSnapshot, SpreadContext, SpreadSnapshot } from "../model/Placement";
import { finiteNumber } from "./Wire";
import { slot } from "./WorkspaceWire";

const positive = pipe(finiteNumber, gtValue(0));
const nonnegative = pipe(finiteNumber, minValue(0));
const photoTransform = object({ x: finiteNumber, y: finiteNumber, zoom: pipe(finiteNumber, minValue(1), maxValue(5)), mirrored: boolean() });
const placement = { id: string(), slotId: string(), photoId: string(), transform: photoTransform };
const coverPlacement = pipe(
  object(placement),
  transform((row) => ({ ...row, kind: "cover" as const })),
);
const spreadPlacement = pipe(
  object({ ...placement, captionCorner: picklist(["top_left", "top_right", "bottom_left", "bottom_right"]) }),
  transform((row) => ({ ...row, kind: "spread" as const })),
);
const box = object({ x: nonnegative, y: nonnegative, width: positive, height: positive });
const photoSlot = pipe(
  object({
    ...slot.entries,
    kind: literal("photo"),
    bleed: pipe(
      array(picklist(["top", "bottom", "left", "right"])),
      check((edges) => new Set(edges).size === edges.length),
    ),
    contentBox: box,
    visibleBox: box,
  }),
  transform(({ bleed, contentBox, visibleBox, ...geometry }) => ({ ...geometry, presentation: { bleed, contentBox, visibleBox } })),
);
const savedSlot = variant("kind", [object({ ...slot.entries, kind: literal("text") }), photoSlot]);
const frame = object({ x: nonnegative, width: positive, height: positive });
const geometry = pipe(
  object({ width: positive, height: positive, spineStart: positive, spineWidth: positive, front: frame, back: frame }),
  check((value) => value.spineStart + value.spineWidth < value.width),
);
const cover = pipe(
  object({
    placements: array(coverPlacement),
    frontSlotId: string(),
    backSlotId: string(),
    spineOwner: picklist(["front", "back"]),
    pageCount: pipe(nonnegative, safeInteger(), multipleOf(2)),
    geometry,
  }),
  check(
    (value) =>
      value.frontSlotId !== value.backSlotId && value.placements.every((row) => row.slotId === value.frontSlotId || row.slotId === value.backSlotId),
  ),
);
const spread = pipe(
  object({
    id: string(),
    kind: picklist(["album_start", "chapter_start", "photos_only"]),
    templateId: string(),
    bleedState: picklist(["disabled", "enabled", "mixed"]),
    bleedAvailable: boolean(),
    slots: array(savedSlot),
    placements: array(spreadPlacement),
  }),
  check(({ slots, placements }) => {
    const photoSlots = new Set(slots.filter((entry) => entry.kind === "photo").map((entry) => entry.id));
    return new Set(slots.map((entry) => entry.id)).size === slots.length && placements.every((row) => photoSlots.has(row.slotId));
  }),
);

export function readPlacementSnapshot(value: unknown, context: CoverContext): CoverSnapshot;
export function readPlacementSnapshot(value: unknown, context: SpreadContext): SpreadSnapshot;
export function readPlacementSnapshot(value: unknown, context: PlacementContext): PlacementSnapshot;
export function readPlacementSnapshot(value: unknown, context: PlacementContext): PlacementSnapshot {
  if (context.kind === "spread") {
    const { id, kind, templateId, slots, bleedState, bleedAvailable, placements } = parse(spread, value);
    if (id !== context.id) throw new Error("Unexpected spread identity");
    requireUniquePlacements(placements);
    return { kind: "spread", id, placements, layout: { kind, templateId, slots, bleed: { state: bleedState, available: bleedAvailable } } };
  }
  const parsed = parse(cover, value);
  requireUniquePlacements(parsed.placements);
  return { kind: "cover", ...parsed };
}

function requireUniquePlacements(placements: readonly Placement[]) {
  if (
    new Set(placements.map((row) => row.id)).size !== placements.length ||
    new Set(placements.map((row) => row.slotId)).size !== placements.length ||
    new Set(placements.map((row) => row.photoId)).size !== placements.length
  )
    throw new Error("Duplicate placement");
}
