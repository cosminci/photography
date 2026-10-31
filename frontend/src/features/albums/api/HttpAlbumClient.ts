import { AlbumRequestError, SpreadOccupiedError, type AlbumClient } from "../model/Album";
import { readAlbum, readFailureMessage, readPage, readSummary, readRecord, readText, readNumber } from "./Wire";
import { readChapter, readChapterIdentity, readCountry, readTemplates } from "./WorkspaceWire";
import { readArray } from "./Wire";
import { readPhotoInventory, readScheduling } from "./PhotoWire";
import { readPlacementSnapshot, readSpread } from "./PlacementWire";
import type {
  CoverContext,
  CoverSnapshot,
  PlacementContext,
  PlacementEdit,
  PlacementSnapshot,
  SpreadContext,
  SpreadSnapshot,
} from "../model/Placement";

export function makeHttpAlbumClient(fetcher: typeof fetch): AlbumClient {
  async function request<T>(
    path: string,
    method: string,
    body: unknown,
    expectedStatus: number,
    decode: (value: unknown) => T,
    signal?: AbortSignal,
  ): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    const init: RequestInit = { method, headers };
    if (signal !== undefined) init.signal = signal;
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(body);
    }
    const response = await fetcher(path, init);
    if (!response.ok) {
      const failure: unknown = await response.json();
      const fields = readRecord(failure);
      if (response.status === 409 && fields["code"] === "spread_occupied") {
        const placements = readNumber(fields["placements"]);
        if (!Number.isInteger(placements) || placements < 1) throw new Error("Invalid placement count");
        throw new SpreadOccupiedError(placements);
      }
      if (response.status === 404 && fields["code"] === "spread_not_found")
        throw new AlbumRequestError(404, "This spread could not be found. Reload the workspace and try again.");
      if (response.status === 409 && fields["code"] === "composition_changed")
        throw new AlbumRequestError(409, "This chapter's composition changed. Reload saved workspace before choosing the action again.");
      throw new AlbumRequestError(response.status, readFailureMessage(failure));
    }
    if (response.status !== expectedStatus) throw new Error("Unexpected album response status");
    const value: unknown = response.status === 204 ? undefined : await response.json();
    return decode(value);
  }

  function readPlacements(id: string, context: CoverContext): Promise<CoverSnapshot>;
  function readPlacements(id: string, context: SpreadContext): Promise<SpreadSnapshot>;
  function readPlacements(id: string, context: PlacementContext): Promise<PlacementSnapshot>;
  function readPlacements(id: string, context: PlacementContext): Promise<PlacementSnapshot> {
    return request(resourcePath(id, context), "GET", undefined, 200, (value) => readPlacementSnapshot(value, context));
  }

  function editPlacements(id: string, context: CoverContext, edit: PlacementEdit): Promise<CoverSnapshot>;
  function editPlacements(id: string, context: SpreadContext, edit: PlacementEdit): Promise<SpreadSnapshot>;
  function editPlacements(id: string, context: PlacementContext, edit: PlacementEdit): Promise<PlacementSnapshot>;
  function editPlacements(id: string, context: PlacementContext, edit: PlacementEdit): Promise<PlacementSnapshot> {
    const base = `${resourcePath(id, context)}/placements`;
    if (edit.operation === "place")
      return request(base, "POST", { slotId: edit.slotId, photoId: edit.photoId }, 200, (value) => readPlacementSnapshot(value, context));
    const path = `${base}/${encodeURIComponent(edit.placementId)}`;
    const body = edit.operation === "adjust" ? { transform: edit.transform } : edit.operation === "move" ? { slotId: edit.slotId } : undefined;
    return request(path, edit.operation === "remove" ? "DELETE" : "PATCH", body, 200, (value) => readPlacementSnapshot(value, context));
  }

  return {
    readPlacements,
    editPlacements,
    templates: (context) => {
      const paths = { photos_only: "photos-only", chapter_start: "chapter-start", album_start: "album-start" };
      return request(`/api/templates/${paths[context]}`, "GET", undefined, 200, readTemplates);
    },
    addSpread: (id, chapterId, afterSpreadId) =>
      request(`/api/albums/${encodeURIComponent(id)}/chapters/${encodeURIComponent(chapterId)}/spreads`, "POST", { afterSpreadId }, 201, (value) =>
        readText(readRecord(value)["id"]),
      ),
    replaceChapterComposition: (id, chapter, expectedSpreadIds, spreadIds) =>
      request(
        `/api/albums/${encodeURIComponent(id)}/chapters/${encodeURIComponent(chapter)}/composition`,
        "PUT",
        { expectedSpreadIds, spreadIds },
        204,
        () => undefined,
      ),
    changeSpreadTemplate: (id, spreadId, templateId, confirmPlacements) =>
      request(
        `/api/albums/${encodeURIComponent(id)}/spreads/${encodeURIComponent(spreadId)}/template${confirmPlacements ? "?confirmPlacements=true" : ""}`,
        "PUT",
        { templateId },
        200,
        (value) => {
          const fields = readRecord(value);
          readText(fields["id"]);
          readText(fields["templateId"]);
        },
      ),
    deleteSpread: (id, spreadId, confirmPlacements) =>
      request(
        `/api/albums/${encodeURIComponent(id)}/spreads/${encodeURIComponent(spreadId)}${confirmPlacements ? "?confirmPlacements=true" : ""}`,
        "DELETE",
        undefined,
        204,
        () => undefined,
      ),
    list: (page) => request(`/api/albums?page=${String(page)}`, "GET", undefined, 200, readPage),
    create: (year) => request("/api/albums", "POST", { year }, 201, readSummary),
    rename: (id, title) => request(`/api/albums/${encodeURIComponent(id)}`, "PATCH", { title }, 200, readSummary),
    open: (id, signal) => request(`/api/albums/${encodeURIComponent(id)}`, "GET", undefined, 200, readAlbum, signal),
    remove: (id) => request(`/api/albums/${encodeURIComponent(id)}`, "DELETE", undefined, 204, () => undefined),
    chapters: (id, signal) =>
      request(
        `/api/albums/${encodeURIComponent(id)}/chapters`,
        "GET",
        undefined,
        200,
        (value) => readArray(readRecord(value)["chapters"]).map(readChapter),
        signal,
      ),
    composition: (id, signal) =>
      request(
        `/api/albums/${encodeURIComponent(id)}/composition`,
        "GET",
        undefined,
        200,
        (value) =>
          readArray(readRecord(value)["spreads"]).map((entry) => {
            const fields = readRecord(entry);
            return { id: readText(fields["id"]), chapter: fields["chapter"] === null ? null : readText(fields["chapter"]) };
          }),
        signal,
      ),
    spread: (id, spreadId, chapter, signal) =>
      request(
        `/api/albums/${encodeURIComponent(id)}/spreads/${encodeURIComponent(spreadId)}`,
        "GET",
        undefined,
        200,
        (value) => readSpread({ ...readRecord(value), chapter }),
        signal,
      ),
    countries: (signal) =>
      request("/api/countries", "GET", undefined, 200, (value) => readArray(readRecord(value)["countries"]).map(readCountry), signal),
    photoSource: (signal) => request("/api/photo-source", "GET", undefined, 200, (value) => readText(readRecord(value)["photoRoot"]), signal),
    chapterPhotos: (id, signal) => request(`/api/chapters/${encodeURIComponent(id)}/photos`, "GET", undefined, 200, readPhotoInventory, signal),
    refreshPhotos: (id) =>
      request(`/api/chapters/${encodeURIComponent(id)}/photo-import`, "PUT", undefined, 202, (value) => {
        const scheduling = readScheduling(value);
        if (scheduling !== "queued" && scheduling !== "coalesced") throw new Error("Refresh was not scheduled");
        return scheduling;
      }),
    createChapter: (id, details) => request(`/api/albums/${encodeURIComponent(id)}/chapters`, "POST", details, 201, readChapterIdentity),
    updateChapter: (id, chapterId, details) => {
      const common = { kind: details.kind, countries: details.countries, sourceFolder: details.sourceFolder };
      const body = details.kind === "home" ? common : { name: details.name, start: details.start, end: details.end, ...common };
      return request(`/api/albums/${encodeURIComponent(id)}/chapters/${encodeURIComponent(chapterId)}`, "PUT", body, 200, readChapterIdentity);
    },
  };
}

function resourcePath(id: string, context: PlacementContext): string {
  const target = context.kind === "cover" ? "cover" : `spreads/${encodeURIComponent(context.id)}`;
  return `/api/albums/${encodeURIComponent(id)}/${target}`;
}
