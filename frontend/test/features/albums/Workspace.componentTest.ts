import { fireEvent, render, screen } from "@solidjs/testing-library";
import { createComponent, type ComponentProps } from "solid-js";
import { describe, expect, expectTypeOf, it, vi } from "vitest";
import { ChapterMetadata } from "../../../src/features/albums/workspace/ChapterMetadata";
import { ChapterDialog } from "../../../src/features/albums/workspace/ChapterDialog";
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
import {
  findSelection,
  findReaderNavigation,
  findSpreadNavigation,
  findSpreadPosition,
  findDeletionSelection,
} from "../../../src/features/albums/workspace/WorkspaceSelection";
import { getChapterCapacityReason, spreadActionReasons } from "../../../src/features/albums/workspace/ChapterOutline";
import { templateCategory } from "../../../src/features/albums/workspace/TemplateSelector";
import type { Spread } from "../../../src/features/albums/model/Workspace";
import { getCoverCredits } from "../../../src/features/albums/workspace/Canvas";
import { searchCountries } from "../../../src/features/albums/workspace/CountryPicker";
import { readChapter, readCountry } from "../../../src/features/albums/api/WorkspaceWire";
import { readSpread } from "../../../src/features/albums/api/PlacementWire";
import { readPhotoInventory, readScheduling } from "../../../src/features/albums/api/PhotoWire";

describe("chapter detail feedback", () => {
  it.each([false, true])(
    "should stop after acknowledged metadata when the editor scope ends before the gear phase with changedGear=%s",
    async (changedGear) => {
      const nativeShow = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "showModal");
      const nativeClose = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "close");
      Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
        configurable: true,
        value(this: HTMLDialogElement) {
          this.open = true;
        },
      });
      Object.defineProperty(HTMLDialogElement.prototype, "close", {
        configurable: true,
        value(this: HTMLDialogElement) {
          this.open = false;
        },
      });
      const chapter = {
        id: "chapter",
        kind: "vacation" as const,
        name: "Kyoto",
        countries: ["jp"],
        start: "2026-04-02",
        end: "2026-04-14",
        sourceFolder: "/photos/Kyoto",
      };
      const camera = { id: "camera", code: "Camera", name: "Camera", kind: "fixed_lens" as const };
      const save = vi.fn().mockResolvedValue({ id: chapter.id, scheduling: "not-requested" });
      const saveGear = vi.fn();
      const onSaved = vi.fn();
      const onReconciled = vi.fn();
      const view = render(() =>
        createComponent(ChapterDialog, {
          chapter,
          countries: [{ id: "jp", name: "Japan", code: "JP" }],
          year: 2026,
          photoRoot: "/photos",
          homeAvailable: false,
          save,
          onSaved,
          onReconciled,
          onClose: vi.fn(),
          returnFocus: vi.fn(),
          resources: {
            albumId: "album",
            maybeChapterId: chapter.id,
            client: {
              chapters: vi.fn(),
              gearCatalog: vi.fn().mockResolvedValue({ cameras: [camera], lenses: [] }),
              chapterGear: vi.fn().mockResolvedValue([]),
              saveChapterGear: saveGear,
              createCamera: vi.fn(),
              updateCamera: vi.fn(),
              deleteCamera: vi.fn(),
              createLens: vi.fn(),
              updateLens: vi.fn(),
              deleteLens: vi.fn(),
            },
          },
          onBlock: (reason) => {
            if (reason === "Finish the chapter edit before leaving." && save.mock.calls.length === 1) view.unmount();
          },
        }),
      );
      try {
        await vi.waitUntil(() => !screen.getByRole<HTMLButtonElement>("button", { name: "Save chapter" }).disabled);
        if (changedGear) fireEvent.change(screen.getByRole("combobox", { name: "Add camera" }), { target: { value: camera.id } });
        fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
        await vi.waitUntil(() => screen.queryByRole("dialog") === null);
        await Promise.resolve();
        expect(save).toHaveBeenCalledOnce();
        expect(saveGear).not.toHaveBeenCalled();
        expect(onSaved).not.toHaveBeenCalled();
        expect(onReconciled).not.toHaveBeenCalled();
      } finally {
        view.unmount();
        if (nativeShow === undefined) Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal");
        else Object.defineProperty(HTMLDialogElement.prototype, "showModal", nativeShow);
        if (nativeClose === undefined) Reflect.deleteProperty(HTMLDialogElement.prototype, "close");
        else Object.defineProperty(HTMLDialogElement.prototype, "close", nativeClose);
      }
    },
  );
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
  it("should require both resolved spread positions in the metadata contract", () => {
    expectTypeOf<ComponentProps<typeof ChapterMetadata>["position"]>().toEqualTypeOf<{
      chapter: { position: number; total: number };
      album: { position: number; total: number };
    }>();
  });

  it("should present both positions and chapter details in the metadata block", () => {
    const expectedMetadata = "Chapter spread2 / 3Album spread4 / 6LocationKyotoCountryJapanPeriod31/12 - 01/01";

    render(() =>
      createComponent(ChapterMetadata, {
        chapter: { name: "Kyoto", kind: "vacation", countries: ["jp"], start: "2025-12-31", end: "2026-01-01" },
        position: { chapter: { position: 2, total: 3 }, album: { position: 4, total: 6 } },
        countries: [{ id: "jp", name: "Japan" }],
        unavailable: undefined,
        onEdit: vi.fn(),
      }),
    );

    const metadata = screen.getByRole("region", { name: "Spread details" });

    expect(metadata).toHaveTextContent(expectedMetadata);
    expect(screen.getByRole("button", { name: "Edit chapter details" })).toBeEnabled();
  });

  it("should edit day/month through typing and native calendars without changing saved years", async () => {
    Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.open = true;
      },
    });
    Object.defineProperty(HTMLDialogElement.prototype, "close", {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.open = false;
      },
    });
    const picker = vi.fn();
    const save = vi.fn(() => new Promise<never>(() => undefined));
    const expectedDetails = {
      kind: "vacation",
      name: "Kyoto",
      countries: ["jp"],
      start: "2025-12-30",
      end: "2026-01-02",
      sourceFolder: "/photos/Kyoto",
    };

    const view = render(() =>
      createComponent(ChapterDialog, {
        chapter: { kind: "vacation", name: "Kyoto", countries: ["jp"], start: "2025-12-31", end: "2026-01-01", sourceFolder: "/photos/Kyoto" },
        countries: [{ id: "jp", name: "Japan", code: "JP" }],
        year: 2026,
        photoRoot: "/photos",
        homeAvailable: false,
        save,
        onSaved: vi.fn(),
        onClose: vi.fn(),
        returnFocus: vi.fn(),
        resources: {
          albumId: "album",
          maybeChapterId: "chapter",
          client: {
            chapters: vi.fn(),
            gearCatalog: vi.fn().mockResolvedValue({ cameras: [], lenses: [] }),
            chapterGear: vi.fn().mockResolvedValue([]),
            saveChapterGear: vi.fn(),
            createCamera: vi.fn(),
            updateCamera: vi.fn(),
            deleteCamera: vi.fn(),
            createLens: vi.fn(),
            updateLens: vi.fn(),
            deleteLens: vi.fn(),
          },
        },
        onReconciled: vi.fn(),
        onBlock: vi.fn(),
      }),
    );
    await vi.waitUntil(() => !screen.getByRole<HTMLButtonElement>("button", { name: "Save chapter" }).disabled);
    const first = screen.getByRole("textbox", { name: "First day" });
    const last = screen.getByRole("textbox", { name: "Last day" });
    const firstCalendar = screen.getByLabelText("Choose first day from calendar");
    const lastCalendar = screen.getByLabelText("Choose last day from calendar");
    Object.defineProperty(firstCalendar, "showPicker", { configurable: true, value: picker });

    expect(first).toHaveValue("31/12");
    expect(last).toHaveValue("01/01");
    fireEvent.input(firstCalendar, { target: { value: "2026-01-01" } });
    expect(screen.getByRole("alert")).toHaveTextContent("Choose dates within 2025.");
    expect(first).toHaveValue("31/12");
    fireEvent.input(lastCalendar, { target: { value: "2025-12-31" } });
    expect(screen.getByRole("alert")).toHaveTextContent("Choose dates within 2026.");
    expect(last).toHaveValue("01/01");

    fireEvent.click(firstCalendar);
    fireEvent.keyDown(firstCalendar, { key: "Enter" });
    fireEvent.keyDown(firstCalendar, { key: " " });
    fireEvent.keyDown(firstCalendar, { key: "ArrowDown" });
    fireEvent.click(lastCalendar);
    fireEvent.input(firstCalendar, { target: { value: "" } });
    fireEvent.input(lastCalendar, { target: { value: "" } });
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));

    expect(first).toHaveValue("");
    expect(last).toHaveValue("");
    expect(screen.getByRole("alert")).toHaveTextContent("Enter valid calendar dates.");
    expect(save).not.toHaveBeenCalled();

    fireEvent.input(firstCalendar, { target: { value: "2025-12-29" } });

    expect(first).toHaveValue("29/12");

    fireEvent.input(first, { target: { value: "30/12" } });
    fireEvent.input(lastCalendar, { target: { value: "2026-01-02" } });
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));

    expect(picker).toHaveBeenCalledTimes(3);
    expect(last).toHaveValue("02/01");
    expect(firstCalendar).toBeDisabled();
    expect(lastCalendar).toBeDisabled();
    expect(firstCalendar).toHaveAccessibleDescription("Saving content. Please wait.");
    expect(lastCalendar).toHaveAccessibleDescription("Saving content. Please wait.");
    expect(save).toHaveBeenCalledWith(expectedDetails);

    view.unmount();
    Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal");
    Reflect.deleteProperty(HTMLDialogElement.prototype, "close");
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

describe("country picker matching", () => {
  it("should search all supplied countries by name or code, including no matches", () => {
    const countries = [
      { id: "ro", name: "Romania", code: "RO" },
      { id: "jp", name: "Japan", code: "JP" },
    ];

    expect(searchCountries(countries, " jp ")).toEqual([countries[1]]);
    expect(searchCountries(countries, "ROM")).toEqual([countries[0]]);
    expect(searchCountries(countries, "")).toEqual(countries);
    expect(searchCountries(countries, "Atlantis")).toEqual([]);
  });
});

describe("chapter outline capacity", () => {
  it.each([
    [64, undefined],
    [65, "This album has 65 interior spreads. No more chapters can be added."],
  ] as const)("should explain chapter creation availability at %i spreads", (count, expected) => {
    expect(getChapterCapacityReason(count)).toBe(expected);
  });

  it.each([
    ["unavailable", undefined, 65, "Saving changes.", ["Saving changes.", "Saving changes.", "Saving changes.", "Saving changes."]],
    [
      "no selection",
      undefined,
      7,
      undefined,
      [
        "Select a chapter opening or photo spread first.",
        "Select a photo spread first.",
        "Select a photo spread first.",
        "Select a photo spread first.",
      ],
    ],
    [
      "album opening",
      0,
      7,
      undefined,
      [
        "Select a chapter opening or photo spread first.",
        "Select a photo spread first.",
        "Select a photo spread first.",
        "Select a photo spread first.",
      ],
    ],
    ["chapter opening", 1, 7, undefined, [undefined, "Select a photo spread first.", "Select a photo spread first.", "Select a photo spread first."]],
    ["capacity", 3, 65, undefined, ["This album has 65 interior spreads. No more spreads can be added.", undefined, undefined, undefined]],
    ["first", 2, 7, undefined, [undefined, "This is the first photo spread in its chapter.", undefined, undefined]],
    ["middle", 3, 7, undefined, [undefined, undefined, undefined, undefined]],
    ["last", 4, 7, undefined, [undefined, undefined, "This is the last photo spread in its chapter.", undefined]],
    [
      "single",
      6,
      7,
      undefined,
      [undefined, "This is the first photo spread in its chapter.", "This is the last photo spread in its chapter.", undefined],
    ],
  ] as const)("should explain spread actions for %s", (_scenario, selectedIndex, total, unavailable, expectedReasons) => {
    const spreads: Spread[] = [
      { id: "album", templateId: "layout", kind: "album_start", chapter: null, slots: [], placements: [] },
      { id: "opening", templateId: "layout", kind: "chapter_start", chapter: "vacation", slots: [], placements: [] },
      ...["first", "middle", "last"].map((id) => ({
        id,
        templateId: "layout",
        kind: "photos_only" as const,
        chapter: "vacation",
        slots: [],
        placements: [],
      })),
      { id: "home-opening", templateId: "layout", kind: "chapter_start", chapter: "home", slots: [], placements: [] },
      { id: "single", templateId: "layout", kind: "photos_only", chapter: "home", slots: [], placements: [] },
    ];
    const selected = selectedIndex === undefined ? undefined : spreads[selectedIndex];
    const remaining = Array.from({ length: total - spreads.length }, (_, index): Spread => ({
      id: `other-${String(index)}`,
      templateId: "layout",
      kind: "photos_only",
      chapter: "other",
      slots: [],
      placements: [],
    }));
    const [add, earlier, later, remove] = expectedReasons;
    const expected = { add, earlier, later, remove };

    expect(spreadActionReasons([...spreads, ...remaining], selected, unavailable)).toEqual(expected);
  });
});

describe("template photo-slot categories", () => {
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

describe("cover credits", () => {
  it("should reject unavailable attribution and preserve display order independently of selection order", () => {
    expect(() => getCoverCredits([])).toThrow("Cover attribution requires an author");
    expect(getCoverCredits(["Iacob", "Cosmin", "Mara"])).toBe("by Cosmin, Mara & Iacob");
  });
  it.each([
    [["Cosmin"], "by Cosmin"],
    [["Mara"], "by Mara"],
    [["Iacob"], "by Iacob"],
    [["Cosmin", "Mara"], "by Cosmin & Mara"],
    [["Cosmin", "Iacob"], "by Cosmin & Iacob"],
    [["Mara", "Iacob"], "by Mara & Iacob"],
    [["Cosmin", "Mara", "Iacob"], "by Cosmin, Mara & Iacob"],
  ] as const)("should credit the selected authors %j", (authors, expected) => {
    expect(getCoverCredits(authors)).toBe(expected);
  });
});

describe("workspace URL selection", () => {
  it.each([
    ["album opening", 0, null],
    ["chapter opening", 1, "chapter"],
    ["photo spread", 2, "photo"],
    ["past the last spread", 3, "photo"],
  ] as const)("should retain the deletion index at %s", (_scenario, index, expected) => {
    const remainingSpreads: readonly Spread[] = [
      { id: "album-opening", templateId: "layout", kind: "album_start", chapter: null, slots: [], placements: [] },
      { id: "chapter-opening", templateId: "layout", kind: "chapter_start", chapter: "chapter", slots: [], placements: [] },
      { id: "photo", templateId: "layout", kind: "photos_only", chapter: "chapter", slots: [], placements: [] },
    ];

    expect(findDeletionSelection(remainingSpreads, index)).toBe(expected);
  });

  it("should reject deletion selection from an empty composition rather than inventing a destination", () => {
    expect(() => findDeletionSelection([], 0)).toThrow("Saved composition has no selectable spread");
  });

  it.each([
    [0, { chapter: { position: 1, total: 1 }, album: { position: 1, total: 5 } }],
    [1, { chapter: { position: 1, total: 3 }, album: { position: 2, total: 5 } }],
    [2, { chapter: { position: 2, total: 3 }, album: { position: 3, total: 5 } }],
    [3, { chapter: { position: 3, total: 3 }, album: { position: 4, total: 5 } }],
    [4, { chapter: { position: 1, total: 1 }, album: { position: 5, total: 5 } }],
  ] as const)("should count openings and exclude Cover for spread index %i", (index, expected) => {
    const spreads: [Spread, Spread, Spread, Spread, Spread] = [
      { id: "album", templateId: "layout", kind: "album_start", chapter: null, slots: [], placements: [] },
      { id: "opening", templateId: "layout", kind: "chapter_start", chapter: "vacation", slots: [], placements: [] },
      { id: "first", templateId: "layout", kind: "photos_only", chapter: "vacation", slots: [], placements: [] },
      { id: "last", templateId: "layout", kind: "photos_only", chapter: "vacation", slots: [], placements: [] },
      { id: "home-opening", templateId: "layout", kind: "chapter_start", chapter: "home", slots: [], placements: [] },
    ];

    expect(findSpreadPosition(spreads, spreads[index])).toEqual(expected);
  });

  it("should resolve URL parts through persisted openings and spread ownership without inventing missing values", () => {
    const spreads = [
      { id: "album-opening", templateId: "layout", chapter: null, kind: "album_start" as const, slots: [], placements: [] },
      { id: "chapter-opening", templateId: "layout", chapter: "chapter", kind: "chapter_start" as const, slots: [], placements: [] },
      { id: "photo", templateId: "layout", chapter: "chapter", kind: "photos_only" as const, slots: [], placements: [] },
    ];

    expect(findSelection(spreads, null)).toEqual({ kind: "spread", spread: spreads[0] });
    expect(findSelection(spreads, "cover")).toEqual({ kind: "cover" });
    expect(findSelection(spreads, "chapter")).toEqual({ kind: "spread", spread: spreads[1] });
    expect(findSelection(spreads, "photo")).toEqual({ kind: "spread", spread: spreads[2] });
    expect(findSelection(spreads, "foreign")).toEqual({ kind: "invalid" });
    expect(findSelection([], null)).toEqual({ kind: "invalid" });
  });

  it.each([
    ["cover", undefined, null],
    [null, "cover", "chapter-opening"],
    ["chapter", null, "photo"],
    ["photo", "chapter-opening", "home-opening"],
    ["home", "photo", undefined],
    ["foreign", undefined, undefined],
  ] as const)("should traverse persisted Reader order from %s without wrapping", (part, previous, next) => {
    const spreads = [
      { id: "album-opening", templateId: "layout", chapter: null, kind: "album_start" as const, slots: [], placements: [] },
      { id: "chapter-opening", templateId: "layout", chapter: "chapter", kind: "chapter_start" as const, slots: [], placements: [] },
      { id: "photo", templateId: "layout", chapter: "chapter", kind: "photos_only" as const, slots: [], placements: [] },
      { id: "home-opening", templateId: "layout", chapter: "home", kind: "chapter_start" as const, slots: [], placements: [] },
    ];

    expect(findReaderNavigation(spreads, findSelection(spreads, part))).toEqual({ previous, next });
  });

  it("should navigate an album opening to Cover and keep an empty workspace unavailable", () => {
    const spreads = [{ id: "opening", templateId: "layout", chapter: null, kind: "album_start" as const, slots: [], placements: [] }];
    const emptyNavigation = { position: undefined, total: 0, first: undefined, previous: undefined, next: undefined, last: undefined };

    expect(findReaderNavigation(spreads, findSelection(spreads, null))).toEqual({ previous: "cover", next: undefined });
    expect(findSpreadNavigation([], findSelection([], null))).toEqual(emptyNavigation);
  });

  it.each([
    [null, 1, undefined, undefined, "chapter", "photo"],
    ["chapter", 2, null, null, "photo", "photo"],
    ["chapter-opening", 2, null, null, "photo", "photo"],
    ["photo", 3, null, "chapter", undefined, undefined],
    ["foreign", undefined, undefined, undefined, undefined, undefined],
    ["cover", undefined, undefined, undefined, undefined, undefined],
  ] as const)(
    "should derive above-editor spread destinations from %s without wrapping or navigating Cover",
    (part, position, first, previous, next, last) => {
      const spreads = [
        { id: "opening", templateId: "layout", chapter: null, kind: "album_start" as const, slots: [], placements: [] },
        { id: "chapter-opening", templateId: "layout", chapter: "chapter", kind: "chapter_start" as const, slots: [], placements: [] },
        { id: "photo", templateId: "layout", chapter: "chapter", kind: "photos_only" as const, slots: [], placements: [] },
      ];
      const expected = { position, total: 3, first, previous, next, last };

      expect(findSpreadNavigation(spreads, findSelection(spreads, part))).toEqual(expected);
    },
  );
});

describe("workspace wire representations", () => {
  const chapter = {
    id: "chapter",
    kind: "vacation",
    name: "Kyoto",
    countries: ["jp"],
    start: "2026-04-02",
    end: "2026-04-14",
    sourceFolder: "/photos/Kyoto",
    description: "",
  };
  const slot = { id: "slot", kind: "photo", x: 2, y: 3, width: 24, height: 40 };
  const spread = { id: "opening", templateId: "layout", chapter: null, kind: "album_start", slots: [slot], placements: [] };
  const workspace = {
    year: 2026,
    photoRoot: "/photos",
    chapters: [chapter],
    spreads: [spread],
    countries: [{ id: "jp", name: "Japan", code: "JP" }],
    photoInventories: {
      chapter: {
        photos: [],
        placedPhotoIds: [],
        counts: { unplaced: 0, total: 0 },
        processing: { state: "idle", unsupportedCount: 0, skippedCount: 0, issues: [] },
      },
    },
  };

  const homeWorkspace = {
    ...workspace,
    chapters: [{ ...chapter, kind: "home", name: "Home" }],
    spreads: [{ ...spread, chapter: "chapter", kind: "chapter_start", slots: [{ ...slot, kind: "text" }] }],
  };
  const photoWorkspace = { ...workspace, spreads: [{ ...spread, chapter: chapter.id, kind: "photos_only" }] };

  it.each([
    ["album_start", workspace],
    ["chapter_start", homeWorkspace],
    ["photos_only", photoWorkspace],
  ] as const)("should decode complete saved metadata, country catalog and exact %s geometry", (_kind, expectedWorkspace) => {
    expect(expectedWorkspace.chapters.map(readChapter)).toEqual(expectedWorkspace.chapters);
    expect(expectedWorkspace.spreads.map(readSpread)).toEqual(expectedWorkspace.spreads);
    expect(expectedWorkspace.countries.map(readCountry)).toEqual(expectedWorkspace.countries);
  });

  it.each([
    { ...spread, kind: "unknown" },
    { ...spread, chapter: chapter.id },
    { ...spread, kind: "chapter_start", chapter: null },
    { ...spread, kind: "photos_only", chapter: null },
    { ...spread, slots: [{ ...slot, kind: "unknown" }] },
    { ...spread, slots: [{ ...slot, x: Number.NaN }] },
  ])("should reject incomplete or unknown spread resource representations %j", (value) => {
    expect(() => readSpread(value)).toThrow();
  });

  it("should reject an unknown chapter type at its resource boundary", () => {
    expect(() => readChapter({ ...chapter, kind: "unknown" })).toThrow();
  });

  describe("photo wire representations", () => {
    const photo = {
      id: "photo",
      filename: "Photo.jpg",
      capturedLocal: "2026-01-01T10:00",
      width: 1200,
      height: 800,
      caption: null,
      thumbnail: { url: "/thumbnails/key/version.jpg", width: 600, height: 400 },
    };
    const processing = {
      state: "completed",
      unsupportedCount: 2,
      skippedCount: 1,
      issues: [{ stage: "read", code: "undecodable", filename: "bad.jpg" }],
    };
    const inventory = { photos: [photo], placedPhotoIds: [], counts: { unplaced: 1, total: 1 }, processing };

    it.each(["idle", "queued", "running", "completed", "failed"])(
      "should retain the complete %s inventory without altering its thumbnail URL",
      (state) => {
        const expected = { ...inventory, processing: { ...processing, state } };

        expect(readPhotoInventory(expected)).toEqual(expected);
        expect(readPhotoInventory({ ...inventory, photos: [{ ...photo, capturedLocal: null, caption: "Caption" }] }).photos[0]?.caption).toBe(
          "Caption",
        );
      },
    );

    it.each([
      { ...inventory, photos: null },
      { ...inventory, photos: [{ ...photo, id: "" }] },
      { ...inventory, counts: { unplaced: -1, total: 1 } },
      { ...inventory, counts: { unplaced: 2, total: 1 } },
      { ...inventory, counts: { unplaced: 0, total: 2 } },
      { ...inventory, counts: { unplaced: 0.5, total: 1 } },
      ...["id", "filename", "caption", "capturedLocal", "width", "height", "thumbnail"].map((field) => ({
        ...inventory,
        photos: [{ ...photo, [field]: undefined }],
      })),
      ...[0, -1, 0.5, Infinity].map((width) => ({ ...inventory, photos: [{ ...photo, thumbnail: { ...photo.thumbnail, width } }] })),
      ...["2026-02-30T10:00", "2026-01-01T25:00", "2026-01-01T10:00Z", ""].map((capturedLocal) => ({
        ...inventory,
        photos: [{ ...photo, capturedLocal }],
      })),
      { ...inventory, photos: [photo, photo], counts: { unplaced: 1, total: 2 } },
      ...["state", "unsupportedCount", "skippedCount", "issues"].map((field) => ({
        ...inventory,
        processing: { ...processing, [field]: undefined },
      })),
      { ...inventory, processing: { ...processing, state: "finished" } },
      { ...inventory, processing: { ...processing, issues: [{ stage: "other", code: "undecodable", filename: null }] } },
      { ...inventory, processing: { ...processing, issues: [{ stage: "read", code: "other", filename: null }] } },
    ])("should reject malformed inventory rather than publish an empty success: %j", (value) => {
      expect(() => readPhotoInventory(value)).toThrow();
    });

    it.each(["queued", "coalesced", "not-requested", "failed"])("should retain saved chapter scheduling %s", (scheduling) => {
      expect(readScheduling({ scheduling })).toBe(scheduling);
    });

    it.each([{}, { scheduling: "completed" }])("should reject missing or malformed scheduling %j", (value) => {
      expect(() => readScheduling(value)).toThrow();
    });
  });
});
