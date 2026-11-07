import { describe, expect, it } from "vitest";
import { derivePhotoRows, derivePhotos, limitPhotoWidth } from "../../../src/features/albums/workspace/PhotoLayout";
import type { Photo, PhotoInventory } from "../../../src/features/albums/model/Photo";

describe("canonical photo selection and chronological whole-image rows", () => {
  const photo: Photo = {
    id: "photo",
    filename: "a.jpg",
    capturedLocal: "2026-01-01T10:00:00",
    width: 1200,
    height: 800,
    caption: null,
    thumbnail: { url: "/thumbnails/key/version.jpg", width: 600, height: 400 },
  };
  const inventory: PhotoInventory = {
    placedPhotoIds: [],
    photos: [photo],
    counts: { unplaced: 0, total: 1 },
    processing: { state: "idle", unsupportedCount: 0, skippedCount: 0, issues: [] },
  };

  it("should combine canonical collections using camera-local order, filename and chapter tie-breaks without deriving counts from photos", () => {
    const later = { ...photo, id: "later", capturedLocal: "2026-01-01T10:00:00.001" };
    const undated = { ...photo, id: "undated", capturedLocal: null };
    const uppercase = { ...photo, id: "upper", filename: "A.jpg", capturedLocal: "2026-01-01T10:00" };
    const collections = {
      z: { ...inventory, photos: [later, undated], counts: { unplaced: 1, total: 2 } },
      b: { ...inventory, photos: [photo, uppercase], counts: { unplaced: 1, total: 2 } },
      a: inventory,
    };
    const expectedOrder = [
      ["b", "upper"],
      ["a", "photo"],
      ["b", "photo"],
      ["z", "later"],
      ["z", "undated"],
    ];

    const combined = derivePhotos(["z", "b", "a"], collections);
    const chapter = derivePhotos(["z"], collections);

    expect(combined.photos.map((item) => [item.chapterId, item.photo.id])).toEqual(expectedOrder);
    expect(combined.counts).toEqual({ unplaced: 2, total: 5 });
    expect(chapter.photos.map((item) => item.photo.id)).toEqual(["later", "undated"]);
    expect(derivePhotos([], {})).toEqual({ photos: [], counts: { unplaced: 0, total: 0 } });
  });

  it.each([200, 320, 320.5, 440.5])("should position every %ipx row without reordering, cropping or a second wrapping interpretation", (width) => {
    const dimensions: readonly (readonly [number, number])[] = [
      [600, 400],
      [400, 600],
      [400, 400],
      [600, 400],
      [400, 600],
    ];
    const photos = dimensions.map(([sourceWidth, sourceHeight], index) => ({
      chapterId: "chapter",
      photo: { ...photo, id: String(index), thumbnail: { ...photo.thumbnail, width: sourceWidth, height: sourceHeight } },
    }));

    const layout = derivePhotoRows(photos, width);
    const rows = layout.rows;

    expect(rows.flatMap((row) => row.items.map((item) => item.photo.photo.id))).toEqual(photos.map((item) => item.photo.id));
    for (const row of rows) {
      expect(row.items.reduce((sum, item) => sum + item.width, 0) + (row.items.length - 1) * 3).toBeCloseTo(width);
      expect(row.items[0]?.left).toBe(0);
      const last = row.items.at(-1);
      expect((last?.left ?? 0) + (last?.width ?? 0)).toBeCloseTo(width);
      for (const item of row.items) {
        expect(item.width / item.height).toBeCloseTo(item.photo.photo.thumbnail.width / item.photo.photo.thumbnail.height);
        expect(item.top).toBeGreaterThanOrEqual(row.top);
        expect(item.top + item.height).toBeLessThanOrEqual(row.top + row.height + 0.000001);
      }
    }
    const lastRow = rows.at(-1);
    expect(layout.height).toBeCloseTo((lastRow?.top ?? 0) + (lastRow?.height ?? 0));
    expect(derivePhotoRows([], width)).toEqual({ height: 0, rows: [] });
  });

  it("should give mixed-row portraits modest emphasis and bound workspace-local pane width", () => {
    const photos = [
      { chapterId: "chapter", photo },
      { chapterId: "chapter", photo: { ...photo, thumbnail: { ...photo.thumbnail, width: 400, height: 600 } } },
    ];

    const rows = derivePhotoRows(photos, 300).rows;
    const heights = rows.flatMap((row) => row.items.map((item) => item.height));
    const boundedWidths = [limitPhotoWidth(100, 1000), limitPhotoWidth(440, 1000), limitPhotoWidth(900, 1000), limitPhotoWidth(320, 300)];

    expect(heights[1]).toBeCloseTo((heights[0] ?? 0) * (1.12 / 0.96));
    expect(boundedWidths).toEqual([200, 440, 628, 200]);
  });

  it("filters authoritative used identities but retains canonical collection metadata", () => {
    const used = { ...inventory, placedPhotoIds: [photo.id] };

    const selected = derivePhotos(["chapter", "missing"], { chapter: used });

    expect(selected.photos).toEqual([]);
    expect(used.photos).toEqual([photo]);
  });
});
