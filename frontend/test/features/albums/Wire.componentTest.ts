import { describe, expect, it } from "vitest";
import { readPage } from "../../../src/features/albums/api/Wire";

describe("album page wire validation", () => {
  it.each([1, 6, 9, 2_147_483_647])("should accept configured page size %i", (pageSize) => {
    const page = { albums: [], page: 1, pageSize, totalAlbums: 0, totalPages: 0 };

    expect(readPage(page)).toEqual(page);
  });

  it.each([0, -1, 1.5, NaN, Infinity, -Infinity, "9", null, undefined])("should reject invalid page size %s", (pageSize) => {
    const page = { albums: [], page: 1, pageSize, totalAlbums: 0, totalPages: 0 };

    expect(() => readPage(page)).toThrow();
  });
});
