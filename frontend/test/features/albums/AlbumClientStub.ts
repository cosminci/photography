import { vi } from "vitest";
import type { AlbumClient } from "../../../src/features/albums/model/Album";

export function buildAlbumClient(overrides: Partial<AlbumClient> = {}): AlbumClient {
  const unexpected = () => vi.fn().mockRejectedValue(new Error("Unexpected album client call"));
  return {
    exportBook: unexpected(),
    photo: unexpected(),
    gearCatalog: unexpected(),
    chapterGear: unexpected(),
    saveChapterGear: unexpected(),
    createCamera: unexpected(),
    updateCamera: unexpected(),
    deleteCamera: unexpected(),
    createLens: unexpected(),
    updateLens: unexpected(),
    deleteLens: unexpected(),
    readPlacements: unexpected(),
    editPlacements: unexpected(),
    list: unexpected(),
    create: unexpected(),
    rename: unexpected(),
    open: unexpected(),
    saveSynopsis: unexpected(),
    saveAuthors: unexpected(),
    saveDescription: unexpected(),
    saveCaption: unexpected(),
    remove: unexpected(),
    chapters: unexpected(),
    composition: unexpected(),
    countries: unexpected(),
    photoSource: unexpected(),
    createChapter: unexpected(),
    updateChapter: unexpected(),
    chapterPhotos: unexpected(),
    refreshPhotos: unexpected(),
    templates: unexpected(),
    addSpread: unexpected(),
    replaceChapterComposition: unexpected(),
    changeSpreadTemplate: unexpected(),
    deleteSpread: unexpected(),
    ...overrides,
  };
}
