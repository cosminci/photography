import { Show, createEffect, on, onCleanup, onMount } from "solid-js";

export function EditorialText(props: { title: string; text: string; gear: string | undefined; synopsis: boolean; textWidth: number | undefined }) {
  let box!: HTMLDivElement;
  let copy!: HTMLDivElement;
  let observer: ResizeObserver | undefined;
  function fit() {
    const style = getComputedStyle(box);
    const available = box.clientHeight - Number.parseFloat(style.paddingTop) - Number.parseFloat(style.paddingBottom);
    if (available <= 0) return;
    box.style.setProperty("--editorial-scale", "1");
    if (copy.scrollHeight <= available) return;
    let lower = 0;
    let upper = 1;
    for (let step = 0; step < 16; step++) {
      const scale = (lower + upper) / 2;
      box.style.setProperty("--editorial-scale", String(scale));
      if (copy.scrollHeight <= available) lower = scale;
      else upper = scale;
    }
    box.style.setProperty("--editorial-scale", String(lower));
  }
  createEffect(
    on([() => props.title, () => props.text, () => props.gear], () => {
      queueMicrotask(fit);
    }),
  );
  onMount(() => {
    if (typeof ResizeObserver === "function") {
      observer = new ResizeObserver(fit);
      observer.observe(box);
    }
  });
  onCleanup(() => observer?.disconnect());
  return (
    <div
      ref={(element) => {
        box = element;
      }}
      class="spread-owner-text"
      role="group"
      aria-label={props.synopsis ? "Synopsis text" : "Chapter text"}
      classList={{ "synopsis-owner-text": props.synopsis }}
      style={props.textWidth === undefined ? undefined : { width: `${String(props.textWidth)}%` }}
    >
      <div
        ref={(element) => {
          copy = element;
        }}
        class="spread-owner-copy"
      >
        <div class="spread-owner-title">{props.title}</div>
        <p>{props.text}</p>
        <Show when={props.gear}>
          <div class="spread-owner-gear" role="group" aria-label="Chapter gear">
            {props.gear}
          </div>
        </Show>
      </div>
    </div>
  );
}
