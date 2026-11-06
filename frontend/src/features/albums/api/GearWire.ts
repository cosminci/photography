import { array, check, literal, looseObject, object, parse, picklist, pipe, transform, variant } from "valibot";
import { gearMounts, sensorFormats, type Camera, type CameraSelection, type GearCatalog, type Lens } from "../model/Gear";
import { nonemptyText } from "./Wire";

const mount = picklist(gearMounts.map((entry) => entry.id));
const format = picklist(sensorFormats.map((entry) => entry.id));
const identity = { id: nonemptyText, code: nonemptyText, name: nonemptyText };
const camera = variant("kind", [
  pipe(
    looseObject({ ...identity, kind: literal("fixed_lens") }),
    check((value) => !("mount" in value) && !("sensorFormat" in value)),
    transform(({ id, code, name, kind }) => ({ id, code, name, kind })),
  ),
  object({ ...identity, kind: literal("interchangeable_lens"), mount, sensorFormat: format }),
]);
const lens = object({ ...identity, mount, coverage: format });
const catalog = pipe(
  object({ cameras: array(camera), lenses: array(lens) }),
  check(
    ({ cameras, lenses }) =>
      new Set(cameras.map((entry) => entry.id)).size === cameras.length && new Set(lenses.map((entry) => entry.id)).size === lenses.length,
  ),
);
const selection = pipe(
  array(
    object({
      cameraId: nonemptyText,
      lensIds: pipe(
        array(nonemptyText),
        check((ids) => new Set(ids).size === ids.length),
      ),
    }),
  ),
  check((cameras) => new Set(cameras.map((entry) => entry.cameraId)).size === cameras.length),
);

export const readGearCatalog = (value: unknown): GearCatalog => parse(catalog, value);
export const readCamera = (value: unknown): Camera => parse(camera, value);
export const readLens = (value: unknown): Lens => parse(lens, value);
export const readChapterGear = (value: unknown): readonly CameraSelection[] => parse(object({ cameras: selection }), value).cameras;
