import { fireEvent, render, screen } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { EditorSessionGate } from "../../../src/features/editing/EditorSessionGate";

const nativeMethods = {
  showModal: Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "showModal"),
  close: Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "close"),
};
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
    this.querySelector<HTMLElement>("input, button")?.focus();
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
  };
});
afterAll(() => {
  for (const [name, descriptor] of Object.entries(nativeMethods)) {
    if (descriptor === undefined) Reflect.deleteProperty(HTMLDialogElement.prototype, name);
    else Object.defineProperty(HTMLDialogElement.prototype, name, descriptor);
  }
});

describe("browser identification and tab gate", () => {
  it("should identify the profile before exposing any application content and retain the application through connection loss", () => {
    const [opened, setOpened] = createSignal(false);
    const [blocked, setBlocked] = createSignal(false);
    const [message, setMessage] = createSignal<string>();
    const [connecting, setConnecting] = createSignal(false);
    const reconnect = vi.fn().mockResolvedValue(undefined);
    const available = () => opened() && !blocked() && message() === undefined;
    const session = { opened, blocked, available, message, connecting, reconnect };
    render(() => (
      <EditorSessionGate session={session}>
        <main>
          <textarea aria-label="Retained draft" />
        </main>
      </EditorSessionGate>
    ));

    expect(screen.queryByRole("main")).not.toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    setOpened(true);
    setMessage(undefined);
    const draft = screen.getByRole("textbox", { name: "Retained draft" });
    fireEvent.input(draft, { target: { value: "Unsaved" } });
    setMessage("Connection lost.");
    setConnecting(true);
    expect(fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }))).toBe(false);
    expect(screen.getByRole("button", { name: "Reconnect browser" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Reconnect browser" }));
    expect(screen.getByRole("textbox", { name: "Retained draft" })).toHaveValue("Unsaved");
    setConnecting(false);
    fireEvent.click(screen.getByRole("button", { name: "Reconnect browser" }));
    expect(reconnect).toHaveBeenCalledTimes(2);
    setBlocked(true);
    expect(screen.queryByRole("main")).not.toBeInTheDocument();
    setBlocked(false);
    expect(screen.getByRole("textbox", { name: "Retained draft" })).toHaveValue("Unsaved");
    setMessage(undefined);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(draft).toHaveValue("Unsaved");
  });
});
