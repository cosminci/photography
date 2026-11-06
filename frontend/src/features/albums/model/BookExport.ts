export interface ExportIssue {
  readonly message: string;
  readonly code: string;
  readonly severity: "blocking" | "warning";
  readonly target: Readonly<Record<string, string>>;
}

export interface BookDownload {
  readonly content: string;
  readonly warnings: readonly ExportIssue[];
}

export interface BookExportClient {
  readonly exportBook: (album: string) => Promise<BookDownload>;
}

export class BookExportBlocked extends Error {
  constructor(readonly issues: readonly ExportIssue[]) {
    super("Resolve the blocking print issues before exporting again.");
  }
}
