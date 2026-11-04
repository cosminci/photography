import { Show, createEffect, createMemo, createSignal } from "solid-js";
import type { PlacementSession } from "./PlacementSession";
import type { PlacementEdit } from "../model/Placement";
import "./preview.css";

export function CompositionControls(props: { session: PlacementSession; unavailable: string | undefined; reader: boolean; feedbackId?: string }) {
  const [restoreFocus, setRestoreFocus] = createSignal(false);
  let control: HTMLButtonElement | HTMLSelectElement | undefined;
  const spread = createMemo(() => {
    const saved = props.session.snapshot();
    return saved?.kind === "spread" ? saved : undefined;
  });
  const cover = createMemo(() => {
    const saved = props.session.snapshot();
    return saved?.kind === "cover" ? saved : undefined;
  });
  const reason = () => props.unavailable ?? props.session.unavailable() ?? props.session.navigationReason();
  const bleedReason = () =>
    reason() ??
    (spread()?.layout.bleed.state === "mixed"
      ? "This spread has nonstandard bleed. The bleed toggle is unavailable."
      : spread()?.layout.bleed.available === false
        ? "This spread has no eligible outer photo slots."
        : undefined);
  const sharedFeedback = () => (props.session.error() !== undefined && reason() === props.session.error() ? props.feedbackId : undefined);

  function edit(command: PlacementEdit, opener: HTMLButtonElement | HTMLSelectElement) {
    const focused = document.activeElement === opener;
    void props.session.edit(command);
    setRestoreFocus(focused);
  }
  createEffect(() => {
    if (!restoreFocus() || reason() !== undefined) return;
    queueMicrotask(() => {
      if (!restoreFocus() || reason() !== undefined || control === undefined || !control.isConnected || control.disabled) return;
      if (document.activeElement === document.body) control.focus();
      setRestoreFocus(false);
    });
  });

  return (
    <Show when={!props.reader}>
      <div class="composition-controls" role="group" aria-label="Saved composition settings">
        <Show when={spread()}>
          {(saved) => (
            <button
              ref={(element) => {
                control = element;
              }}
              type="button"
              class="icon-button bleed-toggle"
              role="checkbox"
              aria-label="Spread bleed"
              title={bleedReason() ?? `Spread bleed: ${saved().layout.bleed.state}`}
              aria-checked={saved().layout.bleed.state === "mixed" ? "mixed" : saved().layout.bleed.state === "enabled"}
              disabled={bleedReason() !== undefined}
              aria-describedby={bleedReason() === undefined ? undefined : (sharedFeedback() ?? "spread-bleed-reason")}
              onClick={(event) => {
                edit({ operation: "bleed", enabled: saved().layout.bleed.state !== "enabled" }, event.currentTarget);
              }}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M7 3C5.5 6 3 8.5 3 11a4 4 0 0 0 8 0c0-2.5-2.5-5-4-8ZM18 10c-1 2-3 4-3 6a3 3 0 0 0 6 0c0-2-2-4-3-6Z" />
              </svg>
            </button>
          )}
        </Show>
        <Show when={spread() !== undefined && sharedFeedback() === undefined && bleedReason()}>
          {(message) => (
            <p id="spread-bleed-reason" class="visually-hidden">
              {message()}
            </p>
          )}
        </Show>
        <Show when={cover()}>
          {(saved) => (
            <label>
              Spine/gutter owner
              <select
                ref={(element) => {
                  control = element;
                }}
                value={saved().spineOwner}
                disabled={reason() !== undefined}
                aria-describedby={reason() === undefined ? undefined : (sharedFeedback() ?? "spine-owner-reason")}
                onChange={(event) => {
                  const owner = event.currentTarget.value;
                  /* v8 ignore else */
                  if (owner === "front" || owner === "back") edit({ operation: "spineOwner", owner }, event.currentTarget);
                }}
              >
                <option value="front">Front cover</option>
                <option value="back">Back cover</option>
              </select>
            </label>
          )}
        </Show>
        <Show when={cover() !== undefined && sharedFeedback() === undefined && reason()}>
          {(message) => (
            <p id="spine-owner-reason" class="control-help">
              {message()}
            </p>
          )}
        </Show>
      </div>
    </Show>
  );
}
