import { fireEvent, render, screen } from "@solidjs/testing-library";
import { createComponent, createSignal } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { ChapterMetadata } from "../../../src/features/albums/workspace/ChapterMetadata";

describe("ChapterMetadata", () => {
  it("should present both positions and chapter details in the metadata block", () => {
    const expectedMetadata = "Chapter spread2 / 3Album spread4 / 6LocationKyotoCountryJapanPeriod31/12 - 01/01";
    const onEdit = vi.fn();
    const [unavailable, setUnavailable] = createSignal<string>();

    render(() =>
      createComponent(ChapterMetadata, {
        chapter: { name: "Kyoto", kind: "vacation", countries: ["jp"], start: "2025-12-31", end: "2026-01-01" },
        position: { chapter: { position: 2, total: 3 }, album: { position: 4, total: 6 } },
        countries: [{ id: "jp", name: "Japan" }],
        get unavailable() {
          return unavailable();
        },
        onEdit,
      }),
    );

    const metadata = screen.getByRole("region", { name: "Spread details" });

    expect(metadata).toHaveTextContent(expectedMetadata);
    expect(screen.getByRole("button", { name: "Edit chapter details" })).toBeEnabled();
    const edit = screen.getByRole("button", { name: "Edit chapter details" });
    fireEvent.click(edit);
    expect(onEdit).toHaveBeenCalledExactlyOnceWith(edit);
    setUnavailable("Read saved chapter");
    expect(edit).toBeDisabled();
    expect(edit).toHaveAccessibleDescription("Read saved chapter");
  });
});
