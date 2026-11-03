import { describe, expect, it } from "vitest";
import { visibleBox } from "../../../src/features/albums/model/Layout";

describe("authoritative slot presentation", () => {
  const raw = { kind: "photo" as const, x: 0.3, y: 1, width: 49.7, height: 98 };
  const contentBox = { x: 0.3, y: 1, width: 49.7, height: 98 };
  const frame = { x: 0, y: 0, width: 50, height: 100 };
  const presentation = { bleed: ["left", "top", "bottom"] as const, contentBox, visibleBox: frame };

  it.each([
    { slot: raw, expected: raw },
    { slot: { ...raw, presentation }, expected: frame },
    { slot: { ...raw, kind: "text" as const, presentation }, expected: { ...raw, kind: "text", presentation } },
  ])("should select the supplied photo frame without changing stored geometry for $slot.kind", ({ slot, expected }) => {
    const before = structuredClone(slot);
    expect(visibleBox(slot)).toEqual(expected);
    expect(slot).toEqual(before);
  });
});
