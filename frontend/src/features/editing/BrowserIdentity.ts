export function suggestBrowserAlias(identity: string): string {
  const adjectives = [
    "Nasty",
    "Sleepy",
    "Dancing",
    "Sneaky",
    "Fancy",
    "Wobbly",
    "Cosmic",
    "Cheeky",
    "Grumpy",
    "Bouncy",
    "Dizzy",
    "Nimble",
    "Silly",
    "Fuzzy",
    "Happy",
    "Mighty",
  ];
  const animals = [
    "rabbit",
    "otter",
    "panda",
    "badger",
    "penguin",
    "fox",
    "wombat",
    "goose",
    "koala",
    "raccoon",
    "lemur",
    "puffin",
    "hedgehog",
    "llama",
    "walrus",
    "squirrel",
  ];
  const maybeAdjective = adjectives[Number.parseInt(identity.slice(0, 1), 16)];
  const maybeAnimal = animals[Number.parseInt(identity.slice(1, 2), 16)];
  if (maybeAdjective === undefined || maybeAnimal === undefined) throw new Error("Cannot name an invalid browser identity.");
  return `${maybeAdjective} ${maybeAnimal}`;
}

export function createBrowserIdentity(): string {
  const bytes = crypto
    .getRandomValues(new Uint8Array(16))
    .map((byte, index) => (index === 6 ? (byte & 0x0f) | 0x40 : index === 8 ? (byte & 0x3f) | 0x80 : byte));
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function readBrowserIdentity(factory: IDBFactory, generate: () => string): Promise<string> {
  return new Promise((resolve, reject) => {
    const opening = factory.open("photography.editor", 1);
    opening.onerror = () => {
      reject(new Error("Browser identification storage could not be opened."));
    };
    opening.onupgradeneeded = () => {
      opening.result.createObjectStore("identity");
    };
    opening.onsuccess = () => {
      const database = opening.result;
      try {
        const transaction = database.transaction("identity", "readwrite");
        const store = transaction.objectStore("identity");
        const reading = store.get("browser");
        let identity: string;
        transaction.onabort = () => {
          database.close();
          reject(new Error("Browser identification could not be retained."));
        };
        transaction.oncomplete = () => {
          database.close();
          resolve(identity);
        };
        reading.onsuccess = () => {
          try {
            const saved: unknown = reading.result;
            if (saved === undefined) {
              identity = generate();
              store.put(identity, "browser");
            } else if (typeof saved === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(saved)) {
              identity = saved;
            } else transaction.abort();
          } catch (failure) {
            transaction.abort();
            reject(new Error("Browser identification could not be created.", { cause: failure }));
          }
        };
      } catch (failure) {
        database.close();
        reject(new Error("Browser identification storage is invalid.", { cause: failure }));
      }
    };
  });
}
