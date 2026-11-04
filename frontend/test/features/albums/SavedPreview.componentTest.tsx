import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { ChapterOutline, SpreadThumbnail } from "../../../src/features/albums/workspace/ChapterOutline";
import { AlbumCoverPreview } from "../../../src/features/albums/dashboard/AlbumCoverPreview";
import type { Photo } from "../../../src/features/albums/model/Photo";
import type { CoverSnapshot, SpreadPlacement } from "../../../src/features/albums/model/Placement";
import type { Spread } from "../../../src/features/albums/model/Workspace";
import { SavedPhoto } from "../../../src/features/albums/workspace/SavedPhoto";

const photo: Photo = {
  id: "photo",
  filename: "Mountains.jpg",
  capturedLocal: null,
  width: 4000,
  height: 2000,
  caption: null,
  thumbnail: { url: "/thumbnail.jpg", width: 400, height: 200 },
};
const placement: SpreadPlacement = {
  kind: "spread",
  id: "placement",
  slotId: "slot",
  photoId: photo.id,
  transform: { x: 10, y: -5, zoom: 2, mirrored: true },
  captionCorner: "bottom_right",
};
const cover: CoverSnapshot = {
  kind: "cover",
  frontSlotId: "front",
  backSlotId: "back",
  spineOwner: "front",
  placements: [{ ...placement, kind: "cover", slotId: "front" }],
};
const album = { id: "album", year: 2026, title: "Journey", createdAt: "2026-01-01T00:00:00Z" };

describe("saved composition previews", () => {
  beforeAll(() => {
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe = vi.fn();
        disconnect = vi.fn();
      },
    );
  });
  afterAll(() => {
    vi.unstubAllGlobals();
  });
  it("should render acknowledged navigator photographs and transforms without acquiring data or replacing navigation", () => {
    const [saved, setSaved] = createSignal<readonly SpreadPlacement[]>([placement]);
    render(() => (
      <SpreadThumbnail slots={[{ id: "slot", kind: "photo", x: 0, y: 0, width: 50, height: 100 }]} placements={saved()} photos={[photo]} />
    ));

    const image = screen.getByAltText(photo.filename);
    expect(image).toHaveAttribute("src", photo.thumbnail.url);
    expect(image).toHaveStyle({ transform: "scaleX(-1)" });
    const width = image.style.width;
    setSaved([{ ...placement, transform: { ...placement.transform, zoom: 3, mirrored: false } }]);

    expect(screen.getByAltText(photo.filename)).toBe(image);
    expect(image.style.width).not.toBe(width);
    expect(image.style.transform).toBe("");
  });

  it("should read the saved cover and its referenced photo, retaining distinct data and media retries", async () => {
    const client = {
      readPlacements: vi.fn().mockRejectedValueOnce(new Error("unavailable")).mockResolvedValue(cover),
      photo: vi.fn().mockResolvedValue(photo),
    };
    render(() => <AlbumCoverPreview album={album} client={client} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Cover preview could not be loaded");
    fireEvent.click(screen.getByRole("button", { name: "Retry cover preview" }));
    const image = await screen.findByAltText(photo.filename);
    expect(client.photo).toHaveBeenCalledExactlyOnceWith(photo.id);
    expect(image).toHaveStyle({ transform: "scaleX(-1)" });
    fireEvent.error(image);

    expect(screen.getByRole("alert")).toHaveTextContent("Front cover photograph could not be loaded");
    fireEvent.click(screen.getByRole("button", { name: "Retry front-cover photograph" }));
    await waitFor(() => {
      expect(image.getAttribute("src")).toContain("retry=1");
    });
    expect(client.readPlacements).toHaveBeenCalledTimes(2);
    expect(client.photo).toHaveBeenCalledTimes(1);
  });

  it("should show both cover faces with their own saved framing and independent media recovery", async () => {
    const back = { ...photo, id: "back-photo", filename: "Forest.jpg" };
    const client = {
      readPlacements: vi.fn().mockResolvedValue({
        ...cover,
        placements: [
          ...cover.placements,
          {
            ...placement,
            kind: "cover",
            id: "back-placement",
            slotId: "back",
            photoId: back.id,
            transform: { x: 0, y: 0, zoom: 1, mirrored: false },
          },
        ],
      }),
      photo: vi.fn().mockImplementation((id: string) => Promise.resolve(id === photo.id ? photo : back)),
    };
    render(() => <AlbumCoverPreview album={album} client={client} />);
    const frontImage = await screen.findByAltText(photo.filename);
    const backImage = await screen.findByAltText(back.filename);
    expect(frontImage).toHaveStyle({ transform: "scaleX(-1)" });
    expect(backImage.style.transform).toBe("");
    expect(client.photo).toHaveBeenCalledTimes(2);
    fireEvent.error(backImage);
    fireEvent.click(screen.getByRole("button", { name: "Retry back-cover photograph" }));
    await waitFor(() => {
      expect(backImage.getAttribute("src")).toContain("retry=1");
    });
    expect(frontImage.getAttribute("src")).not.toContain("retry=");
    expect(client.readPlacements).toHaveBeenCalledTimes(1);
  });

  it("should read a photograph shared by both cover faces once", async () => {
    const client = {
      readPlacements: vi.fn().mockResolvedValue({ ...cover, placements: [...cover.placements, { ...placement, kind: "cover", slotId: "back" }] }),
      photo: vi.fn().mockResolvedValue(photo),
    };
    render(() => <AlbumCoverPreview album={album} client={client} />);
    await waitFor(() => {
      expect(screen.getAllByAltText(photo.filename)).toHaveLength(2);
    });
    expect(client.photo).toHaveBeenCalledExactlyOnceWith(photo.id);
  });
  it("should show an empty front cover without requesting an unrelated photo", async () => {
    const client = { readPlacements: vi.fn().mockResolvedValue({ ...cover, placements: [] }), photo: vi.fn() };
    render(() => <AlbumCoverPreview album={album} client={client} />);

    expect(await screen.findByText("No front-cover photograph")).toBeVisible();
    expect(screen.getByText("No back-cover photograph")).toBeVisible();
    expect(client.photo).not.toHaveBeenCalled();
  });

  it.each([
    { name: "missing referenced photograph", response: () => Promise.reject(new Error("Photo missing")) },
    { name: "foreign photograph identity", response: () => Promise.resolve({ ...photo, id: "foreign" }) },
  ])("should expose $name as a retriable data failure rather than an empty cover", async ({ response }) => {
    let acknowledge!: (value: CoverSnapshot) => void;
    const client = {
      readPlacements: vi
        .fn()
        .mockImplementationOnce(
          () =>
            new Promise<CoverSnapshot>((resolve) => {
              acknowledge = resolve;
            }),
        )
        .mockResolvedValue(cover),
      photo: vi.fn().mockImplementationOnce(response).mockResolvedValue(photo),
    };
    render(() => <AlbumCoverPreview album={album} client={client} />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading cover preview");
    acknowledge(cover);
    expect(await screen.findByRole("alert")).toHaveTextContent("Cover preview could not be loaded");
    expect(screen.queryByText("No front-cover photograph")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry cover preview" }));
    expect(await screen.findByAltText(photo.filename)).toHaveAttribute("src", photo.thumbnail.url);
  });

  it("should retry shared media URLs with an existing query without rereading saved composition", () => {
    const onError = vi.fn();
    const onImage = vi.fn();
    render(() => (
      <SavedPhoto
        photo={{ ...photo, thumbnail: { ...photo.thumbnail, url: "/thumbnail.jpg?v=2" } }}
        transform={placement.transform}
        width={100}
        height={100}
        retry={2}
        onError={onError}
        onImage={onImage}
      />
    ));
    const image = screen.getByAltText(photo.filename);
    expect(image).toHaveAttribute("src", "/thumbnail.jpg?v=2&retry=2");
    expect(onImage).toHaveBeenCalledWith(image);
    fireEvent.error(image);
    expect(onError).toHaveBeenCalledExactlyOnceWith();
  });

  it("should retry navigator data and media while preserving selected links and scroll on acknowledged preview updates", async () => {
    const slot = { id: "slot", kind: "photo" as const, x: 0, y: 0, width: 50, height: 100 };
    const start: Spread = {
      id: "start",
      kind: "album_start",
      chapter: null,
      templateId: "template",
      slots: [slot],
      placements: [placement],
      bleed: { state: "disabled", available: true },
    };
    const opening: Spread = {
      ...start,
      id: "chapter-start",
      kind: "chapter_start",
      chapter: "chapter",
      slots: [{ ...slot, id: "opening-slot" }],
      placements: [],
    };
    const secondPlacement = { ...placement, id: "second-placement", slotId: "second-slot", photoId: "missing" };
    const second: Spread = {
      ...start,
      id: "second",
      kind: "photos_only",
      chapter: "chapter",
      slots: [{ ...slot, id: "second-slot" }],
      placements: [secondPlacement],
    };
    const [photos, setPhotos] = createSignal<readonly Photo[]>([photo]);
    const [spreads, setSpreads] = createSignal<readonly Spread[]>([start, opening, second]);
    const retry = vi.fn(() => setPhotos([photo, { ...photo, id: "missing", filename: "Recovered.jpg" }]));
    const select = vi.fn();
    render(() => (
      <ChapterOutline
        albumId="album"
        chapters={[{ id: "chapter", name: "Mountains" }]}
        spreads={spreads()}
        photos={photos()}
        onRetry={retry}
        part="second"
        creationReason={undefined}
        unavailable={undefined}
        onAdd={vi.fn()}
        onMove={vi.fn()}
        onDelete={vi.fn()}
        onSelect={select}
        onCreate={vi.fn()}
      />
    ));
    expect(screen.getByRole("alert")).toHaveTextContent("Saved navigator photo data is unavailable");
    fireEvent.click(screen.getByRole("button", { name: "Reload navigator data" }));
    expect(retry).toHaveBeenCalledExactlyOnceWith();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    const current = screen.getByRole("link", { name: "Mountains · Photo spread 1" });
    const strip = screen.getByRole("navigation", { name: "Album organization" });
    await waitFor(() => expect(current).toHaveAttribute("aria-current", "page"));
    strip.scrollLeft = 42;
    fireEvent.error(screen.getByAltText(photo.filename));
    fireEvent.error(screen.getByAltText("Recovered.jpg"));
    expect(screen.getByRole("alert")).toHaveTextContent("Some navigator photographs could not be loaded");
    fireEvent.click(screen.getByRole("button", { name: "Retry navigator photographs" }));
    expect(screen.getByAltText(photo.filename)).toHaveAttribute("src", "/thumbnail.jpg?retry=1");
    setSpreads([start, opening, { ...second, placements: [{ ...secondPlacement, transform: { ...placement.transform, zoom: 3 } }] }]);
    expect(screen.getByRole("link", { name: "Mountains · Photo spread 1" })).toBe(current);
    expect(current).toHaveAttribute("aria-current", "page");
    expect(strip.scrollLeft).toBe(42);
    expect(select).not.toHaveBeenCalled();
  });
});
