import { describe, expect, it } from "vitest";
import { readAppConfig } from "../../src/app/AppConfig";

describe("application configuration", () => {
  it.each([
    { environment: {}, historyLimit: 10 },
    { environment: { VITE_BOOK_HISTORY_LIMIT: "1" }, historyLimit: 1 },
    { environment: { VITE_BOOK_HISTORY_LIMIT: "25" }, historyLimit: 25 },
  ])("should configure a history limit of $historyLimit", ({ environment, historyLimit }) => {
    expect(readAppConfig(environment)).toEqual({ historyLimit });
  });

  it.each(["", "0", "-1", "1.5", "no", "Infinity", "9007199254740992"])("should reject invalid history limit %s", (value) => {
    expect(() => readAppConfig({ VITE_BOOK_HISTORY_LIMIT: value })).toThrow("VITE_BOOK_HISTORY_LIMIT must be a positive safe integer.");
  });
});
