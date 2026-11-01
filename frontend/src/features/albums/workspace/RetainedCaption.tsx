import { Show, createEffect, onCleanup, untrack } from "solid-js";
import type { CaptionEditing, CaptionState } from "./PhotoCaption";

export function RetainedCaption(props: { caption: CaptionState; focus: CaptionEditing["focus"]; returnFocus: () => void }) {
  const caption = untrack(() => props.caption);
  const focus = untrack(() => props.focus);
  const returnFocus = untrack(() => props.returnFocus);
  const feedback = `retained-caption-${caption.photoId}`;
  let status!: HTMLDivElement;
  createEffect(() => {
    if (focus.requested() === caption.photoId) {
      status.focus();
      focus.clear();
    }
  });
  onCleanup(() => {
    if (status.contains(document.activeElement)) {
      if (caption.save.draft() !== undefined) focus.request(caption.photoId);
      else returnFocus();
    }
  });
  return (
    <div
      ref={(element) => {
        status = element;
      }}
      role="alert"
      tabindex="-1"
      id={feedback}
    >
      The caption's photo is no longer placed here. Its draft is retained.
      <pre>{caption.save.draft()?.value}</pre>
      <Show when={caption.save.error()}>{(message) => <p>{message()}</p>}</Show>
      <Show when={caption.save.unavailable()}>{(reason) => <p role="status">{reason()}</p>}</Show>
      <button
        type="button"
        disabled={caption.save.pending()}
        aria-describedby={caption.save.pending() ? feedback : undefined}
        onClick={() => {
          void caption.save.commit();
        }}
      >
        {caption.save.recovery() === undefined ? "Save retained caption" : "Read retained caption"}
      </button>
      <button
        type="button"
        disabled={caption.save.unavailable() !== undefined}
        aria-describedby={caption.save.unavailable() === undefined ? undefined : feedback}
        onClick={() => {
          caption.save.cancel();
        }}
      >
        Cancel retained caption
      </button>
    </div>
  );
}
