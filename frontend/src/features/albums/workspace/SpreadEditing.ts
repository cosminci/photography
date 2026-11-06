import { untrack } from "solid-js";
import { AlbumRequestError, type AlbumClient } from "../model/Album";
import { snapshotSpread, type Spread } from "../model/Workspace";
import type { PlacementSnapshot } from "../model/Placement";
import type { createBookHistory } from "./BookHistory";
import { createContentCompensation } from "./ContentCompensation";
import { sameComposition } from "./PlacementCompensation";
import { createSpreadCompensation } from "./SpreadCompensation";
import { buildChapterReorder } from "./WorkspaceSelection";

export type SpreadCommand = { spreadId: string } & (
  | { kind: "add"; chapterId: string }
  | { kind: "move"; direction: "earlier" | "later" }
  | { kind: "template"; templateId: string }
  | { kind: "delete"; index: number }
);
export type SpreadDestination = { kind: "part"; part: string | null } | { kind: "index"; index: number };

export function createSpreadEditing(operations: {
  album: string;
  client: Pick<
    AlbumClient,
    | "addSpread"
    | "replaceChapterComposition"
    | "changeSpreadTemplate"
    | "deleteSpread"
    | "composition"
    | "templates"
    | "readPlacements"
    | "editPlacements"
  >;
  history: ReturnType<typeof createBookHistory>;
  spreads: () => readonly Spread[];
  lossRevision: () => number;
  prepare: (id: string, requireGrant: boolean) => Promise<string | undefined>;
  removed: (destination: string) => void;
  recordComposition: (before: PlacementSnapshot, after: PlacementSnapshot, label: string) => void;
}) {
  const { album, client, history } = operations;
  const replacements = new Map<string, string>();
  const resolve = (id: string) => replacements.get(id) ?? id;
  const identities = {
    resolve,
    replace: (oldId: string, newId: string) => {
      const previous = resolve(oldId);
      for (const [id, current] of replacements) if (current === previous) replacements.set(id, newId);
      replacements.set(oldId, newId);
    },
  };
  let pending: (() => void) | undefined;
  let revision = operations.lossRevision();
  const orderOf = (chapter: string | null) =>
    operations
      .spreads()
      .filter((spread) => spread.chapter === chapter)
      .map((spread) => spread.id);
  const equalOrder = (left: readonly string[], right: readonly string[]) =>
    left.length === right.length && left.every((id, index) => id === right[index]);

  function recordExistence(spread: Spread, order: readonly string[], present: boolean) {
    /* v8 ignore next */
    if (spread.chapter === null) throw new Error("An album opening cannot be recreated as a photo spread.");
    history.record(
      createSpreadCompensation({
        album,
        client,
        chapter: spread.chapter,
        spread: snapshotSpread(spread),
        order,
        present,
        identities,
        prepare: operations.prepare,
        removed: operations.removed,
      }),
    );
  }
  function recordOrder(chapter: string, before: readonly string[], after: readonly string[], target: string) {
    let observed = after.map(resolve);
    history.record(
      createContentCompensation({
        label: "Reorder spread",
        before,
        after,
        equal: (left, right) => equalOrder(left.map(resolve), right.map(resolve)),
        prepare: () => operations.prepare(resolve(target), false),
        read: async () => {
          const members = await client.composition(album, new AbortController().signal);
          observed = members.filter((member) => member.chapter === chapter).map((member) => member.id);
          return observed;
        },
        write: (order) => client.replaceChapterComposition(album, chapter, observed, order.map(resolve)),
      }),
    );
  }
  function reconcile() {
    untrack(() => {
      if (revision === operations.lossRevision()) pending?.();
      pending = undefined;
    });
  }
  async function execute(
    command: SpreadCommand,
    selection: string | null,
    confirmPlacements: boolean,
    visible: readonly Spread[],
  ): Promise<SpreadDestination> {
    pending = undefined;
    revision = operations.lossRevision();
    const before = visible.find((spread) => spread.id === command.spreadId);
    if (before === undefined) throw new AlbumRequestError(404, "The spread is no longer available.");
    const order = visible.filter((spread) => spread.chapter === before.chapter).map((spread) => spread.id);
    if (command.kind === "add") {
      pending = () => {
        if (!equalOrder(orderOf(before.chapter), order))
          history.block(
            "A new spread appeared without a confirmed identity. Review the saved book; Undo cannot skip this edit or guess which spread to remove.",
          );
      };
      const freshId = await client.addSpread(album, command.chapterId, command.spreadId);
      pending = () => {
        const fresh = operations.spreads().find((spread) => spread.id === freshId);
        if (fresh === undefined) throw new AlbumRequestError(404, "The new spread was saved, but its composition could not be read.");
        recordExistence(fresh, orderOf(command.chapterId), true);
      };
      return { kind: "part", part: freshId };
    }
    if (command.kind === "move") {
      const replacement = buildChapterReorder(operations.spreads(), command.spreadId, command.direction);
      const record = () => {
        recordOrder(replacement.chapter, replacement.expectedSpreadIds, replacement.spreadIds, command.spreadId);
      };
      pending = () => {
        const current = orderOf(before.chapter);
        if (equalOrder(current, replacement.spreadIds)) record();
        else if (!equalOrder(current, replacement.expectedSpreadIds))
          history.block("The saved chapter order differs from both known reorder outcomes. Review the saved book.");
      };
      await client.replaceChapterComposition(album, replacement.chapter, replacement.expectedSpreadIds, replacement.spreadIds);
      pending = record;
    } else if (command.kind === "template") {
      const record = (saved: Spread) => {
        operations.recordComposition(snapshotSpread(before), snapshotSpread(saved), "Change template");
      };
      pending = () => {
        const current = operations.spreads().find((spread) => spread.id === command.spreadId);
        if (current?.templateId === command.templateId) record(current);
        else if (current === undefined || !sameComposition(snapshotSpread(before), snapshotSpread(current)))
          history.block("The saved spread differs from both known template outcomes. Review the saved book.");
      };
      await client.changeSpreadTemplate(album, command.spreadId, command.templateId, confirmPlacements);
      pending = () => {
        const saved = operations.spreads().find((spread) => spread.id === command.spreadId);
        if (saved === undefined) throw new AlbumRequestError(404, "The saved template composition could not be read.");
        record(saved);
      };
    } else {
      const record = () => {
        recordExistence(before, order, false);
      };
      pending = () => {
        const current = orderOf(before.chapter);
        if (
          equalOrder(
            current,
            order.filter((id) => id !== before.id),
          )
        )
          record();
        else if (!equalOrder(current, order))
          history.block("The saved chapter membership differs from both known deletion outcomes. Review the saved book.");
      };
      await client.deleteSpread(album, command.spreadId, confirmPlacements);
      pending = record;
      return { kind: "index", index: command.index };
    }
    return { kind: "part", part: selection };
  }
  return { execute, reconcile, resolve };
}
