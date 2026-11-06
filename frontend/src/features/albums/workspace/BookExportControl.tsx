import { For, Show, createSignal, createUniqueId, onCleanup } from "solid-js";
import { Portal } from "solid-js/web";
import { BookExportBlocked, type BookExportClient, type ExportIssue } from "../model/BookExport";
import { describeFailure } from "../model/Presentation";

export function BookExportControl(props: {
  album: string;
  year: number;
  client: BookExportClient;
  unavailable: string | undefined;
  feedbackMount?: HTMLDivElement | undefined;
}) {
  const feedback = createUniqueId();
  const [pending, setPending] = createSignal(false);
  const [message, setMessage] = createSignal<string>();
  const [issues, setIssues] = createSignal<readonly ExportIssue[]>([]);
  let active = true;
  let button!: HTMLButtonElement;
  let status!: HTMLDivElement;
  onCleanup(() => {
    active = false;
  });
  const reason = () => (pending() ? "Checking original photographs and preparing export." : props.unavailable);
  async function exportBook() {
    const album = props.album;
    const year = props.year;
    const focused = document.activeElement === button;
    setPending(true);
    setMessage("Checking original photographs and preparing export.");
    setIssues([]);
    if (focused) status.focus({ preventScroll: true });
    try {
      const saved = await props.client.exportBook(album);
      if (!active || props.album !== album) return;
      const url = URL.createObjectURL(new Blob([saved.content], { type: "application/json" }));
      try {
        const link = document.createElement("a");
        link.href = url;
        link.download = `album-${String(year)}-indesign.json`;
        link.click();
      } finally {
        URL.revokeObjectURL(url);
      }
      setIssues(saved.warnings);
      setMessage("Saved book exported. Use the same JSON for the interior and cover.");
    } catch (failure) {
      if (!active || props.album !== album) return;
      setIssues(failure instanceof BookExportBlocked ? failure.issues : []);
      setMessage(failure instanceof BookExportBlocked ? failure.message : describeFailure(failure));
    } finally {
      if (active) {
        setPending(false);
        if (document.activeElement === status && props.unavailable === undefined) button.focus({ preventScroll: true });
      }
    }
  }
  const output = (
    <div
      ref={(element) => {
        status = element;
      }}
      id={feedback}
      role="status"
      aria-live="polite"
      tabIndex={-1}
      classList={{ "visually-hidden": pending() || message() === undefined }}
    >
      <Show when={reason() ?? message()}>{(text) => <p>{text()}</p>}</Show>
      <Show when={issues().length > 0}>
        <ul>
          <For each={issues()}>
            {(issue) => (
              <li>
                {issue.severity}: {issue.code.replaceAll("_", " ")} - {Object.values(issue.target).join(" / ")}
              </li>
            )}
          </For>
        </ul>
      </Show>
    </div>
  );
  return (
    <div class="reader-entry-control">
      <button
        ref={(element) => {
          button = element;
        }}
        type="button"
        aria-label="Export for InDesign"
        title={reason() ?? "Export for InDesign"}
        aria-busy={pending()}
        disabled={reason() !== undefined}
        aria-describedby={feedback}
        onClick={() => void exportBook()}
      >
        <Show
          when={pending()}
          fallback={
            <svg class="reader-entry-icon" viewBox="0 0 24 24" aria-hidden="true">
              <rect x="2" y="2" width="20" height="20" rx="2" />
              <text x="5" y="17" stroke="none" fill="currentColor" font-family="Arial, sans-serif" font-size="14" font-weight="bold">
                Id
              </text>
            </svg>
          }
        >
          <svg class="reader-entry-icon export-processing" viewBox="0 0 24 24" role="img" aria-label="Export in progress">
            <circle cx="12" cy="12" r="8" stroke-dasharray="36 15" />
          </svg>
        </Show>
      </button>
      <Show when={props.feedbackMount} fallback={output}>
        {(mount) => <Portal mount={mount()}>{output}</Portal>}
      </Show>
    </div>
  );
}
