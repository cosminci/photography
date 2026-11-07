import { createSignal } from "solid-js";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createSpreadEditing } from "../../../src/features/albums/workspace/SpreadEditing";
import { createSpreadCompensation } from "../../../src/features/albums/workspace/SpreadCompensation";
import { createContentCompensation } from "../../../src/features/albums/workspace/ContentCompensation";
import type { HistoryAction } from "../../../src/features/albums/workspace/BookHistory";
import { AlbumRequestError } from "../../../src/features/albums/model/Album";
import { snapshotSpread, type Spread } from "../../../src/features/albums/model/Workspace";
import { buildAlbumClient } from "./AlbumClientStub";
import { buildBookHistory } from "./BookHistoryStub";

vi.mock("../../../src/features/albums/workspace/SpreadCompensation", () => ({ createSpreadCompensation: vi.fn() }));
vi.mock("../../../src/features/albums/workspace/ContentCompensation", () => ({ createContentCompensation: vi.fn() }));
const slot = { id: "slot", kind: "photo" as const, x: 2, y: 4, width: 23, height: 42 };
const opening: Spread = {
  id: "opening",
  chapter: "chapter",
  kind: "chapter_start",
  templateId: "layout",
  slots: [slot],
  placements: [],
  bleed: { state: "disabled", available: true },
};
const first: Spread = { ...opening, id: "first", kind: "photos_only" };
const second: Spread = { ...first, id: "second" };
const added: Spread = { ...first, id: "added" };
const initial = [opening, first, second];
const action: HistoryAction = { label: "Configured history", apply: vi.fn(), reconcile: vi.fn() };
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(createSpreadCompensation).mockReturnValue(action);
  vi.mocked(createContentCompensation).mockReturnValue(action);
});

describe("SpreadEditing", () => {
  it("should acquire acknowledged additions before recording existence and share replacement identities with their compensation", async () => {
    const refs = buildRefs();

    expect(await refs.editing.execute({ kind: "add", spreadId: opening.id, chapterId: "chapter" }, opening.id, false, initial)).toEqual({
      kind: "part",
      part: added.id,
    });
    expect(() => {
      refs.editing.reconcile();
    }).toThrow("The new spread was saved, but its composition could not be read.");
    refs.setSpreads([opening, first, added, second]);
    refs.editing.reconcile();
    refs.editing.reconcile();
    const operations = vi.mocked(createSpreadCompensation).mock.lastCall?.[0];
    if (operations === undefined) throw new Error("Existence compensation was not supplied");

    expect(refs.client.addSpread).toHaveBeenCalledExactlyOnceWith("album", "chapter", opening.id);
    expect(operations.order).toEqual(["opening", "first", "added", "second"]);
    expect(operations.present).toBe(true);
    expect(operations.prepare).toBe(refs.prepare);
    expect(operations.removed).toBe(refs.removed);
    expect(refs.history.record).toHaveBeenCalledExactlyOnceWith(action);
    operations.identities.replace("added", "recreated");
    operations.identities.replace("first", "other");
    operations.identities.replace("recreated", "newest");
    expect(refs.editing.resolve("added")).toBe("newest");
    expect(refs.editing.resolve("first")).toBe("other");
    expect(refs.editing.resolve("unchanged")).toBe("unchanged");
  });

  it("should fence reconciliation after access loss and never guess an unacknowledged addition identity", async () => {
    const refs = buildRefs();
    vi.mocked(refs.client.addSpread).mockRejectedValue(new Error("No acknowledgement"));

    await expect(refs.editing.execute({ kind: "add", spreadId: opening.id, chapterId: "chapter" }, null, false, initial)).rejects.toThrow(
      "No acknowledgement",
    );
    refs.editing.reconcile();
    expect(refs.history.block).not.toHaveBeenCalled();
    await expect(refs.editing.execute({ kind: "add", spreadId: opening.id, chapterId: "chapter" }, null, false, initial)).rejects.toThrow();
    refs.setSpreads([opening, added, first, second]);
    refs.editing.reconcile();
    expect(refs.history.block).toHaveBeenCalledOnce();
    await expect(refs.editing.execute({ kind: "add", spreadId: opening.id, chapterId: "chapter" }, null, false, initial)).rejects.toThrow();
    refs.setRevision(1);
    refs.editing.reconcile();
    expect(refs.history.block).toHaveBeenCalledOnce();
    expect(refs.history.record).not.toHaveBeenCalled();
    await expect(refs.editing.execute({ kind: "delete", spreadId: "absent", index: 1 }, null, false, initial)).rejects.toBeInstanceOf(
      AlbumRequestError,
    );
  });

  it("should forward optimistic order replacement and supply complementary saved reads, equality and resolved writes", async () => {
    const refs = buildRefs();
    vi.mocked(refs.client.composition).mockResolvedValue([
      { id: "opening", chapter: "chapter" },
      { id: "first", chapter: "chapter" },
      { id: "second", chapter: "chapter" },
      { id: "outside", chapter: "other" },
    ]);

    expect(await refs.editing.execute({ kind: "move", spreadId: second.id, direction: "earlier" }, first.id, false, initial)).toEqual({
      kind: "part",
      part: first.id,
    });
    refs.editing.reconcile();
    const operations = vi.mocked(createContentCompensation).mock.lastCall?.[0];
    if (operations === undefined) throw new Error("Order compensation was not supplied");

    expect(refs.client.replaceChapterComposition).toHaveBeenCalledExactlyOnceWith(
      "album",
      "chapter",
      ["opening", "first", "second"],
      ["opening", "second", "first"],
    );
    expect(operations.before).toEqual(["opening", "first", "second"]);
    expect(operations.after).toEqual(["opening", "second", "first"]);
    expect(await operations.prepare()).toBeUndefined();
    expect(refs.prepare).toHaveBeenCalledExactlyOnceWith(second.id, false);
    expect(await operations.read()).toEqual(["opening", "first", "second"]);
    expect(operations.equal(["opening", "first"], ["opening", "first"])).toBe(true);
    expect(operations.equal(["opening", "first"], ["first", "opening"])).toBe(false);
    expect(operations.equal(["opening"], ["opening", "first"])).toBe(false);
    await operations.write(["opening", "second", "first"]);
    expect(refs.client.replaceChapterComposition).toHaveBeenLastCalledWith(
      "album",
      "chapter",
      ["opening", "first", "second"],
      ["opening", "second", "first"],
    );
  });

  it.each([
    { saved: initial, records: false, blocks: false },
    { saved: [opening, second, first], records: true, blocks: false },
    { saved: [opening, first, added], records: false, blocks: true },
  ])("should distinguish known order outcomes from another editor's order after lost acknowledgement", async ({ saved, records, blocks }) => {
    const refs = buildRefs();
    vi.mocked(refs.client.replaceChapterComposition).mockRejectedValue(new Error("No acknowledgement"));

    await expect(refs.editing.execute({ kind: "move", spreadId: second.id, direction: "earlier" }, first.id, false, initial)).rejects.toThrow();
    refs.setSpreads(saved);
    refs.editing.reconcile();

    expect(refs.history.record).toHaveBeenCalledTimes(records ? 1 : 0);
    expect(refs.history.block).toHaveBeenCalledTimes(blocks ? 1 : 0);
  });

  it("should record an acknowledged template from independently acquired geometry and retain failed acquisition for retry", async () => {
    const refs = buildRefs();
    const changed: Spread = { ...first, templateId: "new-layout", slots: [{ ...slot, width: 30 }] };

    expect(await refs.editing.execute({ kind: "template", spreadId: first.id, templateId: "new-layout" }, first.id, true, initial)).toEqual({
      kind: "part",
      part: first.id,
    });
    refs.setSpreads([opening, second]);
    expect(() => {
      refs.editing.reconcile();
    }).toThrow("The saved template composition could not be read.");
    refs.setSpreads([opening, changed, second]);
    refs.editing.reconcile();

    expect(refs.client.changeSpreadTemplate).toHaveBeenCalledExactlyOnceWith("album", first.id, "new-layout", true);
    expect(refs.recordComposition).toHaveBeenCalledExactlyOnceWith(snapshotSpread(first), snapshotSpread(changed), "Change template");
  });

  it.each([
    { saved: initial, records: false, blocks: false },
    { saved: [opening, { ...first, templateId: "new-layout" }, second], records: true, blocks: false },
    { saved: [opening, { ...first, slots: [{ ...slot, width: 30 }] }, second], records: false, blocks: true },
    { saved: [opening, second], records: false, blocks: true },
  ])("should reconcile known template outcomes and refuse incompatible saved geometry", async ({ saved, records, blocks }) => {
    const refs = buildRefs();
    vi.mocked(refs.client.changeSpreadTemplate).mockRejectedValue(new Error("No acknowledgement"));

    await expect(
      refs.editing.execute({ kind: "template", spreadId: first.id, templateId: "new-layout" }, first.id, false, initial),
    ).rejects.toThrow();
    refs.setSpreads(saved);
    refs.editing.reconcile();

    expect(refs.recordComposition).toHaveBeenCalledTimes(records ? 1 : 0);
    expect(refs.history.block).toHaveBeenCalledTimes(blocks ? 1 : 0);
  });

  it("should record deletion against its original chapter order and return the removed album position", async () => {
    const refs = buildRefs();

    expect(await refs.editing.execute({ kind: "delete", spreadId: first.id, index: 1 }, first.id, true, initial)).toEqual({
      kind: "index",
      index: 1,
    });
    refs.editing.reconcile();

    expect(refs.client.deleteSpread).toHaveBeenCalledExactlyOnceWith("album", first.id, true);
    expect(vi.mocked(createSpreadCompensation).mock.lastCall?.[0]).toMatchObject({
      chapter: "chapter",
      present: false,
      order: ["opening", "first", "second"],
    });
  });

  it.each([
    { saved: initial, records: false, blocks: false },
    { saved: [opening, second], records: true, blocks: false },
    { saved: [opening, first, added], records: false, blocks: true },
  ])("should distinguish retained, removed and conflicting membership after an uncertain deletion", async ({ saved, records, blocks }) => {
    const refs = buildRefs();
    vi.mocked(refs.client.deleteSpread).mockRejectedValue(new Error("No acknowledgement"));

    await expect(refs.editing.execute({ kind: "delete", spreadId: first.id, index: 1 }, first.id, false, initial)).rejects.toThrow();
    refs.setSpreads(saved);
    refs.editing.reconcile();

    expect(refs.history.record).toHaveBeenCalledTimes(records ? 1 : 0);
    expect(refs.history.block).toHaveBeenCalledTimes(blocks ? 1 : 0);
  });
});

function buildRefs() {
  const [spreads, setSpreads] = createSignal<readonly Spread[]>(initial);
  const [revision, setRevision] = createSignal(0);
  const { history } = buildBookHistory();
  const client = buildAlbumClient({
    addSpread: vi.fn().mockResolvedValue(added.id),
    replaceChapterComposition: vi.fn().mockResolvedValue(undefined),
    changeSpreadTemplate: vi.fn().mockResolvedValue(undefined),
    deleteSpread: vi.fn().mockResolvedValue(undefined),
  });
  const prepare = vi.fn().mockResolvedValue(undefined);
  const removed = vi.fn();
  const recordComposition = vi.fn();
  const editing = createSpreadEditing({ album: "album", client, history, spreads, lossRevision: revision, prepare, removed, recordComposition });
  return { editing, client, history, setSpreads, setRevision, prepare, removed, recordComposition };
}
