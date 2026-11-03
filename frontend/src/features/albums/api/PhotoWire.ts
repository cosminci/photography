import { issueCodes, issueStages, processingStates, schedulingStates, type Photo, type PhotoInventory, type Scheduling } from "../model/Photo";
import { isCalendarDay } from "../workspace/ChapterDetails";
import { readArray, readNumber, readRecord, readText } from "./Wire";

export function readPhotoInventory(value: unknown): PhotoInventory {
  const fields = readRecord(value);
  const photos = readArray(fields["photos"]).map(readPhoto);
  const counts = readRecord(fields["counts"]);
  const unplaced = readCount(counts["unplaced"]);
  const total = readCount(counts["total"]);
  const placedPhotoIds = readArray(fields["placedPhotoIds"]).map(readIdentity);
  if (total !== photos.length || unplaced > total || new Set(photos.map((photo) => photo.id)).size !== total)
    throw new Error("Invalid photo counts or identities");
  if (
    new Set(placedPhotoIds).size !== placedPhotoIds.length ||
    placedPhotoIds.some((id) => !photos.some((photo) => photo.id === id)) ||
    total - placedPhotoIds.length !== unplaced
  )
    throw new Error("Invalid placed photo identities");
  const processing = readRecord(fields["processing"]);
  return {
    photos,
    placedPhotoIds,
    counts: { unplaced, total },
    processing: {
      state: readChoice(processing["state"], processingStates),
      unsupportedCount: readCount(processing["unsupportedCount"]),
      skippedCount: readCount(processing["skippedCount"]),
      issues: readArray(processing["issues"]).map((entry) => {
        const issue = readRecord(entry);
        return {
          stage: readChoice(issue["stage"], issueStages),
          code: readChoice(issue["code"], issueCodes),
          filename: readNullableText(issue["filename"]),
        };
      }),
    },
  };
}

export function readScheduling(value: unknown): Scheduling {
  return readChoice(readRecord(value)["scheduling"], schedulingStates);
}

export function readPhoto(value: unknown): Photo {
  const fields = readRecord(value);
  const thumbnail = readRecord(fields["thumbnail"]);
  const capturedLocal = readNullableText(fields["capturedLocal"]);
  if (
    capturedLocal !== null &&
    (!/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?$/.test(capturedLocal) || !isCalendarDay(capturedLocal.slice(0, 10)))
  )
    throw new Error("Invalid camera-local capture time");
  return {
    id: readIdentity(fields["id"]),
    filename: readIdentity(fields["filename"]),
    capturedLocal,
    width: readCount(fields["width"], 1),
    height: readCount(fields["height"], 1),
    caption: readNullableText(fields["caption"]),
    thumbnail: { url: readIdentity(thumbnail["url"]), width: readCount(thumbnail["width"], 1), height: readCount(thumbnail["height"], 1) },
  };
}

function readCount(value: unknown, minimum = 0): number {
  const count = readNumber(value);
  if (!Number.isSafeInteger(count) || count < minimum) throw new Error("Invalid photo measurement");
  return count;
}

function readIdentity(value: unknown): string {
  const text = readText(value);
  if (text.length === 0) throw new Error("Expected a nonempty photo value");
  return text;
}

function readNullableText(value: unknown): string | null {
  return value === null ? null : readText(value);
}

function readChoice<T extends string>(value: unknown, choices: readonly T[]): T {
  const choice = choices.find((candidate) => candidate === value);
  if (choice === undefined) throw new Error("Unknown photo feedback");
  return choice;
}
