import { For, Index, Show, createEffect, createMemo, createSignal, on, onCleanup } from "solid-js";
import type { AlbumClient } from "../model/Album";
import type { SpreadContext, SpreadTemplate } from "../model/Workspace";
import { describeFailure } from "../model/Presentation";
import { SpreadThumbnail } from "./ChapterOutline";

export function TemplateSelector(props: {
  context: SpreadContext;
  appliedTemplateId: string;
  load: AlbumClient["templates"];
  unavailable: string | undefined;
  onChange: (templateId: string, opener: HTMLButtonElement) => void;
}) {
  const [templates, setTemplates] = createSignal<readonly SpreadTemplate[]>([]);
  const [error, setError] = createSignal<string>();
  const [loading, setLoading] = createSignal(false);
  const [retry, setRetry] = createSignal(0);
  const [category, setCategory] = createSignal<number>();
  const selectedContext = createMemo(() => props.context);
  const categories = createMemo(() => [...new Set(templates().map(templateCategory))].sort((a, b) => a - b));
  const unavailable = createMemo(() => props.unavailable ?? (loading() ? "Loading templates. Please wait." : undefined));
  createEffect(
    on([selectedContext, retry], ([context]) => {
      let active = true;
      setTemplates([]);
      setError(undefined);
      setLoading(true);
      void props
        .load(context)
        .then(
          (catalog) => {
            if (active) setTemplates(catalog);
          },
          (failure: unknown) => {
            if (active) setError(describeFailure(failure));
          },
        )
        .finally(() => {
          if (active) setLoading(false);
        });
      onCleanup(() => {
        active = false;
      });
    }),
  );
  createEffect(() => {
    const applied = templates().find((template) => template.id === props.appliedTemplateId);
    setCategory(applied === undefined ? categories()[0] : templateCategory(applied));
  });
  return (
    <section class="template-selector" aria-label="Spread template selector">
      <h2>Spread template selector</h2>
      <Show when={loading()}>
        <p role="status">Loading templates…</p>
      </Show>
      <Show when={error()}>
        {(message) => (
          <p role="alert">
            {message()}{" "}
            <button type="button" onClick={() => setRetry((value) => value + 1)}>
              Retry templates
            </button>
          </p>
        )}
      </Show>
      <div class="template-counts" role="group" aria-label="Photo slot count">
        <span>Photo slot count</span>
        <Index each={categories()}>
          {(count) => (
            <button type="button" aria-pressed={category() === count()} onClick={() => setCategory(count())}>
              {count() === 10 ? "10+" : count()}
            </button>
          )}
        </Index>
      </div>
      <div class="template-strip" role="group" aria-label="Templates">
        <For each={templates().filter((template) => templateCategory(template) === category())}>
          {(template) => (
            <button
              type="button"
              class="template-option"
              aria-label={template.name}
              title={unavailable() ?? template.name}
              aria-pressed={props.appliedTemplateId === template.id}
              disabled={unavailable() !== undefined}
              aria-describedby={unavailable() === undefined ? undefined : "template-unavailable"}
              onClick={(event) => {
                props.onChange(template.id, event.currentTarget);
              }}
            >
              <SpreadThumbnail slots={template.slots} />
            </button>
          )}
        </For>
      </div>
      <Show when={unavailable()}>
        {(reason) => (
          <p id="template-unavailable" class="visually-hidden">
            {reason()}
          </p>
        )}
      </Show>
    </section>
  );
}

export function templateCategory(template: Pick<SpreadTemplate, "slots">): number {
  return Math.min(10, template.slots.filter((slot) => slot.kind === "photo").length);
}
