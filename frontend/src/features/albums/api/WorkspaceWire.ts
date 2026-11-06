import { array, check, object, parse, picklist, pipe, string } from "valibot";
import type { Chapter, Country, SpreadTemplate } from "../model/Workspace";
import type { ChapterSave } from "../model/Photo";
import { finiteNumber, nonemptyText } from "./Wire";
import { scheduling } from "./PhotoWire";

const geometry = object({
  kind: picklist(["photo", "text"]),
  x: finiteNumber,
  y: finiteNumber,
  width: finiteNumber,
  height: finiteNumber,
});
export const slot = object({ id: string(), ...geometry.entries });
const chapter = object({
  id: string(),
  kind: picklist(["vacation", "home"]),
  name: string(),
  countries: array(string()),
  start: string(),
  end: string(),
  sourceFolder: string(),
  description: string(),
});
const template = object({
  id: string(),
  name: string(),
  slots: pipe(
    array(geometry),
    check((slots) => slots.some((entry) => entry.kind === "photo")),
  ),
});

export const readChapterIdentity = (value: unknown): ChapterSave => parse(object({ id: nonemptyText, scheduling }), value);
export const readChapter = (value: unknown): Chapter => parse(chapter, value);
export const readTemplates = (value: unknown): readonly SpreadTemplate[] => parse(object({ templates: array(template) }), value).templates;
export const readCountry = (value: unknown): Country => parse(object({ id: string(), name: string(), code: string() }), value);
