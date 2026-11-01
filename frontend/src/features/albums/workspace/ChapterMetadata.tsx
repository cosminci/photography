import { Show, createMemo } from "solid-js";
import type { Chapter, Country } from "../model/Workspace";
import { describeCountryLabel, describePeriod } from "./ChapterDetails";

export function ChapterMetadata(props: {
  chapter: Pick<Chapter, "name" | "countries" | "kind" | "start" | "end">;
  position: {
    chapter: { position: number; total: number };
    album: { position: number; total: number };
  };
  countries: readonly Pick<Country, "id" | "name">[];
  unavailable: string | undefined;
  onEdit: (opener: HTMLButtonElement) => void;
}) {
  const countryNames = createMemo(() =>
    props.countries
      .filter((country) => props.chapter.countries.includes(country.id))
      .map((country) => country.name)
      .join(", "),
  );
  return (
    <section class="spread-details" aria-label="Spread details">
      <div class="chapter-metadata-row">
        <dl class="chapter-metadata">
          <div>
            <dt>Chapter spread</dt>
            <dd aria-label="Chapter spread">
              {props.position.chapter.position} / {props.position.chapter.total}
            </dd>
          </div>
          <div>
            <dt>Album spread</dt>
            <dd aria-label="Album spread">
              {props.position.album.position} / {props.position.album.total}
            </dd>
          </div>
          <div>
            <dt>Location</dt>
            <dd>{props.chapter.name}</dd>
          </div>
          <div>
            <dt>{describeCountryLabel(props.chapter.countries.length)}</dt>
            <dd>{countryNames()}</dd>
          </div>
          <div>
            <dt>Period</dt>
            <dd>{describePeriod(props.chapter)}</dd>
          </div>
        </dl>
        <button
          type="button"
          class="icon-button metadata-edit"
          aria-label="Edit chapter details"
          title="Edit chapter details"
          disabled={props.unavailable !== undefined}
          aria-describedby={props.unavailable !== undefined ? "metadata-feedback" : undefined}
          onClick={(event) => {
            props.onEdit(event.currentTarget);
          }}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="m15 4 5 5M4 20l5-1L20 8a2 2 0 0 0-5-5L4 14z" />
          </svg>
        </button>
      </div>
      <Show when={props.unavailable}>
        {(reason) => (
          <p id="metadata-feedback" class="control-help">
            {reason()}
          </p>
        )}
      </Show>
    </section>
  );
}
