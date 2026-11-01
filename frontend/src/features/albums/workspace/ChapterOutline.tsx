import { For, Show, createEffect, createMemo, on, onCleanup, onMount } from "solid-js";
import type { Chapter, Slot, Spread } from "../model/Workspace";
import { findSelection, findSpreadNavigation } from "./WorkspaceSelection";
import { spreadAspectRatio } from "./Canvas";

export function ChapterOutline(props: {
  albumId: string;
  chapters: readonly Pick<Chapter, "id" | "name">[];
  spreads: readonly Spread[];
  part: string | null;
  creationReason: string | undefined;
  unavailable: string | undefined;
  onAdd: (chapterId: string, spreadId: string, opener: HTMLButtonElement) => void;
  onMove: (spreadId: string, direction: "earlier" | "later", opener: HTMLButtonElement) => void;
  onDelete: (spreadId: string, opener: HTMLButtonElement) => void;
  onSelect: (part: string | null) => void;
  onCreate: (opener: HTMLButtonElement) => void;
}) {
  const selection = createMemo(() => findSelection(props.spreads, props.part));
  const selectedSpread = createMemo(() => {
    const selected = selection();
    return selected.kind === "spread" ? selected.spread : undefined;
  });
  const navigation = createMemo(() => findSpreadNavigation(props.spreads, selection()));
  const reasons = createMemo(() => spreadActionReasons(props.spreads, selectedSpread(), props.unavailable));
  const position = createMemo(() =>
    navigation().position === undefined ? "Choose a spread" : `Spread ${String(navigation().position)} / ${String(navigation().total)}`,
  );
  let strip: HTMLElement;
  let active = true;
  function revealSelectedSpread() {
    if (!active) return;
    const selected = strip.querySelector('[aria-current="page"]');
    if (selected === null) return;
    const bounds = selected.getBoundingClientRect();
    strip.scrollLeft += bounds.left - strip.getBoundingClientRect().left - (strip.clientWidth - bounds.width) / 2;
  }
  createEffect(
    on(
      () => props.part,
      () => {
        queueMicrotask(revealSelectedSpread);
      },
    ),
  );
  onMount(() => {
    const observer = new ResizeObserver(revealSelectedSpread);
    observer.observe(strip);
    onCleanup(() => {
      observer.disconnect();
    });
  });
  onCleanup(() => {
    active = false;
  });

  return (
    <aside class="spread-navigator" aria-label="Spread navigator">
      <nav
        ref={(element) => {
          strip = element;
        }}
        aria-label="Album organization"
        tabindex="0"
      >
        <a
          href={`/albums/${props.albumId}`}
          aria-label="Synopsis"
          title="Synopsis"
          aria-current={selectedSpread()?.kind === "album_start" ? "page" : undefined}
          onClick={(event) => {
            event.preventDefault();
            props.onSelect(null);
          }}
        >
          <Show when={props.spreads.find((spread) => spread.kind === "album_start")}>{(spread) => <SpreadThumbnail slots={spread().slots} />}</Show>
        </a>
        <For each={props.chapters.map((chapter) => chapter.id)}>
          {(chapterId) => (
            <Show when={props.chapters.find((chapter) => chapter.id === chapterId)}>
              {(chapter) => (
                <div class="chapter-spread-group">
                  <For each={props.spreads.filter((spread) => spread.chapter === chapterId).map((spread) => spread.id)}>
                    {(spreadId, index) => (
                      <Show when={props.spreads.find((spread) => spread.id === spreadId)}>
                        {(spread) => {
                          const destination = () => (spread().kind === "chapter_start" ? chapterId : spreadId);
                          const caption = () =>
                            spread().kind === "chapter_start" ? chapter().name : `${chapter().name} · Photo spread ${String(index())}`;
                          return (
                            <a
                              href={`?part=${destination()}`}
                              aria-label={caption()}
                              title={caption()}
                              aria-current={selectedSpread()?.id === spreadId ? "page" : undefined}
                              onClick={(event) => {
                                event.preventDefault();
                                props.onSelect(destination());
                              }}
                            >
                              <SpreadThumbnail slots={spread().slots} />
                            </a>
                          );
                        }}
                      </Show>
                    )}
                  </For>
                </div>
              )}
            </Show>
          )}
        </For>
      </nav>
      <div class="spread-navigation-toolbar">
        <div role="group" aria-label="Spread navigation">
          <SpreadNavigationButton name="First spread" symbol="⇤" destination={navigation().first} onSelect={props.onSelect} />
          <SpreadNavigationButton name="Previous spread" symbol="‹" destination={navigation().previous} onSelect={props.onSelect} />
          <span id="workspace-spread-position" aria-live="polite">
            {position()}
          </span>
          <SpreadNavigationButton name="Next spread" symbol="›" destination={navigation().next} onSelect={props.onSelect} />
          <SpreadNavigationButton name="Last spread" symbol="⇥" destination={navigation().last} onSelect={props.onSelect} />
        </div>
        <div role="group" aria-label="Spread and chapter actions">
          <SpreadAction
            name="Add spread"
            icon="M4 5h16v14H4z M12 8v8 M8 12h8"
            reason={reasons().add}
            onClick={(opener) => {
              const spread = selectedSpread();
              /* v8 ignore next -- Disabled without a chapter-owned spread. */
              if (spread === undefined || spread.chapter === null) return;
              props.onAdd(spread.chapter, spread.id, opener);
            }}
          />
          <SpreadAction name="Add chapter" icon="M4 4h6l2 3h8v13H4z M12 10v7 M8.5 13.5h7" reason={props.creationReason} onClick={props.onCreate} />
        </div>
        <div role="group" aria-label="Selected photo spread actions">
          <SpreadAction
            name="Shift spread left"
            icon="m6 8-4 4 4 4 M2 12h20"
            reason={reasons().earlier}
            onClick={(opener) => {
              const spread = selectedSpread();
              /* v8 ignore next -- Disabled without a selected photo spread. */
              if (spread === undefined) return;
              props.onMove(spread.id, "earlier", opener);
            }}
          />
          <SpreadAction
            name="Shift spread right"
            icon="m18 8 4 4-4 4 M2 12h20"
            reason={reasons().later}
            onClick={(opener) => {
              const spread = selectedSpread();
              /* v8 ignore next -- Disabled without a selected photo spread. */
              if (spread === undefined) return;
              props.onMove(spread.id, "later", opener);
            }}
          />
          <Show when={selectedSpread()?.kind === "photos_only"}>
            <SpreadAction
              name="Delete spread"
              icon="M4 6h16 M9 6V3h6v3 M6 6l1 15h10l1-15 M10 10v7 M14 10v7"
              reason={reasons().remove}
              onClick={(opener) => {
                const spread = selectedSpread();
                /* v8 ignore next -- Disabled without a selected photo spread. */
                if (spread === undefined || spread.chapter === null) return;
                props.onDelete(spread.id, opener);
              }}
            />
          </Show>
        </div>
      </div>
      <Show when={props.spreads.length >= 65}>
        <>
          <p class="spread-action-feedback">This album has 65 interior spreads. No more spreads can be added.</p>
          <p class="spread-action-feedback">This album has 65 interior spreads. No more chapters can be added.</p>
        </>
      </Show>
      <Show when={props.creationReason}>
        {(reason) => (
          <p id="chapter-capacity" class="control-help">
            {reason()}
          </p>
        )}
      </Show>
    </aside>
  );
}

export function spreadActionReasons(
  spreads: readonly Pick<Spread, "id" | "kind" | "chapter">[],
  selected: Pick<Spread, "id" | "kind" | "chapter"> | undefined,
  unavailable: string | undefined,
) {
  const photos = spreads.filter((spread) => spread.kind === "photos_only" && spread.chapter === selected?.chapter);
  const index = photos.findIndex((spread) => spread.id === selected?.id);
  const photoReason = unavailable ?? (selected?.kind !== "photos_only" ? "Select a photo spread first." : undefined);
  return {
    add:
      unavailable ??
      (spreads.length >= 65
        ? "This album has 65 interior spreads. No more spreads can be added."
        : selected === undefined || selected.kind === "album_start"
          ? "Select a chapter opening or photo spread first."
          : undefined),
    earlier: photoReason ?? (index <= 0 ? "This is the first photo spread in its chapter." : undefined),
    later: photoReason ?? (index >= photos.length - 1 ? "This is the last photo spread in its chapter." : undefined),
    remove: photoReason,
  };
}

function SpreadAction(props: { name: string; icon: string; reason: string | undefined; onClick: (opener: HTMLButtonElement) => void }) {
  const descriptionId = () => `spread-${props.name.replaceAll(" ", "-")}`;
  return (
    <>
      <button
        type="button"
        aria-label={props.name}
        title={props.reason ?? props.name}
        disabled={props.reason !== undefined}
        aria-describedby={props.reason === undefined ? undefined : descriptionId()}
        onClick={(event) => {
          props.onClick(event.currentTarget);
        }}
      >
        <svg
          class="workspace-action-icon"
          viewBox="0 0 24 24"
          width="18"
          height="18"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path d={props.icon} />
        </svg>
      </button>
      <Show when={props.reason}>
        {(reason) => (
          <span id={descriptionId()} class="visually-hidden">
            {reason()}
          </span>
        )}
      </Show>
    </>
  );
}

export function getChapterCapacityReason(count: number): string | undefined {
  return count >= 65 ? "This album has 65 interior spreads. No more chapters can be added." : undefined;
}

function SpreadNavigationButton(props: {
  name: string;
  symbol: string;
  destination: string | null | undefined;
  onSelect: (part: string | null) => void;
}) {
  return (
    <button
      type="button"
      aria-label={props.name}
      title={props.name}
      disabled={props.destination === undefined}
      aria-describedby={props.destination === undefined ? "workspace-spread-position" : undefined}
      onClick={() => {
        /* v8 ignore next -- Native disabled endpoint controls cannot activate. */
        if (props.destination !== undefined) props.onSelect(props.destination);
      }}
    >
      {props.symbol}
    </button>
  );
}

export function SpreadThumbnail(props: { slots: readonly Omit<Slot, "id">[] }) {
  return (
    <svg
      aria-hidden="true"
      class="spread-thumbnail"
      viewBox={`0 0 100 ${String(100 / spreadAspectRatio)}`}
      style={{ "aspect-ratio": spreadAspectRatio }}
    >
      <rect width="100%" height="100%" fill="white" />
      <For each={props.slots}>
        {(slot) => (
          <svg x={`${String(slot.x)}%`} y={`${String(slot.y)}%`} width={`${String(slot.width)}%`} height={`${String(slot.height)}%`}>
            <rect width="100%" height="100%" fill={slot.kind === "text" ? "#bed9d9" : "#dfebec"} />
          </svg>
        )}
      </For>
      <path d={`M50 0V${String(100 / spreadAspectRatio)}`} stroke="#9eb5bb" stroke-width=".35" />
    </svg>
  );
}
