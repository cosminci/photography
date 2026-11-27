import { For, Show, createMemo, createSignal, onCleanup, onMount, type JSX } from "solid-js";
import type { PhotoInventory, Scheduling } from "../model/Photo";
import { derivePhotoRows, derivePhotos, limitPhotoWidth } from "./PhotoLayout";
import "./photos.css";
import { photoDragFeedback, startPhotoDrag } from "./PhotoDrag";

export function PhotoPane(props: {
  label: string;
  chapters: readonly { readonly id: string; readonly name: string }[];
  inventories: Readonly<Record<string, PhotoInventory>>;
  inventoryErrors: Readonly<Record<string, string>>;
  scheduling: Readonly<Record<string, Scheduling | "pending">>;
  liveStatus: "connecting" | "catching-up" | "live" | "interrupted" | "invalid";
  onRefresh: () => void;
  onRetryInventory: (id: string) => void;
  onChoosePhoto?: (id: string) => void;
  unavailable?: string | undefined;
  quietUnavailable?: boolean;
  children: JSX.Element;
}) {
  const [collapsed, setCollapsed] = createSignal(false);
  const [chosenWidth, setChosenWidth] = createSignal(320);
  const [availableWidth, setAvailableWidth] = createSignal(1000);
  const [contentWidth, setContentWidth] = createSignal(308);
  const [resizing, setResizing] = createSignal(false);
  const [dragError, setDragError] = createSignal(false);
  const width = createMemo(() => limitPhotoWidth(chosenWidth(), availableWidth()));
  const selection = createMemo(() =>
    derivePhotos(
      props.chapters.map((chapter) => chapter.id),
      props.inventories,
    ),
  );
  const layout = createMemo(() => derivePhotoRows(selection().photos, contentWidth()));
  const geometry = createMemo(
    () => new Map(layout().rows.flatMap((row) => row.items.map((item) => [`${item.photo.chapterId}/${item.photo.photo.id}`, item] as const))),
  );
  const keys = createMemo(() => [...geometry().keys()]);
  const noChapters = () => props.chapters.length === 0;
  const inventoryNeedsRetry = (id: string) => props.liveStatus !== "live" || props.inventoryErrors[id] !== undefined;
  const refreshing = createMemo(() =>
    props.chapters.some(({ id }) => {
      if (props.scheduling[id] === "pending") return true;
      if (inventoryNeedsRetry(id)) return false;
      const state = props.inventories[id]?.processing.state;
      return state === "queued" || state === "running";
    }),
  );
  const refreshReason = () => (noChapters() ? "Add a chapter to refresh photos." : refreshing() ? "Processing photos…" : undefined);
  let group: HTMLDivElement;
  let thumbnails: HTMLDivElement;
  let grip: HTMLButtonElement;
  let drag: { start: number; width: number } | undefined;
  let dragged = false;

  function measure() {
    const available = group.getBoundingClientRect().width;
    if (available > 0) setAvailableWidth(available);
    const content = thumbnails.getBoundingClientRect().width;
    if (content > 0) setContentWidth(content);
  }
  onMount(() => {
    measure();
    let frame: number | undefined;
    const observer = new ResizeObserver(() => {
      if (frame !== undefined) return;
      frame = requestAnimationFrame(() => {
        frame = undefined;
        measure();
      });
    });
    observer.observe(group);
    observer.observe(thumbnails);
    onCleanup(() => {
      observer.disconnect();
      if (frame !== undefined) cancelAnimationFrame(frame);
    });
  });
  onCleanup(endResize);

  function move(event: PointerEvent) {
    if (drag === undefined || (Math.abs(event.clientX - drag.start) < 4 && !dragged)) return;
    dragged = true;
    setResizing(true);
    setCollapsed(false);
    setChosenWidth(limitPhotoWidth(drag.width + event.clientX - drag.start, availableWidth()));
    measure();
  }
  function endResize() {
    drag = undefined;
    setResizing(false);
    window.removeEventListener("pointermove", move);
    window.removeEventListener("pointerup", endResize);
    window.removeEventListener("pointercancel", endResize);
  }

  return (
    <div
      ref={(element) => {
        group = element;
      }}
      role="group"
      aria-label="Photo browser and editor"
      class="photo-workspace"
      classList={{ "photos-collapsed": collapsed(), "photo-resizing": resizing() }}
      style={{ "--photo-width": `${String(width())}px` }}
    >
      <aside class="photo-pane">
        <section id="photo-controls" aria-label={props.label} inert={collapsed()} hidden={collapsed()} class="photo-controls">
          <div class="photo-toolbar">
            <div class="photo-heading">
              <h2>Photos</h2>
              <p class="photo-count" aria-live="polite">
                {selection().counts.unplaced} unplaced / {selection().counts.total} total
              </p>
            </div>
            <button
              type="button"
              class="icon-button photo-refresh"
              aria-label="Refresh photos"
              title={refreshReason() ?? "Refresh photos"}
              disabled={noChapters()}
              aria-disabled={refreshing() ? true : undefined}
              aria-describedby={refreshReason() === undefined ? undefined : "photo-refresh-reason"}
              onClick={() => {
                if (refreshing()) return;
                for (const { id } of props.chapters) {
                  if (inventoryNeedsRetry(id)) props.onRetryInventory(id);
                }
                props.onRefresh();
              }}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M3 12a9 9 0 0 1 15.36-6.36L21 8M21 3v5h-5M21 12a9 9 0 0 1-15.36 6.36L3 16M8 16H3v5" />
              </svg>
            </button>
          </div>
          <Show when={refreshReason()}>
            {(reason) => (
              <span id="photo-refresh-reason" hidden>
                {reason()}
              </span>
            )}
          </Show>
          <Show when={props.liveStatus === "invalid"}>
            <p role="alert">Live photo updates stopped. Reopen the album to reconnect.</p>
          </Show>
          <div
            ref={(element) => {
              thumbnails = element;
            }}
            role="group"
            aria-label="Photo thumbnails"
            class="photo-thumbnails"
            style={{ height: `${String(layout().height)}px` }}
          >
            <For each={keys()}>
              {(key) => (
                <Show when={geometry().get(key)}>
                  {(item) => {
                    let image!: HTMLImageElement;
                    return (
                      <button
                        type="button"
                        aria-label={`Choose ${item().photo.photo.filename}`}
                        draggable={props.unavailable === undefined ? "true" : "false"}
                        disabled={props.unavailable !== undefined}
                        aria-describedby={props.unavailable === undefined ? undefined : "photo-placement-unavailable"}
                        onClick={() => {
                          setDragError(false);
                          props.onChoosePhoto?.(item().photo.photo.id);
                        }}
                        onDragStart={(event) => {
                          setDragError(
                            !startPhotoDrag(
                              event,
                              { kind: "photo", photoId: item().photo.photo.id },
                              {
                                image,
                                frame: event.currentTarget,
                                mirrored: false,
                              },
                            ),
                          );
                        }}
                        style={{
                          width: `${String(item().width)}px`,
                          height: `${String(item().height)}px`,
                          left: `${String(item().left)}px`,
                          top: `${String(item().top)}px`,
                        }}
                      >
                        <img
                          ref={(element) => {
                            image = element;
                          }}
                          src={item().photo.photo.thumbnail.url}
                          alt={item().photo.photo.filename}
                          loading="lazy"
                          decoding="async"
                          draggable={false}
                          style={{ width: `${String(item().width)}px`, height: `${String(item().height)}px` }}
                          width={item().photo.photo.thumbnail.width}
                          height={item().photo.photo.thumbnail.height}
                        />
                      </button>
                    );
                  }}
                </Show>
              )}
            </For>
          </div>
          <Show when={dragError()}>
            <p role="alert">{photoDragFeedback}</p>
          </Show>
          <Show when={props.unavailable}>
            {(reason) => (
              <p
                id="photo-placement-unavailable"
                class="control-help"
                classList={{ "visually-hidden": props.quietUnavailable }}
                role={props.quietUnavailable === true ? undefined : "status"}
              >
                {props.quietUnavailable === true ? "Read only." : reason()}
              </p>
            )}
          </Show>
        </section>
        <button
          ref={(element) => {
            grip = element;
          }}
          type="button"
          class="photo-grip"
          aria-label={collapsed() ? "Expand photo pane" : "Collapse photo pane"}
          aria-expanded={!collapsed()}
          aria-controls="photo-controls"
          aria-describedby="photo-grip-help"
          onPointerDown={(event) => {
            if (event.button !== 0) return;
            event.preventDefault();
            grip.focus();
            dragged = false;
            drag = { start: event.clientX, width: width() };
            event.currentTarget.setPointerCapture(event.pointerId);
            window.addEventListener("pointermove", move);
            window.addEventListener("pointerup", endResize);
            window.addEventListener("pointercancel", endResize);
          }}
          onClick={(event) => {
            if (dragged && event.detail !== 0) {
              dragged = false;
              return;
            }
            setCollapsed(!collapsed());
            grip.focus();
          }}
          onKeyDown={(event) => {
            if (!event.altKey || (event.key !== "ArrowLeft" && event.key !== "ArrowRight")) return;
            event.preventDefault();
            setCollapsed(false);
            setChosenWidth(limitPhotoWidth(width() + (event.key === "ArrowRight" ? 16 : -16), availableWidth()));
            measure();
          }}
        >
          <svg viewBox="0 0 12 20" aria-hidden="true">
            <path d={collapsed() ? "m2 5 4 5-4 5m4-10 4 5-4 5" : "m6 5-4 5 4 5m4-10-4 5 4 5"} />
          </svg>
        </button>
        <span id="photo-grip-help" class="photo-grip-help">
          {width()}px. Drag or use Alt+Left/Right to resize; activate to collapse or restore.
        </span>
      </aside>
      {props.children}
    </div>
  );
}
