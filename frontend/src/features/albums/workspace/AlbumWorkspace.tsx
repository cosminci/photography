import { For, Show, createEffect, createMemo, createSignal, onCleanup, untrack } from "solid-js";
import { AlbumRequestError, SpreadOccupiedError, type Album, type AlbumClient } from "../model/Album";
import { describeFailure } from "../model/Presentation";
import type { Chapter, Workspace } from "../model/Workspace";
import type { ChapterSave, Scheduling } from "../model/Photo";
import { PhotoPane } from "./PhotoPane";
import { ChapterDialog } from "./ChapterDialog";
import { ChapterOutline, getChapterCapacityReason } from "./ChapterOutline";
import { ChapterMetadata } from "./ChapterMetadata";
import { CoverCanvas, SpreadCanvas, coverAspectRatio, spreadAspectRatio } from "./Canvas";
import { FullscreenReader } from "./FullscreenReader";
import {
  buildChapterReorder,
  findDeletionSelection,
  findReaderNavigation,
  findSelection,
  findSpreadNavigation,
  findSpreadPosition,
} from "./WorkspaceSelection";
import "./workspace.css";
import { TemplateSelector } from "./TemplateSelector";
import { SpreadConfirmation } from "./SpreadConfirmation";
import { createPlacementSession } from "./PlacementSession";
import type { PhotoChoice } from "./PhotoDrag";
import type { CanvasPhotos } from "./Canvas";
import { AlbumAuthors } from "./AlbumAuthors";
import { TextEditor } from "./TextEditor";
import { createCaptionEditing } from "./PhotoCaption";

type SpreadAction = { opener: HTMLButtonElement; spreadId: string } & (
  | { kind: "add"; chapterId: string }
  | { kind: "move"; direction: "earlier" | "later" }
  | { kind: "template"; templateId: string }
  | { kind: "delete"; index: number }
);
type SpreadDestination = { kind: "part"; part: string | null } | { kind: "index"; index: number };

export function AlbumWorkspace(props: {
  album: Album;
  workspace: Workspace;
  client: Pick<
    AlbumClient,
    | "createChapter"
    | "updateChapter"
    | "templates"
    | "addSpread"
    | "replaceChapterComposition"
    | "changeSpreadTemplate"
    | "deleteSpread"
    | "readPlacements"
    | "editPlacements"
    | "saveSynopsis"
    | "saveAuthors"
    | "saveDescription"
    | "saveCaption"
  >;
  onRefresh: () => Promise<void>;
  onSaved: (result: ChapterSave) => Promise<void>;
  workspaceError: unknown;
  photoErrors: Readonly<Record<string, string>>;
  scheduling: Readonly<Record<string, Scheduling | "pending">>;
  liveStatus: "connecting" | "catching-up" | "live" | "interrupted" | "invalid";
  onPhotoRefresh: (ids: readonly string[]) => Promise<void>;
  onInventoryRetry: (id: string) => void;
  onAlbumSaved: (saved: Album) => void;
}) {
  const readPart = () => new URL(window.location.href).searchParams.get("part");
  const [part, setPart] = createSignal(readPart());
  const [interior, setInterior] = createSignal<string | null>(untrack(() => (part() === "cover" ? null : part())));
  const [dialog, setDialog] = createSignal<{ chapter: Chapter | undefined; opener: HTMLElement }>();
  const [refreshing, setRefreshing] = createSignal(false);
  const [refreshAfterSave, setRefreshAfterSave] = createSignal(false);
  const [readerEntry, setReaderEntry] = createSignal<HTMLDivElement>();
  const [spreadPending, setSpreadPending] = createSignal(false);
  const [spreadError, setSpreadError] = createSignal<string>();
  const [failedAction, setFailedAction] = createSignal<SpreadAction>();
  const [spreadNeedsReload, setSpreadNeedsReload] = createSignal(false);
  const [confirmation, setConfirmation] = createSignal<{ action: SpreadAction; placements: number }>();
  const [reloadTarget, setReloadTarget] = createSignal<{ destination: SpreadDestination; opener: HTMLButtonElement }>();
  const [choice, setChoice] = createSignal<PhotoChoice>();
  const [reader, setReader] = createSignal(false);
  const [contentBlocks, setContentBlocks] = createSignal<Readonly<Record<string, string>>>({});
  const [textEdit, setTextEdit] = createSignal<{ chapter: Chapter | undefined; opener: HTMLButtonElement }>();
  const contentReason = createMemo(() => Object.values(contentBlocks())[0]);
  function blockContent(key: string, reason: string | undefined) {
    setContentBlocks((current) =>
      reason === undefined ? Object.fromEntries(Object.entries(current).filter(([id]) => id !== key)) : { ...current, [key]: reason },
    );
  }
  async function readContent() {
    await props.onRefresh();
    if (props.workspaceError !== undefined) throw new Error("Saved content acquisition failed");
  }
  async function saveAuthors(authors: Album["authors"]) {
    const metadata = await props.client.saveAuthors(props.album.id, authors);
    props.onAlbumSaved(metadata);
    return metadata.authors;
  }
  async function readAuthors() {
    await readContent();
    return props.album.authors;
  }
  async function saveText(owner: Chapter | undefined, text: string) {
    if (owner !== undefined) {
      await props.client.saveDescription(props.album.id, owner.id, text);
      return undefined;
    }
    const metadata = await props.client.saveSynopsis(props.album.id, text);
    props.onAlbumSaved(metadata);
    return metadata.synopsis;
  }
  async function readText(owner: Chapter | undefined) {
    await readContent();
    if (owner === undefined) return props.album.synopsis;
    const metadata = props.workspace.chapters.find((candidate) => candidate.id === owner.id);
    if (metadata === undefined) throw new Error("The saved chapter could not be found");
    return metadata.description;
  }
  const captions = createCaptionEditing({
    save: async (photoId, text) => {
      const owner = props.workspace.chapters.find(
        (candidate) => props.workspace.photoInventories[candidate.id]?.photos.some((photo) => photo.id === photoId) === true,
      );
      if (owner === undefined) throw new AlbumRequestError(404, "The photo could not be found in its chapter");
      await props.client.saveCaption(owner.id, photoId, text);
    },
    read: async (photoId) => {
      await readContent();
      const photo = Object.values(props.workspace.photoInventories)
        .flatMap((inventory) => inventory.photos)
        .find((candidate) => candidate.id === photoId);
      if (photo === undefined) throw new Error("The saved photo could not be found");
      return photo.caption ?? "";
    },
    onBlock: (photoId, reason) => {
      blockContent(`caption-${photoId}`, reason);
    },
  });
  const placement = createPlacementSession(
    untrack(() => props.client),
    async () => {
      await props.onRefresh();
      if (props.workspaceError !== undefined) throw new Error("Workspace refresh failed");
    },
  );
  const selection = createMemo(() => findSelection(props.workspace.spreads, part()));
  const selectedSpread = createMemo(() => {
    const selected = selection();
    return selected.kind === "spread" ? selected.spread : undefined;
  });
  const displayedSpread = createMemo(() => {
    const spread = selectedSpread();
    const confirmed = placement.snapshot();
    if (spread === undefined || confirmed?.kind !== "spread" || confirmed.id !== spread.id) return spread;
    const common = { ...spread, templateId: confirmed.layout.templateId, slots: confirmed.layout.slots, placements: confirmed.placements };
    return common;
  });
  const chapter = createMemo(() => props.workspace.chapters.find((candidate) => candidate.id === selectedSpread()?.chapter));
  const spreadPosition = createMemo(() => {
    const spread = selectedSpread();
    return spread === undefined ? undefined : findSpreadPosition(props.workspace.spreads, spread);
  });
  const photoChapters = createMemo(() => {
    const owner = chapter();
    return owner === undefined ? props.workspace.chapters : [owner];
  });
  const label = createMemo(() => chapter()?.name ?? "Synopsis");
  const readerLabel = createMemo(() =>
    selection().kind === "invalid"
      ? ""
      : part() === "cover"
        ? "Cover"
        : selectedSpread()?.kind === "photos_only"
          ? `${label()} · Photo spread`
          : label(),
  );
  const navigation = createMemo(() => findReaderNavigation(props.workspace.spreads, selection()));
  const spreadNavigation = createMemo(() => findSpreadNavigation(props.workspace.spreads, selection()));
  const placementTarget = createMemo(() => (part() === "cover" ? props.workspace.spreads : selectedSpread()));
  createEffect(() => {
    placementTarget();
    untrack(() => {
      setChoice(undefined);
      const spread = selectedSpread();
      if (part() === "cover") {
        if (placement.navigationReason() === undefined && !placement.loading()) void placement.load(props.album.id, { kind: "cover" });
      } else if (spread !== undefined) {
        const savedSpread = {
          kind: "spread",
          id: spread.id,
          placements: spread.placements,
          layout: { kind: spread.kind, templateId: spread.templateId, slots: spread.slots },
        } as const;
        const current = placement.snapshot();
        if (current?.kind === "spread" && current.id === spread.id) placement.reconcile(savedSpread);
        else placement.selectSpread(props.album.id, savedSpread);
      } else placement.clear();
    });
  });
  const editing = createMemo<CanvasPhotos>(() => ({
    session: placement,
    photos: Object.values(props.workspace.photoInventories).flatMap((inventory) => inventory.photos),
    choice: choice(),
    onChoose: setChoice,
    unavailable:
      contentReason() ?? (spreadPending() ? "Saving spread. Please wait." : refreshing() ? "Refreshing saved workspace. Please wait." : undefined),
    reader: reader(),
    captions,
  }));
  const navigationReason = createMemo(
    () =>
      contentReason() ?? (spreadNeedsReload() ? "Reload the saved workspace before choosing another spread action." : placement.navigationReason()),
  );
  const refreshReason = createMemo(() =>
    spreadPending()
      ? "Saving spread. Please wait."
      : refreshing()
        ? "Refreshing saved workspace. Please wait."
        : props.workspaceError !== undefined
          ? "Reload the saved workspace before editing chapters."
          : undefined,
  );
  const creationReason = createMemo(() => refreshReason() ?? navigationReason() ?? getChapterCapacityReason(props.workspace.spreads.length));
  let workspaceElement: HTMLElement;
  let refreshGeneration = 0;
  let active = true;
  const workspacePath = window.location.pathname;

  function syncHistory() {
    if (navigationReason() !== undefined) {
      const url = new URL(window.location.href);
      url.pathname = workspacePath;
      const current = part();
      if (current === null) url.searchParams.delete("part");
      else url.searchParams.set("part", current);
      window.history.replaceState(null, "", url);
      return;
    }
    const next = readPart();
    setPart(next);
    if (next !== "cover") setInterior(next);
  }
  window.addEventListener("popstate", syncHistory);
  const preventDiscard = (event: BeforeUnloadEvent) => {
    if (navigationReason() !== undefined) event.preventDefault();
  };
  window.addEventListener("beforeunload", preventDiscard);
  document.addEventListener("keydown", navigateSpread);
  onCleanup(() => {
    active = false;
    refreshGeneration++;
    window.removeEventListener("popstate", syncHistory);
    window.removeEventListener("beforeunload", preventDiscard);
    document.removeEventListener("keydown", navigateSpread);
  });

  function navigateSpread(event: KeyboardEvent) {
    if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || document.fullscreenElement) return;
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
    if (event.target instanceof HTMLElement && event.target.closest("input, textarea, select, [contenteditable], dialog") !== null) return;
    if (refreshReason() !== undefined || part() === "cover") return;
    event.preventDefault();
    const destination = event.key === "ArrowLeft" ? spreadNavigation().previous : spreadNavigation().next;
    if (destination !== undefined) select(destination);
  }

  function select(next: string | null) {
    if (navigationReason() !== undefined) return;
    const url = new URL(window.location.href);
    if (next === null) url.searchParams.delete("part");
    else url.searchParams.set("part", next);
    if (part() !== next) window.history.pushState(null, "", url);
    setPart(next);
    if (next !== "cover") setInterior(next);
  }

  async function refresh(result?: ChapterSave) {
    const generation = ++refreshGeneration;
    setRefreshAfterSave(result !== undefined);
    setRefreshing(true);
    try {
      if (result === undefined) await props.onRefresh();
      else await props.onSaved(result);
    } finally {
      if (generation === refreshGeneration) setRefreshing(false);
    }
  }

  function saved(result: ChapterSave, action: { chapter: Chapter | undefined; opener: HTMLElement }) {
    if (!active) return;
    const opener = action.opener;
    setDialog(undefined);
    if (action.chapter === undefined) select(result.id);
    void refresh(result).then(() => {
      if (opener instanceof HTMLButtonElement && opener.isConnected && !opener.disabled) opener.focus();
      else workspaceElement.focus();
    });
  }

  function restoreFocus(opener: HTMLButtonElement) {
    queueMicrotask(() => {
      if (opener.isConnected && !opener.disabled) opener.focus();
      else workspaceElement.focus();
    });
  }

  async function reloadSpread() {
    const target = reloadTarget();
    /* v8 ignore next -- Retry is rendered only with a saved mutation target. */
    if (target === undefined) return;
    setSpreadError(undefined);
    await refresh();
    if (!active) return;
    if (props.workspaceError !== undefined) {
      workspaceElement.focus();
      return;
    }
    const destination =
      target.destination.kind === "part" ? target.destination.part : findDeletionSelection(props.workspace.spreads, target.destination.index);
    select(destination);
    setSpreadError(undefined);
    setReloadTarget(undefined);
    restoreFocus(target.opener);
  }

  async function mutateSpread(action: SpreadAction, confirmPlacements = false) {
    /* v8 ignore next -- Spread controls are disabled while photo state is unresolved. */
    if (navigationReason() !== undefined) return;
    setSpreadPending(true);
    setSpreadError(undefined);
    setFailedAction(undefined);
    try {
      let destination: SpreadDestination = { kind: "part", part: part() };
      if (action.kind === "add")
        destination = { kind: "part", part: await props.client.addSpread(props.album.id, action.chapterId, action.spreadId) };
      else if (action.kind === "move") {
        const replacement = buildChapterReorder(props.workspace.spreads, action.spreadId, action.direction);
        await props.client.replaceChapterComposition(props.album.id, replacement.chapter, replacement.expectedSpreadIds, replacement.spreadIds);
      } else if (action.kind === "template")
        await props.client.changeSpreadTemplate(props.album.id, action.spreadId, action.templateId, confirmPlacements);
      else {
        await props.client.deleteSpread(props.album.id, action.spreadId, confirmPlacements);
        destination = { kind: "index", index: action.index };
      }
      if (!active) return;
      setConfirmation(undefined);
      setReloadTarget({ destination, opener: action.opener });
      await reloadSpread();
    } catch (failure) {
      if (!active) return;
      if (failure instanceof SpreadOccupiedError) setConfirmation({ action, placements: failure.placements });
      else {
        setSpreadError(describeFailure(failure));
        setFailedAction(action);
        const changed = failure instanceof AlbumRequestError && failure.status === 409;
        if (changed || !(failure instanceof AlbumRequestError) || failure.status >= 500) {
          setSpreadNeedsReload(true);
          setConfirmation(undefined);
          setSpreadError(
            changed
              ? "This chapter's composition changed. Reload saved workspace before choosing the action again."
              : "Spread outcome is uncertain. Reload saved workspace before choosing the action again.",
          );
        }
      }
    } finally {
      if (active) {
        setSpreadPending(false);
        if (confirmation() === undefined && reloadTarget() === undefined) restoreFocus(action.opener);
      }
    }
  }

  return (
    <section
      ref={(element) => {
        workspaceElement = element;
      }}
      tabindex="-1"
      class="workspace"
      aria-label="Album workspace"
    >
      <header class="workspace-heading">
        <div class="workspace-album-heading">
          <h1>{props.album.title}</h1>
          <span aria-hidden="true">|</span>
          <AlbumAuthors
            authors={props.album.authors}
            unavailable={refreshReason() ?? placement.navigationReason() ?? Object.entries(contentBlocks()).find(([key]) => key !== "authors")?.[1]}
            save={saveAuthors}
            read={readAuthors}
            onBlock={(reason) => {
              blockContent("authors", reason);
            }}
          />
          <span aria-hidden="true">|</span>
          <a
            class="back-link"
            href="/"
            onClick={(event) => {
              if (navigationReason() !== undefined) event.preventDefault();
            }}
          >
            Back to album catalog
          </a>
        </div>
        <div class="workspace-controls" role="group" aria-label="Workspace controls">
          <div ref={setReaderEntry} />
          <div class="workspace-mode" role="group" aria-label="Canvas view">
            <button
              type="button"
              aria-pressed={part() !== "cover"}
              disabled={navigationReason() !== undefined}
              aria-describedby={navigationReason() === undefined ? undefined : "photo-navigation-reason"}
              onClick={() => {
                select(interior());
              }}
            >
              Spreads
            </button>
            <button
              type="button"
              aria-pressed={part() === "cover"}
              disabled={navigationReason() !== undefined}
              aria-describedby={navigationReason() === undefined ? undefined : "photo-navigation-reason"}
              onClick={() => {
                select("cover");
              }}
            >
              Cover
            </button>
          </div>
        </div>
      </header>
      <Show when={refreshing()}>
        <p role="status">Refreshing saved workspace…</p>
      </Show>
      <Show when={placement.error()}>
        {(message) => (
          <p role="alert">
            {message()}{" "}
            <button
              type="button"
              onClick={() => {
                void placement.retry();
              }}
            >
              Retry photos
            </button>
          </p>
        )}
      </Show>
      <Show when={navigationReason()}>
        {(message) => (
          <p id="photo-navigation-reason" role="status">
            {message()}
          </p>
        )}
      </Show>
      <Show when={choice()}>
        {(selected) => (
          <p role="status">
            {selected().kind === "photo" ? "Photo selected. Choose a destination slot." : "Move selected. Choose a destination slot to move or swap."}{" "}
            <button
              type="button"
              onClick={() => {
                setChoice(undefined);
              }}
            >
              Cancel photo choice
            </button>
          </p>
        )}
      </Show>
      <For each={captions.sessions()}>
        {(retained) => (
          <Show when={retained.save.draft() !== undefined && !placement.placements().some((row) => row.photoId === retained.photoId)}>
            <div role="alert" id={`retained-caption-${retained.photoId}`}>
              The caption's photo is no longer placed here. Its draft is retained.
              <pre>{retained.save.draft()?.value}</pre>
              <Show when={retained.save.error()}>{(message) => <p>{message()}</p>}</Show>
              <Show when={retained.save.unavailable()}>{(reason) => <p role="status">{reason()}</p>}</Show>
              <button
                type="button"
                disabled={retained.save.pending()}
                aria-describedby={retained.save.pending() ? `retained-caption-${retained.photoId}` : undefined}
                onClick={() => {
                  void retained.save.commit();
                }}
              >
                {retained.save.recovery() === undefined ? "Save retained caption" : "Read retained caption"}
              </button>
              <button
                type="button"
                disabled={retained.save.unavailable() !== undefined}
                aria-describedby={retained.save.unavailable() === undefined ? undefined : `retained-caption-${retained.photoId}`}
                onClick={() => {
                  retained.save.cancel();
                }}
              >
                Cancel retained caption
              </button>
            </div>
          </Show>
        )}
      </For>
      <Show when={props.workspaceError !== undefined}>
        <div role="alert">
          {reloadTarget() !== undefined
            ? "Spread saved, but the workspace could not be refreshed."
            : refreshAfterSave()
              ? "Chapter saved, but the workspace could not be refreshed."
              : "Saved workspace could not be refreshed. Displayed photos may be out of date."}{" "}
          <button
            type="button"
            onClick={() => {
              if (reloadTarget() !== undefined) void reloadSpread();
              else void refresh();
            }}
          >
            Retry workspace
          </button>
        </div>
      </Show>
      <Show when={spreadError() !== undefined && confirmation() === undefined}>
        <p role="alert">
          {spreadError()}{" "}
          <button
            type="button"
            onClick={() => {
              if (spreadNeedsReload()) {
                void refresh().then(() => {
                  if (props.workspaceError === undefined) {
                    setSpreadNeedsReload(false);
                    setFailedAction(undefined);
                    setSpreadError(undefined);
                  }
                });
                return;
              }
              const action = failedAction();
              /* v8 ignore next -- Failure feedback retains its failed action. */
              if (action !== undefined) void mutateSpread(action);
            }}
          >
            {spreadNeedsReload() ? "Reload saved workspace" : "Retry spread action"}
          </button>
        </p>
      </Show>
      <PhotoPane
        label={chapter() === undefined ? "Album photos" : "Chapter photos"}
        chapters={photoChapters()}
        inventories={props.workspace.photoInventories}
        inventoryErrors={props.photoErrors}
        scheduling={props.scheduling}
        liveStatus={props.liveStatus}
        onRefresh={() => {
          void props.onPhotoRefresh(photoChapters().map((owner) => owner.id));
        }}
        onRetryInventory={props.onInventoryRetry}
        onChoosePhoto={(photoId) => setChoice({ kind: "photo", photoId })}
        unavailable={refreshReason() ?? placement.unavailable() ?? navigationReason()}
        onRetryRefresh={(id) => {
          void props.onPhotoRefresh([id]);
        }}
      >
        <div class="workspace-editor">
          <Show when={displayedSpread()}>
            {(spread) => (
              <TemplateSelector
                context={spread().kind}
                appliedTemplateId={spread().templateId}
                load={props.client.templates}
                unavailable={refreshReason() ?? navigationReason()}
                onChange={(templateId, opener) => {
                  if (templateId !== spread().templateId) void mutateSpread({ kind: "template", spreadId: spread().id, templateId, opener });
                }}
              />
            )}
          </Show>
          <article class="workspace-preview" aria-label="Selected part">
            <Show when={props.workspaceError === undefined || contentReason() !== undefined}>
              <Show when={!refreshing() && selection().kind === "invalid"}>
                <div role="alert">
                  This album part could not be found.{" "}
                  <button
                    type="button"
                    onClick={() => {
                      select(null);
                    }}
                  >
                    Go to Synopsis
                  </button>
                </div>
              </Show>
              <FullscreenReader
                aspectRatio={part() === "cover" ? coverAspectRatio : spreadAspectRatio}
                label={readerLabel()}
                entryMount={readerEntry()}
                unavailable={
                  selection().kind === "invalid" ? "Choose an available album part to enter reader mode." : (refreshReason() ?? navigationReason())
                }
                onActive={setReader}
                canPrevious={navigation().previous !== undefined}
                canNext={navigation().next !== undefined}
                onNavigate={(direction) => {
                  const next = direction === -1 ? navigation().previous : navigation().next;
                  /* v8 ignore next -- Endpoint controls and keyboard guards prevent unavailable navigation. */
                  if (next !== undefined) select(next);
                }}
              >
                <Show when={part() === "cover"}>
                  <CoverCanvas title={props.album.title} year={props.workspace.year} authors={props.album.authors} editing={editing()} />
                </Show>
                <Show when={displayedSpread()}>
                  {(spread) => (
                    <SpreadCanvas
                      slots={spread().slots}
                      title={chapter()?.name ?? props.album.title}
                      text={chapter()?.description ?? props.album.synopsis}
                      label={label()}
                      editing={editing()}
                    />
                  )}
                </Show>
              </FullscreenReader>
              <Show when={!reader() && part() !== "cover" && selectedSpread()?.kind !== "photos_only"}>
                <button
                  type="button"
                  class="content-action"
                  aria-label={chapter() === undefined ? "Edit synopsis" : "Edit chapter description"}
                  title={chapter() === undefined ? "Edit synopsis" : "Edit chapter description"}
                  disabled={(refreshReason() ?? navigationReason()) !== undefined}
                  aria-describedby={(refreshReason() ?? navigationReason()) === undefined ? undefined : "photo-navigation-reason"}
                  onClick={(event) => setTextEdit({ chapter: chapter(), opener: event.currentTarget })}
                >
                  <svg class="workspace-action-icon" viewBox="0 0 24 24" aria-hidden="true">
                    <path d="m4 16 12-12 4 4-12 12-5 1ZM14 6l4 4" />
                  </svg>
                </button>
              </Show>
              <Show when={chapter()}>
                {(owner) => (
                  <Show when={spreadPosition()}>
                    {(position) => (
                      <ChapterMetadata
                        chapter={owner()}
                        position={position()}
                        countries={props.workspace.countries}
                        unavailable={refreshReason() ?? navigationReason()}
                        onEdit={(opener) => {
                          setDialog({ chapter: owner(), opener });
                        }}
                      />
                    )}
                  </Show>
                )}
              </Show>
            </Show>
          </article>
          <Show when={part() !== "cover"}>
            <ChapterOutline
              albumId={props.album.id}
              chapters={props.workspace.chapters}
              spreads={props.workspace.spreads}
              part={part()}
              creationReason={creationReason()}
              unavailable={refreshReason() ?? navigationReason()}
              onSelect={select}
              onCreate={(opener) => setDialog({ chapter: undefined, opener })}
              onAdd={(chapterId, spreadId, opener) => {
                void mutateSpread({ kind: "add", chapterId, spreadId, opener });
              }}
              onMove={(spreadId, direction, opener) => {
                void mutateSpread({ kind: "move", spreadId, direction, opener });
              }}
              onDelete={(spreadId, opener) => {
                const index = props.workspace.spreads.findIndex((spread) => spread.id === spreadId);
                void mutateSpread({ kind: "delete", spreadId, index, opener });
              }}
            />
          </Show>
        </div>
      </PhotoPane>
      <Show when={confirmation()}>
        {(occupied) => (
          <SpreadConfirmation
            placements={occupied().placements}
            deleting={occupied().action.kind === "delete"}
            pending={spreadPending()}
            error={spreadError()}
            onConfirm={() => {
              void mutateSpread(occupied().action, true);
            }}
            onCancel={() => {
              const opener = occupied().action.opener;
              setConfirmation(undefined);
              setSpreadError(undefined);
              setFailedAction(undefined);
              restoreFocus(opener);
            }}
          />
        )}
      </Show>
      <Show when={dialog()} keyed>
        {(action) => (
          <ChapterDialog
            save={(details) =>
              action.chapter === undefined
                ? props.client.createChapter(props.album.id, details)
                : props.client.updateChapter(props.album.id, action.chapter.id, details)
            }
            year={props.workspace.year}
            photoRoot={props.workspace.photoRoot}
            countries={props.workspace.countries}
            homeAvailable={!props.workspace.chapters.some((item) => item.kind === "home")}
            chapter={action.chapter}
            onSaved={(result) => {
              saved(result, action);
            }}
            onClose={() => {
              setDialog(undefined);
            }}
            returnFocus={() => {
              action.opener.focus();
            }}
          />
        )}
      </Show>
      <Show when={textEdit()} keyed>
        {(action) => (
          <TextEditor
            heading={action.chapter === undefined ? "Synopsis" : `Chapter description for ${action.chapter.name}`}
            label={action.chapter === undefined ? "Synopsis" : "Chapter description"}
            text={action.chapter?.description ?? props.album.synopsis}
            save={(text) => saveText(action.chapter, text)}
            read={() => readText(action.chapter)}
            onClose={() => {
              setTextEdit(undefined);
            }}
            onBlock={(reason) => {
              blockContent("text", reason);
            }}
            returnFocus={() => {
              restoreFocus(action.opener);
            }}
          />
        )}
      </Show>
    </section>
  );
}
