import type { Chapter, ChapterDetails, Country, Spread, SpreadContext, SpreadTemplate, SpreadMembership } from "./Workspace";
import type { ChapterSave, PhotoInventory } from "./Photo";
import type { PlacementClient } from "./Placement";

export const minimumAlbumYear = 1900;
export const maximumAlbumYear = 2100;

export interface AlbumSummary {
  readonly id: string;
  readonly year: number;
  readonly title: string;
  readonly createdAt: string;
}

export interface Album extends AlbumSummary {
  readonly synopsis: string;
  readonly authors: readonly Author[];
}

export const albumAuthors = ["Cosmin", "Mara", "Iacob"] as const;
export type Author = (typeof albumAuthors)[number];

export interface AlbumPage {
  readonly albums: readonly AlbumSummary[];
  readonly page: number;
  readonly pageSize: number;
  readonly totalAlbums: number;
  readonly totalPages: number;
}

export interface AlbumClient extends PlacementClient {
  readonly list: (page: number) => Promise<AlbumPage>;
  readonly create: (year: number) => Promise<AlbumSummary>;
  readonly rename: (id: string, title: string) => Promise<AlbumSummary>;
  readonly open: (id: string, signal?: AbortSignal) => Promise<Album>;
  readonly saveSynopsis: (id: string, text: string) => Promise<Album>;
  readonly saveAuthors: (id: string, authors: readonly Author[]) => Promise<Album>;
  readonly saveDescription: (id: string, chapterId: string, text: string) => Promise<void>;
  readonly saveCaption: (chapterId: string, photoId: string, text: string) => Promise<void>;
  readonly remove: (id: string) => Promise<void>;
  readonly chapters: (id: string, signal: AbortSignal) => Promise<readonly Chapter[]>;
  readonly composition: (id: string, signal: AbortSignal) => Promise<readonly SpreadMembership[]>;
  readonly spread: (id: string, spreadId: string, chapter: string | null, signal: AbortSignal) => Promise<Spread>;
  readonly countries: (signal: AbortSignal) => Promise<readonly Country[]>;
  readonly photoSource: (signal: AbortSignal) => Promise<string>;
  readonly chapterPhotos: (id: string, signal: AbortSignal) => Promise<PhotoInventory>;
  readonly refreshPhotos: (id: string) => Promise<"queued" | "coalesced">;
  readonly createChapter: (id: string, details: ChapterDetails) => Promise<ChapterSave>;
  readonly updateChapter: (id: string, chapterId: string, details: ChapterDetails) => Promise<ChapterSave>;
  readonly templates: (context: SpreadContext) => Promise<readonly SpreadTemplate[]>;
  readonly addSpread: (id: string, chapterId: string, afterSpreadId: string) => Promise<string>;
  readonly replaceChapterComposition: (
    id: string,
    chapterId: string,
    expectedSpreadIds: readonly string[],
    spreadIds: readonly string[],
  ) => Promise<void>;
  readonly changeSpreadTemplate: (id: string, spreadId: string, templateId: string, confirmPlacements: boolean) => Promise<void>;
  readonly deleteSpread: (id: string, spreadId: string, confirmPlacements: boolean) => Promise<void>;
}

export class SpreadOccupiedError extends Error {
  constructor(readonly placements: number) {
    super(`This spread contains ${String(placements)} placements.`);
  }
}

export class AlbumRequestError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export class AcknowledgedWriteError extends Error {}
