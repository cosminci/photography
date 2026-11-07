import { describe, expect, it } from "vitest";
import {
  findSelection,
  findReaderNavigation,
  findSpreadNavigation,
  findSpreadPosition,
  findDeletionSelection,
  findExportDestination,
} from "../../../src/features/albums/workspace/WorkspaceSelection";
import type { Spread } from "../../../src/features/albums/model/Workspace";

const layout = { templateId: "layout", bleed: { state: "disabled" as const, available: true }, slots: [], placements: [] };
const opening: Spread = { ...layout, id: "album-opening", kind: "album_start", chapter: null };
const chapterOpening: Spread = { ...layout, id: "chapter-opening", kind: "chapter_start", chapter: "chapter" };
const photo: Spread = { ...layout, id: "photo", kind: "photos_only", chapter: "chapter" };
const lastPhoto: Spread = { ...layout, id: "last-photo", kind: "photos_only", chapter: "chapter" };
const homeOpening: Spread = { ...layout, id: "home-opening", kind: "chapter_start", chapter: "home" };
const interior = [opening, chapterOpening, photo];
const readerBook = [...interior, homeOpening];

describe("WorkspaceSelection", () => {
  it.each([
    { target: { kind: "album" }, expected: { label: "Album synopsis", part: null, slot: undefined } },
    { target: { kind: "chapter", chapter: "coast" }, expected: { label: "Coast", part: "coast", slot: undefined } },
    { target: { kind: "chapter", chapter: "removed" }, expected: undefined },
    { target: { kind: "slot", spread: "opening", slot: "photo" }, expected: { label: "Spread 1 Slot 1", part: null, slot: "photo" } },
    { target: { kind: "slot", spread: "chapter-opening", slot: "photo" }, expected: { label: "Spread 2 Slot 1", part: "coast", slot: "photo" } },
    { target: { kind: "slot", spread: "photos", slot: "second" }, expected: { label: "Spread 3 Slot 2", part: "photos", slot: "second" } },
    { target: { kind: "slot", spread: "photos", slot: "removed" }, expected: undefined },
    { target: { kind: "slot", spread: "removed", slot: "photo" }, expected: undefined },
    { target: { kind: "cover", slot: "front" }, expected: { label: "Cover", part: "cover", slot: "front" } },
    { target: { kind: "photo", photo: "removed" }, expected: undefined },
  ])("should resolve export issue $target against current numbered photo slots", ({ target, expected }) => {
    const geometry = {
      ...layout,
      slots: [
        { id: "text", kind: "text" as const, x: 0, y: 0, width: 10, height: 10 },
        { id: "photo", kind: "photo" as const, x: 10, y: 0, width: 10, height: 10 },
        { id: "second", kind: "photo" as const, x: 20, y: 0, width: 10, height: 10 },
      ],
    };
    const spreads: Spread[] = [
      { ...geometry, id: "opening", kind: "album_start", chapter: null },
      { ...geometry, id: "chapter-opening", kind: "chapter_start", chapter: "coast" },
      { ...geometry, id: "photos", kind: "photos_only", chapter: "coast" },
    ];
    const chapters = [
      {
        id: "coast",
        name: "Coast",
        kind: "home" as const,
        countries: [],
        start: "2025-01-01",
        end: "2025-12-31",
        sourceFolder: "/photos",
        description: "",
      },
    ];
    const issue = { code: "low_resolution", severity: "warning" as const, target, message: "Saved diagnostic." };

    expect(findExportDestination({ spreads, chapters }, issue)).toEqual(expected);
  });

  it.each([
    { scenario: "album opening", index: 0, expected: null },
    { scenario: "chapter opening", index: 1, expected: "chapter" },
    { scenario: "photo spread", index: 2, expected: "photo" },
    { scenario: "past the last spread", index: 3, expected: "photo" },
  ])("should retain the deletion index at $scenario", ({ index, expected }) => {
    expect(findDeletionSelection(interior, index)).toBe(expected);
  });

  it("should reject deletion selection from an empty composition rather than inventing a destination", () => {
    expect(() => findDeletionSelection([], 0)).toThrow("Saved composition has no selectable spread");
  });

  it.each([
    { spread: opening, expected: { chapter: { position: 1, total: 1 }, album: { position: 1, total: 5 } } },
    { spread: chapterOpening, expected: { chapter: { position: 1, total: 3 }, album: { position: 2, total: 5 } } },
    { spread: photo, expected: { chapter: { position: 2, total: 3 }, album: { position: 3, total: 5 } } },
    { spread: lastPhoto, expected: { chapter: { position: 3, total: 3 }, album: { position: 4, total: 5 } } },
    { spread: homeOpening, expected: { chapter: { position: 1, total: 1 }, album: { position: 5, total: 5 } } },
  ])("should count openings and exclude Cover for $spread.id", ({ spread, expected }) => {
    const spreads = [opening, chapterOpening, photo, lastPhoto, homeOpening];

    expect(findSpreadPosition(spreads, spread)).toEqual(expected);
  });

  it("should resolve URL parts through persisted openings and spread ownership without inventing missing values", () => {
    expect(findSelection(interior, null)).toEqual({ kind: "spread", spread: opening });
    expect(findSelection(interior, "cover")).toEqual({ kind: "cover" });
    expect(findSelection(interior, "chapter")).toEqual({ kind: "spread", spread: chapterOpening });
    expect(findSelection(interior, "photo")).toEqual({ kind: "spread", spread: photo });
    expect(findSelection(interior, "foreign")).toEqual({ kind: "invalid" });
    expect(findSelection([], null)).toEqual({ kind: "invalid" });
  });

  it.each([
    { part: "cover", expected: { previous: undefined, next: null } },
    { part: null, expected: { previous: "cover", next: "chapter-opening" } },
    { part: "chapter", expected: { previous: null, next: "photo" } },
    { part: "photo", expected: { previous: "chapter-opening", next: "home-opening" } },
    { part: "home", expected: { previous: "photo", next: undefined } },
    { part: "foreign", expected: { previous: undefined, next: undefined } },
  ])("should traverse persisted Reader order from $part without wrapping", ({ part, expected }) => {
    expect(findReaderNavigation(readerBook, findSelection(readerBook, part))).toEqual(expected);
  });

  it("should navigate an album opening to Cover and keep an empty workspace unavailable", () => {
    const spreads = [opening];
    const emptyNavigation = { position: undefined, total: 0, first: undefined, previous: undefined, next: undefined, last: undefined };

    expect(findReaderNavigation(spreads, findSelection(spreads, null))).toEqual({ previous: "cover", next: undefined });
    expect(findSpreadNavigation([], findSelection([], null))).toEqual(emptyNavigation);
  });

  it.each([
    { part: null, expected: { position: 1, total: 3, first: undefined, previous: undefined, next: "chapter", last: "photo" } },
    { part: "chapter", expected: { position: 2, total: 3, first: null, previous: null, next: "photo", last: "photo" } },
    { part: "chapter-opening", expected: { position: 2, total: 3, first: null, previous: null, next: "photo", last: "photo" } },
    { part: "photo", expected: { position: 3, total: 3, first: null, previous: "chapter", next: undefined, last: undefined } },
    { part: "foreign", expected: { position: undefined, total: 3, first: undefined, previous: undefined, next: undefined, last: undefined } },
    { part: "cover", expected: { position: undefined, total: 3, first: undefined, previous: undefined, next: undefined, last: undefined } },
  ])("should derive above-editor spread destinations from $part without wrapping or navigating Cover", ({ part, expected }) => {
    expect(findSpreadNavigation(interior, findSelection(interior, part))).toEqual(expected);
  });
});
