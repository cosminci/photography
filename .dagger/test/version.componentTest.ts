import { describe, expect, it } from "vitest";
import { assertReleaseVersion, releaseVersion } from "../src/version";

describe("release version", () => {
  it.each([
    ["2026-11-08T01:02:03Z", "2026.11.8-T010203"],
    ["2024-02-29T23:59:59Z", "2024.2.29-T235959"],
    ["2026-01-01T00:00:00Z", "2026.1.1-T000000"],
  ])("should use an unpadded UTC date and padded time for %s", (instant, expected) => {
    expect(releaseVersion(new Date(instant))).toBe(expected);
    expect(() => {
      assertReleaseVersion(expected);
    }).not.toThrow();
  });

  it.each([
    "latest",
    "dev",
    "2026.02.01-T000000",
    "2026.2.29-T000000",
    "2026.4.31-T000000",
    "2026.13.1-T000000",
    "2026.1.0-T000000",
    "2026.1.1-T240000",
    "2026.1.1-T006000",
    "2026.1.1-T000060",
    "2026.1.1-T000000\n",
  ])("should reject invalid or mutable release tag %s", (tag) => {
    expect(() => {
      assertReleaseVersion(tag);
    }).toThrow(`invalid release version: ${tag}`);
  });
});
