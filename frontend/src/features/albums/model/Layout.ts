export interface Slot {
  readonly id: string;
  readonly kind: "photo" | "text";
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

export interface SpreadLayout {
  readonly kind: "album_start" | "chapter_start" | "photos_only";
  readonly templateId: string;
  readonly slots: readonly Slot[];
}
