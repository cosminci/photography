import { beforeEach, describe, expect, it, vi } from "vitest";
import { createSpreadCompensation } from "../../../src/features/albums/workspace/SpreadCompensation";
import { createPlacementCompensation } from "../../../src/features/albums/workspace/PlacementCompensation";
import type * as PlacementCompensationModule from "../../../src/features/albums/workspace/PlacementCompensation";
import type { SpreadSnapshot } from "../../../src/features/albums/model/Placement";
import type { HistoryOutcome } from "../../../src/features/albums/workspace/BookHistory";
import { AlbumRequestError } from "../../../src/features/albums/model/Album";

vi.mock("../../../src/features/albums/workspace/PlacementCompensation", async (original) => ({
  ...(await original<typeof PlacementCompensationModule>()),
  createPlacementCompensation: vi.fn(),
}));

const spread: SpreadSnapshot = {
  kind: "spread",
  id: "deleted",
  placements: [],
  layout: { kind: "photos_only", templateId: "template", bleed: { state: "disabled", available: false }, slots: [] },
};
const openingMember = { id: "opening", chapter: "chapter" };
const nextMember = { id: "next", chapter: "chapter" };
const absent = [openingMember, nextMember];
const present = [openingMember, { id: "deleted", chapter: "chapter" }, nextMember];
const recreated = [openingMember, { id: "new", chapter: "chapter" }, nextMember];

describe("SpreadCompensation", () => {
  beforeEach(() => {
    vi.mocked(createPlacementCompensation).mockReset();
    vi.mocked(createPlacementCompensation).mockReturnValue({
      label: "Restore placements",
      apply: vi.fn().mockResolvedValue({ kind: "saved" }),
      reconcile: vi.fn().mockResolvedValue({ kind: "saved" }),
    });
  });

  it("should reject history without a chapter predecessor", () => {
    const built = build();

    expect(() => createSpreadCompensation({ ...built.operations, order: ["deleted"] })).toThrow("must follow a chapter opening");
  });

  it("should reject changed membership before granting access or writing", async () => {
    const built = build();
    built.client.composition.mockResolvedValue([...absent].reverse());

    expect(await built.action.apply("undo")).toEqual({ kind: "conflict", message: "The chapter order changed. This action cannot overwrite it." });
    expect(built.operations.prepare).not.toHaveBeenCalled();
    expect(built.client.addSpread).not.toHaveBeenCalled();
    expect(built.client.deleteSpread).not.toHaveBeenCalled();
  });

  it("should stop creation when its predecessor is unavailable and retain a created identity if its grant is blocked", async () => {
    const built = build();
    built.operations.prepare.mockResolvedValueOnce("Reconnect first.");

    expect(await built.action.apply("undo")).toEqual({ kind: "blocked", message: "Reconnect first." });
    expect(built.client.addSpread).not.toHaveBeenCalled();
    built.operations.prepare.mockResolvedValueOnce(undefined).mockResolvedValueOnce("View only.");
    expect(await built.action.apply("undo")).toEqual({ kind: "blocked", message: "View only.", partial: true });
    built.client.composition.mockResolvedValue(recreated);
    expect(await built.action.apply("undo")).toEqual({ kind: "saved" });
    expect(built.client.addSpread).toHaveBeenCalledExactlyOnceWith("album", "chapter", "opening");
    expect(built.operations.prepare).toHaveBeenLastCalledWith("new", true);
  });

  it.each([
    { failure: new AlbumRequestError(422, "No capacity."), expected: { kind: "blocked", message: "No capacity." } },
    {
      failure: new AlbumRequestError(500, "Write unavailable."),
      expected: { kind: "uncertain", message: "Spread creation was not confirmed. Read saved membership before continuing." },
    },
    {
      failure: new Error("Lost response"),
      expected: { kind: "uncertain", message: "Spread creation was not confirmed. Read saved membership before continuing." },
    },
  ])("should retain $expected.kind creation and recover by reading membership", async ({ failure, expected }) => {
    const built = build();
    built.client.addSpread.mockRejectedValue(failure);

    expect(await built.action.apply("undo")).toEqual(expected);
    if (expected.kind === "uncertain")
      expect(await built.action.apply("undo")).toEqual({ kind: "uncertain", message: "Read saved membership before retrying spread creation." });
    expect(await built.action.reconcile("undo")).toEqual({ kind: "unchanged" });
    expect(built.client.addSpread).toHaveBeenCalledOnce();
  });

  it("should refuse to overwrite an externally edited recreation before creating placement compensation", async () => {
    const built = build();
    built.client.readPlacements.mockResolvedValue({
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

    expect(await built.action.apply("undo")).toEqual({
      kind: "conflict",
      message: "The recreated spread was edited before restoration could finish.",
    });
    expect(createPlacementCompensation).not.toHaveBeenCalled();
  });

  it.each([
    { outcome: { kind: "blocked", message: "Photo unavailable." }, expected: { kind: "blocked", message: "Photo unavailable.", partial: true } },
    { outcome: { kind: "uncertain", message: "Read saved placements." }, expected: { kind: "uncertain", message: "Read saved placements." } },
    { outcome: { kind: "saved" }, expected: { kind: "saved" } },
  ] satisfies readonly { outcome: HistoryOutcome; expected: HistoryOutcome }[])(
    "should delegate restoration and retain its $outcome.kind outcome without another creation",
    async ({ outcome, expected }) => {
      const apply = vi.fn().mockResolvedValue(outcome);
      const reconcile = vi.fn().mockResolvedValue({ kind: "saved" });
      vi.mocked(createPlacementCompensation).mockReturnValue({ label: "Restore placements", apply, reconcile });
      const built = build();

      expect(await built.action.apply("undo")).toEqual(expected);
      built.client.composition.mockResolvedValue(recreated);
      if (outcome.kind === "blocked") expect(await built.action.apply("undo")).toEqual(expected);
      expect(await built.action.reconcile("undo")).toEqual({ kind: "saved" });
      expect(built.client.addSpread).toHaveBeenCalledOnce();
      expect(apply).toHaveBeenCalledWith("undo");
      expect(apply).toHaveBeenCalledTimes(outcome.kind === "blocked" ? 2 : 1);
      expect(reconcile).toHaveBeenCalledExactlyOnceWith("undo");
      const request = vi.mocked(createPlacementCompensation).mock.lastCall?.[0];
      expect(request).toMatchObject({
        album: "album",
        label: "Recreate spread",
        before: spread,
        after: { ...spread, id: "new" },
        client: built.client,
      });
      expect(request?.context()).toEqual({ kind: "spread", id: "new" });
      await request?.authorize();
      expect(built.operations.prepare).toHaveBeenLastCalledWith("new", true);
    },
  );

  it("should recheck access after the saved composition read and refuse changed contents", async () => {
    const built = build(true);
    built.operations.prepare.mockResolvedValueOnce("View only.");

    expect(await built.action.apply("undo")).toEqual({ kind: "blocked", message: "View only." });
    expect(built.client.readPlacements).not.toHaveBeenCalled();
    built.operations.prepare.mockResolvedValueOnce(undefined).mockResolvedValueOnce("Expired while reading.");
    expect(await built.action.apply("undo")).toEqual({ kind: "blocked", message: "Expired while reading." });
    built.client.readPlacements.mockResolvedValue({ ...spread, layout: { ...spread.layout, templateId: "external" } });
    expect(await built.action.apply("undo")).toEqual({ kind: "conflict", message: "The spread changed. Compensation cannot delete it." });
    expect(built.client.deleteSpread).not.toHaveBeenCalled();
  });

  it.each([
    { failure: new AlbumRequestError(409, "View only."), saved: false, kind: "blocked" },
    { failure: new Error("Lost response"), saved: false, kind: "uncertain" },
    { failure: new Error("Lost response"), saved: true, kind: "uncertain" },
  ])("should recover $kind deletion by membership without replaying it when saved=$saved", async ({ failure, saved, kind }) => {
    const built = build(true);
    built.client.deleteSpread.mockRejectedValue(failure);

    expect(await built.action.apply("undo")).toMatchObject({ kind });
    built.client.composition.mockResolvedValue(saved ? absent : present);
    expect(await built.action.reconcile("undo")).toEqual({ kind: saved ? "saved" : "unchanged" });
    expect(built.client.deleteSpread).toHaveBeenCalledExactlyOnceWith("album", "deleted", true);
    expect(built.operations.removed).toHaveBeenCalledTimes(saved ? 1 : 0);
  });

  it("should reconcile completed restoration, detect external changes and leave an absent restore unfinished", async () => {
    const built = build();
    built.client.composition.mockResolvedValue(recreated);

    expect(await built.action.reconcile("undo")).toEqual({ kind: "saved" });
    built.client.readPlacements.mockResolvedValue({ ...spread, layout: { ...spread.layout, templateId: "external" } });
    expect(await built.action.reconcile("undo")).toEqual({ kind: "unchanged" });
    built.client.composition.mockResolvedValue(absent);
    expect(await built.action.reconcile("undo")).toEqual({ kind: "unchanged" });
    built.client.composition.mockResolvedValue([{ id: "external", chapter: "chapter" }]);
    expect(await built.action.reconcile("undo")).toEqual({ kind: "conflict", message: "The saved chapter membership changed." });
    expect(built.client.addSpread).not.toHaveBeenCalled();
  });

  it("should recreate under its returned identity and use that identity when redoing removal", async () => {
    const built = build();

    expect(await built.action.apply("undo")).toEqual({ kind: "saved" });
    built.client.composition.mockResolvedValue(recreated);
    expect(await built.action.apply("redo")).toEqual({ kind: "saved" });
    expect(built.client.addSpread).toHaveBeenCalledExactlyOnceWith("album", "chapter", "opening");
    expect(built.operations.identities.replace).toHaveBeenCalledExactlyOnceWith("deleted", "new");
    expect(built.client.deleteSpread).toHaveBeenCalledExactlyOnceWith("album", "new", true);
    expect(built.operations.removed).toHaveBeenCalledExactlyOnceWith("opening");
  });

  it("should refuse an ambiguous unacknowledged creation without issuing another write", async () => {
    const built = build();
    built.client.addSpread.mockRejectedValue(new Error("Lost identity"));

    expect(await built.action.apply("undo")).toMatchObject({ kind: "uncertain" });
    built.client.composition.mockResolvedValue([...absent, { id: "unconfirmed", chapter: "chapter" }]);
    expect(await built.action.reconcile("undo")).toEqual({
      kind: "conflict",
      message: "A spread appeared without a confirmed identity. Review the saved book; creation will not be replayed.",
    });
    expect(built.client.addSpread).toHaveBeenCalledOnce();
  });
});

function build(included = false) {
  const aliases = new Map([["deleted", included ? "deleted" : "new"]]);
  const client = {
    addSpread: vi.fn().mockResolvedValue("new"),
    deleteSpread: vi.fn().mockResolvedValue(undefined),
    readPlacements: vi.fn().mockResolvedValue({ ...spread, id: included ? "deleted" : "new" }),
    editPlacements: vi.fn(),
    templates: vi.fn(),
    changeSpreadTemplate: vi.fn(),
    composition: vi.fn().mockResolvedValue(included ? present : absent),
  };
  const operations = {
    album: "album",
    chapter: "chapter",
    spread,
    order: ["opening", "deleted", "next"],
    present: included,
    identities: { resolve: (id: string) => aliases.get(id) ?? id, replace: vi.fn() },
    prepare: vi.fn().mockResolvedValue(undefined),
    removed: vi.fn(),
    client,
  };
  return { action: createSpreadCompensation(operations), operations, client };
}
