import { gearMounts, sensorFormats, type Camera, type CameraDetails, type Lens, type LensDetails } from "../model/Gear";

export interface CatalogEdit {
  readonly kind: "camera" | "lens";
  readonly maybeId: string | undefined;
  readonly maybeCode: string | undefined;
  readonly name: string;
  readonly cameraKind: CameraDetails["kind"];
  readonly mount: string;
  readonly format: string;
}
export type CatalogCommand =
  | { readonly kind: "camera"; readonly operation: "save"; readonly maybeId: string | undefined; readonly details: CameraDetails }
  | { readonly kind: "lens"; readonly operation: "save"; readonly maybeId: string | undefined; readonly details: LensDetails }
  | { readonly kind: "camera" | "lens"; readonly operation: "delete"; readonly id: string };

export function buildCatalogCommand(edit: CatalogEdit, deleting: boolean): CatalogCommand | string {
  if (deleting) return edit.maybeId === undefined ? "Choose saved gear before deleting." : { kind: edit.kind, operation: "delete", id: edit.maybeId };
  if (edit.name.trim() === "") return "Enter a name.";
  const code = edit.maybeCode ?? edit.name;
  const common = { code, name: edit.name };
  if (edit.kind === "camera" && edit.cameraKind === "fixed_lens")
    return { kind: "camera", operation: "save", maybeId: edit.maybeId, details: { ...common, kind: "fixed_lens" } };
  const mount = gearMounts.find((item) => item.id === edit.mount);
  const format = sensorFormats.find((item) => item.id === edit.format);
  if (mount === undefined || format === undefined) return "Choose a mount and sensor format or lens coverage.";
  return edit.kind === "camera"
    ? {
        kind: "camera",
        operation: "save",
        maybeId: edit.maybeId,
        details: { ...common, kind: "interchangeable_lens", mount: mount.id, sensorFormat: format.id },
      }
    : { kind: "lens", operation: "save", maybeId: edit.maybeId, details: { ...common, mount: mount.id, coverage: format.id } };
}

export function matchesCatalogCommand(entry: Camera | Lens | undefined, command: CatalogCommand): boolean {
  if (command.operation === "delete") return entry === undefined;
  if (entry === undefined) return false;
  if (entry.code !== command.details.code || entry.name !== command.details.name) return false;
  if (command.kind === "lens") return !("kind" in entry) && entry.mount === command.details.mount && entry.coverage === command.details.coverage;
  if (!("kind" in entry)) return false;
  if (command.details.kind === "fixed_lens") return entry.kind === "fixed_lens";
  return entry.kind === "interchangeable_lens" && entry.mount === command.details.mount && entry.sensorFormat === command.details.sensorFormat;
}

export function buildCameraEdit(camera: Camera): CatalogEdit {
  return {
    kind: "camera",
    maybeId: camera.id,
    maybeCode: camera.code,
    name: camera.name,
    cameraKind: camera.kind,
    mount: camera.kind === "fixed_lens" ? "" : camera.mount,
    format: camera.kind === "fixed_lens" ? "" : camera.sensorFormat,
  };
}

export function buildLensEdit(lens: Lens): CatalogEdit {
  return {
    kind: "lens",
    maybeId: lens.id,
    maybeCode: lens.code,
    name: lens.name,
    cameraKind: "interchangeable_lens",
    mount: lens.mount,
    format: lens.coverage,
  };
}
