import { describe, expect, it, vi } from "vitest";
import { AlbumRequestError } from "../../../src/features/albums/model/Album";
import { createChapterCompensation, type ChapterBookState } from "../../../src/features/albums/workspace/ChapterCompensation";

describe("chapter book compensation", () => {
  it("should leave an unchanged chapter untouched when applying or reconciling its saved state", async () => {
    const book: ChapterBookState = { details: { kind: "home", countries: ["ro"], sourceFolder: "/photos" }, gear: [] };
    const saveDetails = vi.fn();
    const saveGear = vi.fn();
    const action = createChapterCompensation({
      before: book,
      after: book,
      prepare: () => Promise.resolve(undefined),
      readDetails: () => Promise.resolve(book.details),
      readGear: () => Promise.resolve(book.gear),
      saveDetails,
      saveGear,
    });

    expect(await action.apply("undo")).toEqual({ kind: "saved" });
    expect(await action.reconcile("undo")).toEqual({ kind: "saved" });
    expect(saveDetails).not.toHaveBeenCalled();
    expect(saveGear).not.toHaveBeenCalled();
  });

  it("should reconcile an unchanged first portion without skipping it or writing gear", async () => {
    const before: ChapterBookState = { details: { kind: "home", countries: ["ro"], sourceFolder: "/photos" }, gear: [] };
    const after: ChapterBookState = { details: { ...before.details, countries: ["it"] }, gear: [{ cameraId: "camera", lensIds: [] }] };
    const saveDetails = vi.fn().mockRejectedValue(new Error("Lost response"));
    const saveGear = vi.fn();
    const action = createChapterCompensation({
      before,
      after,
      prepare: () => Promise.resolve(undefined),
      readDetails: () => Promise.resolve(after.details),
      readGear: () => Promise.resolve(after.gear),
      saveDetails,
      saveGear,
    });

    expect(await action.apply("undo")).toMatchObject({ kind: "uncertain" });
    expect(await action.reconcile("undo")).toEqual({ kind: "unchanged" });
    expect(saveDetails).toHaveBeenCalledOnce();
    expect(saveGear).not.toHaveBeenCalled();
  });

  it("should group only actually saved portions and reconcile each uncertain portion without replay", async () => {
    const before: ChapterBookState = { details: { kind: "home", countries: ["ro"], sourceFolder: "/photos" }, gear: [] };
    const after: ChapterBookState = { details: { ...before.details, countries: ["it"] }, gear: [{ cameraId: "camera", lensIds: [] }] };
    const readDetails = vi.fn().mockResolvedValueOnce(after.details).mockResolvedValueOnce(before.details);
    const readGear = vi.fn().mockResolvedValueOnce(after.gear).mockResolvedValueOnce(before.gear);
    const saveDetails = vi.fn().mockRejectedValue(new Error("Lost metadata response"));
    const saveGear = vi.fn().mockRejectedValue(new Error("Lost gear response"));
    const action = createChapterCompensation({
      before,
      after,
      prepare: () => Promise.resolve(undefined),
      readDetails,
      readGear,
      saveDetails,
      saveGear,
    });
    expect(await action.apply("undo")).toMatchObject({ kind: "uncertain" });
    expect(await action.apply("redo")).toMatchObject({ kind: "blocked", partial: true });
    expect(await action.reconcile("undo")).toEqual({ kind: "unchanged" });
    expect(saveDetails).toHaveBeenCalledExactlyOnceWith(before.details);
    expect(saveGear).not.toHaveBeenCalled();
    expect(await action.apply("undo")).toMatchObject({ kind: "uncertain" });
    expect(await action.reconcile("undo")).toEqual({ kind: "saved" });
    expect(saveGear).toHaveBeenCalledExactlyOnceWith(before.gear);
  });

  it("should compensate gear alone and preserve independent metadata", async () => {
    const details = { kind: "home" as const, countries: ["ro"], sourceFolder: "/photos" };
    const before: ChapterBookState = { details, gear: [] };
    const after: ChapterBookState = { details, gear: [{ cameraId: "camera", lensIds: [] }] };
    const readDetails = vi.fn();
    const saveDetails = vi.fn();
    const saveGear = vi.fn().mockResolvedValue(undefined);
    const action = createChapterCompensation({
      before,
      after,
      prepare: () => Promise.resolve(undefined),
      readDetails,
      saveDetails,
      readGear: () => Promise.resolve(after.gear),
      saveGear,
    });
    expect(await action.apply("undo")).toEqual({ kind: "saved" });
    expect(saveGear).toHaveBeenCalledExactlyOnceWith([]);
    expect(readDetails).not.toHaveBeenCalled();
    expect(saveDetails).not.toHaveBeenCalled();
  });
  it("should resume only the unfinished part of a grouped metadata and gear edit", async () => {
    const before: ChapterBookState = {
      details: { kind: "home", countries: ["ro"], sourceFolder: "/photos" },
      gear: [],
    };
    const after: ChapterBookState = {
      details: { kind: "home", countries: ["ro", "it"], sourceFolder: "/photos" },
      gear: [{ cameraId: "camera", lensIds: ["lens"] }],
    };
    const readDetails = vi.fn().mockResolvedValueOnce(after.details).mockResolvedValueOnce(before.details);
    const readGear = vi.fn().mockResolvedValueOnce(after.gear).mockResolvedValueOnce(after.gear).mockResolvedValueOnce(before.gear);
    const saveDetails = vi.fn().mockResolvedValue(undefined);
    const saveGear = vi.fn().mockRejectedValueOnce(new AlbumRequestError(422, "Retry gear")).mockResolvedValue(undefined);
    const action = createChapterCompensation({
      before,
      after,
      prepare: () => Promise.resolve(undefined),
      readDetails,
      readGear,
      saveDetails,
      saveGear,
    });
    expect(await action.apply("undo")).toMatchObject({ kind: "blocked", partial: true });
    expect(await action.apply("undo")).toEqual({ kind: "saved" });
    expect(saveDetails).toHaveBeenCalledOnce();
    expect(saveGear.mock.calls).toEqual([[before.gear], [before.gear]]);
    expect(await action.apply("redo")).toEqual({ kind: "saved" });
    expect(saveDetails.mock.calls).toEqual([[before.details], [after.details]]);
    expect(saveGear.mock.calls).toEqual([[before.gear], [before.gear], [after.gear]]);
  });
});
