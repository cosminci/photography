export interface SlotBox {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

export type SpreadEdge = "top" | "bottom" | "left" | "right";

export interface Slot extends SlotBox {
  readonly id: string;
  readonly kind: "photo" | "text";
  readonly presentation?: {
    readonly bleed: readonly SpreadEdge[];
    readonly contentBox: SlotBox;
    readonly visibleBox: SlotBox;
  };
}

export interface SpreadLayout {
  readonly kind: "album_start" | "chapter_start" | "photos_only";
  readonly templateId: string;
  readonly slots: readonly Slot[];
  readonly bleed: { readonly state: "disabled" | "enabled" | "mixed"; readonly available: boolean };
}

export function visibleBox(slot: Omit<Slot, "id">): SlotBox {
  return slot.kind === "photo" ? (slot.presentation?.visibleBox ?? slot) : slot;
}
