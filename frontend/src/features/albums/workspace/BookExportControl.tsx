import { For, Show, createMemo, createSignal, createUniqueId, onCleanup } from "solid-js";
import { Portal } from "solid-js/web";
import { BookExportBlocked, type BookExportClient, type ExportIssue } from "../model/BookExport";
import { describeFailure } from "../model/Presentation";

export function BookExportControl(props: {
  album: string;
  year: number;
  client: BookExportClient;
  unavailable: string | undefined;
  feedbackMount?: HTMLDivElement | undefined;
  issueLink?: (issue: ExportIssue) => { readonly label: string; readonly href: string; readonly navigate: () => void } | undefined;
  onDismiss?: () => void;
}) {
  const feedback = createUniqueId();
  const [pending, setPending] = createSignal(false);
  const [confirmation, setConfirmation] = createSignal(false);
  const [message, setMessage] = createSignal<string>();
  const [issues, setIssues] = createSignal<readonly ExportIssue[]>([]);
  const resolutionIssues = createMemo(() => issues().filter((issue) => issue.code === "low_resolution"));
  const otherIssues = createMemo(() => issues().filter((issue) => issue.code !== "low_resolution"));
  let active = true;
  let button!: HTMLButtonElement;
  let status!: HTMLDivElement;
  let confirm!: HTMLButtonElement;
  onCleanup(() => {
    active = false;
  });
  const reason = () => (pending() ? "Validating saved book and preparing export." : props.unavailable);
  function clearFeedback() {
    setIssues([]);
    setMessage(undefined);
    setConfirmation(false);
    props.onDismiss?.();
  }
  async function exportBook(confirmed: boolean) {
    const album = props.album;
    const year = props.year;
    const focused = document.activeElement === button || document.activeElement === confirm;
    setPending(true);
    setConfirmation(false);
    setMessage("Validating saved book and preparing export.");
    setIssues([]);
    if (focused) status.focus({ preventScroll: true });
    try {
      const saved = await props.client.exportBook(album);
      if (!active || props.album !== album) return;
      setIssues(saved.warnings);
      if (!confirmed && saved.warnings.some((issue) => issue.code === "low_resolution")) {
        setMessage("Some photographs are below 300 DPI. Confirm export to continue.");
        setConfirmation(true);
        return;
      }
      const url = URL.createObjectURL(new Blob([saved.content], { type: "application/json" }));
      try {
        const link = document.createElement("a");
        link.href = url;
        link.download = `album-${String(year)}-indesign.json`;
        link.click();
      } finally {
        URL.revokeObjectURL(url);
      }
      if (confirmed) clearFeedback();
      else setMessage("Saved book exported. Use the same JSON for the interior and cover.");
    } catch (failure) {
      if (!active || props.album !== album) return;
      setIssues(failure instanceof BookExportBlocked ? failure.issues : []);
      setMessage(failure instanceof BookExportBlocked ? failure.message : describeFailure(failure));
    } finally {
      if (active) {
        setPending(false);
        if (document.activeElement === status && props.unavailable === undefined) (confirmation() ? confirm : button).focus({ preventScroll: true });
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
      class="export-feedback"
      classList={{ "visually-hidden": message() === undefined && issues().length === 0 }}
    >
      <Show when={reason() ?? message()}>{(text) => <p classList={{ "visually-hidden": pending() || issues().length > 0 }}>{text()}</p>}</Show>
      <Show when={issues().length > 0}>
        <div class="export-issues">
          <div>
            <Show when={resolutionIssues().length > 0}>
              <p>
                <svg class="export-warning-icon" viewBox="0 0 24 24" aria-hidden="true">
                  <circle cx="12" cy="12" r="9" />
                  <path d="M12 7v6m0 4h.01" />
                </svg>{" "}
                Low-resolution photos:{" "}
                <For each={resolutionIssues()}>
                  {(issue, index) => (
                    <>
                      {index() > 0 ? ", " : ""}
                      {issueLocation(issue)}
                    </>
                  )}
                </For>
                <Show when={confirmation()}>
                  {" "}
                  <span aria-hidden="true">|</span>{" "}
                  <button
                    ref={(element) => {
                      confirm = element;
                    }}
                    type="button"
                    class="export-confirm"
                    disabled={reason() !== undefined}
                    title={reason() ?? "Export despite low-resolution photographs"}
                    aria-describedby={feedback}
                    onClick={() => void exportBook(true)}
                  >
                    Confirm export
                  </button>
                </Show>
              </p>
            </Show>
            <For each={otherIssues()}>
              {(issue) => (
                <p>
                  {issue.severity === "warning" ? "Warning" : "Cannot export"}: {issueLocation(issue, issue.message)}
                </p>
              )}
            </For>
          </div>
          <button
            type="button"
            class="icon-button export-dismiss"
            aria-label="Dismiss export warnings"
            title="Dismiss export warnings"
            onClick={() => {
              clearFeedback();
              button.focus({ preventScroll: true });
            }}
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="m6 6 12 12M18 6 6 18" />
            </svg>
          </button>
        </div>
      </Show>
    </div>
  );
  function issueLocation(issue: ExportIssue, label?: string) {
    return (
      <Show when={props.issueLink?.(issue)} fallback={issue.message}>
        {(link) => (
          <a
            href={link().href}
            title={issue.message}
            onClick={(event) => {
              if (event.button !== 0 || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
              event.preventDefault();
              link().navigate();
            }}
          >
            {label ?? link().label}
          </a>
        )}
      </Show>
    );
  }
  return (
    <div class="reader-entry-control">
      <button
        ref={(element) => {
          button = element;
        }}
        type="button"
        class="indesign-export"
        aria-label="Export for InDesign"
        title={reason() ?? "Export for InDesign"}
        aria-busy={pending()}
        disabled={reason() !== undefined}
        aria-describedby={feedback}
        onClick={() => void exportBook(false)}
      >
        <svg class="reader-entry-icon" viewBox="0 0 24 24" aria-hidden="true">
          <rect x="2" y="2" width="20" height="20" rx="2" />
          <text x="5" y="17" stroke="none" fill="currentColor" font-family="Arial, sans-serif" font-size="14" font-weight="bold">
            Id
          </text>
        </svg>
      </button>
      <Show when={props.feedbackMount} fallback={output}>
        {(mount) => <Portal mount={mount()}>{output}</Portal>}
      </Show>
    </div>
  );
}
