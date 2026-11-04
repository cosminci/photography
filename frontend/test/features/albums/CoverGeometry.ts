import type { CoverGeometry, CoverSnapshot } from "../../../src/features/albums/model/Placement";

export const coverGeometries: Readonly<Record<CoverSnapshot["spineOwner"], CoverGeometry>> = {
  front: {
    width: 2400,
    height: 720,
    spineStart: 1176,
    spineWidth: 48,
    back: { x: 0, width: 1176, height: 720 },
    front: { x: 1176, width: 1224, height: 720 },
  },
  back: {
    width: 2400,
    height: 720,
    spineStart: 1176,
    spineWidth: 48,
    back: { x: 0, width: 1224, height: 720 },
    front: { x: 1224, width: 1176, height: 720 },
  },
};
