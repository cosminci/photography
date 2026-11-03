import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import { BrowserAlias } from "../../../src/features/editing/BrowserAlias";

describe("browser name control", () => {
  it("should edit inline, retain failed input, persist accepted names and restore pencil focus", async () => {
    const [alias, setAlias] = createSignal("Nasty rabbit");
    let finish!: () => void;
    const rename = vi.fn<(value: string) => Promise<void>>().mockImplementationOnce(async () => {
      await new Promise<void>((resolve) => {
        finish = resolve;
      });
      setAlias("Mara");
    });
    render(() => <BrowserAlias session={{ alias, rename }} />);
    const pencil = screen.getByRole("button", { name: "Edit your name" });
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    fireEvent.click(pencil);
    const input = screen.getByRole("textbox", { name: "Your name" });
    expect(input).toHaveFocus();
    expect(input).toHaveValue("Nasty rabbit");
    fireEvent.input(input, { target: { value: "  " } });
    fireEvent.submit(screen.getByRole("form"));
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a name.");
    expect(rename).not.toHaveBeenCalled();
    fireEvent.input(input, { target: { value: " Mara " } });
    fireEvent.submit(screen.getByRole("form"));
    expect(input).toBeDisabled();
    fireEvent.keyDown(input, { key: "Escape" });
    expect(input).toBeInTheDocument();
    finish();
    await waitFor(() => {
      expect(screen.getByText("Mara")).toBeVisible();
    });
    expect(rename).toHaveBeenCalledExactlyOnceWith("Mara");
    expect(pencil).toHaveFocus();
    fireEvent.click(pencil);
    fireEvent.input(screen.getByRole("textbox"), { target: { value: "Discard me" } });
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "a" });
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Escape" });
    expect(pencil).toHaveFocus();
    expect(screen.getByText("Mara")).toBeVisible();
    fireEvent.click(pencil);
    fireEvent.click(screen.getByRole("button", { name: "Cancel name change" }));
    expect(pencil).toHaveFocus();
  });

  it.each([new Error("Backend unavailable"), "unknown"])("should preserve the name draft after failure %s", async (failure) => {
    const rename = vi.fn().mockRejectedValue(failure);
    render(() => <BrowserAlias session={{ alias: () => "Nasty rabbit", rename }} />);
    fireEvent.click(screen.getByRole("button", { name: "Edit your name" }));
    fireEvent.input(screen.getByRole("textbox"), { target: { value: "Mara" } });
    fireEvent.submit(screen.getByRole("form"));
    await screen.findByRole("alert");
    expect(screen.getByRole("textbox")).toHaveValue("Mara");
    expect(screen.getByRole("textbox")).toHaveAccessibleDescription(failure instanceof Error ? failure.message : "Could not save your name.");
    expect(screen.getByRole("button", { name: "Save name" })).toBeEnabled();
  });
});
