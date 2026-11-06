import { array, check, minValue, nullable, object, parse, picklist, pipe, regex, safeInteger, string } from "valibot";
import { issueCodes, issueStages, processingStates, schedulingStates, type Photo, type PhotoInventory, type Scheduling } from "../model/Photo";
import { isCalendarDay } from "../workspace/ChapterDetails";
import { finiteNumber, nonemptyText } from "./Wire";

const count = pipe(finiteNumber, safeInteger(), minValue(0));
const dimension = pipe(count, minValue(1));
export const scheduling = picklist(schedulingStates);
const capturedLocal = nullable(
  pipe(
    string(),
    regex(/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?$/),
    check((value) => isCalendarDay(value.slice(0, 10))),
  ),
);
const photo = object({
  id: nonemptyText,
  filename: nonemptyText,
  capturedLocal,
  width: dimension,
  height: dimension,
  caption: nullable(string()),
  thumbnail: object({ url: nonemptyText, width: dimension, height: dimension }),
});
const inventory = pipe(
  object({
    photos: array(photo),
    placedPhotoIds: array(nonemptyText),
    counts: object({ unplaced: count, total: count }),
    processing: object({
      state: picklist(processingStates),
      unsupportedCount: count,
      skippedCount: count,
      issues: array(object({ stage: picklist(issueStages), code: picklist(issueCodes), filename: nullable(string()) })),
    }),
  }),
  check(({ photos, placedPhotoIds, counts }) => {
    const identities = new Set(photos.map((entry) => entry.id));
    return (
      counts.total === photos.length &&
      counts.unplaced <= counts.total &&
      identities.size === counts.total &&
      new Set(placedPhotoIds).size === placedPhotoIds.length &&
      placedPhotoIds.every((id) => identities.has(id)) &&
      counts.total - placedPhotoIds.length === counts.unplaced
    );
  }),
);

export const readPhotoInventory = (value: unknown): PhotoInventory => parse(inventory, value);
export const readScheduling = (value: unknown): Scheduling => parse(object({ scheduling }), value).scheduling;
export const readPhoto = (value: unknown): Photo => parse(photo, value);
