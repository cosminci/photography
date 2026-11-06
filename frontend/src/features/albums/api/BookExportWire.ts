import { readArray, readRecord, readText } from "./Wire";
import type { BookDownload, ExportIssue } from "../model/BookExport";

export function readExportIssues(value: unknown): readonly ExportIssue[] {
  return readArray(value).map((entry) => {
    const fields = readRecord(entry);
    const severity = fields["severity"];
    if (severity !== "blocking" && severity !== "warning") throw new Error("Invalid export issue severity");
    const target = Object.fromEntries(Object.entries(readRecord(fields["target"])).map(([key, field]) => [key, readText(field)]));
    switch (readText(target["kind"])) {
      case "album":
        break;
      case "chapter":
        readText(target["chapter"]);
        break;
      case "slot":
        readText(target["spread"]);
        readText(target["slot"]);
        break;
      case "cover":
        readText(target["slot"]);
        break;
      case "photo":
        readText(target["photo"]);
        break;
      default:
        throw new Error("Invalid export issue target");
    }
    return { code: readText(fields["code"]), severity, target, message: readText(fields["message"]) };
  });
}

export function readBookDownload(value: unknown): BookDownload {
  const fields = readRecord(value);
  if (fields["formatVersion"] !== 1) throw new Error("Unsupported InDesign export format");
  readRecord(fields["album"]);
  readRecord(fields["interior"]);
  readRecord(fields["cover"]);
  readRecord(fields["gear"]);
  readArray(fields["sources"]);
  readArray(fields["chapters"]);
  return { content: JSON.stringify(value), warnings: readExportIssues(fields["warnings"]) };
}
