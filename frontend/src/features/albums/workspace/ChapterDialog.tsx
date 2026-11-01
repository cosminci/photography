import { Show, createEffect, createMemo, createSignal, onCleanup, onMount, untrack } from "solid-js";
import { AcknowledgedWriteError, AlbumRequestError, type AlbumClient } from "../model/Album";
import type { Chapter, ChapterDetails, Country } from "../model/Workspace";
import type { ChapterSave } from "../model/Photo";
import { sameGear, validateGear, type CameraSelection, type GearCatalog, type GearClient } from "../model/Gear";
import { describeFailure } from "../model/Presentation";
import { CountryPicker } from "./CountryPicker";
import { displayCalendarDay, matchesChapterDetails, readCalendarDay, suggestFolder, validateChapter } from "./ChapterDetails";
import { createContentSave, type ContentSave } from "./ContentSave";
import { ChapterGearFields } from "./ChapterGearFields";
import { GearCatalogDialog } from "./GearCatalogDialog";
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
  onBlock: (reason: string | undefined) => void;
  onClose: () => void;
  returnFocus: () => void;
}) {
  const existing = untrack(() => props.chapter);
  const firstYear = existing?.start.slice(0, 4) ?? String(untrack(() => props.year));
  const lastYear = existing?.end.slice(0, 4) ?? String(untrack(() => props.year));
  const homeAvailable = untrack(() => props.homeAvailable);
  const resources = untrack(() => props.resources);
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
    save: async (draft) => {
      await resources.client.saveChapterGear(resources.albumId, draft.chapterId, draft.cameras);
      return undefined;
    },
    read: (draft) => resources.client.chapterGear(resources.albumId, draft.chapterId, controller.signal),
    equal: (saved, draft) => sameGear(saved, draft.cameras),
    onSaved: (_saved, draft) => {
      closeSaved(draft.chapterId);
    },
  });
  const metadata: ContentSave<ChapterDetails, string | undefined> = createContentSave({
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
      return chapter !== undefined && (metadata.recovery() === "acknowledged" || matchesChapterDetails(chapter, details)) ? chapter.id : undefined;
    },
    equal: (chapterId) => chapterId !== undefined,
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
    if (confirmed() === undefined) {
      metadata.begin(details);
      await metadata.commit();
    }
    if (confirmed() !== undefined && metadata.recovery() === undefined) await saveGear();
  }
  async function saveGear() {
    if (!active) return;
    const chapterId = confirmed();
    const cameras = selected();
    const original = originalGear();
    /* v8 ignore next */
    if (chapterId === undefined || cameras === undefined || original === undefined) throw new Error("Chapter gear has not been acquired.");
    if (sameGear(original, cameras)) closeSaved(chapterId);
    else {
      gear.begin({ chapterId, cameras });
      await gear.commit();
    }
  }
  async function reconcile() {
    status.focus();
    if (metadata.recovery() !== undefined) {
      await metadata.reconcile();
      if (confirmed() !== undefined && metadata.recovery() === undefined) await saveGear();
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
          <Show when={saveReason()}>{(message) => <p role="status">{message()}</p>}</Show>
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
