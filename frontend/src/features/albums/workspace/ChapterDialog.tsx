import { Show, createEffect, createMemo, createSignal, onCleanup, onMount, untrack } from "solid-js";
import { AcknowledgedWriteError, AlbumRequestError, type AlbumClient } from "../model/Album";
import type { Chapter, ChapterDetails, Country } from "../model/Workspace";
import type { ChapterSave } from "../model/Photo";
import { sameGear, validateGear, type CameraSelection, type GearCatalog, type GearClient } from "../model/Gear";
import { describeFailure } from "../model/Presentation";
import { CountryPicker } from "./CountryPicker";
import { chapterDetails, displayCalendarDay, matchesChapterDetails, readCalendarDay, suggestFolder, validateChapter } from "./ChapterDetails";
import { createContentSave, type ContentSave } from "./ContentSave";
import { ChapterGearFields } from "./ChapterGearFields";
import { GearCatalogDialog } from "./GearCatalogDialog";
import type { ChapterBookState } from "./ChapterCompensation";
import "./gear.css";

export function ChapterDialog(props: {
  save: (details: ChapterDetails) => Promise<ChapterSave>;
  year: number;
  photoRoot: string;
  countries: readonly Country[];
  homeAvailable: boolean;
  chapter: Pick<Chapter, "kind" | "name" | "countries" | "start" | "end" | "sourceFolder"> | undefined;
  resources: { client: Pick<AlbumClient, "chapters"> & GearClient; albumId: string; maybeChapterId: string | undefined };
  onSaved: (result: ChapterSave) => void;
  onReconciled: (chapterId: string) => void;
  onBookSaved?: ((before: ChapterBookState, after: ChapterBookState) => void) | undefined;
  onHistoryBoundary?: () => void;
  maybeAuthority?: () => number | undefined;
  onBlock: (reason: string | undefined) => void;
  onClose: () => void;
  returnFocus: () => void;
}) {
  const existing = untrack(() => props.chapter);
  let originalDetails = existing === undefined ? undefined : chapterDetails(existing);
  let savedDetails: ChapterDetails | undefined;
  let sourceBoundary = false;
  const firstYear = existing?.start.slice(0, 4) ?? String(untrack(() => props.year));
  const lastYear = existing?.end.slice(0, 4) ?? String(untrack(() => props.year));
  const homeAvailable = untrack(() => props.homeAvailable);
  const resources = untrack(() => props.resources);
  const maybeAuthority = untrack(() => props.maybeAuthority);
  let bookRevision = maybeAuthority?.();
  const [kind, setKind] = createSignal<"vacation" | "home" | "">(existing?.kind ?? (homeAvailable ? "" : "vacation"));
  const [name, setName] = createSignal(existing?.name ?? "");
  const [countries, setCountries] = createSignal<readonly string[]>(
    untrack(() => existing?.countries ?? props.countries.filter((country) => country.code === "RO").map((country) => country.id)),
  );
  const [start, setStart] = createSignal(displayCalendarDay(existing?.start ?? `${firstYear}-01-01`).slice(0, 5));
  const [end, setEnd] = createSignal(displayCalendarDay(existing?.end ?? `${lastYear}-01-01`).slice(0, 5));
  const [manualFolder, setManualFolder] = createSignal(existing?.sourceFolder);
  const folder = createMemo(() => manualFolder() ?? suggestFolder(props.photoRoot, props.year, kind() === "home" ? "Home" : name()));
  const [error, setError] = createSignal<string>();
  const [catalog, setCatalog] = createSignal<GearCatalog>();
  const [selected, setSelected] = createSignal<readonly CameraSelection[]>();
  const [loadingGear, setLoadingGear] = createSignal(true);
  const [confirmed, setConfirmed] = createSignal<string>();
  const [originalGear, setOriginalGear] = createSignal<readonly CameraSelection[]>();
  const [receipt, setReceipt] = createSignal<ChapterSave>();
  const [catalogOpener, setCatalogOpener] = createSignal<HTMLButtonElement>();
  const controller = new AbortController();
  let dialog!: HTMLDialogElement;
  let status!: HTMLDivElement;
  let active = true;
  const gear = createContentSave<{ chapterId: string; cameras: readonly CameraSelection[] }, readonly CameraSelection[]>({
    maybeAuthority,
    save: async (draft) => {
      await resources.client.saveChapterGear(resources.albumId, draft.chapterId, draft.cameras);
      return undefined;
    },
    read: async (draft) => {
      const revision = maybeAuthority?.();
      const saved = await resources.client.chapterGear(resources.albumId, draft.chapterId, controller.signal);
      if (bookRevision !== revision) {
        const chapters = await resources.client.chapters(resources.albumId, controller.signal);
        if (revision !== maybeAuthority?.()) return saved;
        const chapter = chapters.find((item) => item.id === draft.chapterId);
        if (chapter === undefined) throw new AlbumRequestError(404, "The saved chapter could not be found.");
        originalDetails = chapterDetails(chapter);
        savedDetails = originalDetails;
        sourceBoundary = false;
        bookRevision = revision;
      }
      if (gear.recovery() !== "acknowledged" && !sameGear(saved, draft.cameras)) setOriginalGear(saved);
      return saved;
    },
    equal: (saved, draft) => sameGear(saved, draft.cameras),
    onCompleted: (draft) => {
      recordBook(draft.cameras);
    },
    onSaved: (_saved, draft) => {
      closeSaved(draft.chapterId);
    },
  });
  const metadata: ContentSave<ChapterDetails, string | undefined> = createContentSave({
    maybeAuthority,
    save: async (details) => {
      const saved = await props.save(details);
      setReceipt(saved);
      return saved.id;
    },
    read: async (details) => {
      const chapters = await resources.client.chapters(resources.albumId, controller.signal);
      const id = receipt()?.id ?? resources.maybeChapterId;
      const chapter =
        id === undefined
          ? chapters.find((item) => item.kind === details.kind && item.sourceFolder === details.sourceFolder)
          : chapters.find((item) => item.id === id);
      if (chapter === undefined && metadata.recovery() === "acknowledged") throw new AlbumRequestError(404, "The saved chapter could not be found.");
      const matches = chapter !== undefined && matchesChapterDetails(chapter, details);
      if (chapter !== undefined && metadata.recovery() !== "acknowledged" && !matches) originalDetails = chapterDetails(chapter);
      return chapter !== undefined && (metadata.recovery() === "acknowledged" || matches) ? chapter.id : undefined;
    },
    equal: (chapterId) => chapterId !== undefined,
    onCompleted: (details) => {
      bookRevision = maybeAuthority?.();
      savedDetails = details;
      sourceBoundary = originalDetails?.sourceFolder !== details.sourceFolder;
      if (sourceBoundary) props.onHistoryBoundary?.();
      else recordBook(originalGear());
    },
    onSaved: (chapterId) => {
      /* v8 ignore next */
      if (chapterId === undefined) throw new AcknowledgedWriteError("The saved chapter identity could not be acquired.");
      setConfirmed(chapterId);
    },
  });
  const unavailable = createMemo(() => (loadingGear() ? "Loading saved gear." : (metadata.unavailable() ?? gear.unavailable())));
  const closeReason = createMemo(() => metadata.unavailable() ?? gear.unavailable());
  const saveReason = createMemo(
    () => unavailable() ?? (catalog() === undefined || selected() === undefined ? "Load saved chapter gear before saving." : undefined),
  );
  const metadataBusy = createMemo(() => unavailable() !== undefined || receipt() !== undefined || confirmed() !== undefined);
  const heading = existing === undefined ? "Add a chapter" : "Chapter details";
  onMount(() => {
    dialog.showModal();
    void loadGear();
  });
  createEffect(() => {
    props.onBlock(unavailable() ?? "Finish the chapter edit before leaving.");
  });
  createEffect(() => {
    const failure = error() ?? metadata.error() ?? gear.error();
    if (failure !== undefined && !loadingGear() && !metadata.pending() && !gear.pending()) status.focus();
  });
  onCleanup(() => {
    active = false;
    controller.abort();
    props.onBlock(undefined);
    dialog.close();
    queueMicrotask(props.returnFocus);
  });

  async function loadGear() {
    setLoadingGear(true);
    setError(undefined);
    try {
      const [savedCatalog, cameras] = await Promise.all([
        resources.client.gearCatalog(controller.signal),
        resources.maybeChapterId === undefined
          ? Promise.resolve([])
          : resources.client.chapterGear(resources.albumId, resources.maybeChapterId, controller.signal),
      ]);
      if (active) {
        setCatalog(savedCatalog);
        setSelected(cameras);
        setOriginalGear(cameras);
      }
    } catch (failure) {
      if (active) setError(describeFailure(failure));
    } finally {
      if (active) setLoadingGear(false);
    }
  }
  function closeSaved(chapterId: string) {
    const saved = receipt();
    if (saved !== undefined) props.onSaved(saved);
    else props.onReconciled(chapterId);
  }
  function recordBook(cameras: readonly CameraSelection[] | undefined) {
    const savedGear = originalGear();
    if (!sourceBoundary && originalDetails !== undefined && savedDetails !== undefined && savedGear !== undefined && cameras !== undefined)
      props.onBookSaved?.({ details: originalDetails, gear: savedGear }, { details: savedDetails, gear: cameras });
  }
  function close() {
    if (closeReason() !== undefined) return;
    const chapterId = confirmed();
    if (chapterId === undefined) props.onClose();
    else closeSaved(chapterId);
  }
  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (unavailable() !== undefined) return;
    const selectedKind = kind();
    if (selectedKind === "") {
      setError("Choose a chapter type.");
      return;
    }
    const common = { countries: countries(), sourceFolder: folder() };
    const details: ChapterDetails =
      selectedKind === "home"
        ? { kind: "home", ...common }
        : {
            kind: "vacation",
            name: name().trim(),
            start: readCalendarDay(`${start()}/${firstYear}`),
            end: readCalendarDay(`${end()}/${lastYear}`),
            ...common,
          };
    const validation = validateChapter(details);
    if (validation !== undefined) {
      setError(validation);
      return;
    }
    const savedCatalog = catalog();
    const cameras = selected();
    if (savedCatalog === undefined || cameras === undefined) {
      setError("Load saved chapter gear before saving.");
      return;
    }
    const invalidGear = validateGear(cameras, savedCatalog);
    if (invalidGear !== undefined) {
      setError(invalidGear);
      return;
    }
    setError(undefined);
    const revision = maybeAuthority?.();
    if (resources.maybeChapterId !== undefined && gear.draft() === undefined) gear.begin({ chapterId: resources.maybeChapterId, cameras });
    if (confirmed() === undefined) {
      if (metadata.draft() === undefined) metadata.begin(details);
      else metadata.change(details);
      await metadata.commit(true);
    }
    if (revision !== maybeAuthority?.()) {
      setError("Editing access changed. Review the retained chapter before saving its remaining gear.");
      return;
    }
    if (confirmed() !== undefined && metadata.recovery() === undefined) await saveGear(true);
  }
  async function saveGear(reviewed: boolean) {
    if (!active) return;
    const chapterId = confirmed();
    const cameras = selected();
    const original = originalGear();
    /* v8 ignore next */
    if (chapterId === undefined || cameras === undefined || original === undefined) throw new Error("Chapter gear has not been acquired.");
    if (sameGear(original, cameras)) closeSaved(chapterId);
    else {
      if (gear.draft() === undefined) gear.begin({ chapterId, cameras });
      else gear.change({ chapterId, cameras });
      await gear.commit(reviewed);
    }
  }
  async function reconcile() {
    const revision = maybeAuthority?.();
    status.focus();
    if (metadata.recovery() !== undefined) {
      await metadata.reconcile();
      if (confirmed() !== undefined && metadata.recovery() === undefined && revision === maybeAuthority?.()) await saveGear(false);
    } else await gear.reconcile();
  }
  function openCalendar(event: Event & { currentTarget: HTMLInputElement }) {
    if (typeof event.currentTarget.showPicker !== "function") return;
    event.preventDefault();
    event.currentTarget.showPicker();
  }
  function handleCalendarKey(event: KeyboardEvent & { currentTarget: HTMLInputElement }) {
    if (event.key === "Enter" || event.key === " ") openCalendar(event);
  }
  return (
    <dialog
      ref={(element) => {
        dialog = element;
      }}
      class="album-dialog chapter-dialog has-gear"
      aria-label={heading}
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
    >
      <header class="dialog-heading">
        <h2>{heading}</h2>
        <button
          type="button"
          class="panel-close"
          aria-label="Close"
          disabled={closeReason() !== undefined}
          aria-describedby="chapter-feedback"
          onClick={close}
        >
          ×
        </button>
      </header>
      <form
        aria-label="Chapter details"
        novalidate
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <Show when={existing === undefined && homeAvailable}>
          <label>
            Chapter type
            <select
              value={kind()}
              disabled={metadataBusy()}
              aria-describedby="chapter-feedback"
              onChange={(event) => {
                const value = event.currentTarget.value;
                setKind(value === "home" ? "home" : value === "vacation" ? "vacation" : "");
              }}
            >
              <option value="">Choose a type</option>
              <option value="vacation">Vacation</option>
              <option value="home">Home</option>
            </select>
          </label>
        </Show>
        <Show when={kind() !== ""}>
          <div class="chapter-editor-columns">
            <section class="chapter-details-column" aria-label="Chapter metadata">
              <Show when={kind() === "vacation"}>
                <label>
                  Location / name
                  <input
                    value={name()}
                    disabled={metadataBusy()}
                    aria-describedby="chapter-feedback"
                    onInput={(event) => {
                      setName(event.currentTarget.value);
                    }}
                  />
                </label>
              </Show>
              <CountryPicker countries={props.countries} selected={countries()} disabled={metadataBusy()} onChange={setCountries} />
              <Show when={kind() === "vacation"}>
                <div class="date-fields">
                  <label>
                    First day
                    <input
                      type="text"
                      aria-label="First day"
                      placeholder="dd/mm"
                      maxLength={5}
                      aria-description={`Day/month in ${firstYear}; year is fixed`}
                      value={start()}
                      disabled={metadataBusy()}
                      aria-describedby="chapter-feedback"
                      onInput={(event) => {
                        setStart(event.currentTarget.value);
                      }}
                    />
                    <span class="date-fixed-year" aria-hidden="true">
                      /{firstYear}
                    </span>
                    <input
                      type="date"
                      aria-label="Choose first day from calendar"
                      title="Choose first day from calendar"
                      value={readCalendarDay(`${start()}/${firstYear}`)}
                      min={`${firstYear}-01-01`}
                      max={`${firstYear}-12-31`}
                      disabled={metadataBusy()}
                      aria-describedby="chapter-feedback"
                      onClick={openCalendar}
                      onKeyDown={handleCalendarKey}
                      onInput={(event) => {
                        if (event.currentTarget.validity.rangeUnderflow || event.currentTarget.validity.rangeOverflow) {
                          setError(`Choose dates within ${firstYear}.`);
                          return;
                        }
                        setStart(event.currentTarget.value === "" ? "" : displayCalendarDay(event.currentTarget.value).slice(0, 5));
                      }}
                    />
                    <svg class="date-calendar-icon" viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M5 5h14v16H5z M8 3v4 M16 3v4 M5 10h14 M8 14h2 M14 14h2 M8 17h2" />
                    </svg>
                  </label>
                  <label>
                    Last day
                    <input
                      type="text"
                      aria-label="Last day"
                      placeholder="dd/mm"
                      maxLength={5}
                      aria-description={`Day/month in ${lastYear}; year is fixed`}
                      value={end()}
                      disabled={metadataBusy()}
                      aria-describedby="chapter-feedback"
                      onInput={(event) => {
                        setEnd(event.currentTarget.value);
                      }}
                    />
                    <span class="date-fixed-year" aria-hidden="true">
                      /{lastYear}
                    </span>
                    <input
                      type="date"
                      aria-label="Choose last day from calendar"
                      title="Choose last day from calendar"
                      value={readCalendarDay(`${end()}/${lastYear}`)}
                      min={`${lastYear}-01-01`}
                      max={`${lastYear}-12-31`}
                      disabled={metadataBusy()}
                      aria-describedby="chapter-feedback"
                      onClick={openCalendar}
                      onKeyDown={handleCalendarKey}
                      onInput={(event) => {
                        if (event.currentTarget.validity.rangeUnderflow || event.currentTarget.validity.rangeOverflow) {
                          setError(`Choose dates within ${lastYear}.`);
                          return;
                        }
                        setEnd(event.currentTarget.value === "" ? "" : displayCalendarDay(event.currentTarget.value).slice(0, 5));
                      }}
                    />
                    <svg class="date-calendar-icon" viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M5 5h14v16H5z M8 3v4 M16 3v4 M5 10h14 M8 14h2 M14 14h2 M8 17h2" />
                    </svg>
                  </label>
                </div>
              </Show>
              <label>
                Photo folder
                <input
                  value={folder()}
                  disabled={metadataBusy()}
                  aria-describedby="chapter-feedback"
                  onInput={(event) => {
                    setManualFolder(event.currentTarget.value);
                  }}
                />
              </label>
              <Show when={folder() !== existing?.sourceFolder}>
                <p class="history-boundary-notice">Saving this chapter or new photo source starts a new Undo history. Your current book is kept.</p>
              </Show>
            </section>
            <section class="chapter-gear-column" aria-label="Chapter gear selection">
              <Show when={catalog()}>
                {(savedCatalog) => (
                  <Show when={selected()}>
                    {(cameras) => (
                      <ChapterGearFields
                        catalog={savedCatalog()}
                        selected={cameras()}
                        disabled={unavailable() !== undefined}
                        onChange={setSelected}
                        onManage={setCatalogOpener}
                      />
                    )}
                  </Show>
                )}
              </Show>
            </section>
          </div>
        </Show>
        <div
          ref={(element) => {
            status = element;
          }}
          id="chapter-feedback"
          tabindex="-1"
        >
          <Show when={saveReason()}>
            {(message) => (
              <p role="status" class="control-help">
                {message()}
              </p>
            )}
          </Show>
          <Show when={error() ?? metadata.error() ?? gear.error()}>
            {(message) => (
              <p role="alert">
                {confirmed() === undefined ? "" : "Chapter details are saved; gear remains unresolved. "}
                {message()}
              </p>
            )}
          </Show>
          <Show when={catalog() === undefined && !loadingGear()}>
            <button
              type="button"
              class="gear-manage-link"
              onClick={() => {
                void loadGear();
              }}
            >
              Retry gear
            </button>
          </Show>
          <Show when={metadata.recovery() !== undefined || gear.recovery() !== undefined}>
            <button
              type="button"
              disabled={metadata.pending() || gear.pending()}
              aria-describedby="chapter-feedback"
              onClick={() => {
                void reconcile();
              }}
            >
              Read saved chapter
            </button>
          </Show>
        </div>
        <div class="dialog-actions">
          <button type="button" class="secondary" disabled={closeReason() !== undefined} aria-describedby="chapter-feedback" onClick={close}>
            {confirmed() === undefined ? "Cancel" : "Close"}
          </button>
          <button type="submit" disabled={saveReason() !== undefined} aria-describedby="chapter-feedback">
            {confirmed() !== undefined ? "Save gear" : existing === undefined ? "Add chapter" : "Save chapter"}
          </button>
        </div>
      </form>
      <Show when={catalogOpener()} keyed>
        {(opener) => (
          <GearCatalogDialog
            client={resources.client}
            onClose={() => {
              setCatalogOpener(undefined);
            }}
            returnFocus={() => {
              opener.focus();
            }}
            onChanged={setCatalog}
          />
        )}
      </Show>
    </dialog>
  );
}
