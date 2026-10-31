import { describe, expect, expectTypeOf, it, vi } from "vitest";
import { readPlacementSnapshot, readTransform } from "../../../src/features/albums/api/PlacementWire";
import { readPhotoChoice } from "../../../src/features/albums/workspace/PhotoDrag";
import { makeHttpAlbumClient } from "../../../src/features/albums/api/HttpAlbumClient";
import { readPhotoInventory } from "../../../src/features/albums/api/PhotoWire";
import type { CoverSnapshot, SpreadSnapshot } from "../../../src/features/albums/model/Placement";

describe("placement representation", () => {
  const transform = { x: 10, y: -20, zoom: 2, mirrored: true };
  const placement = { id: "p", slotId: "front", photoId: "photo", transform };
  const layout = {
    kind: "photos_only" as const,
    templateId: "layout",
    slots: [{ id: "front", kind: "photo" as const, x: 0, y: 0, width: 100, height: 100 }],
  };

  it("decodes cover ownership and interior caption corners", () => {
    const cover = { frontSlotId: "front", backSlotId: "back", spineOwner: "back", placements: [placement] };
    const spread = { id: "spread", ...layout, placements: [{ ...placement, captionCorner: "top_left" }] };
    const expectedCover: CoverSnapshot = { ...cover, kind: "cover", spineOwner: "back", placements: [{ ...placement, kind: "cover" }] };
    const expectedSpread: SpreadSnapshot = {
      kind: "spread",
      id: "spread",
      layout,
      placements: [{ ...placement, kind: "spread", captionCorner: "top_left" }],
    };

    const decodedCover = readPlacementSnapshot(cover, { kind: "cover" });
    const decodedSpread = readPlacementSnapshot(spread, { kind: "spread", id: "spread" });

    expectTypeOf(decodedCover).toEqualTypeOf<CoverSnapshot>();
    expectTypeOf(decodedSpread).toEqualTypeOf<SpreadSnapshot>();
    expect(decodedCover).toEqual(expectedCover);
    expect(decodedSpread).toEqual(expectedSpread);
  });

  it.each([
    { ...transform, zoom: 0 },
    { ...transform, zoom: 6 },
    { ...transform, mirrored: "true" },
    { ...transform, x: Infinity },
    { ...transform, y: NaN },
  ])("rejects malformed transform %s", (value) => {
    expect(() => readTransform(value)).toThrow();
  });

  it.each([
    { frontSlotId: "front", backSlotId: "front", spineOwner: "front", placements: [] },
    { frontSlotId: "front", backSlotId: "back", spineOwner: "other", placements: [] },
    { frontSlotId: "a", backSlotId: "b", spineOwner: "front", placements: [placement] },
    { frontSlotId: "front", backSlotId: "back", spineOwner: "front", placements: [placement, placement] },
  ])("rejects malformed cover %s", (value) => {
    expect(() => readPlacementSnapshot(value, { kind: "cover" })).toThrow();
  });

  it.each(["oops", "null", "[]", '{"kind":"other"}', '{"kind":"photo","photoId":1}'])("rejects foreign drag %s", (value) => {
    expect(readPhotoChoice(value)).toBeUndefined();
  });

  it("preserves photo and move identities from native drag data", () => {
    const photoChoice = readPhotoChoice('{"kind":"photo","photoId":"photo"}');
    const moveChoice = readPhotoChoice('{"kind":"move","placementId":"placement"}');

    expect(photoChoice).toEqual({ kind: "photo", photoId: "photo" });
    expect(moveChoice).toEqual({ kind: "move", placementId: "placement" });
  });

  it.each([
    { id: "other", placements: [] },
    { id: "spread", placements: [{ ...placement, captionCorner: "other" }] },
    {
      id: "spread",
      placements: [
        { ...placement, captionCorner: "top_left" },
        { ...placement, id: "other", captionCorner: "top_left" },
      ],
    },
    {
      id: "spread",
      placements: [
        { ...placement, captionCorner: "top_left" },
        { ...placement, id: "other", slotId: "other", captionCorner: "top_left" },
      ],
    },
  ])("rejects foreign spread identities, corners and duplicate associations %s", (value) => {
    expect(() => readPlacementSnapshot(value, { kind: "spread", id: "spread" })).toThrow();
  });

  it("should reject a placement whose slot is absent from the saved spread geometry", () => {
    const saved = { id: "spread", ...layout, placements: [{ ...placement, slotId: "missing", captionCorner: "top_left" }] };

    expect(() => readPlacementSnapshot(saved, { kind: "spread", id: "spread" })).toThrow("Invalid spread placement slot");
  });

  it("should reject duplicate slot identities even when no photos are placed", () => {
    const saved = { id: "spread", ...layout, slots: [...layout.slots, ...layout.slots], placements: [] };

    expect(() => readPlacementSnapshot(saved, { kind: "spread", id: "spread" })).toThrow("Invalid spread placement slot");
  });

  it("uses the actual placement paths and JSON commands for both contexts", async () => {
    const cover = { frontSlotId: "front", backSlotId: "back", spineOwner: "back", placements: [placement] };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(cover)))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: "spread", ...layout, placements: [] })));
    const client = makeHttpAlbumClient(fetcher);
    const expectedRequests = [
      ["/api/albums/album/cover", "GET", undefined],
      ["/api/albums/album/spreads/spread/placements/p", "DELETE", undefined],
    ];

    const savedCover = await client.readPlacements("album", { kind: "cover" });
    const savedSpread = await client.editPlacements("album", { kind: "spread", id: "spread" }, { operation: "remove", placementId: "p" });
    const actualRequests = fetcher.mock.calls.map(([url, init]) => [url, init?.method, init?.body]);

    expectTypeOf(savedCover).toEqualTypeOf<CoverSnapshot>();
    expectTypeOf(savedSpread).toEqualTypeOf<SpreadSnapshot>();
    expect(actualRequests).toEqual(expectedRequests);
  });

  it.each([{ placedPhotoIds: ["photo", "photo"] }, { placedPhotoIds: ["foreign"] }, { placedPhotoIds: ["photo"] }])(
    "rejects inconsistent authoritative used IDs $placedPhotoIds",
    ({ placedPhotoIds }) => {
      const photo = {
        id: "photo",
        filename: "Photo.jpg",
        capturedLocal: null,
        width: 100,
        height: 100,
        caption: null,
        thumbnail: { url: "/photo.jpg", width: 100, height: 100 },
      };
      const inventory = {
        photos: [photo],
        placedPhotoIds,
        counts: { unplaced: 1, total: 1 },
        processing: { state: "idle", unsupportedCount: 0, skippedCount: 0, issues: [] },
      };

      expect(() => readPhotoInventory(inventory)).toThrow("Invalid placed photo identities");
    },
  );
});
