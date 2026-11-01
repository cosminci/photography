import { Show, createEffect, createMemo, createSignal, createUniqueId, onCleanup, type JSX } from "solid-js";
import { Portal } from "solid-js/web";
import "./reader.css";

export function FullscreenReader(props: {
  aspectRatio: number;
  canPrevious: boolean;
  canNext: boolean;
  entryMount?: HTMLDivElement | undefined;
  unavailable: string | undefined;
  onNavigate: (direction: -1 | 1) => void;
  label: string;
  onActive?: (active: boolean) => void;
  children: JSX.Element;
}) {
  const [active, setActive] = createSignal(false);
  const [pending, setPending] = createSignal(false);
  const [error, setError] = createSignal<string>();
  const [returnPending, setReturnPending] = createSignal(false);
  const entryReason = createMemo(() => props.unavailable ?? (pending() ? "Entering fullscreen. Please wait." : undefined));
  const reasonId = createUniqueId();
  let stage!: HTMLDivElement;
  let opener!: HTMLButtonElement;
  let status!: HTMLSpanElement;
  let exitButton: HTMLButtonElement | undefined;

  function syncFullscreen() {
    const entered = document.fullscreenElement === stage;
    const exited = active() && !entered;
    setActive(entered);
    props.onActive?.(entered);
    if (entered) exitButton?.focus();
    if (exited) {
      setError(undefined);
      if (opener.disabled) {
        status.focus();
        setReturnPending(true);
      } else opener.focus();
    }
  }
  createEffect(() => {
    if (returnPending() && entryReason() === undefined) {
      setReturnPending(false);
      if (document.activeElement === status) opener.focus();
    }
  });
  document.addEventListener("fullscreenchange", syncFullscreen);
  document.addEventListener("keydown", handleKeyDown);
  onCleanup(() => {
    document.removeEventListener("fullscreenchange", syncFullscreen);
    document.removeEventListener("keydown", handleKeyDown);
  });

  async function enter() {
    setError(undefined);
    setPending(true);
    try {
      if (typeof stage.requestFullscreen !== "function") throw new Error("Fullscreen unavailable");
      await stage.requestFullscreen();
    } catch {
      setError("Browser fullscreen could not be started. You can still use the workspace.");
    } finally {
      setPending(false);
      if (error() !== undefined) opener.focus();
    }
  }

  async function exit() {
    try {
      await document.exitFullscreen();
    } catch {
      setError("Browser fullscreen could not be closed. Press Escape to leave fullscreen.");
    }
  }

  function handleKeyDown(event: KeyboardEvent) {
    if (!active()) return;
    if (event.key === "Escape") {
      event.preventDefault();
      void exit();
    } else if (event.key === "Tab") {
      const controls = Array.from(stage.querySelectorAll<HTMLButtonElement>("button:not(:disabled)"));
      const index = controls.findIndex((control) => control === document.activeElement);
      const next = index < 0 ? (event.shiftKey ? controls.length - 1 : 0) : (index + (event.shiftKey ? -1 : 1) + controls.length) % controls.length;
      event.preventDefault();
      controls[next]?.focus();
    } else if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      event.preventDefault();
      const direction = event.key === "ArrowLeft" ? -1 : 1;
      if (direction === -1 ? props.canPrevious : props.canNext) props.onNavigate(direction);
    }
  }

  const entry = (
    <div class="reader-entry-control">
      <button
        ref={(element) => {
          opener = element;
        }}
        type="button"
        aria-label="Reader mode"
        title="Reader mode"
        disabled={entryReason() !== undefined}
        aria-describedby={entryReason() !== undefined ? reasonId : undefined}
        onClick={() => void enter()}
      >
        <svg class="reader-entry-icon" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 5.5C9 3.5 5.5 3 2 4v15c3.5-1 7-.5 10 1.5C15 18.5 18.5 18 22 19V4c-3.5-1-7-.5-10 1.5Zm0 0v15M5 8c1.5-.2 3 .1 4 .7M5 12c1.5-.2 3 .1 4 .7M15 8.7c1-.6 2.5-.9 4-.7M15 12.7c1-.6 2.5-.9 4-.7" />
        </svg>
      </button>
      <span
        ref={(element) => {
          status = element;
        }}
        id={reasonId}
        class="control-help"
        tabindex="-1"
      >
        {entryReason()}
      </span>
    </div>
  );

  return (
    <>
      <div class="reader-entry" classList={{ "reader-entry-mounted": props.entryMount !== undefined }}>
        <Show when={props.label !== ""}>
          <h2 classList={{ "control-help": props.entryMount !== undefined }}>{props.label}</h2>
        </Show>
        <Show when={props.entryMount} fallback={entry}>
          {(mount) => <Portal mount={mount()}>{entry}</Portal>}
        </Show>
      </div>
      <div
        ref={(element) => {
          stage = element;
        }}
        class="reader-stage"
        classList={{ "reader-active": active() }}
        role={active() ? "dialog" : undefined}
        aria-modal={active() ? true : undefined}
        aria-label={active() ? "Fullscreen reader" : undefined}
      >
        <div class="reader-canvas" style={{ "--canvas-aspect-ratio": props.aspectRatio }}>
          {props.children}
        </div>
        <Show when={active()}>
          <div class="reader-controls" role="group" aria-label="Reader controls">
            <div>
              <button
                type="button"
                disabled={!props.canPrevious}
                onClick={() => {
                  props.onNavigate(-1);
                }}
              >
                Previous spread
              </button>
            </div>
            <p role="status" aria-live="polite">
              {props.label}
            </p>
            <div>
              <button
                type="button"
                disabled={!props.canNext}
                onClick={() => {
                  props.onNavigate(1);
                }}
              >
                Next spread
              </button>
            </div>
            <button
              ref={(element) => {
                exitButton = element;
              }}
              type="button"
              onClick={() => void exit()}
            >
              Exit reader
            </button>
          </div>
        </Show>
        <Show when={error()}>
          {(message) => (
            <p class="reader-error" role="alert">
              {message()}
            </p>
          )}
        </Show>
      </div>
    </>
  );
}
