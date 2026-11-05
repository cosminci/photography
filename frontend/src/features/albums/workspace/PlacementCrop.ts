import type { PhotoTransform } from "../model/Placement";
import "../../../../../scripts/indesign/crop.js";

interface Dimensions {
  readonly width: number;
  readonly height: number;
}

export function boundTransform(photo: Dimensions, slot: Dimensions, transform: PhotoTransform): PhotoTransform {
  return PhotographyCrop.bound(photo, slot, transform);
}

export function cropImage(photo: Dimensions, slot: Dimensions, transform: PhotoTransform) {
  return PhotographyCrop.crop(photo, slot, transform);
}

export function panTransform(transform: PhotoTransform, delta: { readonly x: number; readonly y: number }): PhotoTransform {
  return { ...transform, x: transform.x + (transform.mirrored ? -delta.x : delta.x), y: transform.y + delta.y };
}
