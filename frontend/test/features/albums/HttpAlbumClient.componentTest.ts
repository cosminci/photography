import { describe, expect, it, vi } from "vitest";
import { makeHttpAlbumClient } from "../../../src/features/albums/api/HttpAlbumClient";
import { AcknowledgedWriteError, AlbumRequestError } from "../../../src/features/albums/model/Album";
import type { PlacementContext, PlacementEdit } from "../../../src/features/albums/model/Placement";
import type { Spread } from "../../../src/features/albums/model/Workspace";
import { buildChapterReorder } from "../../../src/features/albums/workspace/WorkspaceSelection";

describe("same-origin album HTTP client", () => {
  it.each([
    { operation: "create" as const, returnedId: "" },
    { operation: "update" as const, returnedId: "" },
    { operation: "update" as const, returnedId: "another-chapter" },
  ])("should retain acknowledgement for $operation returning identity '$returnedId' without another request", async ({ operation, returnedId }) => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        new Response(JSON.stringify({ id: returnedId, scheduling: "not-requested" }), { status: operation === "create" ? 201 : 200 }),
      );
    const client = makeHttpAlbumClient(fetcher);
    const details = { kind: "home" as const, countries: ["country"], sourceFolder: "/photos/Home" };

    await expect(
      operation === "create" ? client.createChapter("album", details) : client.updateChapter("album", "chapter", details),
    ).rejects.toBeInstanceOf(AcknowledgedWriteError);
    expect(fetcher).toHaveBeenCalledOnce();
  });
  it.each([
    { code: "catalog_conflict", message: "This catalog entry conflicts with an existing entry." },
    { code: "invalidates_selection", message: "This change would invalidate saved chapter gear." },
    { code: "camera_used", message: "This camera is used by a chapter and cannot be deleted." },
    { code: "lens_used", message: "This lens is used by a chapter and cannot be deleted." },
  ])("should explain catalog rejection $code without retrying its mutation", async ({ code, message }) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ code }), { status: 409 }));
    const client = makeHttpAlbumClient(fetcher);

    await expect(client.deleteCamera("camera")).rejects.toEqual(new AlbumRequestError(409, message));
    expect(fetcher).toHaveBeenCalledOnce();
  });
  it("should create and edit each catalog resource with exact codes, complete acknowledgements and no mutation replay", async () => {
    const camera = {
      code: "  Camera code  ",
      name: "山 body",
      kind: "interchangeable_lens" as const,
      mount: "sony_e" as const,
      sensorFormat: "aps_c" as const,
    };
    const lens = { code: "Original lens code", name: "山 lens", mount: "nikon_z" as const, coverage: "full_frame" as const };
    const createdCamera = { id: "camera/id", ...camera };
    const createdLens = { id: "lens/id", ...lens };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(createdCamera), { status: 201 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(createdLens), { status: 201 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = makeHttpAlbumClient(fetcher);

    expect(await client.createCamera(camera)).toEqual(createdCamera);
    await client.updateCamera(createdCamera.id, camera);
    await client.deleteCamera(createdCamera.id);
    expect(await client.createLens(lens)).toEqual(createdLens);
    await client.updateLens(createdLens.id, lens);
    await client.deleteLens(createdLens.id);

    expect(fetcher.mock.calls.map(([url, init]) => [url, init?.method, init?.body])).toEqual([
      ["/api/cameras", "POST", JSON.stringify(camera)],
      ["/api/cameras/camera%2Fid", "PUT", JSON.stringify(camera)],
      ["/api/cameras/camera%2Fid", "DELETE", undefined],
      ["/api/lenses", "POST", JSON.stringify(lens)],
      ["/api/lenses/lens%2Fid", "PUT", JSON.stringify(lens)],
      ["/api/lenses/lens%2Fid", "DELETE", undefined],
    ]);
  });
  it("should acquire shared gear and replace only scoped chapter selections", async () => {
    const camera = { id: "camera", code: "Camera code", name: "Saved camera", kind: "fixed_lens" };
    const selection = [{ cameraId: camera.id, lensIds: [] }];
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ cameras: [camera], lenses: [] })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ cameras: selection })))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = makeHttpAlbumClient(fetcher);

    expect(await client.gearCatalog()).toEqual({ cameras: [camera], lenses: [] });
    expect(await client.chapterGear("album/id", "chapter/id")).toEqual(selection);
    await client.saveChapterGear("album/id", "chapter/id", selection);

    expect(fetcher.mock.calls.map(([url, init]) => [url, init?.method, init?.body])).toEqual([
      ["/api/gear", "GET", undefined],
      ["/api/albums/album%2Fid/chapters/chapter%2Fid/gear", "GET", undefined],
      ["/api/albums/album%2Fid/chapters/chapter%2Fid/gear", "PUT", JSON.stringify({ cameras: selection })],
    ]);
  });
  it.each(["not JSON", '{"id":"album"}'])("should distinguish acknowledged malformed saved metadata %s from an uncertain write", async (body) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(body, { status: 200 }));
    const client = makeHttpAlbumClient(fetcher);

    await expect(client.saveSynopsis("album", "Draft")).rejects.toEqual(
      new AcknowledgedWriteError("The write was acknowledged, but its saved response could not be read."),
    );
    expect(fetcher).toHaveBeenCalledOnce();
  });
  it.each(["top_left", "top_right", "bottom_left", "bottom_right"] as const)(
    "should save %s independently and publish the complete saved spread",
    async (captionCorner) => {
      const transform = { x: 3, y: 4, zoom: 2, mirrored: true };
      const slot = { id: "slot", kind: "photo", x: 0, y: 0, width: 100, height: 100 };
      const placement = { id: "placement", slotId: slot.id, photoId: "photo", captionCorner, transform };
      const wire = { id: "spread/id", kind: "photos_only", templateId: "template", slots: [slot], placements: [placement] };
      const saved = {
        kind: "spread",
        id: wire.id,
        layout: { kind: wire.kind, templateId: wire.templateId, slots: wire.slots },
        placements: [{ kind: "spread", ...placement }],
      };
      const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(wire)));
      const client = makeHttpAlbumClient(fetcher);

      const result = await client.editPlacements(
        "album/id",
        { kind: "spread", id: wire.id },
        { operation: "captionCorner", placementId: placement.id, captionCorner },
      );

      expect(result).toEqual(saved);
      expect(fetcher).toHaveBeenCalledExactlyOnceWith("/api/albums/album%2Fid/spreads/spread%2Fid/placements/placement", {
        method: "PATCH",
        headers: { Accept: "application/json", "Content-Type": "application/json" },
        body: JSON.stringify({ captionCorner }),
      });
      await expect(
        client.editPlacements("album", { kind: "cover" }, { operation: "captionCorner", placementId: placement.id, captionCorner }),
      ).rejects.toThrow("Caption corners belong to interior placements");
      expect(fetcher).toHaveBeenCalledOnce();
    },
  );
  it("should save editorial resources with exact bodies and complete saved album metadata", async () => {
    const album = {
      id: "album/id",
      title: "Saved title",
      year: 2026,
      createdAt: "2026-01-01T00:00:00Z",
      synopsis: "  山\n\n ",
      authors: ["Cosmin", "Iacob"],
    };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(album)))
      .mockResolvedValueOnce(new Response(JSON.stringify(album)))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = makeHttpAlbumClient(fetcher);

    expect(await client.saveSynopsis(album.id, album.synopsis)).toEqual(album);
    expect(await client.saveAuthors(album.id, ["Cosmin", "Iacob"])).toEqual(album);
    await client.saveDescription(album.id, "chapter/id", "");
    await client.saveCaption("chapter/id", "photo/id", "  山\n\n ");
    expect(fetcher.mock.calls.map(([url, init]) => [url, init?.method, init?.body])).toEqual([
      ["/api/albums/album%2Fid/synopsis", "PUT", JSON.stringify({ text: album.synopsis })],
      ["/api/albums/album%2Fid/authors", "PUT", '{"authors":["Cosmin","Iacob"]}'],
      ["/api/albums/album%2Fid/chapters/chapter%2Fid/description", "PUT", '{"text":""}'],
      ["/api/chapters/chapter%2Fid/photos/photo%2Fid/caption", "PUT", JSON.stringify({ text: album.synopsis })],
    ]);
  });
  it("should independently decode populated chapter, composition, spread and country resources with cancellation", async () => {
    const chapter = {
      id: "chapter",
      kind: "home",
      name: "Home",
      countries: ["ro"],
      start: "2026-01-01",
      end: "2026-12-31",
      sourceFolder: "/photos/Home",
      description: "",
    };
    const spread = { id: "spread", kind: "chapter_start", templateId: "layout", slots: [], placements: [] };
    const composition = [
      { id: "opening", chapter: null },
      { id: "spread", chapter: "chapter" },
    ];
    const country = { id: "ro", name: "Romania", code: "RO" };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ chapters: [chapter] })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ spreads: composition })))
      .mockResolvedValueOnce(new Response(JSON.stringify(spread)))
      .mockResolvedValueOnce(new Response(JSON.stringify({ countries: [country] })));
    const client = makeHttpAlbumClient(fetcher);
    const signal = new AbortController().signal;

    expect(await client.chapters("album", signal)).toEqual([chapter]);
    expect(await client.composition("album", signal)).toEqual(composition);
    expect(await client.spread("album", "spread", "chapter", signal)).toEqual({ ...spread, chapter: "chapter" });
    expect(await client.countries(signal)).toEqual([country]);
    expect(fetcher.mock.calls.every(([, init]) => init?.signal === signal)).toBe(true);
  });

  it.each(["cover", "spread"] as const)("should create and update individual %s placement resources without operation envelopes", async (kind) => {
    const context: PlacementContext = kind === "cover" ? { kind } : { kind, id: "spread" };
    const snapshot =
      kind === "cover"
        ? { frontSlotId: "front", backSlotId: "back", spineOwner: "front", placements: [] }
        : { id: "spread", kind: "photos_only", templateId: "layout", slots: [], placements: [] };
    const transform = { x: 0, y: 0, zoom: 1, mirrored: false };
    const edits: PlacementEdit[] = [
      { operation: "place", slotId: "slot", photoId: "photo" },
      { operation: "adjust", placementId: "p", transform },
      { operation: "move", placementId: "p", slotId: "other" },
    ];
    const fetcher = vi.fn<typeof fetch>().mockImplementation(() => Promise.resolve(new Response(JSON.stringify(snapshot))));
    const client = makeHttpAlbumClient(fetcher);

    for (const edit of edits) await client.editPlacements("album", context, edit);

    const base = `/api/albums/album/${kind === "cover" ? "cover" : "spreads/spread"}/placements`;
    expect(fetcher.mock.calls.map(([url, init]) => [url, init?.method, init?.body])).toEqual([
      [base, "POST", '{"slotId":"slot","photoId":"photo"}'],
      [`${base}/p`, "PATCH", JSON.stringify({ transform })],
      [`${base}/p`, "PATCH", '{"slotId":"other"}'],
    ]);
  });

  it.each([
    [[], "missing", "earlier", "This spread cannot be moved"],
    [[{ id: "opening", chapter: null }], "opening", "later", "This spread cannot be moved"],
    [
      [
        { id: "opening", chapter: "c" },
        { id: "first", chapter: "c" },
      ],
      "opening",
      "later",
      "No neighboring photo spread",
    ],
    [
      [
        { id: "opening", chapter: "c" },
        { id: "first", chapter: "c" },
      ],
      "first",
      "earlier",
      "No neighboring photo spread",
    ],
    [
      [
        { id: "opening", chapter: "c" },
        { id: "first", chapter: "c" },
      ],
      "first",
      "later",
      "No neighboring photo spread",
    ],
  ] as const)("should reject invalid displayed composition movement", (spreads, target, direction, message) => {
    const displayed = spreads.map<Spread>((member) =>
      member.chapter === null
        ? { ...member, kind: "album_start", chapter: null, templateId: "layout", slots: [], placements: [] }
        : { ...member, kind: "photos_only", chapter: member.chapter, templateId: "layout", slots: [], placements: [] },
    );
    expect(() => buildChapterReorder(displayed, target, direction)).toThrow(message);
  });

  it("should replace complete chapter membership when moving later while retaining unrelated chapter members", async () => {
    const displayed: Spread[] = ["opening", "first", "second"].map((id) => ({
      id,
      kind: "photos_only",
      chapter: "c",
      templateId: "layout",
      slots: [],
      placements: [],
    }));
    const replacement = buildChapterReorder(displayed, "first", "later");
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(new Response(null, { status: 204 }));
    await makeHttpAlbumClient(fetcher).replaceChapterComposition("album", replacement.chapter, replacement.expectedSpreadIds, replacement.spreadIds);
    expect(fetcher).toHaveBeenCalledOnce();
    expect(fetcher.mock.calls[0]?.[1]?.body).toBe('{"expectedSpreadIds":["opening","first","second"],"spreadIds":["opening","second","first"]}');
  });

  it("should expose a changed observed composition without reacquiring or replaying a resource replacement", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response('{"code":"composition_changed"}', { status: 409 }));
    await expect(
      makeHttpAlbumClient(fetcher).replaceChapterComposition("album", "chapter", ["opening", "first", "second"], ["opening", "second", "first"]),
    ).rejects.toEqual(new AlbumRequestError(409, "This chapter's composition changed. Reload saved workspace before choosing the action again."));
    expect(fetcher).toHaveBeenCalledOnce();
  });
  it("should use merged spread routes and preserve occupied counts until explicit confirmation", async () => {
    const template = { id: "layout", name: "P", slots: [{ kind: "photo", x: 0, y: 0, width: 100, height: 100 }] };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ templates: [template] })))
      .mockResolvedValueOnce(new Response('{"id":"new"}', { status: 201 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response('{"code":"spread_occupied","placements":7}', { status: 409 }))
      .mockResolvedValueOnce(new Response('{"id":"new","templateId":"layout"}'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = makeHttpAlbumClient(fetcher);
    const expectedPaths = [
      "/api/templates/photos-only",
      "/api/albums/album%2Fid/chapters/chapter%2Fid/spreads",
      "/api/albums/album%2Fid/chapters/chapter/composition",
      "/api/albums/album%2Fid/spreads/new/template",
      "/api/albums/album%2Fid/spreads/new/template?confirmPlacements=true",
      "/api/albums/album%2Fid/spreads/new?confirmPlacements=true",
    ];

    const templates = await client.templates("photos_only");
    const inserted = await client.addSpread("album/id", "chapter/id", "opening");
    await client.replaceChapterComposition("album/id", "chapter", ["opening", "before", "new"], ["opening", "new", "before"]);
    const occupied = await client.changeSpreadTemplate("album/id", "new", "layout", false).catch((failure: unknown) => failure);
    await client.changeSpreadTemplate("album/id", "new", "layout", true);
    await client.deleteSpread("album/id", "new", true);

    expect(templates).toEqual([template]);
    expect(inserted).toBe("new");
    expect(occupied).toMatchObject({ placements: 7 });
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual(expectedPaths);
    expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ method: "POST", body: '{"afterSpreadId":"opening"}' });
    expect(fetcher.mock.calls[2]?.[1]).toMatchObject({
      method: "PUT",
      body: '{"expectedSpreadIds":["opening","before","new"],"spreadIds":["opening","new","before"]}',
    });
  });

  it.each([{ slots: [] }, { slots: [{ kind: "text", x: 0, y: 0, width: 100, height: 100 }] }])(
    "should reject a catalog without photo slots: %j",
    async ({ slots }) => {
      const fetcher = vi
        .fn<typeof fetch>()
        .mockResolvedValue(new Response(JSON.stringify({ templates: [{ id: "invalid", name: "Invalid", slots }] })));
      const client = makeHttpAlbumClient(fetcher);

      await expect(client.templates("album_start")).rejects.toThrow("A spread template must contain a photo slot");
    },
  );

  it.each([
    [409, { code: "spread_occupied", placements: 0 }, "Invalid placement count"],
    [409, { code: "spread_occupied", placements: 1.5 }, "Invalid placement count"],
    [404, { code: "spread_not_found" }, "This spread could not be found."],
  ])("should reject spread failure %i %j at the HTTP boundary", async (status, body, message) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(body), { status }));
    const client = makeHttpAlbumClient(fetcher);

    await expect(client.deleteSpread("album", "spread", false)).rejects.toThrow(message);
    expect(fetcher.mock.calls[0]?.[0]).toBe("/api/albums/album/spreads/spread");
  });
  it("should load persisted workspace geometry and send only type-specific chapter inputs", async () => {
    const workspace = { year: 2026, photoRoot: "/nas/photos", chapters: [], countries: [], spreads: [], photoInventories: {} };
    const vacation = {
      kind: "vacation" as const,
      name: "Kyoto",
      countries: ["jp"],
      start: "2025-12-31",
      end: "2026-01-01",
      sourceFolder: "/photos/Kyoto",
    };
    const home = { kind: "home" as const, countries: ["ro"], sourceFolder: "/photos/Home" };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response('{"chapters":[]}'))
      .mockResolvedValueOnce(new Response('{"spreads":[]}'))
      .mockResolvedValueOnce(new Response('{"countries":[]}'))
      .mockResolvedValueOnce(new Response('{"photoRoot":"/nas/photos"}'))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: "vacation", scheduling: "failed" }), { status: 201 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: "chapter/id", scheduling: "not-requested" })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: "home", scheduling: "queued" }), { status: 201 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: "home", scheduling: "coalesced" })));
    const client = makeHttpAlbumClient(fetcher);
    const expectedVacation = {
      name: vacation.name,
      start: vacation.start,
      end: vacation.end,
      kind: "vacation",
      countries: vacation.countries,
      sourceFolder: vacation.sourceFolder,
    };
    const expectedHome = { kind: "home", countries: home.countries, sourceFolder: home.sourceFolder };
    const expectedVacationCreation = ["/api/albums/album%2Fid/chapters", { method: "POST", body: JSON.stringify(vacation) }];
    const expectedVacationUpdate = ["/api/albums/album%2Fid/chapters/chapter%2Fid", { method: "PUT", body: JSON.stringify(expectedVacation) }];

    const signal = new AbortController().signal;
    const [chapters, composition, countries, photoRoot] = await Promise.all([
      client.chapters("album/id", signal),
      client.composition("album/id", signal),
      client.countries(signal),
      client.photoSource(signal),
    ]);
    const createdVacation = await client.createChapter("album/id", vacation);
    const updatedVacation = await client.updateChapter("album/id", "chapter/id", vacation);
    const createdHome = await client.createChapter("album/id", home);
    const updatedHome = await client.updateChapter("album/id", "home", home);

    expect({ chapters, composition, countries, photoRoot }).toEqual({
      chapters: workspace.chapters,
      composition: [],
      countries: workspace.countries,
      photoRoot: workspace.photoRoot,
    });
    expect(createdVacation).toEqual({ id: "vacation", scheduling: "failed" });
    expect(updatedVacation).toEqual({ id: "chapter/id", scheduling: "not-requested" });
    expect(createdHome).toEqual({ id: "home", scheduling: "queued" });
    expect(updatedHome).toEqual({ id: "home", scheduling: "coalesced" });
    expect(fetcher.mock.calls.slice(0, 4).map(([url]) => url)).toEqual([
      "/api/albums/album%2Fid/chapters",
      "/api/albums/album%2Fid/composition",
      "/api/countries",
      "/api/photo-source",
    ]);
    expect(fetcher.mock.calls[4]).toMatchObject(expectedVacationCreation);
    expect(fetcher.mock.calls[5]).toMatchObject(expectedVacationUpdate);
    expect(fetcher.mock.calls[6]?.[1]).toMatchObject({ method: "POST", body: JSON.stringify(home) });
    expect(fetcher.mock.calls[7]?.[1]).toMatchObject({ method: "PUT", body: JSON.stringify(expectedHome) });
  });
  it("should read chapter inventories with cancellation and schedule Refresh without a request body", async () => {
    const inventory = {
      photos: [],
      placedPhotoIds: [],
      counts: { unplaced: 0, total: 0 },
      processing: { state: "failed", unsupportedCount: 2, skippedCount: 1, issues: [{ stage: "cleanup", code: "cleanup_failed", filename: null }] },
    };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(inventory)))
      .mockResolvedValueOnce(new Response('{"scheduling":"queued"}', { status: 202 }))
      .mockResolvedValueOnce(new Response('{"scheduling":"coalesced"}', { status: 202 }))
      .mockResolvedValueOnce(new Response('{"scheduling":"failed"}', { status: 202 }))
      .mockResolvedValueOnce(new Response('{"code":"service_failure"}', { status: 500 }));
    const client = makeHttpAlbumClient(fetcher);
    const controller = new AbortController();
    const expectedFailure = new AlbumRequestError(500, "The request could not be completed. Please try again.");
    const expectedRead = ["/api/chapters/chapter%2Fid/photos", { method: "GET", headers: { Accept: "application/json" }, signal: controller.signal }];
    const expectedRefresh = ["/api/chapters/chapter%2Fid/photo-import", { method: "PUT", headers: { Accept: "application/json" } }];

    const loaded = await client.chapterPhotos("chapter/id", controller.signal);
    const queued = await client.refreshPhotos("chapter/id");
    const coalesced = await client.refreshPhotos("chapter/id");

    expect(loaded).toEqual(inventory);
    expect(queued).toBe("queued");
    expect(coalesced).toBe("coalesced");
    await expect(client.refreshPhotos("chapter/id")).rejects.toThrow();
    await expect(client.chapterPhotos("chapter/id", controller.signal)).rejects.toEqual(expectedFailure);
    expect(fetcher.mock.calls[0]).toEqual(expectedRead);
    expect(fetcher.mock.calls[1]).toEqual(expectedRefresh);
  });
  it("should send dashboard album operations with immutable year creation and free-form rename", async () => {
    const album = {
      id: "00000000-0000-0000-0000-000000000001",
      year: 2026,
      title: "Journey of the Shepherds 2026",
      createdAt: "2026-10-24T12:00:00Z",
    };
    const page = { albums: [album], page: 1, pageSize: 6, totalAlbums: 1, totalPages: 1 };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(page)))
      .mockResolvedValueOnce(new Response(JSON.stringify(album), { status: 201 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...album, synopsis: "", authors: ["Cosmin", "Mara", "Iacob"] })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...album, title: "Asian capitals" })))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = makeHttpAlbumClient(fetcher);
    const expectedUrls = ["/api/albums?page=1", "/api/albums", `/api/albums/${album.id}`, `/api/albums/${album.id}`, `/api/albums/${album.id}`];
    const expectedCreation = { method: "POST", body: JSON.stringify({ year: album.year }), headers: { "Content-Type": "application/json" } };

    const listed = await client.list(1);
    const created = await client.create(album.year);
    const opened = await client.open(album.id);
    const renamed = await client.rename(album.id, "Asian capitals");
    await client.remove(album.id);

    expect(listed).toEqual(page);
    expect(created).toEqual(album);
    expect(opened).toEqual({ ...album, synopsis: "", authors: ["Cosmin", "Mara", "Iacob"] });
    expect(renamed).toEqual({ ...album, title: "Asian capitals" });
    expect(fetcher.mock.calls.map((call) => call[0])).toEqual(expectedUrls);
    expect(fetcher.mock.calls[1]?.[1]).toMatchObject(expectedCreation);
    expect(fetcher.mock.calls[2]?.[1]).toMatchObject({ method: "GET" });
    expect(fetcher.mock.calls[3]?.[1]).toMatchObject({ method: "PATCH", body: JSON.stringify({ title: "Asian capitals" }) });
    expect(fetcher.mock.calls[4]?.[1]).toMatchObject({ method: "DELETE" });
  });

  it.each([
    [400, { message: "Title is blank" }, "Title is blank"],
    [404, { code: "album_not_found" }, "The request could not be completed. Please try again."],
    [500, { code: "service_failure" }, "The request could not be completed. Please try again."],
  ])("should preserve HTTP %i failures without exposing success", async (status, body, message) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(body), { status }));
    const client = makeHttpAlbumClient(fetcher);

    await expect(client.open("missing")).rejects.toEqual(new AlbumRequestError(status, message));
  });

  it("should reject network failures, invalid JSON and invalid success shapes", async () => {
    const failure = new Error("Connection unavailable");
    const fetcher = vi
      .fn<typeof fetch>()
      .mockRejectedValueOnce(failure)
      .mockResolvedValueOnce(new Response("<html>not an API</html>"))
      .mockResolvedValueOnce(new Response(JSON.stringify({ albums: [] })));
    const client = makeHttpAlbumClient(fetcher);

    await expect(client.list(1)).rejects.toBe(failure);
    await expect(client.list(1)).rejects.toBeInstanceOf(SyntaxError);
    await expect(client.list(1)).rejects.toBeInstanceOf(Error);
  });

  it("should reject a deletion response that violates the no-content contract", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response("{}"));
    const client = makeHttpAlbumClient(fetcher);

    await expect(client.remove("album")).rejects.toThrow("The write was acknowledged, but its saved response could not be read.");
  });
});
