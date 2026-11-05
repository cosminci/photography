export {};

interface Dimensions {
  readonly width: number;
  readonly height: number;
}
interface Transform {
  readonly x: number;
  readonly y: number;
  readonly zoom: number;
  readonly mirrored: boolean;
}
declare global {
  var PhotographyCrop: {
    bound: (photo: Dimensions, slot: Dimensions, transform: Transform) => Transform;
    crop: (photo: Dimensions, slot: Dimensions, transform: Transform) => Dimensions & { readonly left: number; readonly top: number };
  };
}
