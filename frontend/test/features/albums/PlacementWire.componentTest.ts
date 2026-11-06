import { describe, expect, expectTypeOf, it, vi } from "vitest";
import { readPlacementSnapshot, readTransform } from "../../../src/features/albums/api/PlacementWire";
import { readPhotoChoice } from "../../../src/features/albums/workspace/PhotoDrag";
import { makeHttpAlbumClient } from "../../../src/features/albums/api/HttpAlbumClient";
import { readPhotoInventory } from "../../../src/features/albums/api/PhotoWire";
import type { CoverSnapshot, SpreadSnapshot } from "../../../src/features/albums/model/Placement";
import { coverGeometries } from "./CoverGeometry";

describe("placement representation", () => {
  const transform = { x: 10, y: -20, zoom: 2, mirrored: true };
  const placement = { id: "p", slotId: "front", photoId: "photo", transform };
  const layout = {
    kind: "photos_only" as const,
    templateId: "layout",
    bleed: { state: "disabled" as const, available: true },
    slots: [
      {
        id: "front",
        kind: "photo" as const,
        x: 0,
        y: 0,
        width: 100,
        height: 100,
        presentation: { bleed: [], contentBox: { x: 1, y: 1, width: 98, height: 98 }, visibleBox: { x: 1, y: 1, width: 98, height: 98 } },
      },
    ],
  };
  const wireLayout = {
    ...layout,
    bleedState: layout.bleed.state,
    bleedAvailable: layout.bleed.available,
    slots: layout.slots.map(({ presentation, ...slot }) => ({ ...slot, ...presentation })),
  };
  const box = { x: 1, y: 1, width: 98, height: 98 };
  const projectedSlot = { id: "front", kind: "photo", x: 0, y: 0, width: 100, height: 100, bleed: [], contentBox: box, visibleBox: box };

  it.each(["front", "back"] as const)("should retain authoritative %s-owned print geometry without recomputing dimensions", (spineOwner) => {
    const geometry = coverGeometries[spineOwner];
    const saved = readPlacementSnapshot(
      { frontSlotId: "front", backSlotId: "back", spineOwner, pageCount: 24, placements: [], geometry },
      { kind: "cover" },
    );

    expect(saved).toHaveProperty("geometry", geometry);
    expect(saved.pageCount).toBe(24);
  });

  it.each([
    undefined,
    { ...coverGeometries.front, width: 0 },
    { ...coverGeometries.front, height: 0 },
    { ...coverGeometries.front, spineStart: 0 },
    { ...coverGeometries.front, spineWidth: 0 },
    { ...coverGeometries.front, spineWidth: 2400 },
    { ...coverGeometries.front, front: { x: -1, width: 100, height: 720 } },
    { ...coverGeometries.front, front: { x: 0, width: 0, height: 720 } },
    { ...coverGeometries.front, back: { x: 0, width: 100, height: 0 } },
  ])("should reject missing or malformed authoritative cover geometry %s", (geometry) => {
    expect(() =>
      readPlacementSnapshot(
        { frontSlotId: "front", backSlotId: "back", spineOwner: "front", pageCount: 30, placements: [], geometry },
        { kind: "cover" },
      ),
    ).toThrow();
  });

  it.each([undefined, -2, 1, 1.5, Number.MAX_SAFE_INTEGER + 1])("should reject invalid actual cover page count %s", (pageCount) => {
    expect(() =>
      readPlacementSnapshot(
        { frontSlotId: "front", backSlotId: "back", spineOwner: "front", pageCount, placements: [], geometry: coverGeometries.front },
        { kind: "cover" },
      ),
    ).toThrow();
  });

  it.each(["top", "bottom", "left", "right"])("should preserve the saved %s direction without inferring another bleed selection", (edge) => {
    const saved = { id: "spread", ...wireLayout, placements: [], slots: [{ ...projectedSlot, bleed: [edge] }] };
    const result = readPlacementSnapshot(saved, { kind: "spread", id: "spread" });

    expect(result.layout.slots).toEqual([
      { id: "front", kind: "photo", x: 0, y: 0, width: 100, height: 100, presentation: { bleed: [edge], contentBox: box, visibleBox: box } },
    ]);
  });

  it.each([
    { fields: { bleedState: "unknown" }, message: "Invalid spread bleed state" },
    { fields: { bleedAvailable: "yes" }, message: "Invalid spread bleed state" },
    { fields: { slots: [{ ...projectedSlot, bleed: ["inner"] }] }, message: "Invalid saved bleed direction" },
    { fields: { slots: [{ ...projectedSlot, bleed: ["left", "left"] }] }, message: "Duplicate saved bleed direction" },
    ...[
      { ...box, x: -1 },
      { ...box, y: -1 },
      { ...box, width: 0 },
      { ...box, height: 0 },
    ].map((contentBox) => ({ fields: { slots: [{ ...projectedSlot, contentBox }] }, message: "Invalid saved photo box" })),
  ])("should reject incomplete or malformed authoritative projection $fields", ({ fields, message }) => {
    const saved = { id: "spread", ...wireLayout, placements: [], ...fields };
    expect(() => readPlacementSnapshot(saved, { kind: "spread", id: "spread" })).toThrow(message);
  });

  it("should reject bleed or spine commands sent to the wrong composition without issuing a write", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const client = makeHttpAlbumClient(fetcher);

    await expect(client.editPlacements("album", { kind: "cover" }, { operation: "bleed", enabled: true })).rejects.toThrow(
      "Bleed selection belongs to interior spreads",
    );
    await expect(client.editPlacements("album", { kind: "spread", id: "spread" }, { operation: "spineOwner", owner: "back" })).rejects.toThrow(
      "Spine ownership belongs to the cover",
    );
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("should read an individual referenced photograph through its encoded public photo resource", async () => {
    const photo = {
      id: "photo/id",
      filename: "Mountains.jpg",
      capturedLocal: null,
      width: 4000,
      height: 2000,
      caption: "Saved caption",
      thumbnail: { url: "/thumbnail.jpg", width: 400, height: 200 },
    };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(photo)));

    expect(await makeHttpAlbumClient(fetcher).photo(photo.id)).toEqual(photo);
    expect(fetcher).toHaveBeenCalledExactlyOnceWith("/api/photos/photo%2Fid", { method: "GET", headers: { Accept: "application/json" } });
  });

  it("should preserve saved directions and authoritative visible and caption boxes without changing template geometry", () => {
    const raw = { id: "front", kind: "photo", x: 0, y: 0, width: 100, height: 100 };
    const contentBox = { x: 0.3, y: 0.9, width: 99.4, height: 98.2 };
    const visibleBox = { x: 0, y: 0.9, width: 99.7, height: 98.2 };
    const saved = {
      id: "spread",
      kind: "photos_only",
      templateId: "layout",
      bleedState: "mixed",
      bleedAvailable: true,
      slots: [{ ...raw, bleed: ["left"], contentBox, visibleBox }],
      placements: [],
    };

    expect(readPlacementSnapshot(saved, { kind: "spread", id: "spread" })).toEqual({
      kind: "spread",
      id: "spread",
      layout: {
        kind: "photos_only",
        templateId: "layout",
        bleed: { state: "mixed", available: true },
        slots: [{ ...raw, presentation: { bleed: ["left"], contentBox, visibleBox } }],
      },
      placements: [],
    });
  });

  it("should route bleed and spine ownership through the existing acknowledged composition saves", async () => {
    const spread = {
      id: "spread",
      kind: "photos_only",
      templateId: "layout",
      bleedState: "disabled",
      bleedAvailable: false,
      slots: [],
      placements: [],
    };
    const cover = { frontSlotId: "front", backSlotId: "back", pageCount: 30, geometry: coverGeometries.back, spineOwner: "back", placements: [] };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(spread)))
      .mockResolvedValueOnce(new Response(JSON.stringify(cover)));
    const client = makeHttpAlbumClient(fetcher);

    await client.editPlacements("album", { kind: "spread", id: "spread" }, { operation: "bleed", enabled: true });
    await client.editPlacements("album", { kind: "cover" }, { operation: "spineOwner", owner: "back" });

    expect(fetcher.mock.calls.map(([url, init]) => [url, init?.method, init?.body])).toEqual([
      ["/api/albums/album/spreads/spread/bleed", "PUT", '{"enabled":true}'],
      ["/api/albums/album/cover/spine-owner", "PUT", '{"owner":"back"}'],
    ]);
  });

  it("decodes cover ownership and interior caption corners", () => {
    const cover = {
      frontSlotId: "front",
      backSlotId: "back",
      pageCount: 30,
      geometry: coverGeometries.back,
      spineOwner: "back",
      placements: [placement],
    };
    const spread = { id: "spread", ...wireLayout, placements: [{ ...placement, captionCorner: "top_left" }] };
    const expectedCover: CoverSnapshot = {
      ...cover,
      kind: "cover",
      pageCount: 30,
      geometry: coverGeometries.back,
      spineOwner: "back",
      placements: [{ ...placement, kind: "cover" }],
    };
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
    { frontSlotId: "front", backSlotId: "front", pageCount: 30, geometry: coverGeometries.front, spineOwner: "front", placements: [] },
    { frontSlotId: "front", backSlotId: "back", spineOwner: "other", placements: [] },
    { frontSlotId: "a", backSlotId: "b", pageCount: 30, geometry: coverGeometries.front, spineOwner: "front", placements: [placement] },
    {
      frontSlotId: "front",
      backSlotId: "back",
      pageCount: 30,
      geometry: coverGeometries.front,
      spineOwner: "front",
      placements: [placement, placement],
    },
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
    const saved = { id: "spread", ...wireLayout, placements: [{ ...placement, slotId: "missing", captionCorner: "top_left" }] };

    expect(() => readPlacementSnapshot(saved, { kind: "spread", id: "spread" })).toThrow("Invalid spread placement slot");
  });

  it("should reject duplicate slot identities even when no photos are placed", () => {
    const saved = { id: "spread", ...wireLayout, slots: [...wireLayout.slots, ...wireLayout.slots], placements: [] };

    expect(() => readPlacementSnapshot(saved, { kind: "spread", id: "spread" })).toThrow("Invalid spread placement slot");
  });

  it("uses the actual placement paths and JSON commands for both contexts", async () => {
    const cover = {
      frontSlotId: "front",
      backSlotId: "back",
      pageCount: 30,
      geometry: coverGeometries.back,
      spineOwner: "back",
      placements: [placement],
    };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(cover)))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: "spread", ...wireLayout, placements: [] })));
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
