import { gearMounts, sensorFormats, type Camera, type CameraSelection, type GearCatalog, type Lens } from "../model/Gear";
import { readArray, readRecord, readText } from "./Wire";

export function readGearCatalog(value: unknown): GearCatalog {
  const fields = readRecord(value);
  const cameras = readArray(fields["cameras"]).map(readCamera);
  const lenses = readArray(fields["lenses"]).map(readLens);
  if (new Set(cameras.map((entry) => entry.id)).size !== cameras.length || new Set(lenses.map((entry) => entry.id)).size !== lenses.length)
    throw new Error("Duplicate catalog identities");
  return { cameras, lenses };
}

export function readCamera(value: unknown): Camera {
  const fields = readRecord(value);
  const common = { id: readGearText(fields["id"]), code: readGearText(fields["code"]), name: readGearText(fields["name"]) };
  if (fields["kind"] === "fixed_lens") {
    if ("mount" in fields || "sensorFormat" in fields) throw new Error("Fixed optics cannot contain mount or sensor format");
    return { ...common, kind: "fixed_lens" };
  }
  if (fields["kind"] !== "interchangeable_lens") throw new Error("Unknown camera kind");
  return {
    ...common,
    kind: "interchangeable_lens",
    mount: readChoice(fields["mount"], gearMounts),
    sensorFormat: readChoice(fields["sensorFormat"], sensorFormats),
  };
}

export function readLens(value: unknown): Lens {
  const fields = readRecord(value);
  return {
    id: readGearText(fields["id"]),
    code: readGearText(fields["code"]),
    name: readGearText(fields["name"]),
    mount: readChoice(fields["mount"], gearMounts),
    coverage: readChoice(fields["coverage"], sensorFormats),
  };
}

export function readChapterGear(value: unknown): readonly CameraSelection[] {
  const cameras = readArray(readRecord(value)["cameras"]).map((entry) => {
    const fields = readRecord(entry);
    const lensIds = readArray(fields["lensIds"]).map(readGearText);
    if (new Set(lensIds).size !== lensIds.length) throw new Error("Duplicate selected lenses");
    return { cameraId: readGearText(fields["cameraId"]), lensIds };
  });
  if (new Set(cameras.map((row) => row.cameraId)).size !== cameras.length) throw new Error("Duplicate selected cameras");
  return cameras;
}

function readGearText(value: unknown): string {
  const text = readText(value);
  if (text === "") throw new Error("Expected a nonempty gear value");
  return text;
}

function readChoice<T extends string>(value: unknown, choices: readonly { id: T }[]): T {
  const choice = choices.find((item) => item.id === value);
  if (choice === undefined) throw new Error("Unknown gear compatibility");
  return choice.id;
}
