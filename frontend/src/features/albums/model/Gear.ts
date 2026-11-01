export const gearMounts = [
  { id: "sony_e", name: "Sony E" },
  { id: "nikon_z", name: "Nikon Z" },
] as const;
export const sensorFormats = [
  { id: "aps_c", name: "APS-C" },
  { id: "full_frame", name: "Full frame" },
] as const;
export type GearMount = (typeof gearMounts)[number]["id"];
export type SensorFormat = (typeof sensorFormats)[number]["id"];
export type CameraDetails = { readonly code: string; readonly name: string } & (
  { readonly kind: "fixed_lens" } | { readonly kind: "interchangeable_lens"; readonly mount: GearMount; readonly sensorFormat: SensorFormat }
);
export type Camera = CameraDetails & { readonly id: string };
export interface LensDetails {
  readonly code: string;
  readonly name: string;
  readonly mount: GearMount;
  readonly coverage: SensorFormat;
}
export type Lens = LensDetails & { readonly id: string };
export interface GearCatalog {
  readonly cameras: readonly Camera[];
  readonly lenses: readonly Lens[];
}
export interface CameraSelection {
  readonly cameraId: string;
  readonly lensIds: readonly string[];
}
export interface GearClient {
  readonly gearCatalog: (signal?: AbortSignal) => Promise<GearCatalog>;
  readonly chapterGear: (albumId: string, chapterId: string, signal?: AbortSignal) => Promise<readonly CameraSelection[]>;
  readonly saveChapterGear: (albumId: string, chapterId: string, cameras: readonly CameraSelection[]) => Promise<void>;
  readonly createCamera: (details: CameraDetails) => Promise<Camera>;
  readonly updateCamera: (id: string, details: CameraDetails) => Promise<void>;
  readonly deleteCamera: (id: string) => Promise<void>;
  readonly createLens: (details: LensDetails) => Promise<Lens>;
  readonly updateLens: (id: string, details: LensDetails) => Promise<void>;
  readonly deleteLens: (id: string) => Promise<void>;
}

export function isLensCompatible(camera: Camera, lens: Lens): boolean {
  return camera.kind === "interchangeable_lens" && camera.mount === lens.mount && (camera.sensorFormat === "aps_c" || lens.coverage === "full_frame");
}

export function describeCompatibility(mount: GearMount, format: SensorFormat): string {
  const mountName = mount === "sony_e" ? "Sony E" : "Nikon Z";
  const formatName = format === "aps_c" ? "APS-C" : "Full frame";
  return `${mountName} · ${formatName}`;
}

export function validateGear(selected: readonly CameraSelection[], catalog: GearCatalog): string | undefined {
  if (new Set(selected.map((row) => row.cameraId)).size !== selected.length) return "Choose each camera once.";
  for (const row of selected) {
    const camera = catalog.cameras.find((item) => item.id === row.cameraId);
    if (camera === undefined) return "A selected camera is no longer available.";
    if (new Set(row.lensIds).size !== row.lensIds.length) return "Choose each lens once per camera.";
    if (camera.kind === "fixed_lens") {
      if (row.lensIds.length !== 0) return "Fixed-lens cameras cannot have selected lenses.";
    } else {
      if (row.lensIds.length === 0) return "Choose at least one lens for each interchangeable-lens camera.";
      for (const id of row.lensIds) {
        const lens = catalog.lenses.find((item) => item.id === id);
        if (lens === undefined || !isLensCompatible(camera, lens)) return "Choose compatible lenses for each camera.";
      }
    }
  }
  return undefined;
}

export function sameGear(left: readonly CameraSelection[], right: readonly CameraSelection[]): boolean {
  return (
    left.length === right.length &&
    left.every((row, index) => {
      const other = right[index];
      return (
        row.cameraId === other?.cameraId &&
        row.lensIds.length === other.lensIds.length &&
        row.lensIds.every((id, lensIndex) => id === other.lensIds[lensIndex])
      );
    })
  );
}

export function describeGear(selection: readonly CameraSelection[], catalog: GearCatalog): string {
  const lines: string[] = [];
  const galaxyModels: string[] = [];
  let galaxyLine: number | undefined;
  for (const use of selection) {
    const camera = catalog.cameras.find((item) => item.id === use.cameraId);
    if (camera === undefined) throw new Error("Saved camera is missing from the catalog");
    const lenses = use.lensIds.map((id) => {
      const lens = catalog.lenses.find((item) => item.id === id);
      if (lens === undefined) throw new Error("Saved lens is missing from the catalog");
      return lens.name;
    });
    const galaxy = /^Samsung Galaxy (.+)$/.exec(camera.name)?.[1];
    if (galaxy !== undefined && lenses.length === 0) {
      galaxyModels.push(galaxy.replace(/^(S\d+)\s+FE$/, "$1FE"));
      galaxyLine ??= lines.length;
      lines[galaxyLine] = `Samsung Galaxy ${galaxyModels.join(" + ")}`;
    } else lines.push(lenses.length === 0 ? camera.name : `${camera.name} w/ ${describeLenses(lenses)}`);
  }
  return lines.join("\n");
}

export function describeLenses(names: readonly string[]): string {
  const lines: string[] = [];
  const groups = new Map<string, { index: number; models: string[] }>();
  for (const name of names) {
    const match = /^Sigma (\d+(?:\.\d+)?)mm (.+)$/.exec(name);
    const model = match?.[1];
    const suffix = match?.[2];
    if (model === undefined || suffix === undefined) {
      lines.push(name);
      continue;
    }
    const group = groups.get(suffix);
    if (group === undefined) {
      groups.set(suffix, { index: lines.length, models: [model] });
      lines.push(name);
    } else {
      group.models.push(model);
      lines[group.index] = `Sigma ${group.models.join(" & ")}mm ${suffix}`;
    }
  }
  return lines.join(" + ");
}
