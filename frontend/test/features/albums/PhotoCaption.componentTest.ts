import { createRoot } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { createCaptionEditing } from "../../../src/features/albums/workspace/PhotoCaption";
import { createContentSave } from "../../../src/features/albums/workspace/ContentSave";
import { buildContentSave } from "./ContentSaveStub";

vi.mock("../../../src/features/albums/workspace/ContentSave", () => ({ createContentSave: vi.fn() }));
describe("PhotoCaption", () => {
  it("should retain one save owner per photo and scope its save, read, blocking and completion programs", async () => {
    const refs = buildContentSave<string>();
    vi.mocked(createContentSave<string>).mockReturnValue(refs.save);
    const save = vi.fn().mockResolvedValue(undefined);
    const read = vi.fn().mockResolvedValue("Saved caption");
    const onBlock = vi.fn();
    const onCompleted = vi.fn();
    const root = createRoot((dispose) => ({ dispose, editing: createCaptionEditing({ save, read, onBlock, onCompleted, maybeAuthority: () => 7 }) }));

    expect(root.editing.getPhotoSave("photo")).toBe(refs.save);
    expect(root.editing.getPhotoSave("photo")).toBe(refs.save);
    expect(root.editing.sessions()).toEqual([{ photoId: "photo", save: refs.save }]);
    const operations = vi.mocked(createContentSave<string>).mock.lastCall?.[0];
    if (operations === undefined) throw new Error("Caption save operations were not supplied");
    await operations.save("Requested caption");
    expect(await operations.read("Draft")).toBe("Saved caption");
    operations.onCompleted?.("Before", "After");
    operations.onSaved("Saved", "Draft");

    expect(save).toHaveBeenCalledExactlyOnceWith("photo", "Requested caption");
    expect(read).toHaveBeenCalledExactlyOnceWith("photo");
    expect(onCompleted).toHaveBeenCalledExactlyOnceWith("photo", "Before", "After");
    expect(operations.equal("Same", "Same")).toBe(true);
    expect(operations.equal("Before", "After")).toBe(false);
    expect(operations.rebase?.("Saved", "Draft")).toBe("Saved");
    expect(operations.maybeAuthority?.()).toBe(7);
    root.editing.focus.request("photo");
    expect(root.editing.focus.requested()).toBe("photo");
    root.editing.focus.clear();
    expect(root.editing.focus.requested()).toBeUndefined();
    root.dispose();
    expect(createContentSave).toHaveBeenCalledOnce();
  });
});
