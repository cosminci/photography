import { array, literal, object, objectWithRest, parse, picklist, string, unknown, variant } from "valibot";
import { jsonObject } from "../../../shared/Json";
import type { BookDownload, ExportIssue } from "../model/BookExport";

const target = variant("kind", [
  objectWithRest({ kind: literal("album") }, string()),
  objectWithRest({ kind: literal("chapter"), chapter: string() }, string()),
  objectWithRest({ kind: literal("slot"), spread: string(), slot: string() }, string()),
  objectWithRest({ kind: literal("cover"), slot: string() }, string()),
  objectWithRest({ kind: literal("photo"), photo: string() }, string()),
]);
const issues = array(object({ code: string(), severity: picklist(["blocking", "warning"]), target, message: string() }));
const download = object({
  formatVersion: literal(1),
  album: jsonObject,
  interior: jsonObject,
  cover: jsonObject,
  gear: jsonObject,
  sources: array(unknown()),
  chapters: array(unknown()),
  warnings: issues,
});

export const readExportIssues = (value: unknown): readonly ExportIssue[] => parse(issues, value);
export function readBookDownload(value: unknown): BookDownload {
  const { warnings } = parse(download, value);
  return { content: JSON.stringify(value), warnings };
}
