import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { TemplateSelector, templateCategory } from "../../../src/features/albums/workspace/TemplateSelector";
import type { SpreadContext, SpreadTemplate } from "../../../src/features/albums/model/Workspace";
import { SpreadThumbnail } from "../../../src/features/albums/workspace/ChapterOutline";

vi.mock("../../../src/features/albums/workspace/ChapterOutline", () => ({ SpreadThumbnail: vi.fn(() => null) }));
afterEach(cleanup);

describe("TemplateSelector", () => {
  it("should acquire a context catalog, group available photo counts and forward the chosen identity and opener", async () => {
    const photo = { kind: "photo" as const, x: 0, y: 0, width: 10, height: 10 };
    const catalog: readonly SpreadTemplate[] = [
      { id: "one", name: "One photo", slots: [photo] },
      { id: "many", name: "Many photos", slots: Array.from({ length: 10 }, () => photo) },
    ];
    const load = vi.fn().mockResolvedValue(catalog);
    const onChange = vi.fn();
    const [unavailable, setUnavailable] = createSignal<string>();
    render(() =>
      TemplateSelector({
        context: "photos_only",
        appliedTemplateId: "one",
        load,
        get unavailable() {
          return unavailable();
        },
        onChange,
      }),
    );
    const first = await screen.findByRole("button", { name: "One photo" });
    const thumbnail = vi.mocked(SpreadThumbnail).mock.calls[0]?.[0];
    if (thumbnail === undefined) throw new Error("Template thumbnail was not supplied");
    expect(thumbnail.slots).toBe(catalog[0]?.slots);

    fireEvent.click(first);
    expect(onChange).toHaveBeenCalledExactlyOnceWith("one", first);
    expect(first).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(screen.getByRole("button", { name: "10+" }));
    const many = screen.getByRole("button", { name: "Many photos" });
    expect(many).toHaveAttribute("aria-pressed", "false");
    setUnavailable("Read saved content");
    expect(many).toBeDisabled();
    expect(many).toHaveAccessibleDescription("Read saved content");
    expect(load).toHaveBeenCalledExactlyOnceWith("photos_only");
  });

  it("should leave the current catalog usable when an obsolete acquisition fails", async () => {
    let fail!: (failure: Error) => void;
    const obsolete = new Promise<readonly SpreadTemplate[]>((_, reject) => {
      fail = reject;
    });
    const load = vi
      .fn()
      .mockReturnValueOnce(obsolete)
      .mockResolvedValue([{ id: "current", name: "Current", slots: [] }]);
    const [context, setContext] = createSignal<SpreadContext>("album_start");
    render(() =>
      TemplateSelector({
        get context() {
          return context();
        },
        appliedTemplateId: "current",
        load,
        unavailable: undefined,
        onChange: vi.fn(),
      }),
    );
    setContext("photos_only");
    await screen.findByRole("button", { name: "Current" });

    fail(new Error("Obsolete acquisition failed"));
    await Promise.resolve();

    expect(screen.getByRole("button", { name: "Current" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "Retry templates" })).not.toBeInTheDocument();
  });
  it("should retry failed catalogs and ignore obsolete replies after a context change or disposal", async () => {
    let resolve!: (value: readonly SpreadTemplate[]) => void;
    const old = new Promise<readonly SpreadTemplate[]>((complete) => {
      resolve = complete;
    });
    const catalog = [{ id: "current", name: "Current", slots: [] }];
    const load = vi.fn().mockRejectedValueOnce(new Error("Unavailable")).mockReturnValueOnce(old).mockResolvedValue(catalog);
    const [context, setContext] = createSignal<SpreadContext>("album_start");
    render(() =>
      TemplateSelector({
        get context() {
          return context();
        },
        appliedTemplateId: "missing",
        load,
        unavailable: undefined,
        onChange: vi.fn(),
      }),
    );
    fireEvent.click(await screen.findByRole("button", { name: "Retry templates" }));
    expect(screen.getByRole("region", { name: "Spread template selector" })).toHaveAttribute("aria-busy", "true");
    setContext("photos_only");
    await screen.findByRole("button", { name: "Current" });

    resolve([{ id: "old", name: "Obsolete", slots: [] }]);
    await Promise.resolve();
    expect(screen.queryByRole("button", { name: "Obsolete" })).not.toBeInTheDocument();
    expect(load.mock.calls).toEqual([["album_start"], ["album_start"], ["photos_only"]]);
    cleanup();
    setContext("chapter_start");
    expect(load).toHaveBeenCalledTimes(3);
  });

  it.each([
    [1, 2, 1],
    [9, 1, 9],
    [10, 0, 10],
    [11, 3, 10],
    [24, 0, 10],
  ])("should categorize %i photo slots and %i text slots as %i", (photos, texts, expected) => {
    const slots = [
      ...Array.from({ length: photos }, () => ({ kind: "photo" as const, x: 0, y: 0, width: 10, height: 10 })),
      ...Array.from({ length: texts }, () => ({ kind: "text" as const, x: 0, y: 0, width: 10, height: 10 })),
    ];

    expect(templateCategory({ slots })).toBe(expected);
  });
});
