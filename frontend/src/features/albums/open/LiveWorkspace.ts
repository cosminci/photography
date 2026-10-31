import { createEffect, createSignal, on, onCleanup, type Accessor } from "solid-js";
import { AcknowledgedWriteError, AlbumRequestError, type Album, type AlbumClient } from "../model/Album";
import type { AlbumPhotoEvents, ChapterSave, PhotoEvent, Scheduling } from "../model/Photo";
import type { Spread, SpreadMembership, Workspace } from "../model/Workspace";

export function createLiveWorkspace(
  albumId: Accessor<string>,
  client: Pick<AlbumClient, "open" | "chapters" | "composition" | "spread" | "countries" | "photoSource" | "chapterPhotos" | "refreshPhotos">,
  events: AlbumPhotoEvents,
) {
  const [album, setAlbum] = createSignal<Album>();
  const [workspace, setWorkspace] = createSignal<Workspace>();
  const [loading, setLoading] = createSignal(true);
  const [error, setError] = createSignal<unknown>();
  const [liveStatus, setLiveStatus] = createSignal<"connecting" | "catching-up" | "live" | "interrupted" | "invalid">("connecting");
  const [inventoryErrors, setInventoryErrors] = createSignal<Readonly<Record<string, string>>>({});
  const [scheduling, setScheduling] = createSignal<Readonly<Record<string, Scheduling | "pending">>>({});
  let metadataRevision = 0;
  let operations: {
    reload: () => Promise<void>;
    retryChapter: (id: string) => void;
    refresh: (ids: readonly string[]) => Promise<void>;
    saved: (result: ChapterSave) => Promise<void>;
  };

  createEffect(
    on(albumId, (id) => {
      let active = true;
      let epoch = 0;
      let reading: Promise<void> | undefined;
      let readAgain = false;
      const invalidated = new Set<string>();
      const chapters = new Map<string, { running: boolean; dirty: boolean }>();
      const commands = new Map<string, Promise<void>>();
      const controller = new AbortController();
      let metadataRead: Promise<Album> | undefined;
      setAlbum(undefined);
      setWorkspace(undefined);
      setError(undefined);
      setLoading(true);
      setLiveStatus("connecting");
      setInventoryErrors({});
      setScheduling({});

      function readAlbum(): Promise<Album> {
        if (metadataRead !== undefined) return metadataRead;
        const revision = metadataRevision;
        metadataRead = client
          .open(id, controller.signal)
          .then((metadata) => {
            if (active && revision !== metadataRevision) throw new AcquisitionConflict("Album content changed during acquisition");
            if (active && workspace() === undefined) setAlbum(metadata);
            return metadata;
          })
          .finally(() => {
            metadataRead = undefined;
          });
        return metadataRead;
      }

      async function readSpread(member: SpreadMembership): Promise<Spread> {
        const spread = await readExistingMember(client.spread(id, member.id, member.chapter, controller.signal));
        if (spread.id !== member.id || spread.chapter !== member.chapter) throw new AcquisitionConflict("Spread identity mismatch");
        return spread;
      }

      function reload(): Promise<void> {
        if (!active) return Promise.resolve();
        if (reading !== undefined) {
          readAgain = true;
          return reading;
        }
        const run = async () => {
          let failure: Error | undefined;
          let reconciliationAttempts = 0;
          setLoading(true);
          do {
            readAgain = false;
            epoch++;
            for (const chapter of chapters.values()) chapter.dirty = false;
            try {
              const revision = metadataRevision;
              const [metadata, orderedChapters, composition, countries, photoRoot] = await Promise.all([
                readAlbum(),
                client.chapters(id, controller.signal),
                client.composition(id, controller.signal),
                client.countries(controller.signal),
                client.photoSource(controller.signal),
              ]);
              if (!active) return;
              if (metadata.id !== id) throw new Error("Unexpected album identity");
              const [spreads, inventories] = await Promise.all([
                Promise.all(composition.map(readSpread)),
                Promise.all(
                  orderedChapters.map(
                    async (chapter) => [chapter.id, await readExistingMember(client.chapterPhotos(chapter.id, controller.signal))] as const,
                  ),
                ),
              ]);
              if (revision !== metadataRevision) throw new AcquisitionConflict("Album content changed during workspace acquisition");
              const snapshot: Workspace = {
                year: metadata.year,
                chapters: orderedChapters,
                countries,
                photoRoot,
                spreads,
                photoInventories: Object.fromEntries(inventories),
              };
              requireCoherentWorkspace(snapshot);
              if (controller.signal.aborted) return;
              setAlbum(metadata);
              setWorkspace(snapshot);
              setInventoryErrors({});
              failure = undefined;
            } catch (cause) {
              failure = cause instanceof Error ? cause : new Error("Saved workspace could not be loaded");
              if (cause instanceof AcquisitionConflict && reconciliationAttempts < 2) {
                reconciliationAttempts++;
                readAgain = true;
              }
            }
          } while (needsAnotherRead());
          reading = undefined;
          if (!active) return;
          setError(() => failure);
          setLoading(false);
          if (liveStatus() === "catching-up" && failure === undefined) setLiveStatus("live");
          const pending = [...invalidated];
          invalidated.clear();
          for (const chapter of pending) retryChapter(chapter);
        };
        reading = run();
        return reading;
      }

      function needsAnotherRead(): boolean {
        return active && readAgain;
      }

      function retryChapter(chapterId: string): void {
        if (!active) return;
        if (reading !== undefined) {
          invalidated.add(chapterId);
          return;
        }
        if (workspace()?.chapters.some((chapter) => chapter.id === chapterId) !== true) {
          void reload();
          return;
        }
        const state = chapters.get(chapterId) ?? { running: false, dirty: false };
        chapters.set(chapterId, state);
        if (state.running) {
          state.dirty = true;
          return;
        }
        const started = epoch;
        state.running = true;
        void client
          .chapterPhotos(chapterId, controller.signal)
          .then((inventory) => {
            const snapshot = workspace();
            if (snapshot === undefined || !active || started !== epoch) return;
            setWorkspace({ ...snapshot, photoInventories: { ...snapshot.photoInventories, [chapterId]: inventory } });
            setInventoryErrors((current) => Object.fromEntries(Object.entries(current).filter(([key]) => key !== chapterId)));
          })
          .catch(() => {
            if (active && started === epoch)
              setInventoryErrors((current) => ({
                ...current,
                [chapterId]: "Photo inventory could not be loaded. Retained photos may be out of date.",
              }));
          })
          .finally(() => {
            state.running = false;
            if (active && state.dirty) {
              state.dirty = false;
              retryChapter(chapterId);
            }
          });
      }

      async function refresh(ids: readonly string[]): Promise<void> {
        if (!active) return;
        await Promise.all(
          ids.map((chapterId) => {
            const existing = commands.get(chapterId);
            if (existing !== undefined) return existing;
            setScheduling((current) => ({ ...current, [chapterId]: "pending" }));
            const command = client
              .refreshPhotos(chapterId)
              .then((result) => {
                if (active) setScheduling((current) => ({ ...current, [chapterId]: result }));
              })
              .catch(() => {
                if (active) setScheduling((current) => ({ ...current, [chapterId]: "failed" }));
              })
              .finally(() => {
                commands.delete(chapterId);
              });
            commands.set(chapterId, command);
            return command;
          }),
        );
      }

      function receive(event: PhotoEvent) {
        if (!active) return;
        if (event.kind === "inventory-changed") retryChapter(event.chapterId);
        else if (event.kind === "subscribed") {
          setLiveStatus("catching-up");
          void reload();
        } else setLiveStatus(event.kind);
      }

      operations = {
        reload,
        retryChapter,
        refresh,
        saved: async (result) => {
          if (!active) return;
          setScheduling((current) => ({ ...current, [result.id]: result.scheduling }));
          await reload();
        },
      };
      let close: (() => void) | undefined;
      void readAlbum().catch((failure: unknown) => {
        if (active) {
          setError(() => failure);
          setLoading(false);
        }
      });
      try {
        close = events.subscribe(id, receive);
      } catch (failure) {
        setError(() => failure);
        setLoading(false);
        setLiveStatus("interrupted");
      }
      onCleanup(() => {
        active = false;
        controller.abort();
        close?.();
        invalidated.clear();
      });
    }),
  );
  return {
    album,
    workspace,
    loading,
    error,
    liveStatus,
    inventoryErrors,
    scheduling,
    reload: () => operations.reload(),
    retryChapter: (id: string) => {
      operations.retryChapter(id);
    },
    refresh: (ids: readonly string[]) => operations.refresh(ids),
    saved: (result: ChapterSave) => operations.saved(result),
    publishAlbum: (saved: Album) => {
      if (saved.id !== albumId()) throw new AcknowledgedWriteError("Unexpected saved album identity");
      metadataRevision++;
      setAlbum(saved);
    },
  };
}

class AcquisitionConflict extends Error {}

async function readExistingMember<T>(read: Promise<T>): Promise<T> {
  try {
    return await read;
  } catch (failure) {
    if (failure instanceof AlbumRequestError && failure.status === 404)
      throw new AcquisitionConflict("Composition changed during resource acquisition");
    throw failure;
  }
}

function requireCoherentWorkspace(snapshot: Workspace): void {
  const chapterIds = snapshot.chapters.map((chapter) => chapter.id);
  if (new Set(chapterIds).size !== chapterIds.length) throw new AcquisitionConflict("Duplicate chapter identities");
  const home = snapshot.chapters.findIndex((chapter) => chapter.kind === "home");
  if (home !== -1 && home !== chapterIds.length - 1) throw new AcquisitionConflict("Home must be the final chapter");
  if (new Set(snapshot.spreads.map((spread) => spread.id)).size !== snapshot.spreads.length)
    throw new AcquisitionConflict("Duplicate spread identities");

  let chapterIndex = -1;
  for (const [index, spread] of snapshot.spreads.entries()) {
    if (index === 0) {
      if (spread.kind !== "album_start") throw new AcquisitionConflict("Missing Synopsis opening");
    } else if (spread.kind === "chapter_start") {
      chapterIndex++;
      if (spread.chapter !== chapterIds[chapterIndex]) throw new AcquisitionConflict("Chapter composition changed during acquisition");
    } else if (spread.kind !== "photos_only" || spread.chapter !== chapterIds[chapterIndex]) {
      throw new AcquisitionConflict("Invalid chapter spread block");
    }
  }
  if (snapshot.spreads.length === 0 || chapterIndex + 1 !== chapterIds.length) throw new AcquisitionConflict("Incomplete chapter composition");
}
