import { describe, expect, it } from "vitest";
import { readAlbum, readFailureMessage, readPage, readSummary } from "../../../src/features/albums/api/Wire";

describe("album wire representations", () => {
  it.each([undefined, [], ["Mara", "Mara"], ["Unknown"], ["Cosmin", 1]].map((authors) => ({ authors })))(
    "should reject invalid album authors $authors",
    ({ authors }) => {
      expect(() => readAlbum({ ...summary, synopsis: "", authors })).toThrow();
    },
  );
  const summary = {
    id: "00000000-0000-0000-0000-000000000001",
    year: 2026,
    title: "Journey of the Shepherds 2026",
    createdAt: "2026-10-24T12:00:00Z",
  };
  const page = { albums: [summary], page: 1, pageSize: 6, totalAlbums: 1, totalPages: 1 };

  it("should retain exact album metadata and pagination", () => {
    const album = { ...summary, synopsis: "At dawn.", authors: ["Cosmin", "Mara", "Iacob"] };

    expect(readSummary(summary)).toEqual(summary);
    expect(readAlbum(album)).toEqual(album);
    expect(readPage(page)).toEqual(page);
  });

  it.each([
    null,
    [],
    42,
    { ...summary, title: 42 },
    { ...summary, year: undefined },
    { ...summary, year: "2026" },
    { ...summary, year: 2026.5 },
    { ...summary, year: 1899 },
    { ...summary, year: 2101 },
  ])("should reject invalid summary %j", (value) => {
    expect(() => readSummary(value)).toThrow();
  });

  it.each([
    { ...page, page: "1" },
    { ...page, page: Number.NaN },
    { ...page, page: 1.5 },
    { ...page, page: 0 },
    { ...page, pageSize: 5 },
    { ...page, totalAlbums: -1 },
    { ...page, albums: {} },
  ])("should reject invalid page %j", (value) => {
    expect(() => readPage(value)).toThrow();
  });

  it("should reject an album without its synopsis", () => {
    expect(() => readAlbum(summary)).toThrow();
  });

  it.each([
    [undefined, "The request could not be completed. Please try again."],
    [null, "The request could not be completed. Please try again."],
    [{}, "The request could not be completed. Please try again."],
    [{ message: 3 }, "The request could not be completed. Please try again."],
    [{ message: "Use a title." }, "Use a title."],
  ])("should expose only human-readable failure messages from %j", (value, expected) => {
    expect(readFailureMessage(value)).toBe(expected);
  });
});
