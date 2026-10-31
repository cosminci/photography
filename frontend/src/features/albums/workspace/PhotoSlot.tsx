import { For, Show, createMemo, createSignal, createUniqueId, onCleanup } from "solid-js";
import type { Photo } from "../model/Photo";
import { captionCorners, type Placement, type PlacementEdit, type PhotoTransform } from "../model/Placement";
import { PhotoCaption, type CaptionEditing } from "./PhotoCaption";
import { boundTransform, cropImage, panTransform } from "./PlacementCrop";
import { photoDragFeedback, photoDragType, readPhotoChoice, startPhotoDrag, type PhotoChoice } from "./PhotoDrag";
import type { PlacementSession } from "./PlacementSession";
import "./placement.css";

export function PhotoSlot(props: {
  slotId: string;
  label: string;
  width: number;
  height: number;
  placement: Placement | undefined;
  photo: Photo | undefined;
  session: PlacementSession;
  choice: PhotoChoice | undefined;
  onChoose: (choice: PhotoChoice | undefined) => void;
  unavailable: string | undefined;
  reader: boolean;
  captions?: CaptionEditing | undefined;
}) {
  const reasonId = createUniqueId();
  const reason = createMemo(() => props.unavailable ?? props.session.unavailable());
  const actionReason = createMemo(() => reason() ?? props.session.navigationReason());
  const [dragError, setDragError] = createSignal(false);
  const moving = createMemo(() => props.choice?.kind === "move" && props.choice.placementId === props.placement?.id);
  const caption = createMemo(() => {
    const row = props.placement;
    const photo = props.photo;
    return row?.kind === "spread" && photo !== undefined ? { row, photo } : undefined;
  });
  const alternativeCorners = createMemo(() => {
    const row = props.placement;
    return row?.kind === "spread" ? captionCorners.filter((corner) => corner !== row.captionCorner) : [];
  });
  const crop = createMemo(() => {
    const photo = props.photo;
    const placement = props.placement;
    return photo === undefined || placement === undefined ? undefined : cropImage(photo, props, placement.transform);
  });
  let image: HTMLImageElement | undefined;
  let surface!: HTMLButtonElement;
  let burst: { timer: ReturnType<typeof setTimeout>; id: string; transform: PhotoTransform } | undefined;
  let pan: { x: number; y: number; transform: PhotoTransform; original: PhotoTransform; id: string } | undefined;
  function flush() {
    if (burst === undefined) return;
    clearTimeout(burst.timer);
    const { id, transform } = burst;
    burst = undefined;
    props.session.complete(id, transform);
  }
  onCleanup(() => {
    if (burst !== undefined) clearTimeout(burst.timer);
  });

  function adjust(transform: PhotoTransform, complete = false) {
    const photo = props.photo;
    const row = props.placement;
    if (photo === undefined || row === undefined) return;
    const bounded = boundTransform(photo, props, transform);
    props.session.preview(row.id, bounded);
    if (complete) {
      if (burst !== undefined) clearTimeout(burst.timer);
      burst = undefined;
      props.session.complete(row.id, bounded);
    } else {
      if (burst !== undefined) clearTimeout(burst.timer);
      burst = { id: row.id, transform: bounded, timer: setTimeout(flush, 250) };
    }
  }

  function destination(choice: PhotoChoice | undefined) {
    if (choice === undefined || reason() !== undefined) return;
    flush();
    const command =
      choice.kind === "photo"
        ? { operation: "place" as const, photoId: choice.photoId, slotId: props.slotId }
        : { operation: "move" as const, placementId: choice.placementId, slotId: props.slotId };
    void edit(command);
    props.onChoose(undefined);
  }

  async function edit(command: PlacementEdit) {
    await props.session.edit(command);
    document.getElementById(`photo-slot-${props.slotId}`)?.focus();
  }

  function key(event: KeyboardEvent) {
    const row = props.placement;
    if (row === undefined || event.altKey || event.ctrlKey || event.metaKey) return;
    const { transform } = row;
    const steps: Record<string, PhotoTransform> = {
      ArrowLeft: panTransform(transform, { x: -12, y: 0 }),
      ArrowRight: panTransform(transform, { x: 12, y: 0 }),
      ArrowUp: panTransform(transform, { x: 0, y: -12 }),
      ArrowDown: panTransform(transform, { x: 0, y: 12 }),
      "+": { ...transform, zoom: Math.min(5, transform.zoom + 0.1) },
      "=": { ...transform, zoom: Math.min(5, transform.zoom + 0.1) },
      "-": { ...transform, zoom: Math.max(1, transform.zoom - 0.1) },
    };
    const next = steps[event.key];
    if (next === undefined) return;
    event.preventDefault();
    event.stopPropagation();
    adjust(next);
  }

  return (
    <div class="photo-slot" classList={{ "photo-slot-moving": moving() }} role="group" aria-label={props.label}>
      <button
        ref={(element) => {
          surface = element;
        }}
        type="button"
        id={`photo-slot-${props.slotId}`}
        class="photo-slot-surface"
        aria-hidden={props.reader ? true : undefined}
        aria-label={`${props.label}${props.photo === undefined ? ": empty" : `: ${props.photo.filename}`}`}
        aria-describedby={props.reader ? undefined : reason() === undefined ? `${reasonId}-help` : reasonId}
        disabled={props.reader || reason() !== undefined}
        onClick={() => {
          destination(props.choice);
        }}
        onDragOver={(event) => {
          if (reason() === undefined && event.dataTransfer?.types.includes(photoDragType) === true) event.preventDefault();
        }}
        onDrop={(event) => {
          event.preventDefault();
          destination(readPhotoChoice(event.dataTransfer?.getData(photoDragType) ?? ""));
        }}
        onWheel={(event) => {
          if (props.reader || reason() !== undefined || props.placement === undefined) return;
          event.preventDefault();
          const transform = props.placement.transform;
          adjust({ ...transform, zoom: Math.max(1, Math.min(5, transform.zoom - event.deltaY * 0.002)) });
        }}
        onKeyDown={key}
        onBlur={flush}
        onPointerDown={(event) => {
          if (event.button !== 0 || props.placement === undefined || props.photo === undefined || props.choice !== undefined) return;
          flush();
          pan = {
            x: event.clientX,
            y: event.clientY,
            transform: boundTransform(props.photo, props, props.placement.transform),
            original: props.placement.transform,
            id: props.placement.id,
          };
          surface.setPointerCapture(event.pointerId);
        }}
        onPointerMove={(event) => {
          if (pan === undefined || props.photo === undefined) return;
          const box = surface.getBoundingClientRect();
          const transform = boundTransform(
            props.photo,
            props,
            panTransform(pan.transform, {
              x: ((event.clientX - pan.x) * props.width) / box.width,
              y: ((event.clientY - pan.y) * props.height) / box.height,
            }),
          );
          props.session.preview(pan.id, transform);
        }}
        onPointerUp={() => {
          if (pan === undefined || props.placement === undefined) return;
          props.session.complete(pan.id, props.placement.transform);
          pan = undefined;
        }}
        onPointerCancel={() => {
          if (pan === undefined) return;
          props.session.restore(pan.id, pan.original);
          pan = undefined;
        }}
      >
        <Show when={props.photo}>
          {(photo) => (
            <Show when={crop()}>
              {(box) => (
                <img
                  ref={(element) => {
                    image = element;
                  }}
                  src={photo().thumbnail.url}
                  alt={photo().filename}
                  draggable={false}
                  style={{
                    width: `${String((box().width / props.width) * 100)}%`,
                    height: `${String((box().height / props.height) * 100)}%`,
                    left: `${String((box().left / props.width) * 100)}%`,
                    top: `${String((box().top / props.height) * 100)}%`,
                    transform: props.placement?.transform.mirrored === true ? "scaleX(-1)" : undefined,
                  }}
                />
              )}
            </Show>
          )}
        </Show>
      </button>
      <Show when={caption()}>
        {(content) => (
          <Show
            when={props.captions}
            fallback={
              <div class="photo-caption" data-corner={content().row.captionCorner}>
                {content().photo.caption}
              </div>
            }
          >
            {(captions) => (
              <Show when={content().photo.id} keyed>
                <PhotoCaption
                  photo={content().photo}
                  corner={content().row.captionCorner}
                  reader={props.reader}
                  unavailable={actionReason()}
                  editing={captions()}
                />
              </Show>
            )}
          </Show>
        )}
      </Show>
      <Show when={!props.reader}>
        <span id={`${reasonId}-help`} class="control-help">
          Choose a collection photo, then activate a slot. Pan with arrows; zoom with + or −.
        </span>
        <Show when={reason()}>
          {(message) => (
            <span id={reasonId} class="control-help">
              {message()}
            </span>
          )}
        </Show>
        <Show when={actionReason()}>
          {(message) => (
            <span id={`${reasonId}-action`} class="control-help">
              {message()}
            </span>
          )}
        </Show>
        <Show when={props.placement}>
          {(row) => (
            <div class="photo-slot-actions" role="group" aria-label={`Actions for ${props.label}`}>
              <button
                type="button"
                class="photo-slot-action"
                draggable={actionReason() === undefined ? "true" : "false"}
                aria-label={`Move photo from ${props.label}`}
                aria-pressed={moving()}
                title="Drag to move or swap. Keyboard: activate, then choose a destination."
                disabled={actionReason() !== undefined}
                aria-describedby={actionReason() === undefined ? undefined : `${reasonId}-action`}
                onDragStart={(event) => {
                  setDragError(
                    !startPhotoDrag(
                      event,
                      { kind: "move", placementId: row().id },
                      {
                        image,
                        frame: surface,
                        mirrored: row().transform.mirrored,
                      },
                    ),
                  );
                }}
                onClick={(event) => {
                  if (event.detail === 0) {
                    setDragError(false);
                    props.onChoose({ kind: "move", placementId: row().id });
                  }
                }}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M8 12V6a1.5 1.5 0 0 1 3 0v5-7a1.5 1.5 0 0 1 3 0v7-6a1.5 1.5 0 0 1 3 0v7-3a1.5 1.5 0 0 1 3 0v7c0 3-2 5-5 5h-3c-2 0-3-1-4-3l-3-5a1.5 1.5 0 0 1 2-2l1 1Z" />
                </svg>
              </button>
              <button
                type="button"
                class="photo-slot-action"
                aria-label={`Mirror photo in ${props.label}`}
                aria-pressed={row().transform.mirrored}
                title="Mirror photo"
                disabled={reason() !== undefined}
                aria-describedby={reason() === undefined ? undefined : reasonId}
                onClick={() => {
                  adjust({ ...row().transform, mirrored: !row().transform.mirrored }, true);
                }}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M12 3v18M9 6 3 18h6ZM15 6l6 12h-6Z" />
                </svg>
              </button>
              <button
                type="button"
                class="photo-slot-action"
                aria-label={`Remove photo from ${props.label}`}
                title="Remove photo"
                disabled={actionReason() !== undefined}
                aria-describedby={actionReason() === undefined ? undefined : `${reasonId}-action`}
                onClick={() => {
                  void edit({ operation: "remove", placementId: row().id });
                }}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="m6 6 12 12M6 18 18 6" />
                </svg>
              </button>
              <Show when={row().kind === "spread"}>
                <For each={alternativeCorners()}>
                  {(corner) => (
                    <button
                      type="button"
                      class="photo-slot-action"
                      aria-label={`Caption ${corner.replace("_", " ")}`}
                      title={`Caption ${corner.replace("_", " ")}`}
                      disabled={actionReason() !== undefined}
                      aria-describedby={actionReason() === undefined ? undefined : `${reasonId}-action`}
                      onClick={() => {
                        void edit({ operation: "captionCorner", placementId: row().id, captionCorner: corner });
                      }}
                    >
                      <svg viewBox="0 0 24 24" aria-hidden="true">
                        <path
                          d={
                            corner === "top_left"
                              ? "M4 10V4h6M4 7h10M4 10h7"
                              : corner === "top_right"
                                ? "M14 4h6v6M10 7h10M13 10h7"
                                : corner === "bottom_left"
                                  ? "M4 14v6h6M4 14h7M4 17h10"
                                  : "M14 20h6v-6M13 14h7M10 17h10"
                          }
                        />
                      </svg>
                    </button>
                  )}
                </For>
              </Show>
            </div>
          )}
        </Show>
        <Show when={dragError()}>
          <span class="photo-drag-feedback" role="alert">
            {photoDragFeedback}
          </span>
        </Show>
      </Show>
    </div>
  );
}
