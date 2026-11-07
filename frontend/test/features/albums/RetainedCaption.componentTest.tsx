import { cleanup, fireEvent, render, screen } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RetainedCaption } from "../../../src/features/albums/workspace/RetainedCaption";
import { buildContentSave } from "./ContentSaveStub";

afterEach(cleanup);
describe("RetainedCaption", () => {
  it.each([{ resolved: true }, { resolved: false }])(
    "should route retained commands and hand focus back according to resolved=$resolved",
    ({ resolved }) => {
      const refs = buildContentSave<string>();
      refs.setDraft({ original: "Saved", value: "Retained" });
      const [requested, setRequested] = createSignal<string>();
      const focus = { requested, request: vi.fn(), clear: vi.fn() };
      const returnFocus = vi.fn();
      const view = render(() => <RetainedCaption caption={{ photoId: "photo", save: refs.save }} focus={focus} returnFocus={returnFocus} />);
      setRequested("photo");

      expect(screen.getByRole("alert")).toHaveFocus();
      expect(focus.clear).toHaveBeenCalledOnce();
      fireEvent.click(screen.getByRole("button", { name: "Save retained caption" }));
      fireEvent.click(screen.getByRole("button", { name: "Cancel retained caption" }));
      expect(refs.save.commit).toHaveBeenCalledExactlyOnceWith(true);
      expect(refs.save.cancel).toHaveBeenCalledOnce();
      refs.setRecovery("uncertain");
      refs.setError("Outcome uncertain");
      refs.setUnavailable("Read saved content before continuing.");
      refs.setPending(true);
      expect(screen.getByRole("button", { name: "Read retained caption" })).toBeDisabled();
      expect(screen.getByRole("status")).toHaveTextContent("Read saved content before continuing.");
      refs.setPending(false);
      refs.setUnavailable(undefined);
      fireEvent.click(screen.getByRole("button", { name: "Read retained caption" }));
      if (resolved) refs.setDraft(undefined);
      screen.getByRole("alert").focus();
      view.unmount();

      expect(returnFocus).toHaveBeenCalledTimes(resolved ? 1 : 0);
      expect(focus.request).toHaveBeenCalledTimes(resolved ? 0 : 1);
    },
  );

  it("should leave unrelated focus untouched when retained feedback is disposed", () => {
    const refs = buildContentSave<string>();
    const focus = { requested: () => undefined, request: vi.fn(), clear: vi.fn() };
    const returnFocus = vi.fn();
    const view = render(() => <RetainedCaption caption={{ photoId: "photo", save: refs.save }} focus={focus} returnFocus={returnFocus} />);

    view.unmount();

    expect(returnFocus).not.toHaveBeenCalled();
    expect(focus.request).not.toHaveBeenCalled();
  });
});
