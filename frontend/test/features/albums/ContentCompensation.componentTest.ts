import { describe, expect, it, vi } from "vitest";
import { createContentCompensation } from "../../../src/features/albums/workspace/ContentCompensation";
import { AlbumRequestError } from "../../../src/features/albums/model/Album";

describe("content compensating commands", () => {
  it("should preserve a rejected content save and block before reads when the session is unavailable", async () => {
    const prepare = vi.fn().mockResolvedValue("Reconnect first.");
    const read = vi.fn().mockResolvedValue("After");
    const write = vi.fn().mockRejectedValue(new AlbumRequestError(422, "Content is invalid."));
    const action = createContentCompensation({
      label: "Edit synopsis",
      before: "Before",
      after: "After",
      read,
      write,
      equal: (left, right) => left === right,
      prepare,
    });
    expect(await action.apply("undo")).toEqual({ kind: "blocked", message: "Reconnect first." });
    expect(read).not.toHaveBeenCalled();
    prepare.mockResolvedValue(undefined);
    expect(await action.apply("undo")).toEqual({ kind: "blocked", message: "Content is invalid." });
    expect(await action.reconcile("undo")).toEqual({ kind: "unchanged" });
    read.mockResolvedValue("Unexpected");
    expect(await action.reconcile("undo")).toMatchObject({ kind: "conflict" });
  });
  it("should not write when editing access is lost while saved content is being read", async () => {
    const write = vi.fn();
    const prepare = vi.fn().mockResolvedValueOnce(undefined).mockResolvedValue("Editing access was lost.");
    const action = createContentCompensation({
      label: "Edit caption",
      before: "Before",
      after: "After",
      read: () => Promise.resolve("After"),
      write,
      equal: (left, right) => left === right,
      prepare,
    });
    expect(await action.apply("undo")).toEqual({ kind: "blocked", message: "Editing access was lost." });
    expect(write).not.toHaveBeenCalled();
  });
  it("should reverse and reapply only the saved content after checking current content", async () => {
    const read = vi.fn().mockResolvedValueOnce("After").mockResolvedValueOnce("Before");
    const write = vi.fn().mockResolvedValue(undefined);
    const action = createContentCompensation({
      label: "Edit caption",
      before: "Before",
      after: "After",
      read,
      write,
      equal: (left, right) => left === right,
      prepare: () => Promise.resolve(undefined),
    });

    expect(await action.apply("undo")).toEqual({ kind: "saved" });
    expect(write).toHaveBeenCalledExactlyOnceWith("Before");
    expect(await action.apply("redo")).toEqual({ kind: "saved" });
    expect(write.mock.calls).toEqual([["Before"], ["After"]]);
  });

  it("should reject independently changed content and never write on read reconciliation", async () => {
    const write = vi.fn().mockRejectedValue(new Error("Lost acknowledgement"));
    const read = vi.fn().mockResolvedValueOnce("After").mockResolvedValueOnce("Before").mockResolvedValueOnce("Someone else's edit");
    const action = createContentCompensation({
      label: "Edit description",
      before: "Before",
      after: "After",
      read,
      write,
      equal: (left, right) => left === right,
      prepare: () => Promise.resolve(undefined),
    });

    expect(await action.apply("undo")).toMatchObject({ kind: "uncertain" });
    expect(await action.reconcile("undo")).toEqual({ kind: "saved" });
    expect(await action.apply("redo")).toMatchObject({ kind: "conflict" });
    expect(write).toHaveBeenCalledOnce();
  });
});
