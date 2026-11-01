import { describe, expect, it } from "vitest";
import { readCamera, readChapterGear, readGearCatalog, readLens } from "../../../src/features/albums/api/GearWire";

const fixed = { id: "fixed", code: "code", name: "Fixed", kind: "fixed_lens" };
const body = { id: "body", code: "body", name: "Body", kind: "interchangeable_lens", mount: "nikon_z", sensorFormat: "full_frame" };
const lens = { id: "lens", code: "lens", name: "Lens", mount: "sony_e", coverage: "aps_c" };

describe("gear resource decoding", () => {
  it("should retain complete ordered catalogs and explicitly empty selections", () => {
    expect(readGearCatalog({ cameras: [fixed, body], lenses: [lens] })).toEqual({ cameras: [fixed, body], lenses: [lens] });
    expect(readGearCatalog({ cameras: [], lenses: [] })).toEqual({ cameras: [], lenses: [] });
    expect(readChapterGear({ cameras: [] })).toEqual([]);
    const shared = [
      { cameraId: "a", lensIds: ["x", "y"] },
      { cameraId: "b", lensIds: ["x"] },
    ];
    expect(readChapterGear({ cameras: shared })).toEqual(shared);
  });
  it.each([
    { cameras: [fixed, fixed], lenses: [] },
    { cameras: [], lenses: [lens, lens] },
  ])("should reject duplicate catalog identities in $cameras/$lenses", (value) => {
    expect(() => readGearCatalog(value)).toThrow("Duplicate catalog identities");
  });
  it.each([
    { ...fixed, mount: null },
    { ...fixed, sensorFormat: null },
    { ...fixed, kind: "interchangeable" },
    { ...body, mount: "unknown" },
    { ...body, sensorFormat: "unknown" },
    { ...fixed, code: "" },
    { ...fixed, name: "" },
    { ...fixed, id: "" },
  ])("should reject malformed camera $value", (value) => {
    expect(() => readCamera(value)).toThrow();
  });
  it.each([
    { ...lens, coverage: "unknown" },
    { ...lens, mount: "unknown" },
    { ...lens, name: "" },
  ])("should reject malformed lens $value", (value) => {
    expect(() => readLens(value)).toThrow();
  });
  it.each([
    { cameras: [{ cameraId: "a", lensIds: ["x", "x"] }] },
    {
      cameras: [
        { cameraId: "a", lensIds: [] },
        { cameraId: "a", lensIds: [] },
      ],
    },
    { cameras: [{ cameraId: "a" }] },
    { cameras: [{ cameraId: "", lensIds: [] }] },
  ])("should reject malformed chapter selection $cameras", (value) => {
    expect(() => readChapterGear(value)).toThrow();
  });
});
