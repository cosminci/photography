export {};

interface NamedResource {
  readonly id: string;
  readonly name: string;
}
declare global {
  var PhotographyGear: {
    describeGear: (
      selection: readonly { readonly cameraId: string; readonly lensIds: readonly string[] }[],
      catalog: { readonly cameras: readonly NamedResource[]; readonly lenses: readonly NamedResource[] },
    ) => string;
    describeLenses: (names: readonly string[]) => string;
  };
}
