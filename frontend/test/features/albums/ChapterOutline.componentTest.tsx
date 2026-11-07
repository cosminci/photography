import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import {
  ChapterOutline,
  SpreadThumbnail,
  getChapterCapacityReason,
  spreadActionReasons,
} from "../../../src/features/albums/workspace/ChapterOutline";
import { SavedPhoto } from "../../../src/features/albums/workspace/SavedPhoto";
import type { Spread } from "../../../src/features/albums/model/Workspace";

vi.mock("../../../src/features/albums/workspace/SavedPhoto", () => ({ SavedPhoto: vi.fn(() => null) }));
const observers: Observer[] = [];
class Observer implements ResizeObserver {
  constructor(readonly callback: ResizeObserverCallback) {
    observers.push(this);
  }
  observe = vi.fn<ResizeObserver["observe"]>();
  unobserve = vi.fn<ResizeObserver["unobserve"]>();
  disconnect = vi.fn<ResizeObserver["disconnect"]>();
}
beforeEach(() => {
  vi.clearAllMocks();
  observers.length = 0;
  vi.stubGlobal("ResizeObserver", Observer);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("ChapterOutline", () => {
  it("should complement selection, creation, chapter-local movement and removal while preserving native navigation endpoints", async () => {
    const opening: Spread = {
      id: "opening",
      kind: "album_start",
      chapter: null,
      templateId: "layout",
      slots: [],
      placements: [],
      bleed: { state: "disabled", available: true },
    };
    const chapter: Spread = { ...opening, id: "chapter-opening", kind: "chapter_start", chapter: "chapter" };
    const first: Spread = { ...chapter, id: "first", kind: "photos_only" };
    const middle: Spread = { ...first, id: "middle" };
    const last: Spread = { ...first, id: "last" };
    const [part, setPart] = createSignal<string | null>("middle");
    const [unavailable, setUnavailable] = createSignal<string>();
    const onSelect = vi.fn();
    const onCreate = vi.fn();
    const onAdd = vi.fn();
    const onMove = vi.fn();
    const onDelete = vi.fn();
    render(() => (
      <ChapterOutline
        albumId="album"
        chapters={[{ id: "chapter", name: "Kyoto" }]}
        spreads={[opening, chapter, first, middle, last]}
        part={part()}
        unavailable={unavailable()}
        creationReason={unavailable()}
        onSelect={onSelect}
        onCreate={onCreate}
        onAdd={onAdd}
        onMove={onMove}
        onDelete={onDelete}
      />
    ));
    const add = screen.getByRole("button", { name: "Add spread" });
    const left = screen.getByRole("button", { name: "Shift spread left" });
    const right = screen.getByRole("button", { name: "Shift spread right" });
    const remove = screen.getByRole("button", { name: "Delete spread" });
    const create = screen.getByRole("button", { name: "Add chapter" });

    fireEvent.click(add);
    fireEvent.click(left);
    fireEvent.click(right);
    fireEvent.click(remove);
    fireEvent.click(create);
    fireEvent.click(screen.getByRole("link", { name: "Synopsis" }));
    fireEvent.click(screen.getByRole("link", { name: "Kyoto" }));
    fireEvent.click(screen.getByRole("link", { name: "Kyoto · Photo spread 1" }));
    for (const name of ["First spread", "Previous spread", "Next spread", "Last spread"]) fireEvent.click(screen.getByRole("button", { name }));

    expect(onAdd).toHaveBeenCalledExactlyOnceWith("chapter", "middle", add);
    expect(onMove.mock.calls).toEqual([
      ["middle", "earlier", left],
      ["middle", "later", right],
    ]);
    expect(onDelete).toHaveBeenCalledExactlyOnceWith("middle", remove);
    expect(onCreate).toHaveBeenCalledExactlyOnceWith(create);
    expect(onSelect.mock.calls).toEqual([[null], ["chapter"], ["first"], [null], ["first"], ["last"], ["last"]]);
    setUnavailable("Read saved workspace");
    expect(create).toHaveAccessibleDescription("Read saved workspace");
    setPart("absent");
    expect(screen.getByText("Choose a spread")).toBeInTheDocument();
    await Promise.resolve();
    cleanup();
    const observer = observers[0];
    if (observer === undefined) throw new Error("Navigator observer was not created");
    observer.callback([], observer);
    expect(observer.disconnect).toHaveBeenCalledOnce();
  });

  it("should show capacity and missing media feedback, report image errors and retry only supplied acquisition callbacks", () => {
    const savedPhoto = {
      id: "photo",
      filename: "Saved.jpg",
      width: 1200,
      height: 800,
      capturedLocal: null,
      caption: null,
      thumbnail: { url: "/saved.jpg", width: 600, height: 400 },
    };
    const slot = { id: "slot", kind: "photo" as const, x: 2, y: 4, width: 23, height: 42 };
    const placement = {
      id: "placement",
      kind: "spread" as const,
      slotId: slot.id,
      photoId: savedPhoto.id,
      captionCorner: "bottom_right" as const,
      transform: { x: 0, y: 0, zoom: 1, mirrored: false },
    };
    const opening: Spread = {
      id: "opening",
      kind: "album_start",
      chapter: null,
      templateId: "layout",
      slots: [slot],
      placements: [placement],
      bleed: { state: "disabled", available: true },
    };
    const [photos, setPhotos] = createSignal<readonly (typeof savedPhoto)[]>([savedPhoto]);
    const retry = vi.fn();
    render(() => (
      <ChapterOutline
        albumId="album"
        chapters={[]}
        spreads={Array.from({ length: 65 }, (_, index) => ({ ...opening, id: `spread-${String(index)}` }))}
        photos={photos()}
        part={null}
        creationReason="Capacity reached"
        unavailable={undefined}
        onSelect={vi.fn()}
        onCreate={vi.fn()}
        onAdd={vi.fn()}
        onMove={vi.fn()}
        onDelete={vi.fn()}
        onRetry={retry}
      />
    ));
    const media = vi.mocked(SavedPhoto).mock.lastCall?.[0];
    if (media === undefined) throw new Error("Saved navigator media was not supplied");

    expect(media.photo).toBe(savedPhoto);
    media.onError();
    fireEvent.click(screen.getByRole("button", { name: "Retry navigator photographs" }));
    expect(media.retry).toBe(1);
    setPhotos([]);
    fireEvent.click(screen.getByRole("button", { name: "Reload navigator data" }));
    expect(retry).toHaveBeenCalledOnce();
    expect(screen.getByText("This album has 65 interior spreads. No more chapters can be added.")).toBeInTheDocument();
    cleanup();
    render(() => (
      <SpreadThumbnail slots={[slot, { ...slot, id: "text", kind: "text", x: 50, width: 48 }]} placements={[placement]} photos={[savedPhoto]} />
    ));
    vi.mocked(SavedPhoto).mock.lastCall?.[0].onError();
    const deliveries = vi.mocked(SavedPhoto).mock.calls.length;
    render(() => <SpreadThumbnail slots={[slot]} />);
    expect(SavedPhoto).toHaveBeenCalledTimes(deliveries);
  });

  it.each([
    [64, undefined],
    [65, "This album has 65 interior spreads. No more chapters can be added."],
  ] as const)("should explain chapter creation availability at %i spreads", (count, expected) => {
    expect(getChapterCapacityReason(count)).toBe(expected);
  });

  const availableActions = { add: undefined, earlier: undefined, later: undefined, remove: undefined };
  const photoSelection = "Select a photo spread first.";
  const photoActions = { earlier: photoSelection, later: photoSelection, remove: photoSelection };
  const missingSelection = { add: "Select a chapter opening or photo spread first.", ...photoActions };
  const firstPhoto = "This is the first photo spread in its chapter.";
  const lastPhoto = "This is the last photo spread in its chapter.";
  it.each([
    {
      name: "unavailable",
      selectedIndex: undefined,
      total: 65,
      unavailable: "Saving changes.",
      expected: { add: "Saving changes.", earlier: "Saving changes.", later: "Saving changes.", remove: "Saving changes." },
    },
    { name: "no selection", selectedIndex: undefined, total: 7, unavailable: undefined, expected: missingSelection },
    { name: "album opening", selectedIndex: 0, total: 7, unavailable: undefined, expected: missingSelection },
    { name: "chapter opening", selectedIndex: 1, total: 7, unavailable: undefined, expected: { add: undefined, ...photoActions } },
    {
      name: "capacity",
      selectedIndex: 3,
      total: 65,
      unavailable: undefined,
      expected: { ...availableActions, add: "This album has 65 interior spreads. No more spreads can be added." },
    },
    { name: "first", selectedIndex: 2, total: 7, unavailable: undefined, expected: { ...availableActions, earlier: firstPhoto } },
    { name: "middle", selectedIndex: 3, total: 7, unavailable: undefined, expected: availableActions },
    { name: "last", selectedIndex: 4, total: 7, unavailable: undefined, expected: { ...availableActions, later: lastPhoto } },
    { name: "single", selectedIndex: 6, total: 7, unavailable: undefined, expected: { ...availableActions, earlier: firstPhoto, later: lastPhoto } },
  ])("should explain spread actions for $name", ({ selectedIndex, total, unavailable, expected }) => {
    const spreads: Spread[] = [
      {
        id: "album",
        bleed: { state: "disabled" as const, available: true },
        templateId: "layout",
        kind: "album_start",
        chapter: null,
        slots: [],
        placements: [],
      },
      {
        id: "opening",
        bleed: { state: "disabled" as const, available: true },
        templateId: "layout",
        kind: "chapter_start",
        chapter: "vacation",
        slots: [],
        placements: [],
      },
      ...["first", "middle", "last"].map((id) => ({
        id,
        bleed: { state: "disabled" as const, available: true },
        templateId: "layout",
        kind: "photos_only" as const,
        chapter: "vacation",
        slots: [],
        placements: [],
      })),
      {
        id: "home-opening",
        bleed: { state: "disabled" as const, available: true },
        templateId: "layout",
        kind: "chapter_start",
        chapter: "home",
        slots: [],
        placements: [],
      },
      {
        id: "single",
        bleed: { state: "disabled" as const, available: true },
        templateId: "layout",
        kind: "photos_only",
        chapter: "home",
        slots: [],
        placements: [],
      },
    ];
    const selected = selectedIndex === undefined ? undefined : spreads[selectedIndex];
    const remaining = Array.from({ length: total - spreads.length }, (_, index): Spread => ({
      id: `other-${String(index)}`,
      bleed: { state: "disabled" as const, available: true },
      templateId: "layout",
      kind: "photos_only",
      chapter: "other",
      slots: [],
      placements: [],
    }));
    expect(spreadActionReasons([...spreads, ...remaining], selected, unavailable)).toEqual(expected);
  });
});
