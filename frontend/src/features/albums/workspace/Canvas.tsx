import { For, Show, createMemo } from "solid-js";
import type { Slot } from "../model/Workspace";
import type { Photo } from "../model/Photo";
import type { PlacementSession } from "./PlacementSession";
import { PhotoSlot } from "./PhotoSlot";
import type { PhotoChoice } from "./PhotoDrag";
import { albumAuthors, type Author } from "../model/Album";
import type { CaptionEditing } from "./PhotoCaption";

export interface CanvasPhotos {
  readonly session: PlacementSession;
  readonly photos: readonly Photo[];
  readonly choice: PhotoChoice | undefined;
  readonly onChoose: (choice: PhotoChoice | undefined) => void;
  readonly unavailable: string | undefined;
  readonly reader: boolean;
  readonly captions?: CaptionEditing;
}

const spreadWidth = 2381.102;
const spreadHeight = 765.354;
export const spreadAspectRatio = spreadWidth / spreadHeight;
export const coverAspectRatio = 3;

export function SpreadCanvas(props: { slots: readonly Slot[]; title: string; text: string; label: string; editing?: CanvasPhotos }) {
  return (
    <div class="canvas-frame">
      <div
        class="spread-canvas-stage"
        role={props.editing !== undefined && !props.editing.reader ? "group" : "img"}
        aria-label={`${props.label} composition`}
      >
        <svg
          class="spread-canvas"
          role="img"
          aria-label={`${props.label} template geometry`}
          viewBox={`0 0 ${String(spreadWidth)} ${String(spreadHeight)}`}
          style={{ "aspect-ratio": spreadAspectRatio }}
        >
          <rect width={spreadWidth} height={spreadHeight} fill="white" />
          <For each={props.slots}>
            {(slot) => (
              <svg x={`${String(slot.x)}%`} y={`${String(slot.y)}%`} width={`${String(slot.width)}%`} height={`${String(slot.height)}%`}>
                <Show
                  when={slot.kind === "photo"}
                  fallback={
                    <foreignObject width="100%" height="100%">
                      <div class="spread-owner-text">
                        <div class="spread-owner-title">{props.title}</div>
                        <p>{props.text}</p>
                      </div>
                    </foreignObject>
                  }
                >
                  <rect width="100%" height="100%" fill="#dfebec" stroke="white" stroke-width="3" />
                </Show>
              </svg>
            )}
          </For>
        </svg>
        <Show when={props.editing}>
          {(editing) => (
            <For each={props.slots.filter((slot) => slot.kind === "photo").map((slot) => slot.id)}>
              {(slotId, index) => (
                <Show when={props.slots.find((slot) => slot.id === slotId)}>
                  {(slot) => (
                    <div
                      class="spread-photo-frame"
                      style={{
                        left: `${String(slot().x)}%`,
                        top: `${String(slot().y)}%`,
                        width: `${String(slot().width)}%`,
                        height: `${String(slot().height)}%`,
                      }}
                    >
                      <SlotPhoto
                        slotId={slotId}
                        label={`Photo slot ${String(index() + 1)}`}
                        width={(spreadWidth * slot().width) / 100}
                        height={(spreadHeight * slot().height) / 100}
                        editing={editing()}
                      />
                    </div>
                  )}
                </Show>
              )}
            </For>
          )}
        </Show>
      </div>
    </div>
  );
}

export function CoverCanvas(props: { title: string; year: number; authors: readonly Author[]; editing?: CanvasPhotos }) {
  const composition = createMemo(() => {
    const snapshot = props.editing?.session.snapshot();
    return snapshot?.kind === "cover" ? snapshot : undefined;
  });
  return (
    <div
      class="cover-canvas"
      role={props.editing !== undefined && !props.editing.reader ? "group" : "img"}
      aria-label={`Cover: back on the left; front on the right. ${props.title}. ${getCoverCredits(props.authors)}`}
      style={{ "aspect-ratio": coverAspectRatio }}
    >
      <div class="cover-back">
        <Show when={composition()}>
          {(cover) => (
            <div class="cover-photo">
              <Show when={props.editing}>
                {(editing) => <SlotPhoto slotId={cover().backSlotId} label="Back cover" width={1148} height={765.354} editing={editing()} />}
              </Show>
            </div>
          )}
        </Show>
        <span>Back cover</span>
      </div>
      <div class="cover-front">
        <Show when={composition()}>
          {(cover) => (
            <div class="cover-photo">
              <Show when={props.editing}>
                {(editing) => <SlotPhoto slotId={cover().frontSlotId} label="Front cover" width={1148} height={765.354} editing={editing()} />}
              </Show>
            </div>
          )}
        </Show>
        <span>Front cover</span>
        <div class="cover-title">
          <h3>{props.title}</h3>
          <p>
            {getCoverCredits(props.authors)} · {props.year}
          </p>
        </div>
      </div>
    </div>
  );
}

export function getCoverCredits(authors: readonly Author[]): string {
  const ordered = albumAuthors.filter((author) => authors.includes(author));
  const last = ordered.at(-1);
  if (last === undefined) throw new Error("Cover attribution requires an author");
  if (ordered.length === 1) return `by ${last}`;
  return `by ${ordered.slice(0, -1).join(", ")} & ${last}`;
}

function SlotPhoto(props: { slotId: string; label: string; width: number; height: number; editing: CanvasPhotos }) {
  const placement = () => props.editing.session.placements().find((row) => row.slotId === props.slotId);
  return (
    <PhotoSlot
      slotId={props.slotId}
      label={props.label}
      width={props.width}
      height={props.height}
      placement={placement()}
      photo={props.editing.photos.find((photo) => photo.id === placement()?.photoId)}
      session={props.editing.session}
      choice={props.editing.choice}
      onChoose={props.editing.onChoose}
      unavailable={props.editing.unavailable}
      reader={props.editing.reader}
      captions={props.editing.captions}
    />
  );
}
