import {
  array,
  check,
  finite,
  integer,
  maxValue,
  minLength,
  minValue,
  number,
  object,
  parse,
  picklist,
  pipe,
  safeParse,
  string,
  unknown,
} from "valibot";
import { jsonObject } from "../../../shared/Json";
import { albumAuthors, maximumAlbumYear, minimumAlbumYear, type Album, type AlbumPage, type AlbumSummary } from "../model/Album";

export const finiteNumber = pipe(number(), finite());
export const nonemptyText = pipe(string(), minLength(1));
const summary = object({
  id: string(),
  year: pipe(finiteNumber, integer(), minValue(minimumAlbumYear), maxValue(maximumAlbumYear)),
  title: string(),
  createdAt: string(),
});
const album = object({
  ...summary.entries,
  synopsis: string(),
  authors: pipe(
    array(picklist(albumAuthors)),
    minLength(1),
    check((authors) => new Set(authors).size === authors.length),
  ),
});
const page = object({
  albums: array(summary),
  page: pipe(finiteNumber, integer(), minValue(1)),
  pageSize: pipe(finiteNumber, integer(), minValue(1)),
  totalAlbums: pipe(finiteNumber, integer(), minValue(0)),
  totalPages: pipe(finiteNumber, integer(), minValue(0)),
});

export const readSummary = (value: unknown): AlbumSummary => parse(summary, value);
export const readAlbum = (value: unknown): Album => parse(album, value);
export const readPage = (value: unknown): AlbumPage => parse(page, value);

export function readFailureMessage(value: unknown): string {
  const result = safeParse(object({ message: string() }), value);
  return result.success ? result.output.message : "The request could not be completed. Please try again.";
}

export const readRecord = (value: unknown): Record<string, unknown> => parse(jsonObject, value);
export const readText = (value: unknown): string => parse(string(), value);
export const readNumber = (value: unknown): number => parse(finiteNumber, value);
export const readArray = (value: unknown): readonly unknown[] => parse(array(unknown()), value);
