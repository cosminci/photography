import { cleanup, fireEvent, render, screen } from "@solidjs/testing-library";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createSignal } from "solid-js";
import { TextEditor } from "../../../src/features/albums/workspace/TextEditor";
import { createContentSave } from "../../../src/features/albums/workspace/ContentSave";
import { buildContentSave } from "./ContentSaveStub";
import { useDialogs } from "./DialogStub";

vi.mock("../../../src/features/albums/workspace/ContentSave", () => ({ createContentSave: vi.fn() }));
useDialogs();
afterEach(cleanup);

describe("TextEditor", () => {
  it.each([{ singleLine: true }, { singleLine: false }])(
    "should edit singleLine=$singleLine and delegate completion, reads and focus",
    async ({ singleLine }) => {
      const refs = buildContentSave<string>();
      vi.mocked(createContentSave<string>).mockReturnValue(refs.save);
      const save = vi.fn().mockResolvedValue("Acknowledged");
      const read = vi.fn().mockResolvedValue("Saved read");
      const close = vi.fn();
      const completed = vi.fn();
      const returnFocus = vi.fn();
      render(() => (
        <TextEditor
          heading="Edit saved text"
          label="Text"
          text="Original"
          singleLine={singleLine}
          save={save}
          read={read}
          onClose={close}
          onCompleted={completed}
          onBlock={vi.fn()}
          returnFocus={returnFocus}
          ownership={{ maybeAuthority: 1, reason: "Unavailable", recoveryLabel: "Recover", pending: false, recover: vi.fn() }}
        />
      ));
      const input = screen.getByRole("textbox", { name: "Text" });
      const operations = vi.mocked(createContentSave<string>).mock.lastCall?.[0];
      if (operations === undefined) throw new Error("Content operations were not supplied");

      fireEvent.input(input, { target: { value: "Requested" } });
      fireEvent.submit(screen.getByRole("form", { name: "Edit saved text" }));
      expect(refs.save.begin).toHaveBeenCalledWith("Original");
      expect(refs.save.change).toHaveBeenCalledWith("Requested");
      expect(refs.save.commit).toHaveBeenCalledWith(true);
      expect(await operations.save("Requested")).toBe("Acknowledged");
      expect(await operations.read("Draft")).toBe("Saved read");
      expect(operations.equal("Same", "Same")).toBe(true);
      expect(operations.equal("Saved", "Different")).toBe(false);
      expect(operations.rebase?.("Saved", "Original")).toBe("Saved");
      expect(operations.maybeAuthority?.()).toBe(1);
      operations.onCompleted?.("Before", "After");
      operations.onSaved("Acknowledged", "Requested");
      expect(completed).toHaveBeenCalledWith("Before", "After");
      expect(close).toHaveBeenCalledOnce();
      cleanup();
      await Promise.resolve();
      expect(returnFocus).toHaveBeenCalledOnce();
    },
  );

  it("should validate before submission, preserve rejected text and distinguish uncertain readback from safe cancellation", () => {
    const refs = buildContentSave<string>();
    vi.mocked(createContentSave<string>).mockReturnValue(refs.save);
    const close = vi.fn();
    const [authority, setAuthority] = createSignal<number | undefined>(1);
    const recover = vi.fn();
    render(() => (
      <TextEditor
        heading="Edit"
        label="Text"
        text="Original"
        save={vi.fn()}
        read={vi.fn()}
        onClose={close}
        onBlock={vi.fn()}
        returnFocus={vi.fn()}
        validate={(text) => (text === "" ? "Required text" : undefined)}
        ownership={{ maybeAuthority: authority(), reason: "Retained draft", recoveryLabel: "Recover", pending: false, recover }}
      />
    ));
    const input = screen.getByRole("textbox", { name: "Text" });

    fireEvent.input(input, { target: { value: "" } });
    fireEvent.submit(screen.getByRole("form", { name: "Edit" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Required text");
    expect(refs.save.commit).not.toHaveBeenCalled();
    fireEvent.input(input, { target: { value: "Corrected" } });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    refs.setPending(true);
    refs.setError("Save failed");
    refs.setPending(false);
    expect(input).toHaveFocus();
    refs.setPending(true);
    refs.setRecovery("uncertain");
    refs.setUnavailable("Read saved content before continuing.");
    refs.setPending(false);
    expect(screen.getByText("Save failed").parentElement).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "Read saved content" }));
    expect(refs.save.reconcile).toHaveBeenCalledOnce();
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    expect(close).not.toHaveBeenCalled();
    setAuthority(undefined);
    fireEvent.click(screen.getByRole("button", { name: "Recover" }));
    expect(recover).toHaveBeenCalledOnce();
    refs.setUnavailable(undefined);
    refs.setRecovery(undefined);
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    expect(refs.save.cancel).toHaveBeenCalledOnce();
    expect(close).toHaveBeenCalledOnce();
    vi.mocked(createContentSave<string>).mock.lastCall?.[0].onCompleted?.("Before", "After");
  });
});
