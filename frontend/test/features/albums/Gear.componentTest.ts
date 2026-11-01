import { describe, expect, it } from "vitest";
import {
  describeCompatibility,
  describeGear,
  describeLenses,
  isLensCompatible,
  sameGear,
  validateGear,
  type Camera,
  type GearCatalog,
  type CameraSelection,
  type Lens,
} from "../../../src/features/albums/model/Gear";

const fixed: Camera = { id: "phone", name: "Samsung Galaxy S22", code: "phone", kind: "fixed_lens" };
const body: Camera = { id: "body", name: "Sony body", code: "body", kind: "interchangeable_lens", mount: "sony_e", sensorFormat: "aps_c" };
const lens: Lens = { id: "lens", name: "Sigma 30mm f/2.8 DN Art", code: "lens", mount: "sony_e", coverage: "aps_c" };
const catalog: GearCatalog = { cameras: [fixed, body], lenses: [lens] };

describe("gear selections and presentation", () => {
  it.each([
    { camera: fixed, lens, compatible: false },
    { camera: body, lens, compatible: true },
    { camera: body, lens: { ...lens, coverage: "full_frame" as const }, compatible: true },
    { camera: { ...body, sensorFormat: "full_frame" as const }, lens, compatible: false },
    { camera: { ...body, sensorFormat: "full_frame" as const }, lens: { ...lens, coverage: "full_frame" as const }, compatible: true },
    { camera: body, lens: { ...lens, mount: "nikon_z" as const }, compatible: false },
  ])(
    "should determine compatibility for $camera.kind/$camera.sensorFormat/$lens.mount/$lens.coverage",
    ({ camera, lens: selectedLens, compatible }) => {
      expect(isLensCompatible(camera, selectedLens)).toBe(compatible);
    },
  );
  it.each([
    { selected: [], error: undefined },
    { selected: [{ cameraId: fixed.id, lensIds: [] }], error: undefined },
    { selected: [{ cameraId: body.id, lensIds: [lens.id] }], error: undefined },
    {
      selected: [
        { cameraId: fixed.id, lensIds: [] },
        { cameraId: fixed.id, lensIds: [] },
      ],
      error: "Choose each camera once.",
    },
    { selected: [{ cameraId: "missing", lensIds: [] }], error: "A selected camera is no longer available." },
    { selected: [{ cameraId: body.id, lensIds: [lens.id, lens.id] }], error: "Choose each lens once per camera." },
    { selected: [{ cameraId: fixed.id, lensIds: [lens.id] }], error: "Fixed-lens cameras cannot have selected lenses." },
    { selected: [{ cameraId: body.id, lensIds: [] }], error: "Choose at least one lens for each interchangeable-lens camera." },
    { selected: [{ cameraId: body.id, lensIds: ["missing"] }], error: "Choose compatible lenses for each camera." },
  ])("should validate $selected with $error", ({ selected, error }) => {
    expect(validateGear(selected, catalog)).toBe(error);
  });
  it("should reject a saved lens that is incompatible with the selected camera", () => {
    expect(validateGear([{ cameraId: body.id, lensIds: [lens.id] }], { ...catalog, lenses: [{ ...lens, mount: "nikon_z" }] })).toBe(
      "Choose compatible lenses for each camera.",
    );
  });
  it.each([
    { mount: "sony_e" as const, format: "aps_c" as const, text: "Sony E · APS-C" },
    { mount: "nikon_z" as const, format: "full_frame" as const, text: "Nikon Z · Full frame" },
  ])("should describe $text", ({ mount, format, text }) => {
    expect(describeCompatibility(mount, format)).toBe(text);
  });
  it.each([
    { left: [], right: [], equal: true },
    { left: [{ cameraId: "a", lensIds: ["x", "y"] }], right: [{ cameraId: "a", lensIds: ["x", "y"] }], equal: true },
    { left: [], right: [{ cameraId: "a", lensIds: [] }], equal: false },
    { left: [{ cameraId: "a", lensIds: [] }], right: [{ cameraId: "b", lensIds: [] }], equal: false },
    { left: [{ cameraId: "a", lensIds: ["x"] }], right: [{ cameraId: "a", lensIds: [] }], equal: false },
    { left: [{ cameraId: "a", lensIds: ["x", "y"] }], right: [{ cameraId: "a", lensIds: ["y", "x"] }], equal: false },
  ])("should compare ordered selection $left/$right as $equal", ({ left, right, equal }) => {
    expect(sameGear(left, right)).toBe(equal);
  });
  it("should retain first-selected groups, shared Sigma suffixes and separate lens groups", () => {
    expect(describeLenses(["Sigma 30mm f/2.8 DN Art", "Nikkor 28-400mm f/4-8", "Sigma 60mm f/2.8 DN Art", "Sigma 16mm f/1.4 DC DN"])).toBe(
      "Sigma 30 & 60mm f/2.8 DN Art + Nikkor 28-400mm f/4-8 + Sigma 16mm f/1.4 DC DN",
    );
  });
  it("should group Galaxy models at their first selected member and preserve other cameras and shared lenses", () => {
    const secondPhone = { ...fixed, id: "phone2", code: "phone2", name: "Samsung Galaxy S24 FE" };
    const thirdPhone = { ...fixed, id: "phone3", code: "phone3", name: "Samsung Galaxy S25" };
    const secondBody = { ...body, id: "body2", code: "body2" };
    const selections: readonly CameraSelection[] = [
      { cameraId: fixed.id, lensIds: [] },
      { cameraId: body.id, lensIds: [lens.id] },
      { cameraId: secondPhone.id, lensIds: [] },
      { cameraId: secondBody.id, lensIds: [lens.id] },
      { cameraId: thirdPhone.id, lensIds: [] },
    ];

    expect(describeGear(selections, { cameras: [fixed, body, secondPhone, secondBody, thirdPhone], lenses: [lens] })).toBe(
      "Samsung Galaxy S22 + S24FE + S25\nSony body w/ Sigma 30mm f/2.8 DN Art\nSony body w/ Sigma 30mm f/2.8 DN Art",
    );
    expect(describeGear([{ cameraId: "other", lensIds: [] }], { cameras: [{ ...fixed, id: "other", name: "Other phone" }], lenses: [] })).toBe(
      "Other phone",
    );
    expect(describeGear([], catalog)).toBe("");
    expect(() => describeGear([{ cameraId: "missing", lensIds: [] }], catalog)).toThrow("Saved camera is missing from the catalog");
    expect(() => describeGear([{ cameraId: body.id, lensIds: ["missing"] }], catalog)).toThrow("Saved lens is missing from the catalog");
  });
});
