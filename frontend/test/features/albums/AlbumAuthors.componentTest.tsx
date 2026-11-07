import { cleanup, fireEvent, render, screen } from "@solidjs/testing-library";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AlbumAuthors } from "../../../src/features/albums/workspace/AlbumAuthors";
import { createContentSave } from "../../../src/features/albums/workspace/ContentSave";
import type { Author } from "../../../src/features/albums/model/Album";
import { buildContentSave } from "./ContentSaveStub";

vi.mock("../../../src/features/albums/workspace/ContentSave", () => ({ createContentSave: vi.fn() }));
afterEach(cleanup);

describe("AlbumAuthors", () => {
  it("should deliver canonical selection commands and delegate saved-content comparison and publication", async () => {
    const refs = buildContentSave<readonly Author[]>();
    vi.mocked(createContentSave<readonly Author[]>).mockReturnValue(refs.save);
    const save = vi.fn().mockResolvedValue(["Mara"]);
    const read = vi.fn().mockResolvedValue(["Cosmin"]);
    const onCompleted = vi.fn();
    const onBlock = vi.fn();
    render(() => (
      <>
        <AlbumAuthors
          authors={["Mara", "Cosmin"]}
          save={save}
          read={read}
          unavailable={undefined}
          onBlock={onBlock}
          onCompleted={onCompleted}
          maybeAuthority={() => 7}
        />
        <button type="button">Continue viewing</button>
      </>
    ));
    const button = screen.getByRole("button", { name: "Mara" });
    const operations = vi.mocked(createContentSave<readonly Author[]>).mock.lastCall?.[0];
    if (operations === undefined) throw new Error("Content operations were not supplied");

    fireEvent.click(button);
    expect(refs.save.begin).toHaveBeenCalledWith(["Mara", "Cosmin"]);
    expect(refs.save.change).toHaveBeenCalledWith(["Cosmin"]);
    expect(refs.save.commit).toHaveBeenCalledOnce();
    expect(await operations.save(["Mara"])).toEqual(["Mara"]);
    expect(await operations.read(["Iacob"])).toEqual(["Cosmin"]);
    expect(operations.equal(["Cosmin", "Mara"], ["Mara", "Cosmin"])).toBe(true);
    expect(operations.equal(["Cosmin"], ["Mara"])).toBe(false);
    expect(operations.equal(["Cosmin"], ["Cosmin", "Mara"])).toBe(false);
    expect(operations.rebase?.(["Mara"], ["Cosmin"])).toEqual(["Mara"]);
    expect(operations.maybeAuthority?.()).toBe(7);
    operations.onCompleted?.(["Cosmin"], ["Mara"]);
    expect(onCompleted).toHaveBeenCalledWith(["Cosmin"], ["Mara"]);
    operations.onSaved(["Mara"], ["Cosmin"]);
    await new Promise((resolve) => {
      queueMicrotask(() => {
        resolve(undefined);
      });
    });
    expect(button).toHaveFocus();
    refs.setDraft({ original: ["Cosmin", "Mara"], value: ["Mara"] });
    operations.onSaved(["Mara"], ["Mara"]);
    await new Promise((resolve) => {
      queueMicrotask(() => {
        resolve(undefined);
      });
    });
    expect(screen.getByRole("group", { name: "Album authors" })).toHaveFocus();
    expect(screen.getByRole("button", { name: "Mara" })).toBeDisabled();
    expect(onBlock).toHaveBeenLastCalledWith("Resolve the author save before leaving.");
    const outside = screen.getByRole("button", { name: "Continue viewing" });
    outside.focus();
    operations.onSaved(["Mara"], ["Mara"]);
    await new Promise((resolve) => {
      queueMicrotask(() => {
        resolve(undefined);
      });
    });
    expect(outside).toHaveFocus();
  });

  it("should expose independent recovery, retry and pending states without implementing their save engine", () => {
    const refs = buildContentSave<readonly Author[]>();
    vi.mocked(createContentSave<readonly Author[]>).mockReturnValue(refs.save);
    refs.setError("Rejected authors");
    const onBlock = vi.fn();
    render(() => <AlbumAuthors authors={["Cosmin"]} save={vi.fn()} read={vi.fn()} unavailable={undefined} onBlock={onBlock} />);

    fireEvent.click(screen.getByRole("button", { name: "Retry authors" }));
    expect(refs.save.commit).toHaveBeenCalledWith(true);
    refs.setRecovery("uncertain");
    fireEvent.click(screen.getByRole("button", { name: "Read saved authors" }));
    expect(refs.save.reconcile).toHaveBeenCalledOnce();
    refs.setPending(true);
    refs.setUnavailable("Saving content. Please wait.");
    expect(screen.getByRole("button", { name: "Read saved authors" })).toBeDisabled();
    refs.setPending(false);
    expect(screen.getByRole("group", { name: "Album authors" })).toHaveFocus();
    vi.mocked(createContentSave<readonly Author[]>).mock.lastCall?.[0].onCompleted?.(["Cosmin"], ["Mara"]);
    cleanup();
    expect(onBlock).toHaveBeenLastCalledWith(undefined);
  });
});
