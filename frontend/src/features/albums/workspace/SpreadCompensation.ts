import { AlbumRequestError, type AlbumClient } from "../model/Album";
import type { SpreadSnapshot } from "../model/Placement";
import type { HistoryAction, HistoryDirection, HistoryOutcome } from "./BookHistory";
import { createPlacementCompensation, sameComposition, type CompensationPlacementClient } from "./PlacementCompensation";

export function createSpreadCompensation(operations: {
  album: string;
  chapter: string;
  spread: SpreadSnapshot;
  order: readonly string[];
  present: boolean;
  identities: { resolve: (id: string) => string; replace: (oldId: string, newId: string) => void };
  prepare: (id: string, requireGrant: boolean) => Promise<string | undefined>;
  removed: (destination: string) => void;
  client: Pick<AlbumClient, "addSpread" | "deleteSpread" | "composition" | "templates" | "changeSpreadTemplate"> & CompensationPlacementClient;
}): HistoryAction {
  let present = operations.present;
  let unknownCreation = false;
  let compensation: HistoryAction | undefined;
  const identity = () => operations.identities.resolve(operations.spread.id);
  const expectedOrder = (included: boolean) =>
    operations.order.filter((id) => included || id !== operations.spread.id).map(operations.identities.resolve);
  const wantsPresent = (direction: HistoryDirection) => (direction === "undo" ? !operations.present : operations.present);
  const predecessor = operations.order[operations.order.indexOf(operations.spread.id) - 1];
  if (predecessor === undefined) throw new Error("A photo spread must follow a chapter opening or another photo spread.");
  const anchor = () => operations.identities.resolve(predecessor);

  async function readOrder() {
    const members = await operations.client.composition(operations.album, new AbortController().signal);
    return members.filter((member) => member.chapter === operations.chapter).map((member) => member.id);
  }
  function sameOrder(left: readonly string[], right: readonly string[]) {
    return left.length === right.length && left.every((id, index) => id === right[index]);
  }
  async function restore(): Promise<HistoryOutcome> {
    if (!present) {
      const reason = await operations.prepare(anchor(), false);
      if (reason !== undefined) return { kind: "blocked", message: reason };
      unknownCreation = true;
      try {
        const fresh = await operations.client.addSpread(operations.album, operations.chapter, anchor());
        operations.identities.replace(operations.spread.id, fresh);
        present = true;
        unknownCreation = false;
      } catch (failure) {
        if (failure instanceof AlbumRequestError && failure.status < 500) {
          unknownCreation = false;
          return { kind: "blocked", message: failure.message };
        }
        return { kind: "uncertain", message: "Spread creation was not confirmed. Read saved membership before continuing." };
      }
    }
    const reason = await operations.prepare(identity(), true);
    if (reason !== undefined) return { kind: "blocked", message: reason, partial: true };
    if (compensation === undefined) {
      const fresh = await operations.client.readPlacements(operations.album, { kind: "spread", id: identity() });
      if (fresh.placements.length > 0) return { kind: "conflict", message: "The recreated spread was edited before restoration could finish." };
      compensation = createPlacementCompensation({
        album: operations.album,
        label: "Recreate spread",
        before: operations.spread,
        after: fresh,
        context: () => ({ kind: "spread", id: identity() }),
        authorize: () => operations.prepare(identity(), true),
        client: operations.client,
      });
    }
    const result = await compensation.apply("undo");
    return result.kind === "blocked" ? { ...result, partial: true } : result;
  }
  async function remove(): Promise<HistoryOutcome> {
    const reason = await operations.prepare(identity(), true);
    if (reason !== undefined) return { kind: "blocked", message: reason };
    const saved = await operations.client.readPlacements(operations.album, { kind: "spread", id: identity() });
    if (!sameComposition(saved, operations.spread)) return { kind: "conflict", message: "The spread changed. Compensation cannot delete it." };
    const lostAccess = await operations.prepare(identity(), true);
    if (lostAccess !== undefined) return { kind: "blocked", message: lostAccess };
    try {
      await operations.client.deleteSpread(operations.album, identity(), true);
      present = false;
      compensation = undefined;
      operations.removed(anchor());
      return { kind: "saved" };
    } catch (failure) {
      return failure instanceof AlbumRequestError && failure.status < 500
        ? { kind: "blocked", message: failure.message }
        : { kind: "uncertain", message: "Spread removal was not confirmed. Read saved membership before continuing." };
    }
  }
  async function apply(direction: HistoryDirection): Promise<HistoryOutcome> {
    if (unknownCreation) return { kind: "uncertain", message: "Read saved membership before retrying spread creation." };
    const order = await readOrder();
    if (!sameOrder(order, expectedOrder(present)))
      return { kind: "conflict", message: "The chapter order changed. This action cannot overwrite it." };
    return wantsPresent(direction) ? restore() : remove();
  }
  async function reconcile(direction: HistoryDirection): Promise<HistoryOutcome> {
    const order = await readOrder();
    if (unknownCreation) {
      if (!sameOrder(order, expectedOrder(false)))
        return { kind: "conflict", message: "A spread appeared without a confirmed identity. Review the saved book; creation will not be replayed." };
      unknownCreation = false;
      return { kind: "unchanged" };
    }
    const exists = order.includes(identity());
    if (!sameOrder(order, expectedOrder(exists))) return { kind: "conflict", message: "The saved chapter membership changed." };
    present = exists;
    if (!wantsPresent(direction)) {
      if (!present) {
        compensation = undefined;
        operations.removed(anchor());
        return { kind: "saved" };
      }
      return { kind: "unchanged" };
    }
    if (!present) return { kind: "unchanged" };
    if (compensation !== undefined) return compensation.reconcile("undo");
    const saved = await operations.client.readPlacements(operations.album, { kind: "spread", id: identity() });
    return { kind: sameComposition(saved, operations.spread) ? "saved" : "unchanged" };
  }
  return { label: operations.present ? "Add spread" : "Delete spread", apply, reconcile };
}
