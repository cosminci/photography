import { describe, expect, it } from "vitest";
import {
  describeCountryLabel,
  describePeriod,
  displayCalendarDay,
  readCalendarDay,
  isCalendarDay,
  suggestFolder,
  validateChapter,
  matchesChapterDetails,
} from "../../../src/features/albums/workspace/ChapterDetails";

describe("ChapterDetails", () => {
  it("should reconcile complete chapter details while preserving saved source, countries and date years", () => {
    const chapter = {
      id: "chapter",
      kind: "vacation" as const,
      name: "Kyoto",
      countries: ["jp"],
      start: "2025-12-31",
      end: "2026-01-01",
      sourceFolder: "/photos/Kyoto",
      description: "",
    };
    const details = {
      kind: chapter.kind,
      name: chapter.name,
      countries: chapter.countries,
      start: chapter.start,
      end: chapter.end,
      sourceFolder: chapter.sourceFolder,
    };
    expect(matchesChapterDetails(chapter, details)).toBe(true);
    expect(matchesChapterDetails(undefined, details)).toBe(false);
    expect(matchesChapterDetails({ ...chapter, sourceFolder: "/elsewhere" }, details)).toBe(false);
    expect(matchesChapterDetails({ ...chapter, countries: ["ro"] }, details)).toBe(false);
    expect(matchesChapterDetails({ ...chapter, countries: ["ro", "jp"] }, details)).toBe(false);
    expect(matchesChapterDetails({ ...chapter, name: "Other" }, details)).toBe(false);
    expect(matchesChapterDetails({ ...chapter, start: "2026-12-31" }, details)).toBe(false);
    expect(matchesChapterDetails({ ...chapter, end: "2026-01-02" }, details)).toBe(false);
    expect(matchesChapterDetails({ ...chapter, kind: "home" }, { kind: "home", countries: ["jp"], sourceFolder: chapter.sourceFolder })).toBe(true);
  });

  it.each([
    [1, "Country"],
    [2, "Countries"],
  ] as const)("should label %i country selections as %s", (count, label) => {
    expect(describeCountryLabel(count)).toBe(label);
  });

  it.each([
    ["2000-02-29", true],
    ["1900-02-29", false],
    ["2026-00-01", false],
    ["2026-13-01", false],
    ["2026-01-00", false],
    ["2026-01-32", false],
  ])("should recognize calendar day %s without timezone conversion", (value, valid) => {
    expect(isCalendarDay(value)).toBe(valid);
  });

  it.each([
    ["2026-04-02", "2026-04-02", undefined],
    ["2025-12-31", "2026-01-01", undefined],
    ["2024-02-29", "2024-03-01", undefined],
    ["2026-02-29", "2026-03-01", "Enter valid calendar dates."],
    ["2026-04-31", "2026-05-01", "Enter valid calendar dates."],
    ["2026-04-03", "2026-04-02", "Last day must not precede first day."],
    ["", "2026-01-01", "Enter valid calendar dates."],
  ])("should validate the inclusive calendar period %s to %s", (start, end, expected) => {
    const draft = { kind: "vacation" as const, name: "Kyoto", countries: ["jp"], start, end, sourceFolder: "/photos/2026/selection/Kyoto" };

    expect(validateChapter(draft)).toBe(expected);
  });

  it.each([
    [{ kind: "home" as const, countries: ["ro"], sourceFolder: "/photos/Home" }, undefined],
    [{ kind: "home" as const, countries: [], sourceFolder: "/photos/Home" }, "Select at least one country."],
    [{ kind: "home" as const, countries: ["ro"], sourceFolder: "relative" }, "Enter an absolute photo folder without parent traversal."],
    [{ kind: "home" as const, countries: ["ro"], sourceFolder: "/photos/../Home" }, "Enter an absolute photo folder without parent traversal."],
    [{ kind: "home" as const, countries: ["ro"], sourceFolder: "" }, "Enter an absolute photo folder without parent traversal."],
    ...["/", "/photos//Kyoto", "/photos/./Kyoto", "/photos/Kyoto/", "/photos\\Kyoto", "/photos/\0Kyoto"].map(
      (sourceFolder) =>
        [{ kind: "home" as const, countries: ["ro"], sourceFolder }, "Enter an absolute photo folder without parent traversal."] as const,
    ),
    [
      { kind: "vacation" as const, name: " ", countries: ["ro"], start: "2026-01-01", end: "2026-01-01", sourceFolder: "/photos/a" },
      "Enter a location / name.",
    ],
    [
      { kind: "vacation" as const, name: " Home ", countries: ["ro"], start: "2026-01-01", end: "2026-01-01", sourceFolder: "/photos/a" },
      "Choose a vacation name other than Home.",
    ],
  ] as const)("should give chapter input feedback for %j", (draft, expected) => {
    expect(validateChapter(draft)).toBe(expected);
  });

  it("should derive full paths and calendar display without changing nominal years", () => {
    expect(suggestFolder("/mnt/photos/", 2026, " Kyoto ")).toBe("/mnt/photos/2026/selection/Kyoto");
    expect(suggestFolder("/", 2026, "Home")).toBe("/2026/selection/Home");
    expect(describePeriod({ kind: "home", start: "2026-01-01", end: "2026-12-31" })).toBe("Throughout the year");
    expect(describePeriod({ kind: "vacation", start: "2025-12-31", end: "2026-01-01" })).toBe("31/12 - 01/01");
  });

  it.each([
    ["31/12/2025", "2025-12-31"],
    ["29/02/2024", "2024-02-29"],
    ["29/02/2026", ""],
    ["31/04/2026", ""],
    ["2026-04-02", ""],
    ["2/04/2026", ""],
    ["", ""],
  ])("should read day-first calendar input %s as %s", (input, expected) => {
    expect(readCalendarDay(input)).toBe(expected);
  });

  it("should preserve the full saved date when displaying an editable day", () => {
    expect(displayCalendarDay("2025-12-31")).toBe("31/12/2025");
  });
});
