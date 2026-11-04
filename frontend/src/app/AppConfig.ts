export function readAppConfig(environment: Readonly<Record<string, string | boolean | undefined>>) {
  const historyLimit = Number(environment["VITE_BOOK_HISTORY_LIMIT"] ?? "10");
  if (!Number.isSafeInteger(historyLimit) || historyLimit <= 0) throw new Error("VITE_BOOK_HISTORY_LIMIT must be a positive safe integer.");
  return { historyLimit };
}
