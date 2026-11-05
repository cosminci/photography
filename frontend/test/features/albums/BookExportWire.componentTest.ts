import { describe, expect, it } from "vitest";
import { readBookDownload, readExportIssues } from "../../../src/features/albums/api/BookExportWire";

describe("InDesign download boundary", () => {
  const warning = { code: "low_resolution", severity: "warning", target: { kind: "slot", spread: "spread", slot: "portrait" } };
  const book = { formatVersion: 1, album: { title: "Țară" }, sources: [], interior: {}, chapters: [], cover: {}, gear: {}, warnings: [warning] };

  it("should preserve the complete saved payload and exact authored characters", () => {
    const result = readBookDownload(book);

    expect(JSON.parse(result.content)).toEqual(book);
    expect(result.warnings).toEqual([warning]);
  });

  it.each([
    { field: "formatVersion", value: 2 },
    { field: "album", value: null },
    { field: "sources", value: {} },
    { field: "interior", value: [] },
    { field: "chapters", value: "" },
    { field: "cover", value: false },
    { field: "gear", value: null },
    { field: "warnings", value: undefined },
  ])("should reject incompatible $field instead of downloading it", ({ field, value }) => {
    expect(() => readBookDownload({ ...book, [field]: value })).toThrow();
  });

  it.each([
    { kind: "album" },
    { kind: "chapter", chapter: "coast" },
    { kind: "slot", spread: "opening", slot: "portrait" },
    { kind: "cover", slot: "front" },
    { kind: "photo", photo: "original" },
  ])("should retain the complete $kind issue owner", (target) => {
    const issue = { code: "original_unavailable", severity: "blocking", target };

    expect(readExportIssues([issue])).toEqual([issue]);
  });

  it.each([
    { code: "empty_synopsis", severity: "info", target: { kind: "album" } },
    { code: 4, severity: "blocking", target: { kind: "album" } },
    { code: "original_unavailable", severity: "blocking", target: { kind: "unknown" } },
    { code: "empty_description", severity: "blocking", target: { kind: "chapter" } },
    { code: "empty_photo_slot", severity: "blocking", target: { kind: "spread", spread: "opening" } },
    { code: "empty_photo_slot", severity: "blocking", target: { kind: "slot", spread: "opening" } },
    { code: "empty_photo_slot", severity: "blocking", target: { kind: "cover" } },
    { code: "original_unavailable", severity: "blocking", target: { kind: "photo" } },
  ])("should reject malformed issue $target", (issue) => {
    expect(() => readExportIssues([issue])).toThrow();
  });
});
