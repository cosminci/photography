import { describe, expect, it } from "vitest";
import {
  buildCameraEdit,
  buildCatalogCommand,
  buildLensEdit,
  matchesCatalogCommand,
  type CatalogEdit,
  type CatalogCommand,
} from "../../../src/features/albums/workspace/CatalogEdit";
import type { Camera, Lens } from "../../../src/features/albums/model/Gear";

const body: Camera = { id: "body", code: "original body code", name: "Body", kind: "interchangeable_lens", mount: "sony_e", sensorFormat: "aps_c" };
const fixed: Camera = { id: "fixed", code: "original fixed code", name: "Fixed", kind: "fixed_lens" };
const lens: Lens = { id: "lens", code: "original lens code", name: "Lens", mount: "nikon_z", coverage: "full_frame" };
const edit: CatalogEdit = {
  kind: "camera",
  maybeId: undefined,
  maybeCode: undefined,
  name: "  山  ",
  cameraKind: "fixed_lens",
  mount: "",
  format: "",
};

describe("catalog form commands", () => {
  it.each([fixed, body])("should preserve $kind code and compatibility on camera edits", (camera) => {
    expect(buildCatalogCommand(buildCameraEdit(camera), false)).toEqual({
      kind: "camera",
      operation: "save",
      maybeId: camera.id,
      details: { ...camera, id: undefined },
    });
  });
  it("should preserve the lens code and use exact entered names as new codes", () => {
    expect(buildLensEdit(lens)).toEqual({
      kind: "lens",
      maybeId: lens.id,
      maybeCode: lens.code,
      name: lens.name,
      cameraKind: "interchangeable_lens",
      mount: lens.mount,
      format: lens.coverage,
    });
    expect(buildCatalogCommand(edit, false)).toEqual({
      kind: "camera",
      operation: "save",
      maybeId: undefined,
      details: { code: edit.name, name: edit.name, kind: "fixed_lens" },
    });
    expect(buildCatalogCommand({ ...edit, kind: "lens", mount: "sony_e", format: "aps_c" }, false)).toEqual({
      kind: "lens",
      operation: "save",
      maybeId: undefined,
      details: { code: edit.name, name: edit.name, mount: "sony_e", coverage: "aps_c" },
    });
    expect(buildCatalogCommand(buildLensEdit(lens), false)).toEqual({
      kind: "lens",
      operation: "save",
      maybeId: lens.id,
      details: { code: lens.code, name: lens.name, mount: lens.mount, coverage: lens.coverage },
    });
  });
  it.each([
    { value: edit, deleting: true, error: "Choose saved gear before deleting." },
    { value: { ...edit, name: "" }, deleting: false, error: "Enter a name." },
    { value: { ...edit, name: " \n " }, deleting: false, error: "Enter a name." },
    { value: { ...edit, cameraKind: "interchangeable_lens" as const }, deleting: false, error: "Choose a mount and sensor format or lens coverage." },
    { value: { ...edit, kind: "lens" as const, mount: "sony_e" }, deleting: false, error: "Choose a mount and sensor format or lens coverage." },
  ])("should validate $value with $error", ({ value, deleting, error }) => {
    expect(buildCatalogCommand(value, deleting)).toBe(error);
  });
  it("should reconcile delete and complete save facts without confusing resource kinds or partial values", () => {
    const deletion: CatalogCommand = { kind: "camera", operation: "delete", id: fixed.id };
    const bodyCommand: CatalogCommand = { kind: "camera", operation: "save", maybeId: body.id, details: body };
    const fixedCommand: CatalogCommand = { kind: "camera", operation: "save", maybeId: fixed.id, details: fixed };
    const lensCommand: CatalogCommand = { kind: "lens", operation: "save", maybeId: lens.id, details: lens };

    expect(buildCatalogCommand(buildCameraEdit(fixed), true)).toEqual(deletion);
    expect(matchesCatalogCommand(undefined, deletion)).toBe(true);
    expect(matchesCatalogCommand(fixed, deletion)).toBe(false);
    expect(matchesCatalogCommand(undefined, fixedCommand)).toBe(false);
    expect(matchesCatalogCommand({ ...fixed, code: "changed" }, fixedCommand)).toBe(false);
    expect(matchesCatalogCommand({ ...fixed, name: "changed" }, fixedCommand)).toBe(false);
    expect(matchesCatalogCommand(fixed, fixedCommand)).toBe(true);
    expect(matchesCatalogCommand(body, bodyCommand)).toBe(true);
    expect(matchesCatalogCommand({ ...body, mount: "nikon_z" }, bodyCommand)).toBe(false);
    expect(matchesCatalogCommand({ ...body, sensorFormat: "full_frame" }, bodyCommand)).toBe(false);
    expect(matchesCatalogCommand(fixed, bodyCommand)).toBe(false);
    expect(matchesCatalogCommand(lens, bodyCommand)).toBe(false);
    expect(matchesCatalogCommand({ ...lens, code: body.code, name: body.name }, bodyCommand)).toBe(false);
    expect(matchesCatalogCommand(lens, lensCommand)).toBe(true);
    expect(matchesCatalogCommand({ ...lens, mount: "sony_e" }, lensCommand)).toBe(false);
    expect(matchesCatalogCommand({ ...lens, coverage: "aps_c" }, lensCommand)).toBe(false);
    expect(matchesCatalogCommand(body, lensCommand)).toBe(false);
    expect(matchesCatalogCommand({ ...body, code: lens.code, name: lens.name }, lensCommand)).toBe(false);
  });
});
