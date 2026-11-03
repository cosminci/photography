import { For, Show, createMemo, createSignal, createUniqueId, onCleanup } from "solid-js";
import type { Photo } from "../model/Photo";
import { captionCorners, type Placement, type PlacementEdit, type PhotoTransform } from "../model/Placement";
import { PhotoCaption, type CaptionEditing } from "./PhotoCaption";
import { boundTransform, panTransform } from "./PlacementCrop";
import { SavedPhoto } from "./SavedPhoto";
import { photoDragFeedback, photoDragType, readPhotoChoice, startPhotoDrag, type PhotoChoice } from "./PhotoDrag";
import type { PlacementSession } from "./PlacementSession";
import "./placement.css";

const cornerIcons = {
  top_left: { label: "Top left", path: "M5 5h6v3H5z" },
  top_right: { label: "Top right", path: "M13 5h6v3h-6z" },
  bottom_left: { label: "Bottom left", path: "M5 16h6v3H5z" },
  bottom_right: { label: "Bottom right", path: "M13 16h6v3h-6z" },
};

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
  ownershipUnavailable?: string | undefined;
  reader: boolean;
  captions?: CaptionEditing | undefined;
}) {
  const reasonId = createUniqueId();
  const reason = createMemo(() => props.unavailable ?? props.session.unavailable());
  const actionReason = createMemo(() => reason() ?? props.session.navigationReason());
  const [dragError, setDragError] = createSignal(false);
  const [mediaFailed, setMediaFailed] = createSignal(false);
  const [mediaRetry, setMediaRetry] = createSignal(0);
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
            <Show when={props.placement}>
              {(placement) => (
                <SavedPhoto
                  photo={photo()}
                  transform={placement().transform}
                  width={props.width}
                  height={props.height}
                  retry={mediaRetry()}
                  onImage={(element) => {
                    image = element;
                  }}
                  onError={() => setMediaFailed(true)}
                />
              )}
            </Show>
          )}
        </Show>
      </button>
      <Show when={mediaFailed()}>
        <div class="caption-feedback" role="alert">
          Photograph could not be loaded.
          <button
            type="button"
            onClick={() => {
              setMediaFailed(false);
              setMediaRetry((value) => value + 1);
            }}
          >
            Retry photograph
          </button>
        </div>
      </Show>
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
                  ownershipUnavailable={props.ownershipUnavailable}
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
                <div class="photo-caption-actions" role="group" aria-label="Move caption">
                  <For each={alternativeCorners()}>
                    {(corner) => (
                      <button
                        type="button"
                        class="photo-slot-action"
                        aria-label={`Move caption to ${cornerIcons[corner].label.toLowerCase()}`}
                        title={cornerIcons[corner].label}
                        disabled={actionReason() !== undefined}
                        aria-describedby={actionReason() === undefined ? undefined : `${reasonId}-action`}
                        onClick={() => {
                          void edit({ operation: "captionCorner", placementId: row().id, captionCorner: corner });
                        }}
                      >
                        <svg viewBox="0 0 24 24" aria-hidden="true">
                          <rect x="3" y="3" width="18" height="18" rx="1" />
                          <path d={cornerIcons[corner].path} />
                        </svg>
                      </button>
                    )}
                  </For>
                </div>
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
