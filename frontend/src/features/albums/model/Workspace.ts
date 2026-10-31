export interface Country {
  readonly id: string;
  readonly name: string;
  readonly code: string;
}

export interface Chapter {
  readonly id: string;
  readonly kind: "vacation" | "home";
  readonly name: string;
  readonly countries: readonly string[];
  readonly start: string;
  readonly end: string;
  readonly sourceFolder: string;
  readonly description: string;
}

export type ChapterDetails =
  | {
      readonly kind: "vacation";
      readonly name: string;
      readonly countries: readonly string[];
      readonly start: string;
      readonly end: string;
      readonly sourceFolder: string;
    }
  | { readonly kind: "home"; readonly countries: readonly string[]; readonly sourceFolder: string };

export type Spread = SpreadLayout & {
  readonly id: string;
  readonly placements: readonly SpreadPlacement[];
} & ({ readonly kind: "album_start"; readonly chapter: null } | { readonly kind: "chapter_start" | "photos_only"; readonly chapter: string });

export type SpreadContext = Spread["kind"];

export interface SpreadMembership {
  readonly id: string;
  readonly chapter: string | null;
}

export interface SpreadTemplate {
  readonly id: string;
  readonly name: string;
  readonly slots: readonly Omit<Slot, "id">[];
}

export interface Workspace {
  readonly photoInventories: Readonly<Record<string, PhotoInventory>>;
  readonly year: number;
  readonly photoRoot: string;
  readonly chapters: readonly Chapter[];
  readonly spreads: readonly Spread[];
  readonly countries: readonly Country[];
}
import type { PhotoInventory } from "./Photo";
import type { SpreadPlacement } from "./Placement";
import type { Slot, SpreadLayout } from "./Layout";
export type { Slot } from "./Layout";
