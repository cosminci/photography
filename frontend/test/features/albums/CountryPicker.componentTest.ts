import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { CountryPicker, searchCountries } from "../../../src/features/albums/workspace/CountryPicker";

const nativeScroll = Object.getOwnPropertyDescriptor(Element.prototype, "scrollIntoView");
beforeAll(() => {
  Object.defineProperty(Element.prototype, "scrollIntoView", { configurable: true, value: vi.fn() });
});
afterAll(() => {
  if (nativeScroll === undefined) Reflect.deleteProperty(Element.prototype, "scrollIntoView");
  else Object.defineProperty(Element.prototype, "scrollIntoView", nativeScroll);
});
afterEach(cleanup);

describe("CountryPicker", () => {
  it("should offer keyboard and pointer country selection without taking ownership of the supplied selection", async () => {
    const countries = [
      { id: "ro", name: "Romania", code: "RO" },
      { id: "jp", name: "Japan", code: "JP" },
    ];
    const [selected, setSelected] = createSignal<readonly string[]>([]);
    const onChange = vi.fn();
    render(() =>
      CountryPicker({
        countries,
        get selected() {
          return selected();
        },
        disabled: false,
        onChange,
      }),
    );
    const input = screen.getByRole("combobox", { name: "Search countries" });

    fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChange).toHaveBeenLastCalledWith(["jp"]);
    fireEvent.keyDown(input, { key: "ArrowUp" });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChange).toHaveBeenLastCalledWith(["ro"]);
    fireEvent.keyDown(input, { key: "Escape" });
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    fireEvent.keyDown(input, { key: "Escape" });
    fireEvent.keyDown(input, { key: "Enter" });
    input.focus();
    const option = screen.getByRole("option", { name: /Japan/ });
    fireEvent.mouseDown(option);
    fireEvent.click(option);
    expect(onChange).toHaveBeenLastCalledWith(["jp"]);
    setSelected(["jp"]);
    fireEvent.click(screen.getByRole("button", { name: "Remove Japan" }));
    expect(onChange).toHaveBeenLastCalledWith([]);
    expect(input).toHaveFocus();
    fireEvent.blur(input, { relatedTarget: option });
    expect(screen.getByRole("listbox")).toBeInTheDocument();
    fireEvent.blur(input, { relatedTarget: document.body });
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    fireEvent.input(input, { target: { value: "Atlantis" } });
    expect(screen.getByRole("status")).toHaveTextContent("No countries match");
    fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "Enter" });
    fireEvent.blur(input);
    await Promise.resolve();
    expect(onChange).toHaveBeenCalledTimes(4);
  });

  it("should search all supplied countries by name or code, including no matches", () => {
    const countries = [
      { id: "ro", name: "Romania", code: "RO" },
      { id: "jp", name: "Japan", code: "JP" },
    ];

    expect(searchCountries(countries, " jp ")).toEqual([countries[1]]);
    expect(searchCountries(countries, "ROM")).toEqual([countries[0]]);
    expect(searchCountries(countries, "")).toEqual(countries);
    expect(searchCountries(countries, "Atlantis")).toEqual([]);
  });
});
