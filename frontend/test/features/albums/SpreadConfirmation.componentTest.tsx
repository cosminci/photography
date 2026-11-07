import { cleanup, fireEvent, render, screen } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SpreadConfirmation } from "../../../src/features/albums/workspace/SpreadConfirmation";
import { useDialogs } from "./DialogStub";

useDialogs();
afterEach(cleanup);
describe("SpreadConfirmation", () => {
  it.each([{ deleting: true }, { deleting: false }])("should explain deleting=$deleting and protect an in-flight confirmation", ({ deleting }) => {
    const [pending, setPending] = createSignal(false);
    const [error, setError] = createSignal<string>();
    const confirm = vi.fn();
    const cancel = vi.fn();
    render(() => <SpreadConfirmation placements={3} deleting={deleting} pending={pending()} error={error()} onConfirm={confirm} onCancel={cancel} />);
    const dialog = screen.getByRole("alertdialog");

    expect(dialog).toHaveTextContent("This spread contains 3 placements");
    expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "Confirm" }));
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    fireEvent(dialog, new Event("cancel", { cancelable: true }));
    expect(confirm).toHaveBeenCalledOnce();
    expect(cancel).toHaveBeenCalledTimes(2);
    setPending(true);
    fireEvent(dialog, new Event("cancel", { cancelable: true }));
    expect(cancel).toHaveBeenCalledTimes(2);
    expect(screen.getByRole("button", { name: "Confirm" })).toBeDisabled();
    setError("Confirmation failed");
    expect(screen.getByRole("alert")).toHaveTextContent("Confirmation failed");
    setPending(false);
    expect(screen.getByRole("button", { name: "Retry confirmation" })).toBeEnabled();
  });
});
