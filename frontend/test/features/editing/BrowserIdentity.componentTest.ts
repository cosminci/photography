import { IDBFactory } from "fake-indexeddb";
import { describe, expect, it, vi } from "vitest";
import { createBrowserIdentity, readBrowserIdentity } from "../../../src/features/editing/BrowserIdentity";

const identity = "00000000-0000-4000-8000-000000000001";

async function seed(factory: IDBFactory, value: unknown, version = 1, store = "identity") {
  await new Promise<void>((resolve, reject) => {
    const opening = factory.open("photography.editor", version);
    opening.onerror = () => {
      reject(new Error("Test database could not be seeded.", { cause: opening.error }));
    };
    opening.onupgradeneeded = () => {
      opening.result.createObjectStore(store);
    };
    opening.onsuccess = () => {
      const database = opening.result;
      const transaction = database.transaction(store, "readwrite");
      transaction.objectStore(store).put(value, "browser");
      transaction.oncomplete = () => {
        database.close();
        resolve();
      };
    };
  });
}

describe("remembered browser identity", () => {
  it("should allocate one durable identity for simultaneous first-run tabs", async () => {
    const factory = new IDBFactory();
    const generate = vi.fn(createBrowserIdentity);

    const identities = await Promise.all(Array.from({ length: 10 }, () => readBrowserIdentity(factory, generate)));

    expect(new Set(identities).size).toBe(1);
    expect(generate).toHaveBeenCalledTimes(1);
    expect(await readBrowserIdentity(factory, generate)).toBe(identities[0]);
    expect(generate).toHaveBeenCalledTimes(1);
  });

  it("should generate independent RFC 4122 identities on trusted-network HTTP without randomUUID", () => {
    const first = createBrowserIdentity();
    expect(first).toMatch(/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/);
    expect(createBrowserIdentity()).not.toBe(first);
  });

  it.each([null, {}, 1, "", "invalid"])("should reject invalid retained identity %s without replacing it", async (saved) => {
    const factory = new IDBFactory();
    await seed(factory, saved);
    const generate = vi.fn(() => identity);

    await expect(readBrowserIdentity(factory, generate)).rejects.toThrow("Browser identification could not be retained.");
    await expect(readBrowserIdentity(factory, generate)).rejects.toThrow("Browser identification could not be retained.");
    expect(generate).not.toHaveBeenCalled();
  });

  it("should report an incompatible persisted schema rather than leave application opening pending", async () => {
    const factory = new IDBFactory();
    await seed(factory, identity, 1, "other");

    await expect(readBrowserIdentity(factory, createBrowserIdentity)).rejects.toThrow("Browser identification storage is invalid.");
  });

  it("should report a storage opening failure", async () => {
    const factory = new IDBFactory();
    await seed(factory, identity, 2);

    await expect(readBrowserIdentity(factory, createBrowserIdentity)).rejects.toThrow("Browser identification storage could not be opened.");
  });

  it("should abort failed identity generation and allow a later successful retry", async () => {
    const factory = new IDBFactory();
    const failure = new Error("random source unavailable");

    await expect(
      readBrowserIdentity(factory, () => {
        throw failure;
      }),
    ).rejects.toMatchObject({ cause: failure });
    expect(await readBrowserIdentity(factory, () => identity)).toBe(identity);
  });
});
