import { For, Show, createEffect, createMemo, createSignal, on } from "solid-js";
import type { Country, Slot } from "../model/Workspace";
import type { Photo } from "../model/Photo";
import type { PlacementSession } from "./PlacementSession";
import { PhotoSlot } from "./PhotoSlot";
import type { PhotoChoice } from "./PhotoDrag";
import { albumAuthors, type Author } from "../model/Album";
import type { CaptionEditing } from "./PhotoCaption";
import { EditorialText } from "./EditorialText";

export interface CanvasPhotos {
  readonly session: PlacementSession;
  readonly photos: readonly Photo[];
  readonly choice: PhotoChoice | undefined;
  readonly onChoose: (choice: PhotoChoice | undefined) => void;
  readonly unavailable: string | undefined;
  readonly reader: boolean;
  readonly captions?: CaptionEditing;
  readonly onEditText?: (opener: HTMLButtonElement) => void;
  readonly textUnavailable?: string | undefined;
}

const spreadWidth = 2381.102;
const spreadHeight = 765.354;
export const spreadAspectRatio = spreadWidth / spreadHeight;
export const coverAspectRatio = 3;

export function SpreadCanvas(props: {
  slots: readonly Slot[];
  title: string;
  text: string;
  label: string;
  gear?: string | undefined;
  synopsisCountries?: readonly Country[] | undefined;
  editing?: CanvasPhotos;
}) {
  const [failedFlags, setFailedFlags] = createSignal<readonly string[]>([]);
  createEffect(
    on(
      () => props.synopsisCountries?.map((country) => country.id).join(","),
      () => setFailedFlags([]),
    ),
  );
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
          <Show when={props.synopsisCountries !== undefined}>
            <For each={props.slots.filter((slot) => slot.kind === "text")}>
              {(slot) => (
                <rect
                  x={`${String(slot.x + slot.width / 2 - 20)}%`}
                  y="0"
                  width="40%"
                  height="100%"
                  fill="#282828"
                  role="img"
                  aria-label="Synopsis background"
                />
              )}
            </For>
          </Show>
          <For each={props.slots.map((slot) => slot.id)}>
            {(slotId) => (
              <Show when={props.slots.find((slot) => slot.id === slotId)}>
                {(slot) => (
                  <svg x={`${String(slot().x)}%`} y={`${String(slot().y)}%`} width={`${String(slot().width)}%`} height={`${String(slot().height)}%`}>
                    <Show
                      when={slot().kind === "photo"}
                      fallback={
                        <foreignObject width="100%" height="100%">
                          <EditorialText
                            title={props.title}
                            text={props.text}
                            gear={props.gear}
                            synopsis={props.synopsisCountries !== undefined}
                            textWidth={props.synopsisCountries === undefined ? undefined : (20 / slot().width) * 100}
                          />
                        </foreignObject>
                      }
                    >
                      <rect width="100%" height="100%" fill="#dfebec" stroke="white" stroke-width="3" />
                    </Show>
                  </svg>
                )}
              </Show>
            )}
          </For>
          <Show when={props.synopsisCountries}>
            {(flags) => (
              <For each={props.slots.filter((slot) => slot.kind === "text")}>
                {(slot) => {
                  const stripY = () => ((slot.y + slot.height) / 100) * spreadHeight - 116;
                  const startX = () => ((slot.x + slot.width / 2) / 100) * spreadWidth - (flags().length * 46 - 4) / 2;
                  return (
                    <Show when={flags().length > 0}>
                      <g role="group" aria-label="Synopsis country flags">
                        <rect x={`${String(slot.x + slot.width / 2 - 20)}%`} y={stripY()} width="40%" height="44" fill="white" />
                        <For each={flags()}>
                          {(country, index) => (
                            <Show when={!failedFlags().includes(country.name)}>
                              <image
                                href={`/flags/${encodeURIComponent(country.code.toLowerCase())}.png`}
                                x={startX() + index() * 46}
                                y={stripY() + 8}
                                width="42"
                                height="28"
                                preserveAspectRatio="xMidYMid meet"
                                role="img"
                                aria-label={`${country.name} flag`}
                                onError={() => setFailedFlags((current) => [...current, country.name])}
                              />
                            </Show>
                          )}
                        </For>
                      </g>
                    </Show>
                  );
                }}
              </For>
            )}
          </Show>
        </svg>
        <Show when={props.editing !== undefined && !props.editing.reader && props.editing.onEditText !== undefined}>
          <For each={props.slots.filter((slot) => slot.kind === "text").map((slot) => slot.id)}>
            {(slotId) => (
              <Show when={props.slots.find((slot) => slot.id === slotId)}>
                {(slot) => (
                  <button
                    type="button"
                    class="icon-button content-text-edit"
                    aria-label={props.label === "Synopsis" ? "Edit synopsis" : "Edit chapter description"}
                    title={props.label === "Synopsis" ? "Edit synopsis" : "Edit chapter description"}
                    style={{ left: `${String(slot().x + slot().width)}%`, top: `${String(slot().y)}%` }}
                    disabled={props.editing?.textUnavailable !== undefined}
                    aria-describedby={props.editing?.textUnavailable === undefined ? undefined : "content-edit-reason"}
                    onClick={(event) => props.editing?.onEditText?.(event.currentTarget)}
                  >
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="m15 4 5 5M4 20l5-1L20 8a2 2 0 0 0-5-5L4 14z" />
                    </svg>
                  </button>
                )}
              </Show>
            )}
          </For>
        </Show>
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
      <Show when={failedFlags().length > 0}>
        <p role="alert">Flag artwork is unavailable for {failedFlags().join(", ")}.</p>
      </Show>
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
