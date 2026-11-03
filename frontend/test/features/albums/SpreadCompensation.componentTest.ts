import { describe, expect, it, vi } from "vitest";
import { createSpreadCompensation } from "../../../src/features/albums/workspace/SpreadCompensation";
import type { SpreadSnapshot } from "../../../src/features/albums/model/Placement";
import { AlbumRequestError, type AlbumClient } from "../../../src/features/albums/model/Album";

const spread: SpreadSnapshot = {
  kind: "spread",
  id: "deleted",
  placements: [],
  layout: { kind: "photos_only", templateId: "template", bleed: { state: "disabled", available: false }, slots: [] },
};
function build(present = false, saved = spread) {
  let order = present ? ["opening", "deleted", "next"] : ["opening", "next"];
  const identities = new Map<string, string>();
  const resolve = (id: string) => identities.get(id) ?? id;
  const prepare = vi.fn().mockResolvedValue(undefined);
  const removed = vi.fn();
  const addSpread = vi.fn(() => {
    order = ["opening", "new", "next"];
    return Promise.resolve("new");
  });
  const deleteSpread = vi.fn(() => {
    order = ["opening", "next"];
    return Promise.resolve();
  });
  const readPlacements = vi.fn<AlbumClient["readPlacements"]>().mockImplementation(() => Promise.resolve({ ...saved, id: resolve(saved.id) }));
  const editPlacements = vi.fn().mockRejectedValue(new AlbumRequestError(422, "Photo unavailable."));
  const action = createSpreadCompensation({
    album: "album",
    chapter: "chapter",
    spread: saved,
    order: ["opening", "deleted", "next"],
    present,
    identities: {
      resolve,
      replace: (oldId, newId) => {
        identities.set(oldId, newId);
      },
    },
    prepare,
    removed,
    client: {
      addSpread,
      deleteSpread,
      readPlacements,
      editPlacements,
      templates: vi.fn(),
      changeSpreadTemplate: vi.fn(),
      composition: () => Promise.resolve(order.map((id) => ({ id, chapter: "chapter" }))),
    },
  });
  return {
    action,
    prepare,
    removed,
    addSpread,
    deleteSpread,
    readPlacements,
    editPlacements,
    setOrder: (value: string[]) => {
      order = value;
    },
  };
}

describe("spread compensating commands", () => {
  it("should reject a malformed spread history without a chapter predecessor", () => {
    expect(() =>
      createSpreadCompensation({
        album: "album",
        chapter: "chapter",
        spread,
        order: ["deleted"],
        present: false,
        identities: { resolve: (id) => id, replace: vi.fn() },
        prepare: vi.fn(),
        removed: vi.fn(),
        client: {
          addSpread: vi.fn(),
          deleteSpread: vi.fn(),
          readPlacements: vi.fn(),
          editPlacements: vi.fn(),
          templates: vi.fn(),
          changeSpreadTemplate: vi.fn(),
          composition: vi.fn(),
        },
      }),
    ).toThrow("must follow a chapter opening");
  });

  it("should refuse a changed order without adding or removing anything", async () => {
    const built = build();
    built.setOrder(["next", "opening"]);
    expect(await built.action.apply("undo")).toMatchObject({ kind: "conflict" });
    expect(built.addSpread).not.toHaveBeenCalled();
    expect(built.deleteSpread).not.toHaveBeenCalled();
  });

  it("should not create a spread when the browser session is unavailable", async () => {
    const built = build();
    built.prepare.mockResolvedValue("Reconnect first.");
    expect(await built.action.apply("undo")).toEqual({ kind: "blocked", message: "Reconnect first." });
    expect(built.addSpread).not.toHaveBeenCalled();
  });

  it("should retain a rejected creation without treating it as an unconfirmed created spread", async () => {
    const built = build();
    built.addSpread.mockRejectedValue(new AlbumRequestError(422, "No capacity."));
    expect(await built.action.apply("undo")).toEqual({ kind: "blocked", message: "No capacity." });
    expect(await built.action.reconcile("undo")).toEqual({ kind: "unchanged" });
    expect(await built.action.apply("undo")).toMatchObject({ kind: "blocked" });
  });

  it("should require read recovery before explicitly retrying an unconfirmed creation", async () => {
    const built = build();
    built.addSpread.mockRejectedValue(new Error("Lost response"));
    expect(await built.action.apply("undo")).toMatchObject({ kind: "uncertain" });
    expect(await built.action.apply("undo")).toMatchObject({ kind: "uncertain" });
    expect(built.addSpread).toHaveBeenCalledOnce();
    expect(await built.action.reconcile("undo")).toEqual({ kind: "unchanged" });
    expect(built.addSpread).toHaveBeenCalledOnce();
  });

  it("should retain a newly created spread when acquiring it fails and never create it again", async () => {
    const built = build();
    built.prepare.mockResolvedValueOnce(undefined).mockResolvedValue("View only.");
    expect(await built.action.apply("undo")).toEqual({ kind: "blocked", message: "View only.", partial: true });
    built.prepare.mockResolvedValue(undefined);
    expect(await built.action.apply("undo")).toEqual({ kind: "saved" });
    expect(built.addSpread).toHaveBeenCalledOnce();
  });

  it("should not overwrite a recreated spread edited before its grant was acquired", async () => {
    const built = build();
    built.readPlacements.mockResolvedValue({
      ...spread,
      placements: [
        {
          kind: "spread",
          id: "external",
          slotId: "slot",
          photoId: "external",
          transform: { x: 0, y: 0, zoom: 1, mirrored: false },
          captionCorner: "top_left",
        },
      ],
    });
    expect(await built.action.apply("undo")).toMatchObject({ kind: "conflict" });
    expect(built.editPlacements).not.toHaveBeenCalled();
  });

  it("should preserve partial restoration and resume a failed photo placement without adding another spread", async () => {
    const populated: SpreadSnapshot = {
      ...spread,
      layout: { ...spread.layout, slots: [{ kind: "photo", id: "slot", x: 0, y: 0, width: 100, height: 100 }] },
      placements: [
        {
          kind: "spread",
          id: "old",
          slotId: "slot",
          photoId: "photo",
          transform: { x: 0, y: 0, zoom: 1, mirrored: false },
          captionCorner: "bottom_right",
        },
      ],
    };
    const built = build(false, populated);
    built.readPlacements.mockResolvedValue({ ...populated, id: "new", placements: [] });
    expect(await built.action.apply("undo")).toMatchObject({ kind: "blocked", partial: true });
    built.editPlacements.mockResolvedValue({ ...populated, id: "new" });
    expect(await built.action.apply("undo")).toEqual({ kind: "saved" });
    expect(built.addSpread).toHaveBeenCalledOnce();
  });

  it("should not remove an added spread whose saved contents changed", async () => {
    const built = build(true);
    built.readPlacements.mockResolvedValue({ ...spread, layout: { ...spread.layout, templateId: "someone-elses-template" } });
    expect(await built.action.apply("undo")).toMatchObject({ kind: "conflict" });
    expect(built.deleteSpread).not.toHaveBeenCalled();
  });

  it("should check access both before and after the composition read before deleting", async () => {
    const built = build(true);
    built.prepare.mockResolvedValue("View only.");
    expect(await built.action.apply("undo")).toMatchObject({ kind: "blocked" });
    expect(built.readPlacements).not.toHaveBeenCalled();
    built.prepare.mockResolvedValueOnce(undefined).mockResolvedValue("Expired while reading.");
    expect(await built.action.apply("undo")).toMatchObject({ kind: "blocked", message: "Expired while reading." });
    expect(built.deleteSpread).not.toHaveBeenCalled();
  });

  it.each([false, true])("should reconcile an unconfirmed deletion by reading membership only (saved=%s)", async (saved) => {
    const built = build(true);
    built.deleteSpread.mockImplementation(() => {
      if (saved) built.setOrder(["opening", "next"]);
      return Promise.reject(new Error("Lost response"));
    });
    expect(await built.action.apply("undo")).toMatchObject({ kind: "uncertain" });
    expect(await built.action.reconcile("undo")).toEqual({ kind: saved ? "saved" : "unchanged" });
    expect(built.deleteSpread).toHaveBeenCalledOnce();
    expect(built.removed).toHaveBeenCalledTimes(saved ? 1 : 0);
  });

  it("should retain a rejected deletion and detect an unrelated chapter membership change on read recovery", async () => {
    const built = build(true);
    built.deleteSpread.mockRejectedValue(new AlbumRequestError(409, "View only."));
    expect(await built.action.apply("undo")).toEqual({ kind: "blocked", message: "View only." });
    built.setOrder(["opening", "unrelated", "next"]);
    expect(await built.action.reconcile("undo")).toMatchObject({ kind: "conflict" });
  });

  it("should recognize a completed restore without replaying and reject a changed visible restore", async () => {
    const built = build();
    built.setOrder(["opening", "deleted", "next"]);
    expect(await built.action.reconcile("undo")).toEqual({ kind: "saved" });
    built.readPlacements.mockResolvedValue({ ...spread, layout: { ...spread.layout, templateId: "different" } });
    expect(await built.action.reconcile("undo")).toEqual({ kind: "unchanged" });
    expect(built.addSpread).not.toHaveBeenCalled();
  });

  it("should reconcile an uncertain recreation step without issuing any photo or creation writes", async () => {
    const populated: SpreadSnapshot = {
      ...spread,
      layout: { ...spread.layout, slots: [{ kind: "photo", id: "slot", x: 0, y: 0, width: 100, height: 100 }] },
      placements: [
        {
          kind: "spread",
          id: "old",
          slotId: "slot",
          photoId: "photo",
          transform: { x: 0, y: 0, zoom: 1, mirrored: false },
          captionCorner: "bottom_right",
        },
      ],
    };
    const built = build(false, populated);
    built.readPlacements.mockResolvedValue({ ...populated, id: "new", placements: [] });
    built.editPlacements.mockRejectedValue(new Error("Lost acknowledgement"));
    expect(await built.action.apply("undo")).toMatchObject({ kind: "uncertain" });
    built.readPlacements.mockResolvedValue({ ...populated, id: "new" });
    expect(await built.action.reconcile("undo")).toEqual({ kind: "saved" });
    expect(built.addSpread).toHaveBeenCalledOnce();
    expect(built.editPlacements).toHaveBeenCalledOnce();
  });
  it("should recreate a deleted spread with a new identity and use that identity on redo", async () => {
    let order = ["opening", "next"];
    const replace = vi.fn();
    const add = vi.fn(() => {
      order = ["opening", "new", "next"];
      return Promise.resolve("new");
    });
    const remove = vi.fn(() => {
      order = ["opening", "next"];
      return Promise.resolve();
    });
    const action = createSpreadCompensation({
      album: "album",
      chapter: "chapter",
      spread,
      order: ["opening", "deleted", "next"],
      present: false,
      identities: { resolve: (id) => (id === "deleted" && add.mock.calls.length > 0 ? "new" : id), replace },
      prepare: () => Promise.resolve(undefined),
      removed: vi.fn(),
      client: {
        addSpread: add,
        deleteSpread: remove,
        readPlacements: vi.fn().mockResolvedValue({ ...spread, id: "new" }),
        editPlacements: vi.fn(),
        templates: vi.fn(),
        changeSpreadTemplate: vi.fn(),
        composition: vi.fn(() => Promise.resolve(order.map((id) => ({ id, chapter: "chapter" })))),
      },
    });

    expect(await action.apply("undo")).toEqual({ kind: "saved" });
    expect(add).toHaveBeenCalledExactlyOnceWith("album", "chapter", "opening");
    expect(replace).toHaveBeenCalledExactlyOnceWith("deleted", "new");
    expect(await action.apply("redo")).toEqual({ kind: "saved" });
    expect(remove).toHaveBeenCalledExactlyOnceWith("album", "new", true);
  });

  it("should not duplicate an unacknowledged creation when saved membership changed ambiguously", async () => {
    const add = vi.fn().mockRejectedValue(new Error("Lost identity response"));
    const composition = vi
      .fn()
      .mockResolvedValueOnce([{ id: "opening", chapter: "chapter" }])
      .mockResolvedValue([
        { id: "opening", chapter: "chapter" },
        { id: "unconfirmed", chapter: "chapter" },
      ]);
    const action = createSpreadCompensation({
      album: "album",
      chapter: "chapter",
      spread,
      order: ["opening", "deleted"],
      present: false,
      identities: { resolve: (id) => id, replace: vi.fn() },
      prepare: () => Promise.resolve(undefined),
      removed: vi.fn(),
      client: {
        addSpread: add,
        deleteSpread: vi.fn(),
        readPlacements: vi.fn(),
        editPlacements: vi.fn(),
        templates: vi.fn(),
        changeSpreadTemplate: vi.fn(),
        composition,
      },
    });

    expect(await action.apply("undo")).toMatchObject({ kind: "uncertain" });
    expect(await action.reconcile("undo")).toMatchObject({ kind: "conflict" });
    expect(add).toHaveBeenCalledOnce();
  });
});
