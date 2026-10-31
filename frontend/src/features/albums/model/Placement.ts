export interface PhotoTransform {
  readonly x: number;
  readonly y: number;
  readonly zoom: number;
  readonly mirrored: boolean;
}

interface PlacementFields {
  readonly id: string;
  readonly slotId: string;
  readonly photoId: string;
  readonly transform: PhotoTransform;
}

export interface SpreadPlacement extends PlacementFields {
  readonly kind: "spread";
  readonly captionCorner: "top_left" | "top_right" | "bottom_left" | "bottom_right";
}
export interface CoverPlacement extends PlacementFields {
  readonly kind: "cover";
}
export type Placement = SpreadPlacement | CoverPlacement;
export interface SpreadContext {
  readonly kind: "spread";
  readonly id: string;
}
export interface CoverContext {
  readonly kind: "cover";
}
export type PlacementContext = SpreadContext | CoverContext;
export interface SpreadSnapshot {
  readonly kind: "spread";
  readonly id: string;
  readonly placements: readonly SpreadPlacement[];
  readonly layout: SpreadLayout;
}
import type { SpreadLayout } from "./Layout";
export interface CoverSnapshot {
  readonly kind: "cover";
  readonly placements: readonly CoverPlacement[];
  readonly frontSlotId: string;
  readonly backSlotId: string;
  readonly spineOwner: "front" | "back";
}
export type PlacementSnapshot = SpreadSnapshot | CoverSnapshot;
export type PlacementEdit =
  | { readonly operation: "place"; readonly slotId: string; readonly photoId: string }
  | { readonly operation: "remove"; readonly placementId: string }
  | { readonly operation: "move"; readonly placementId: string; readonly slotId: string }
  | { readonly operation: "adjust"; readonly placementId: string; readonly transform: PhotoTransform };

export interface PlacementClient {
  readonly readPlacements: {
    (albumId: string, context: CoverContext): Promise<CoverSnapshot>;
    (albumId: string, context: SpreadContext): Promise<SpreadSnapshot>;
    (albumId: string, context: PlacementContext): Promise<PlacementSnapshot>;
  };
  readonly editPlacements: {
    (albumId: string, context: CoverContext, edit: PlacementEdit): Promise<CoverSnapshot>;
    (albumId: string, context: SpreadContext, edit: PlacementEdit): Promise<SpreadSnapshot>;
    (albumId: string, context: PlacementContext, edit: PlacementEdit): Promise<PlacementSnapshot>;
  };
}
