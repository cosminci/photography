import { sameGear, type CameraSelection } from "../model/Gear";
import type { ChapterDetails } from "../model/Workspace";
import { sameChapterDetails } from "./ChapterDetails";
import type { HistoryAction, HistoryDirection, HistoryOutcome } from "./BookHistory";
import { createContentCompensation } from "./ContentCompensation";

export interface ChapterBookState {
  details: ChapterDetails;
  gear: readonly CameraSelection[];
}

export function sameChapterBook(left: ChapterBookState, right: ChapterBookState) {
  return sameChapterDetails(left.details, right.details) && sameGear(left.gear, right.gear);
}

export function createChapterCompensation(operations: {
  before: ChapterBookState;
  after: ChapterBookState;
  prepare: () => Promise<string | undefined>;
  readDetails: () => Promise<ChapterDetails>;
  readGear: () => Promise<readonly CameraSelection[]>;
  saveDetails: (details: ChapterDetails) => Promise<unknown>;
  saveGear: (gear: readonly CameraSelection[]) => Promise<unknown>;
}): HistoryAction {
  let actions: readonly HistoryAction[] | undefined;
  let index = 0;
  let direction: HistoryDirection | undefined;
  function portions() {
    actions ??= [
      ...(sameChapterDetails(operations.before.details, operations.after.details)
        ? []
        : [
            createContentCompensation({
              label: "Edit chapter details",
              before: operations.before.details,
              after: operations.after.details,
              prepare: operations.prepare,
              equal: sameChapterDetails,
              read: operations.readDetails,
              write: operations.saveDetails,
            }),
          ]),
      ...(sameGear(operations.before.gear, operations.after.gear)
        ? []
        : [
            createContentCompensation({
              label: "Edit chapter gear",
              before: operations.before.gear,
              after: operations.after.gear,
              prepare: operations.prepare,
              equal: sameGear,
              read: operations.readGear,
              write: operations.saveGear,
            }),
          ]),
    ];
    return actions;
  }
  function finish(outcome: HistoryOutcome): HistoryOutcome {
    if (outcome.kind === "saved") index++;
    if (index >= portions().length) {
      direction = undefined;
      index = 0;
      return { kind: "saved" };
    }
    return outcome.kind === "blocked" && index > 0 ? { ...outcome, partial: true } : outcome;
  }
  return {
    label: "Edit chapter details and gear",
    apply: async (requested) => {
      if (direction !== undefined && requested !== direction)
        return { kind: "blocked", partial: true, message: "Finish the interrupted chapter change before reversing direction." };
      direction = requested;
      for (const action of portions().slice(index)) {
        const outcome = await action.apply(requested);
        const result = finish(outcome);
        if (outcome.kind !== "saved" || index === 0) return result;
      }
      return finish({ kind: "saved" });
    },
    reconcile: async (requested) => {
      const action = portions()[index];
      if (action === undefined) return { kind: "saved" };
      const outcome = await action.reconcile(requested);
      const result = finish(outcome);
      return outcome.kind === "saved" && direction !== undefined ? { kind: "unchanged" } : result;
    },
  };
}
