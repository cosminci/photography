import { For, Show, createSignal, createUniqueId, onCleanup } from "solid-js";
import { BookExportBlocked, type BookExportClient, type ExportIssue } from "../model/BookExport";
import { describeFailure } from "../model/Presentation";

export function BookExportControl(props: { album: string; year: number; client: BookExportClient; unavailable: string | undefined }) {
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
  const reason = () => (pending() ? "Validating the saved book. Please wait." : props.unavailable);
  async function exportBook() {
    const album = props.album;
    const year = props.year;
    const focused = document.activeElement === button;
    setPending(true);
    setMessage("Validating the saved book...");
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
  return (
    <div class="book-export">
      <button
        ref={(element) => {
          button = element;
        }}
        type="button"
        disabled={reason() !== undefined}
        aria-describedby={feedback}
        onClick={() => void exportBook()}
      >
        Export for InDesign
      </button>
      <div
        ref={(element) => {
          status = element;
        }}
        id={feedback}
        role="status"
        aria-live="polite"
        tabIndex={-1}
      >
        <Show when={reason() ?? message()}>{(text) => <p class="control-help">{text()}</p>}</Show>
        <p class="control-help">Exports saved content only; unsaved drafts stay in the editor.</p>
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
    </div>
  );
}
