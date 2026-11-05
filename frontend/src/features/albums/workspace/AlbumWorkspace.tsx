import { For, Show, createEffect, createMemo, createSignal, on, onCleanup, untrack } from "solid-js";
import { AcknowledgedWriteError, AlbumRequestError, SpreadOccupiedError, type Album, type AlbumClient } from "../model/Album";
import { describeFailure, validateTitle } from "../model/Presentation";
import type { Chapter, Spread, Workspace } from "../model/Workspace";
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
import { CompositionControls } from "./CompositionControls";
import type { PhotoChoice } from "./PhotoDrag";
import type { CanvasPhotos } from "./Canvas";
import { describeGear, validateGear, type GearClient } from "../model/Gear";
import { AlbumAuthors } from "./AlbumAuthors";
import { TextEditor } from "./TextEditor";
import { createCaptionEditing } from "./PhotoCaption";
import { RetainedCaption } from "./RetainedCaption";
import type { EditingTarget, WorkspaceEditingSession } from "../../editing/EditorSession";
import { BrowserAlias } from "../../editing/BrowserAlias";
import { createBookHistory, type HistoryDirection } from "./BookHistory";
import { BookHistoryControls } from "./BookHistoryControls";
import { BookExportControl } from "./BookExportControl";
import { createContentCompensation } from "./ContentCompensation";
import { createPlacementCompensation, sameComposition } from "./PlacementCompensation";
import type { PlacementContext, PlacementEdit, PlacementSnapshot } from "../model/Placement";
import { createSpreadCompensation } from "./SpreadCompensation";
import { createChapterCompensation, sameChapterBook, type ChapterBookState } from "./ChapterCompensation";
import { chapterDetails } from "./ChapterDetails";

type SpreadAction = { opener: HTMLButtonElement; spreadId: string } & (
  | { kind: "add"; chapterId: string }
  | { kind: "move"; direction: "earlier" | "later" }
  | { kind: "template"; templateId: string }
  | { kind: "delete"; index: number }
);
type SpreadDestination = { kind: "part"; part: string | null } | { kind: "index"; index: number };

export function AlbumWorkspace(props: {
  album: Album;
  session: WorkspaceEditingSession;
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
    | "rename"
    | "saveDescription"
    | "saveCaption"
    | "chapters"
    | "composition"
    | "exportBook"
    | keyof GearClient
  >;
  onRefresh: () => Promise<void>;
  onSaved: (result: ChapterSave) => Promise<void>;
  workspaceError: unknown;
  workspaceLoading: boolean;
  photoErrors: Readonly<Record<string, string>>;
  scheduling: Readonly<Record<string, Scheduling | "pending">>;
  liveStatus: "connecting" | "catching-up" | "live" | "interrupted" | "invalid";
  onPhotoRefresh: (ids: readonly string[]) => Promise<void>;
  onInventoryRetry: (id: string) => void;
  onAlbumSaved: (saved: Album) => void;
  historyLimit?: number | undefined;
  onReady?: (() => void) | undefined;
}) {
  const history = createBookHistory(untrack(() => props.historyLimit));
  const [historyBusy, setHistoryBusy] = createSignal(false);
  const historyReason = createMemo(() =>
    historyBusy() || history.unavailable() ? history.message() || "Finish the history action before editing." : undefined,
  );
  let compensationEpoch = untrack(() => props.session.lossRevision());
  let ownershipRequest = Promise.resolve();
  const spreadIdentities = new Map<string, string>();
  const resolveSpreadId = (id: string) => spreadIdentities.get(id) ?? id;
  const identities = {
    resolve: resolveSpreadId,
    replace: (oldId: string, newId: string) => {
      const previous = resolveSpreadId(oldId);
      for (const [id, current] of spreadIdentities) if (current === previous) spreadIdentities.set(id, newId);
      spreadIdentities.set(oldId, newId);
    },
  };
  let pendingSpreadHistory: (() => void) | undefined;
  let uncertainSpreadHistory: (() => void) | undefined;
  createEffect(
    on(
      () => props.session.lossRevision(),
      () => {
        history.reset("Editing access was lost. Undo history has been cleared; retained drafts still require review.");
      },
      { defer: true },
    ),
  );
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
  const [ownershipLoading, setOwnershipLoading] = createSignal(false);
  let ownershipGeneration = 0;
  let sessionWasAvailable = untrack(() => props.session.available());
  const [contentBlocks, setContentBlocks] = createSignal<Readonly<Record<string, string>>>({});
  const [textEdit, setTextEdit] = createSignal<
    { kind: "name"; opener: HTMLButtonElement } | { kind: "description"; chapter: Chapter | undefined; opener: HTMLButtonElement }
  >();
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
    publishAlbum(metadata);
    return metadata.authors;
  }
  function publishAlbum(metadata: Album) {
    try {
      props.onAlbumSaved(metadata);
    } catch (cause) {
      throw new AcknowledgedWriteError("Saved album metadata could not be published. Read saved content again.", { cause });
    }
  }
  async function readAuthors() {
    await readContent();
    return props.album.authors;
  }
  async function saveName(title: string) {
    const metadata = await props.client.rename(props.album.id, title.trim());
    if (metadata.id !== props.album.id) throw new AcknowledgedWriteError("Unexpected saved album identity");
    publishAlbum({ ...props.album, title: metadata.title });
    return metadata.title;
  }
  async function readName() {
    await readContent();
    return props.album.title;
  }
  const historyAccessLost = () => !active || compensationEpoch !== props.session.lossRevision() || !props.session.available();
  async function prepareHistoryTarget(destination: string | null, requireGrant = true) {
    if (historyAccessLost()) return "Editing access changed. History cannot replay after access loss.";
    if (findSelection(props.workspace.spreads, destination).kind === "invalid") {
      await readContent();
      if (historyAccessLost()) return "Editing access changed. History cannot replay after access loss.";
      if (findSelection(props.workspace.spreads, destination).kind === "invalid") return "The saved spread is no longer available.";
    }
    setReader(false);
    setChoice(undefined);
    showSelection(destination);
    await Promise.resolve();
    await ownershipRequest;
    if (historyAccessLost()) return "Editing access changed. History cannot replay after access loss.";
    if (requireGrant && ownershipReason() !== undefined) await confirmOwnership(editingTarget(), true);
    if (historyAccessLost()) return "Editing access changed. History cannot replay after access loss.";
    return requireGrant ? ownershipReason() : undefined;
  }
  async function traverseHistory(direction: HistoryDirection) {
    compensationEpoch = props.session.lossRevision();
    await runHistory(() => history.traverse(direction));
  }
  async function reconcileHistory() {
    await runHistory(() => history.reconcile());
  }
  async function runHistory(operation: () => Promise<void>) {
    /* v8 ignore next */
    if (historyBusy()) return;
    setHistoryBusy(true);
    try {
      await operation();
      await refresh();
    } finally {
      setHistoryBusy(false);
    }
  }
  function recordText(owner: Chapter | undefined, before: string, after: string) {
    if (before === after) return;
    const destination = owner?.id ?? null;
    history.record(
      createContentCompensation({
        label: owner === undefined ? "Edit synopsis" : "Edit chapter description",
        before,
        after,
        read: () => readText(owner),
        write: (text) => saveText(owner, text),
        equal: (left, right) => left === right,
        prepare: () => prepareHistoryTarget(destination),
      }),
    );
  }
  function recordPlacement(before: PlacementSnapshot, after: PlacementSnapshot, edit: PlacementEdit) {
    const labels: Record<PlacementEdit["operation"], string> = {
      place: "Place photo",
      remove: "Remove photo",
      move: "Move photo",
      adjust: "Adjust photo",
      captionCorner: "Change caption corner",
      bleed: "Change spread bleed",
      spineOwner: "Change spine owner",
    };
    recordComposition(before, after, labels[edit.operation]);
  }
  function recordComposition(before: PlacementSnapshot, after: PlacementSnapshot, label: string) {
    if (sameComposition(before, after)) return;
    const context = (): PlacementContext => (before.kind === "cover" ? { kind: "cover" } : { kind: "spread", id: resolveSpreadId(before.id) });
    history.record(
      createPlacementCompensation({
        album: props.album.id,
        label,
        before,
        after,
        context,
        authorize: () => prepareHistoryTarget(before.kind === "cover" ? "cover" : resolveSpreadId(before.id)),
        client: props.client,
      }),
    );
  }
  function snapshotSpread(spread: Spread) {
    return {
      kind: "spread" as const,
      id: spread.id,
      placements: spread.placements,
      layout: { kind: spread.kind, templateId: spread.templateId, slots: spread.slots, bleed: spread.bleed },
    };
  }
  function recordSpreadExistence(spread: Spread, order: readonly string[], present: boolean) {
    /* v8 ignore next */
    if (spread.chapter === null) throw new Error("An album opening cannot be recreated as a photo spread.");
    history.record(
      createSpreadCompensation({
        album: props.album.id,
        chapter: spread.chapter,
        spread: snapshotSpread(spread),
        order,
        present,
        identities,
        client: props.client,
        prepare: prepareHistoryTarget,
        removed: showSelection,
      }),
    );
  }
  function recordSpreadOrder(chapterId: string, before: readonly string[], after: readonly string[], target: string) {
    let observed = after.map(resolveSpreadId);
    history.record(
      createContentCompensation({
        label: "Reorder spread",
        before,
        after,
        equal: (left, right) => {
          const resolved = right.map(resolveSpreadId);
          return left.length === right.length && left.every((id, index) => resolveSpreadId(id) === resolved[index]);
        },
        prepare: () => prepareHistoryTarget(resolveSpreadId(target), false),
        read: async () => {
          const members = await props.client.composition(props.album.id, new AbortController().signal);
          observed = members.filter((member) => member.chapter === chapterId).map((member) => member.id);
          return observed;
        },
        write: (order) => props.client.replaceChapterComposition(props.album.id, chapterId, observed, order.map(resolveSpreadId)),
      }),
    );
  }
  async function saveText(owner: Chapter | undefined, text: string) {
    if (owner !== undefined) {
      await props.client.saveDescription(props.album.id, owner.id, text);
      return undefined;
    }
    const metadata = await props.client.saveSynopsis(props.album.id, text);
    publishAlbum(metadata);
    return metadata.synopsis;
  }
  async function readText(owner: Chapter | undefined) {
    await readContent();
    if (owner === undefined) return props.album.synopsis;
    const metadata = props.workspace.chapters.find((candidate) => candidate.id === owner.id);
    if (metadata === undefined) throw new Error("The saved chapter could not be found");
    return metadata.description;
  }
  async function saveCaption(photoId: string, text: string) {
    const owner = props.workspace.chapters.find(
      (candidate) => props.workspace.photoInventories[candidate.id]?.photos.some((photo) => photo.id === photoId) === true,
    );
    if (owner === undefined) throw new AlbumRequestError(404, "The photo could not be found in its chapter");
    await props.client.saveCaption(owner.id, photoId, text);
  }
  async function readCaption(photoId: string) {
    await readContent();
    const photo = Object.values(props.workspace.photoInventories)
      .flatMap((inventory) => inventory.photos)
      .find((candidate) => candidate.id === photoId);
    if (photo === undefined) throw new Error("The saved photo could not be found");
    return photo.caption ?? "";
  }
  const captions = createCaptionEditing({
    maybeAuthority: () => (props.session.available() && ownershipReason() === undefined ? props.session.authority() : undefined),
    save: saveCaption,
    read: readCaption,
    onBlock: (photoId, reason) => {
      blockContent(`caption-${photoId}`, reason);
    },
    onCompleted: (photoId, before, after) => {
      if (before === after) return;
      const destination = part();
      history.record(
        createContentCompensation({
          label: "Edit caption",
          before,
          after,
          read: () => readCaption(photoId),
          write: (text) => saveCaption(photoId, text),
          equal: (left, right) => left === right,
          prepare: () => prepareHistoryTarget(destination === null || destination === "cover" ? destination : resolveSpreadId(destination)),
        }),
      );
    },
  });
  const placement = createPlacementSession(
    untrack(() => props.client),
    async () => {
      await props.onRefresh();
      if (props.workspaceError !== undefined) throw new Error("Workspace refresh failed");
    },
    {
      available: () => ownershipReason() === undefined,
      authority: () => props.session.authority(),
    },
    recordPlacement,
  );
  const savedCoverAspectRatio = createMemo(() => {
    const compositionSnapshot = placement.snapshot();
    return compositionSnapshot?.kind === "cover" ? compositionSnapshot.geometry.width / compositionSnapshot.geometry.height : coverAspectRatio;
  });
  const selection = createMemo(() => findSelection(props.workspace.spreads, part()));
  const selectedSpread = createMemo(() => {
    const selected = selection();
    return selected.kind === "spread" ? selected.spread : undefined;
  });
  const editingTarget = createMemo<EditingTarget | undefined>(
    () => {
      const spread = selectedSpread();
      return part() === "cover" ? { kind: "cover", id: props.album.id } : spread === undefined ? undefined : { kind: "spread", id: spread.id };
    },
    undefined,
    { equals: (maybePrevious, maybeNext) => maybePrevious?.kind === maybeNext?.kind && maybePrevious?.id === maybeNext?.id },
  );
  const ownershipReason = createMemo(() => {
    const target = editingTarget();
    return ownershipLoading()
      ? "Reading saved content and confirming editing ownership."
      : target === undefined
        ? "Choose an available spread."
        : props.session.targetReason(target);
  });
  const photoRecoveryReason = createMemo(() =>
    placement.saving() ? "Saving photos. Please wait." : placement.loading() ? "Reading saved photos. Please wait." : undefined,
  );
  const photoApplyReason = createMemo(() => ownershipReason() ?? photoRecoveryReason());
  async function confirmOwnership(maybeTarget: EditingTarget | null | undefined, explicit: boolean, reconnect = false) {
    const generation = ++ownershipGeneration;
    const before = props.session.authority();
    setOwnershipLoading(true);
    await props.session.select(maybeTarget ?? null, explicit);
    if (!active || generation !== ownershipGeneration) return;
    if (explicit || reconnect || before !== props.session.authority()) await refresh();
    if (generation === ownershipGeneration) setOwnershipLoading(false);
  }
  createEffect(
    on([editingTarget, reader, () => props.session.available()], () => {
      const target = editingTarget();
      if (!props.session.available()) {
        sessionWasAvailable = false;
        ownershipGeneration++;
        setOwnershipLoading(false);
        return;
      }
      const reconnect = !sessionWasAvailable;
      sessionWasAvailable = true;
      ownershipRequest = confirmOwnership(reader() ? null : target, false, reconnect);
    }),
  );
  const displayedSpread = createMemo(() => {
    const spread = selectedSpread();
    const confirmed = placement.snapshot();
    if (spread === undefined || confirmed?.kind !== "spread" || confirmed.id !== spread.id) return spread;
    const common = {
      ...spread,
      bleed: confirmed.layout.bleed,
      templateId: confirmed.layout.templateId,
      slots: confirmed.layout.slots,
      placements: confirmed.placements,
    };
    return common;
  });
  const chapter = createMemo(() => props.workspace.chapters.find((candidate) => candidate.id === selectedSpread()?.chapter));
  const navigatorSpreads = createMemo(() => {
    const acknowledged = displayedSpread();
    return props.workspace.spreads.map((spread) => (spread.id === acknowledged?.id ? acknowledged : spread));
  });
  const selectedChapterId = createMemo(() => chapter()?.id);
  const [gearText, setGearText] = createSignal<string>();
  const [gearError, setGearError] = createSignal<string>();
  const [gearLoading, setGearLoading] = createSignal(false);
  let presented = false;
  createEffect(() => {
    if (presented || ownershipLoading() || props.workspaceLoading || gearLoading()) return;
    presented = true;
    props.onReady?.();
  });
  let gearGeneration = 0;
  async function loadGearPresentation() {
    const generation = ++gearGeneration;
    const owner = chapter();
    if (owner === undefined) setGearText(undefined);
    setGearError(undefined);
    setGearLoading(owner !== undefined);
    if (owner === undefined) return;
    try {
      const [catalog, cameras] = await Promise.all([props.client.gearCatalog(), props.client.chapterGear(props.album.id, owner.id)]);
      if (!active || generation !== gearGeneration) return;
      const invalid = validateGear(cameras, catalog);
      if (invalid !== undefined) setGearError(invalid);
      else setGearText(describeGear(cameras, catalog));
    } catch (failure) {
      if (active && generation === gearGeneration) setGearError(describeFailure(failure));
    } finally {
      if (active && generation === gearGeneration) setGearLoading(false);
    }
  }
  createEffect(
    on(selectedChapterId, () => {
      setGearText(undefined);
      void loadGearPresentation();
    }),
  );
  let connected = false;
  createEffect(
    on(
      () => props.liveStatus,
      (status) => {
        if (status !== "live") return;
        if (connected) void loadGearPresentation();
        connected = true;
      },
    ),
  );
  const albumCountries = createMemo(() =>
    props.workspace.countries
      .filter((country) => props.workspace.chapters.some((owner) => owner.countries.includes(country.id)))
      .toSorted((first, second) => first.code.localeCompare(second.code)),
  );
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
    const canReconcile = placement.navigationReason() === undefined;
    if (props.workspaceLoading || refreshing() || !canReconcile) return;
    untrack(() => {
      setChoice(undefined);
      const spread = selectedSpread();
      if (part() === "cover") {
        if (!placement.loading()) void placement.load(props.album.id, { kind: "cover" });
      } else if (spread !== undefined) {
        const savedSpread = {
          kind: "spread",
          id: spread.id,
          placements: spread.placements,
          layout: { kind: spread.kind, bleed: spread.bleed, templateId: spread.templateId, slots: spread.slots },
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
      ownershipReason() ??
      historyReason() ??
      contentReason() ??
      (spreadPending()
        ? "Saving spread. Please wait."
        : refreshing() || props.workspaceLoading
          ? "Refreshing saved workspace. Please wait."
          : undefined),
    reader: reader() || ownershipReason() !== undefined,
    ownershipUnavailable: ownershipReason(),
    captions,
    onEditText: (opener) => setTextEdit({ kind: "description", chapter: chapter(), opener }),
    get textUnavailable() {
      return contentEditReason();
    },
  }));
  const navigationReason = createMemo(
    () =>
      historyReason() ??
      contentReason() ??
      (spreadPending()
        ? "Saving spread. Please wait."
        : spreadNeedsReload()
          ? "Reload the saved workspace before choosing another spread action."
          : reloadTarget() !== undefined
            ? "Read the saved spread before continuing."
            : placement.navigationReason()),
  );
  const otherOwner = createMemo(() => {
    const maybeTarget = editingTarget();
    return maybeTarget === undefined ? undefined : props.session.owner(maybeTarget);
  });
  let observedOwnershipRevision = untrack(() => props.session.ownershipRevision());
  createEffect(
    on(
      [() => props.session.ownershipRevision(), ownershipLoading, () => props.session.targetPending(), navigationReason],
      ([revision, loading, pending, maybeNavigationReason]) => {
        if (revision === observedOwnershipRevision || loading || pending || maybeNavigationReason !== undefined) return;
        observedOwnershipRevision = revision;
        const maybeTarget = editingTarget();
        if (!reader() && props.session.available() && maybeTarget !== undefined && otherOwner() !== undefined)
          void confirmOwnership(maybeTarget, true);
      },
    ),
  );
  const refreshReason = createMemo(() =>
    !props.session.available()
      ? "The browser session is unconfirmed. Reconnect before editing."
      : spreadPending()
        ? "Saving spread. Please wait."
        : refreshing() || props.workspaceLoading
          ? "Refreshing saved workspace. Please wait."
          : props.workspaceError !== undefined
            ? "Reload the saved workspace before editing chapters."
            : undefined,
  );
  const creationReason = createMemo(() => refreshReason() ?? navigationReason() ?? getChapterCapacityReason(props.workspace.spreads.length));
  const spreadReadReason = createMemo(() =>
    reloadTarget() !== undefined || spreadNeedsReload() ? "Read the saved spread before continuing." : undefined,
  );
  const contentEditReason = createMemo(() => ownershipReason() ?? refreshReason() ?? navigationReason());
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
    if (window.location.pathname !== workspacePath) return;
    const next = readPart();
    void refresh();
    setPart(next);
    if (next !== "cover") setInterior(next);
  }
  window.addEventListener("popstate", syncHistory);
  const preventDiscard = (event: BeforeUnloadEvent) => {
    if (navigationReason() !== undefined) event.preventDefault();
  };
  window.addEventListener("beforeunload", preventDiscard);
  document.addEventListener("keydown", navigateSpread);
  document.addEventListener("click", leaveAlbum);
  onCleanup(() => {
    active = false;
    ownershipGeneration++;
    refreshGeneration++;
    window.removeEventListener("popstate", syncHistory);
    window.removeEventListener("beforeunload", preventDiscard);
    document.removeEventListener("keydown", navigateSpread);
    document.removeEventListener("click", leaveAlbum);
  });

  function leaveAlbum(event: MouseEvent) {
    if (event.defaultPrevented || event.button !== 0 || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    const link = event.target instanceof Element ? event.target.closest<HTMLAnchorElement>("a[href]") : null;
    if (link?.target !== "" || link.hasAttribute("download")) return;
    const destination = new URL(link.href);
    if (destination.origin !== window.location.origin || destination.pathname !== "/") return;
    event.preventDefault();
    if (navigationReason() !== undefined) return;
    void props.session.select(null).then((released) => {
      if (released && active) {
        window.history.pushState(null, "", destination);
        window.dispatchEvent(new PopStateEvent("popstate"));
      }
    });
  }

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
    void refresh();
    showSelection(next);
  }

  function showSelection(next: string | null) {
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
      if (active && generation === refreshGeneration && props.workspaceError === undefined) await loadGearPresentation();
    } finally {
      if (generation === refreshGeneration) setRefreshing(false);
    }
  }

  function saved(result: ChapterSave, action: { chapter: Chapter | undefined; opener: HTMLElement }) {
    const opener = action.opener;
    setDialog(undefined);
    if (action.chapter === undefined) showSelection(result.id);
    void refresh(result).then(() => {
      if (opener instanceof HTMLButtonElement && opener.isConnected && !opener.disabled) opener.focus({ preventScroll: true });
      else workspaceElement.focus({ preventScroll: true });
    });
  }

  function restoreFocus(opener: HTMLButtonElement) {
    queueMicrotask(() => {
      if (opener.isConnected && !opener.disabled) opener.focus({ preventScroll: true });
      else workspaceElement.focus({ preventScroll: true });
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
    try {
      pendingSpreadHistory?.();
      pendingSpreadHistory = undefined;
    } catch (failure) {
      setSpreadError(describeFailure(failure));
      workspaceElement.focus();
      return;
    }
    showSelection(destination);
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
    uncertainSpreadHistory = undefined;
    try {
      const before = navigatorSpreads().find((spread) => spread.id === action.spreadId);
      if (before === undefined) throw new AlbumRequestError(404, "The spread is no longer available.");
      const order = navigatorSpreads()
        .filter((spread) => spread.chapter === before.chapter)
        .map((spread) => spread.id);
      const epoch = props.session.lossRevision();
      const currentOrder = () => props.workspace.spreads.filter((spread) => spread.chapter === before.chapter).map((spread) => spread.id);
      const equalOrder = (left: readonly string[], right: readonly string[]) =>
        left.length === right.length && left.every((id, index) => id === right[index]);
      let destination: SpreadDestination = { kind: "part", part: part() };
      if (action.kind === "add") {
        uncertainSpreadHistory = () => {
          untrack(() => {
            if (epoch === props.session.lossRevision() && !equalOrder(currentOrder(), order))
              history.block(
                "A new spread appeared without a confirmed identity. Review the saved book; Undo cannot skip this edit or guess which spread to remove.",
              );
          });
        };
        const freshId = await props.client.addSpread(props.album.id, action.chapterId, action.spreadId);
        destination = { kind: "part", part: freshId };
        pendingSpreadHistory = () => {
          untrack(() => {
            if (epoch !== props.session.lossRevision()) return;
            const fresh = props.workspace.spreads.find((spread) => spread.id === freshId);
            if (fresh === undefined) throw new AlbumRequestError(404, "The new spread was saved, but its composition could not be read.");
            recordSpreadExistence(
              fresh,
              props.workspace.spreads.filter((spread) => spread.chapter === action.chapterId).map((spread) => spread.id),
              true,
            );
          });
        };
      } else if (action.kind === "move") {
        const replacement = buildChapterReorder(props.workspace.spreads, action.spreadId, action.direction);
        uncertainSpreadHistory = () => {
          untrack(() => {
            if (epoch !== props.session.lossRevision()) return;
            const current = currentOrder();
            if (equalOrder(current, replacement.spreadIds))
              recordSpreadOrder(replacement.chapter, replacement.expectedSpreadIds, replacement.spreadIds, action.spreadId);
            else if (!equalOrder(current, replacement.expectedSpreadIds))
              history.block("The saved chapter order differs from both known reorder outcomes. Review the saved book.");
          });
        };
        await props.client.replaceChapterComposition(props.album.id, replacement.chapter, replacement.expectedSpreadIds, replacement.spreadIds);
        pendingSpreadHistory = () => {
          untrack(() => {
            if (epoch === props.session.lossRevision())
              recordSpreadOrder(replacement.chapter, replacement.expectedSpreadIds, replacement.spreadIds, action.spreadId);
          });
        };
      } else if (action.kind === "template") {
        uncertainSpreadHistory = () => {
          untrack(() => {
            if (epoch !== props.session.lossRevision()) return;
            const current = props.workspace.spreads.find((spread) => spread.id === action.spreadId);
            if (current?.templateId === action.templateId) recordComposition(snapshotSpread(before), snapshotSpread(current), "Change template");
            else if (current === undefined || !sameComposition(snapshotSpread(before), snapshotSpread(current)))
              history.block("The saved spread differs from both known template outcomes. Review the saved book.");
          });
        };
        await props.client.changeSpreadTemplate(props.album.id, action.spreadId, action.templateId, confirmPlacements);
        pendingSpreadHistory = () => {
          untrack(() => {
            if (epoch !== props.session.lossRevision()) return;
            const savedSpread = props.workspace.spreads.find((spread) => spread.id === action.spreadId);
            if (savedSpread === undefined) throw new AlbumRequestError(404, "The saved template composition could not be read.");
            recordComposition(snapshotSpread(before), snapshotSpread(savedSpread), "Change template");
          });
        };
      } else {
        uncertainSpreadHistory = () => {
          untrack(() => {
            if (epoch !== props.session.lossRevision()) return;
            const current = currentOrder();
            if (
              equalOrder(
                current,
                order.filter((id) => id !== before.id),
              )
            )
              recordSpreadExistence(before, order, false);
            else if (!equalOrder(current, order))
              history.block("The saved chapter membership differs from both known deletion outcomes. Review the saved book.");
          });
        };
        await props.client.deleteSpread(props.album.id, action.spreadId, confirmPlacements);
        destination = { kind: "index", index: action.index };
        pendingSpreadHistory = () => {
          untrack(() => {
            if (epoch === props.session.lossRevision()) recordSpreadExistence(before, order, false);
          });
        };
      }
      uncertainSpreadHistory = undefined;
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
            maybeAuthority={() => (props.session.available() ? props.session.lossRevision() : undefined)}
            authors={props.album.authors}
            unavailable={
              historyReason() ??
              refreshReason() ??
              spreadReadReason() ??
              placement.navigationReason() ??
              Object.entries(contentBlocks()).find(([key]) => key !== "authors")?.[1]
            }
            save={saveAuthors}
            read={readAuthors}
            onCompleted={(before, after) => {
              if (before.length === after.length && before.every((author) => after.includes(author))) return;
              history.record(
                createContentCompensation({
                  label: "Change authors",
                  before,
                  after,
                  read: readAuthors,
                  write: saveAuthors,
                  equal: (left, right) => left.length === right.length && left.every((author) => right.includes(author)),
                  prepare: () => prepareHistoryTarget(part(), false),
                }),
              );
            }}
            onBlock={(reason) => {
              blockContent("authors", reason);
            }}
          />
          <span aria-hidden="true">|</span>
          <a class="back-link" href="/">
            Back to album catalog
          </a>
        </div>
        <div class="workspace-controls" role="group" aria-label="Workspace controls">
          <BookExportControl
            album={props.album.id}
            year={props.album.year}
            client={props.client}
            unavailable={historyReason() ?? contentReason() ?? refreshReason() ?? spreadReadReason() ?? placement.navigationReason()}
          />
          <Show when={!reader()}>
            <BookHistoryControls
              history={history}
              unavailable={
                (historyBusy() ? "Finishing the history action. Please wait." : undefined) ??
                contentReason() ??
                refreshReason() ??
                spreadReadReason() ??
                placement.navigationReason() ??
                (confirmation() !== undefined ? "Finish the spread confirmation." : undefined)
              }
              traverse={traverseHistory}
              reconcile={reconcileHistory}
            />
          </Show>
          <BrowserAlias session={props.session} />
          <Show when={!reader() && ownershipReason() !== undefined && otherOwner() === undefined && editingTarget()}>
            {(target) => (
              <button
                type="button"
                disabled={!props.session.available() || props.session.targetPending() || ownershipLoading()}
                aria-describedby="editing-ownership-reason"
                onClick={() => {
                  void confirmOwnership(target(), true);
                }}
              >
                Edit spread
              </button>
            )}
          </Show>
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
        <p role="status" class="control-help">
          Refreshing saved workspace…
        </p>
      </Show>
      <Show when={!reader() && ownershipReason()}>
        {(reason) => (
          <p id="editing-ownership-reason" role="status" class="control-help">
            {reason()}
          </p>
        )}
      </Show>
      <Show when={placement.error()}>
        {(message) => (
          <p id="photo-save-feedback" role="alert">
            {message()}{" "}
            <Show when={photoRecoveryReason()}>
              {(reason) => (
                <span class="visually-hidden" id="photo-recovery-reason">
                  {reason()}
                </span>
              )}
            </Show>
            <button
              type="button"
              disabled={photoRecoveryReason() !== undefined}
              aria-describedby={photoRecoveryReason() === undefined ? undefined : "photo-recovery-reason"}
              onClick={() => {
                void placement.retry();
              }}
            >
              Retry photos
            </button>
            <Show when={placement.retained()}>
              <button
                type="button"
                disabled={photoApplyReason() !== undefined}
                aria-label="Apply retained photo adjustments"
                aria-describedby={photoApplyReason() === undefined ? undefined : "photo-apply-reason"}
                onClick={() => {
                  void placement.applyRetained();
                }}
              >
                Apply retained photo adjustments
                <Show when={photoApplyReason()}>
                  {(reason) => (
                    <span class="visually-hidden" id="photo-apply-reason">
                      {reason()}
                    </span>
                  )}
                </Show>
              </button>
              <button
                type="button"
                disabled={photoRecoveryReason() !== undefined}
                aria-describedby={photoRecoveryReason() === undefined ? undefined : "photo-recovery-reason"}
                onClick={() => {
                  void placement.discardRetained();
                }}
              >
                Discard retained photo adjustments
              </button>
            </Show>
          </p>
        )}
      </Show>
      <Show when={navigationReason()}>
        {(message) => (
          <p id="photo-navigation-reason" class="control-help" role="status">
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
            <RetainedCaption
              caption={retained}
              focus={captions.focus}
              returnFocus={() => {
                workspaceElement.focus();
              }}
            />
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
              if (reloadTarget() !== undefined) {
                void reloadSpread();
                return;
              }
              if (spreadNeedsReload()) {
                void refresh().then(() => {
                  if (props.workspaceError === undefined) {
                    uncertainSpreadHistory?.();
                    uncertainSpreadHistory = undefined;
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
            {reloadTarget() !== undefined ? "Retry workspace" : spreadNeedsReload() ? "Reload saved workspace" : "Retry spread action"}
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
        unavailable={ownershipReason() ?? refreshReason() ?? placement.unavailable() ?? navigationReason()}
        onRetryRefresh={(id) => {
          void props.onPhotoRefresh([id]);
        }}
      >
        <div class="workspace-editor">
          <Show when={part() === "cover"}>
            <CompositionControls session={placement} unavailable={editing().unavailable} reader={reader()} feedbackId="photo-save-feedback" />
          </Show>
          <Show when={displayedSpread()}>
            {(spread) => (
              <TemplateSelector
                context={spread().kind}
                appliedTemplateId={spread().templateId}
                load={props.client.templates}
                unavailable={ownershipReason() ?? refreshReason() ?? navigationReason()}
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
                aspectRatio={part() === "cover" ? savedCoverAspectRatio() : spreadAspectRatio}
                label={readerLabel()}
                entryMount={readerEntry()}
                unavailable={
                  selection().kind === "invalid"
                    ? "Choose an available album part to enter reader mode."
                    : (refreshReason() ?? navigationReason() ?? (gearLoading() ? "Loading saved chapter gear." : gearError()))
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
                <Show when={gearLoading()}>
                  <p role="status" class="control-help">
                    Loading saved chapter gear.
                  </p>
                </Show>
                <Show when={gearError()}>
                  {(message) => (
                    <p role="alert" class="reader-error">
                      {message()}{" "}
                      <button
                        type="button"
                        onClick={() => {
                          void loadGearPresentation();
                        }}
                      >
                        Read chapter gear
                      </button>
                    </p>
                  )}
                </Show>
                <div class="workspace-lock-frame" classList={{ "workspace-locked": otherOwner() !== undefined }}>
                  <Show when={otherOwner()}>
                    {(owner) => (
                      <span class="workspace-lock-owner" role="status">
                        {owner()}
                      </span>
                    )}
                  </Show>
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
                        gear={gearText()}
                        synopsisCountries={spread().kind === "album_start" ? albumCountries() : undefined}
                        editing={editing()}
                      />
                    )}
                  </Show>
                </div>
              </FullscreenReader>
              <Show when={contentEditReason()}>
                {(reason) => (
                  <p id="content-edit-reason" class="control-help">
                    {reason()}
                  </p>
                )}
              </Show>
              <Show when={chapter()}>
                {(owner) => (
                  <Show when={spreadPosition()}>
                    {(position) => (
                      <ChapterMetadata
                        leading={
                          <CompositionControls
                            session={placement}
                            unavailable={editing().unavailable}
                            reader={reader()}
                            feedbackId="photo-save-feedback"
                          />
                        }
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
              <Show when={chapter() === undefined && part() !== "cover" && !reader()}>
                <div class="chapter-metadata-row">
                  <div class="spread-commands" role="group" aria-label="Spread commands">
                    <CompositionControls session={placement} unavailable={editing().unavailable} reader={reader()} feedbackId="photo-save-feedback" />
                    <button
                      type="button"
                      class="icon-button"
                      aria-label="Edit album name"
                      title="Edit album name"
                      disabled={(refreshReason() ?? navigationReason()) !== undefined}
                      aria-describedby={(refreshReason() ?? navigationReason()) === undefined ? undefined : "album-name-reason"}
                      onClick={(event) => {
                        setTextEdit({ kind: "name", opener: event.currentTarget });
                      }}
                    >
                      <svg viewBox="0 0 24 24" aria-hidden="true">
                        <path d="m15 4 5 5M4 20l5-1L20 8a2 2 0 0 0-5-5L4 14z" />
                      </svg>
                    </button>
                    <Show when={refreshReason() ?? navigationReason()}>
                      {(reason) => (
                        <span class="visually-hidden" id="album-name-reason">
                          {reason()}
                        </span>
                      )}
                    </Show>
                  </div>
                </div>
              </Show>
            </Show>
          </article>
          <Show when={part() !== "cover"}>
            <ChapterOutline
              albumId={props.album.id}
              chapters={props.workspace.chapters}
              spreads={navigatorSpreads()}
              photos={editing().photos}
              onRetry={() => {
                void refresh();
              }}
              part={part()}
              creationReason={creationReason()}
              unavailable={refreshReason() ?? navigationReason()}
              destructiveUnavailable={ownershipReason()}
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
        {(action) => {
          const editedChapter = action.chapter;
          let grouped: { before: ChapterBookState; after: ChapterBookState; revision: number } | undefined;
          return (
            <ChapterDialog
              maybeAuthority={() => (props.session.available() ? props.session.lossRevision() : undefined)}
              resources={{ client: props.client, albumId: props.album.id, maybeChapterId: action.chapter?.id }}
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
              onHistoryBoundary={() => {
                history.reset("The chapter or photo source was saved. A new Undo history has started.");
              }}
              onBookSaved={
                editedChapter === undefined
                  ? undefined
                  : (before, after) => {
                      const revision = props.session.lossRevision();
                      if (grouped?.revision !== revision) grouped = undefined;
                      if (grouped !== undefined) {
                        grouped.before.details = before.details;
                        grouped.before.gear = before.gear;
                        grouped.after.details = after.details;
                        grouped.after.gear = after.gear;
                      } else if (!sameChapterBook(before, after)) {
                        const recorded = { ...after };
                        const entry = createChapterCompensation({
                          before,
                          after: recorded,
                          prepare: () => prepareHistoryTarget(editedChapter.id, false),
                          readDetails: async () => {
                            const chapters = await props.client.chapters(props.album.id, new AbortController().signal);
                            const savedChapter = chapters.find((item) => item.id === editedChapter.id);
                            if (savedChapter === undefined) throw new AlbumRequestError(404, "The chapter is no longer available.");
                            return chapterDetails(savedChapter);
                          },
                          readGear: () => props.client.chapterGear(props.album.id, editedChapter.id),
                          saveDetails: (details) => props.client.updateChapter(props.album.id, editedChapter.id, details),
                          saveGear: (gear) => props.client.saveChapterGear(props.album.id, editedChapter.id, gear),
                        });
                        history.record(entry);
                        grouped = { before, after: recorded, revision };
                      }
                    }
              }
              onSaved={(result) => {
                saved(result, action);
              }}
              onReconciled={(chapterId) => {
                setDialog(undefined);
                if (action.chapter === undefined) showSelection(chapterId);
                void refresh().then(() => {
                  if (action.opener.isConnected && !(action.opener instanceof HTMLButtonElement && action.opener.disabled))
                    action.opener.focus({ preventScroll: true });
                  else workspaceElement.focus({ preventScroll: true });
                });
              }}
              onBlock={(reason) => {
                blockContent("chapter", reason);
              }}
              onClose={() => {
                setDialog(undefined);
              }}
              returnFocus={() => {
                if (action.opener.isConnected && !(action.opener instanceof HTMLButtonElement && action.opener.disabled))
                  action.opener.focus({ preventScroll: true });
                else workspaceElement.focus({ preventScroll: true });
              }}
            />
          );
        }}
      </Show>
      <Show when={textEdit()} keyed>
        {(action) => {
          const owner = action.kind === "description" ? action.chapter : undefined;
          return (
            <TextEditor
              singleLine={action.kind === "name"}
              validate={action.kind === "name" ? validateTitle : undefined}
              ownership={{
                maybeAuthority:
                  props.session.available() && (action.kind === "name" || ownershipReason() === undefined)
                    ? action.kind === "name"
                      ? props.session.lossRevision()
                      : props.session.authority()
                    : undefined,
                recoveryLabel: props.session.available() ? "Edit spread" : "Reconnect browser",
                reason: ownershipReason() ?? "Editing ownership is unavailable. Your draft is retained.",
                pending: props.session.targetPending() || ownershipLoading(),
                recover: async () => {
                  if (!props.session.available()) await props.session.reconnect();
                  else await confirmOwnership(editingTarget(), true);
                },
              }}
              heading={action.kind === "name" ? "Edit album name" : owner === undefined ? "Album synopsis" : `Chapter description for ${owner.name}`}
              label={action.kind === "name" ? "Album name" : owner === undefined ? "Synopsis" : "Chapter description"}
              text={action.kind === "name" ? props.album.title : (owner?.description ?? props.album.synopsis)}
              save={(text) => (action.kind === "name" ? saveName(text) : saveText(owner, text))}
              read={() => (action.kind === "name" ? readName() : readText(owner))}
              onCompleted={(before, after) => {
                if (action.kind === "description") recordText(owner, before, after);
                else if (before !== after)
                  history.record(
                    createContentCompensation({
                      label: "Rename album",
                      before,
                      after,
                      read: readName,
                      write: saveName,
                      equal: (left, right) => left === right,
                      prepare: () => prepareHistoryTarget(null, false),
                    }),
                  );
              }}
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
          );
        }}
      </Show>
    </section>
  );
}
