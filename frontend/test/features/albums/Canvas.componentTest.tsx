import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { CoverCanvas, SpreadCanvas, getCoverCredits, type CanvasPhotos } from "../../../src/features/albums/workspace/Canvas";
import { PhotoSlot } from "../../../src/features/albums/workspace/PhotoSlot";
import { EditorialText } from "../../../src/features/albums/workspace/EditorialText";
import { CoverSurface } from "../../../src/features/albums/workspace/CoverSurface";
import type { Slot } from "../../../src/features/albums/model/Workspace";
import { buildPlacementSession } from "./PlacementSessionStub";
import { coverGeometries } from "./CoverGeometry";

vi.mock("../../../src/features/albums/workspace/PhotoSlot", () => ({ PhotoSlot: vi.fn(() => null) }));
vi.mock("../../../src/features/albums/workspace/EditorialText", () => ({ EditorialText: vi.fn(() => null) }));
vi.mock("../../../src/features/albums/workspace/CoverSurface", () => ({
  CoverSurface: vi.fn((props: Parameters<typeof CoverSurface>[0]) => (
    <>
      {props.back}
      {props.front}
      {props.children}
    </>
  )),
}));
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const text = { id: "text", kind: "text" as const, x: 50, y: 4, width: 40, height: 90 };
const photo = { id: "slot", kind: "photo" as const, x: 2, y: 4, width: 23, height: 42 };

describe("Canvas", () => {
  it("should project text, visible photo geometry and independent placement data into child inputs", () => {
    const refs = buildPlacementSession();
    const savedPhoto = {
      id: "photo",
      filename: "Saved.jpg",
      width: 1200,
      height: 800,
      capturedLocal: null,
      caption: null,
      thumbnail: { url: "/saved.jpg", width: 600, height: 400 },
    };
    const placement = {
      id: "placement",
      kind: "spread" as const,
      slotId: photo.id,
      photoId: savedPhoto.id,
      captionCorner: "bottom_right" as const,
      transform: { x: 0, y: 0, zoom: 1, mirrored: false },
    };
    refs.setPlacements([placement]);
    const [slots, setSlots] = createSignal<readonly Slot[]>([text, photo]);
    const onChoose = vi.fn();
    const onEditText = vi.fn();
    const editing: CanvasPhotos = {
      session: refs.session,
      photos: [savedPhoto],
      choice: { kind: "photo", photoId: savedPhoto.id },
      onChoose,
      onEditText,
      unavailable: undefined,
      reader: false,
      highlightedSlot: photo.id,
    };
    render(() => <SpreadCanvas slots={slots()} title="Title" text="Saved text" label="Chapter" gear="Saved gear" editing={editing} />);
    const child = vi.mocked(PhotoSlot).mock.lastCall?.[0];
    if (child === undefined) throw new Error("Photo slot was not supplied");

    expect({ ...child }).toMatchObject({
      slotId: photo.id,
      label: "Photo slot 1",
      placement,
      photo: savedPhoto,
      session: refs.session,
      choice: editing.choice,
      onChoose,
      unavailable: undefined,
      reader: false,
      highlighted: true,
    });
    expect(vi.mocked(EditorialText).mock.lastCall?.[0].text).toBe("Saved text");
    expect({ ...vi.mocked(EditorialText).mock.lastCall?.[0] }).toEqual({
      title: "Title",
      text: "Saved text",
      gear: "Saved gear",
      synopsis: false,
      textWidth: undefined,
    });
    const edit = screen.getByRole("button", { name: "Edit chapter description" });
    fireEvent.click(edit);
    expect(onEditText).toHaveBeenCalledExactlyOnceWith(edit);
    setSlots([
      text,
      {
        ...photo,
        presentation: {
          bleed: ["left"],
          contentBox: photo,
          visibleBox: { x: 0, y: 4, width: 25, height: 42 },
        },
      },
    ]);
    expect(vi.mocked(PhotoSlot).mock.lastCall?.[0].width).toBe(595.2755);
    refs.setPlacements([]);
    expect(vi.mocked(PhotoSlot).mock.lastCall?.[0].placement).toBeUndefined();
    expect(vi.mocked(PhotoSlot).mock.lastCall?.[0].photo).toBeUndefined();
    setSlots([]);
    expect(screen.queryByRole("button", { name: "Edit chapter description" })).not.toBeInTheDocument();
  });

  it("should render synopsis artwork, report failed flags and restore changed country artwork", () => {
    const refs = buildPlacementSession();
    const [countries, setCountries] = createSignal<readonly { id: string; name: string; code: string }[]>([{ id: "jp", name: "Japan", code: "JP" }]);
    const editing: CanvasPhotos = {
      session: refs.session,
      photos: [],
      choice: undefined,
      onChoose: vi.fn(),
      onEditText: vi.fn(),
      unavailable: undefined,
      textUnavailable: "Read content",
      reader: false,
    };
    render(() => <SpreadCanvas slots={[text]} title="Title" text="Synopsis" label="Synopsis" synopsisCountries={countries()} editing={editing} />);

    expect(screen.getByRole("img", { name: "Japan flag" })).toHaveAttribute("href", "/flags/jp.png");
    expect(vi.mocked(EditorialText).mock.lastCall?.[0].textWidth).toBe(50);
    expect(screen.getByRole("button", { name: "Edit synopsis" })).toBeDisabled();
    fireEvent.error(screen.getByRole("img", { name: "Japan flag" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Flag artwork is unavailable for Japan.");
    setCountries([]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Synopsis country flags" })).not.toBeInTheDocument();
    setCountries([{ id: "ro", name: "Romania", code: "RO" }]);
    expect(screen.getByRole("img", { name: "Romania flag" })).toBeInTheDocument();
  });

  it("should present read-only geometry without exposing editing commands", () => {
    render(() => <SpreadCanvas slots={[text, photo]} title="Title" text="Text" label="Synopsis" />);

    expect(screen.getByRole("img", { name: "Synopsis composition" })).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("should project acknowledged cover sides and ownership while leaving acquisition and placement to collaborators", () => {
    const refs = buildPlacementSession();
    const [reader, setReader] = createSignal(false);
    const editing: CanvasPhotos = {
      session: refs.session,
      photos: [],
      choice: undefined,
      onChoose: vi.fn(),
      unavailable: undefined,
      get reader() {
        return reader();
      },
    };
    render(() => <CoverCanvas title="Title" year={2026} authors={["Mara", "Cosmin"]} editing={editing} />);
    expect(screen.getByRole("group", { name: /Cover: back/ })).toHaveAttribute("aria-busy", "true");
    refs.setSnapshot({
      kind: "cover",
      placements: [],
      frontSlotId: "front",
      backSlotId: "back",
      spineOwner: "front",
      pageCount: 30,
      geometry: coverGeometries.front,
    });

    expect(
      vi
        .mocked(PhotoSlot)
        .mock.calls.map(([props]) => ({ ...props }))
        .map((props) => props.slotId),
    ).toEqual(["back", "front"]);
    expect(screen.getByText("by Cosmin & Mara · 2026")).toBeInTheDocument();
    expect(vi.mocked(CoverSurface).mock.lastCall?.[0].geometry).toBe(coverGeometries.front);
    expect(vi.mocked(CoverSurface).mock.lastCall?.[0].spineOwner).toBe("front");
    refs.setSnapshot({
      kind: "cover",
      placements: [],
      frontSlotId: "front",
      backSlotId: "back",
      spineOwner: "back",
      pageCount: 30,
      geometry: coverGeometries.back,
    });
    expect(screen.getByText("Spine/gutter: back cover")).toBeInTheDocument();
    setReader(true);
    expect(screen.getByRole("img", { name: /Cover: back/ })).toHaveAttribute("aria-busy", "false");
    refs.setSnapshot({
      kind: "spread",
      id: "spread",
      placements: [],
      layout: { kind: "photos_only", templateId: "layout", slots: [], bleed: { state: "disabled", available: true } },
    });
    expect(screen.getByRole("img", { name: /Cover: back/ })).toHaveAttribute("aria-busy", "true");
  });

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
