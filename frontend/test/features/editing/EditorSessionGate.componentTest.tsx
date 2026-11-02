import { fireEvent, render, screen } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { EditorSessionGate } from "../../../src/features/editing/EditorSessionGate";

describe("browser identification and tab gate", () => {
  it("should identify the profile before exposing any application content and retain the application through connection loss", () => {
    const [opened, setOpened] = createSignal(false);
    const [blocked, setBlocked] = createSignal(false);
    const [message, setMessage] = createSignal<string | undefined>("Identify this browser.");
    const [identifying, setIdentifying] = createSignal(true);
    const [connecting, setConnecting] = createSignal(false);
    const identify = vi.fn().mockResolvedValue(undefined);
    const reconnect = vi.fn().mockResolvedValue(undefined);
    const session = { opened, blocked, message, identifying, connecting, identify, reconnect };
    render(() => (
      <EditorSessionGate session={session}>
        <main>
          <textarea aria-label="Retained draft" />
        </main>
      </EditorSessionGate>
    ));

    expect(screen.queryByRole("main")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Identify this browser.");
    const input = screen.getByRole("textbox", { name: "Identification alias" });
    expect(input).toHaveAccessibleDescription(/not a login/);
    fireEvent.input(input, { target: { value: "Mara" } });
    fireEvent.submit(screen.getByRole("form", { name: "Browser identification" }));
    expect(identify).toHaveBeenCalledExactlyOnceWith("Mara");
    setIdentifying(false);
    setOpened(true);
    setMessage(undefined);
    const draft = screen.getByRole("textbox", { name: "Retained draft" });
    fireEvent.input(draft, { target: { value: "Unsaved" } });
    setMessage("Connection lost.");
    setConnecting(true);
    expect(screen.getByRole("button", { name: "Reconnect browser" })).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "Retained draft" })).toHaveValue("Unsaved");
    setConnecting(false);
    fireEvent.click(screen.getByRole("button", { name: "Reconnect browser" }));
    expect(reconnect).toHaveBeenCalledOnce();
    setBlocked(true);
    expect(screen.queryByRole("main")).not.toBeInTheDocument();
    setBlocked(false);
    expect(screen.getByRole("textbox", { name: "Retained draft" })).toHaveValue("Unsaved");
  });
});
