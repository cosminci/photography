import { describe, expect, it } from "vitest";
import { composeSpread, snapshotSpread } from "../../../src/features/albums/model/Workspace";
import type { SpreadSnapshot } from "../../../src/features/albums/model/Placement";

describe("saved workspace spread projection", () => {
  it.each([
    { kind: "album_start", chapter: null },
    { kind: "chapter_start", chapter: "chapter" },
    { kind: "photos_only", chapter: "chapter" },
  ] as const)("should retain saved geometry and membership for $kind and remove membership from placement snapshots", ({ kind, chapter }) => {
    const saved: SpreadSnapshot = {
      kind: "spread",
      id: "spread",
      placements: [],
      layout: { kind, templateId: "template", slots: [], bleed: { state: "disabled", available: false } },
    };
    const composed = {
      id: "spread",
      kind,
      chapter,
      templateId: "template",
      slots: [],
      placements: [],
      bleed: { state: "disabled" as const, available: false },
    };

    expect(composeSpread(saved, chapter)).toEqual(composed);
    expect(snapshotSpread(composeSpread(saved, chapter))).toEqual(saved);
  });

  it.each([
    { kind: "album_start", chapter: "chapter" },
    { kind: "chapter_start", chapter: null },
    { kind: "photos_only", chapter: null },
  ] as const)("should reject $kind geometry with incompatible chapter membership", ({ kind, chapter }) => {
    const saved: SpreadSnapshot = {
      kind: "spread",
      id: "spread",
      placements: [],
      layout: { kind, templateId: "template", slots: [], bleed: { state: "disabled", available: false } },
    };

    expect(() => composeSpread(saved, chapter)).toThrow("Spread kind does not match chapter membership");
  });
});
