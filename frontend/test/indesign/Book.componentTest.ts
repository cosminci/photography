import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { createContext, runInContext } from "node:vm";
import { describe, expect, it } from "vitest";

describe("shipped InDesign book logic", () => {
  function evaluate(expression: string): unknown {
    const context = createContext({});
    runInContext(readFileSync(resolve(import.meta.dirname, "../../../scripts/indesign/crop.js"), "utf8"), context);
    runInContext(readFileSync(resolve(import.meta.dirname, "../../../scripts/indesign/gear.js"), "utf8"), context);
    runInContext(readFileSync(resolve(import.meta.dirname, "../../../scripts/indesign/book.jsxinc"), "utf8"), context);
    const result: unknown = runInContext(expression, context);
    return result;
  }

  it.each([
    { title: "Journey of the Shepherds 2025", expected: "Journey of the Shepherds" },
    { title: "Journey of the Shepherds", expected: "Journey of the Shepherds" },
    { title: "Remembering 2024", expected: "Remembering 2024" },
    { title: "2025", expected: "2025" },
  ])("should separate the existing year suffix from the printed cover title: $title", ({ title, expected }) => {
    expect(evaluate(`PhotographyBook.coverTitle({title:${JSON.stringify(title)},year:2025})`)).toEqual(expected);
  });

  it.each([
    { font: "InkFree", input: "Țară și știință: ă â î ș ț Ș Ț", expected: "Tara si stiinta: a a i s t S T" },
    { font: "IDDragonXing", input: "Crème brûlée, Åland, Łódź, S\u0326", expected: "Creme brulee, Aland, Lodz, S" },
    { font: "GaramondPremrPro", input: "Țară și știință — 日本", expected: "Țară și știință — 日本" },
    { font: "InkFree", input: "A\u0306 — 日本", expected: "A — 日本" },
    { font: "IDDragonXing", input: "Hội An, Ḃrăila, Ơ Ư ơ ư", expected: "Hoi An, Braila, O U o u" },
  ])("should apply approved diacritic policy for $font without dropping other symbols", ({ font, input, expected }) => {
    expect(evaluate(`PhotographyBook.printText(${JSON.stringify(input)},${JSON.stringify(font)})`)).toEqual(expected);
  });

  it.each([
    { mirrored: false, x: 20, expected: { left: -30, top: -25, width: 200, height: 100 } },
    { mirrored: true, x: 20, expected: { left: -70, top: -25, width: 200, height: 100 } },
    { mirrored: false, x: 500, expected: { left: 0, top: -25, width: 200, height: 100 } },
    { mirrored: true, x: 500, expected: { left: -100, top: -25, width: 200, height: 100 } },
  ])("should preserve saved point shifts and mirror crop for $mirrored / $x", ({ mirrored, x, expected }) => {
    expect(
      evaluate(`PhotographyBook.crop({width:200,height:100},{width:100,height:50},{xPt:${String(x)},yPt:0,zoom:2,mirrored:${String(mirrored)}})`),
    ).toEqual(expected);
  });

  it.each([
    { root: "/mnt/photos", source: "/mnt/photos/2025/coast", local: "/Volumes/Travel", expected: "/Volumes/Travel/2025/coast/same.jpg" },
    { root: "/mnt/photos/2025/coast", source: "/mnt/photos/2025/coast", local: "/Volumes/coast", expected: "/Volumes/coast/same.jpg" },
  ])("should map only exact source roots: $source", ({ root, source, local, expected }) => {
    expect(evaluate(`PhotographyBook.mapOriginal(${JSON.stringify(source)},"same.jpg",${JSON.stringify(root)},${JSON.stringify(local)})`)).toEqual(
      expected,
    );
  });

  it.each([
    { source: "/mnt/photos-other/coast", filename: "same.jpg" },
    { source: "/mnt/photos/../coast", filename: "same.jpg" },
    { source: "/mnt/photos/coast", filename: "../same.jpg" },
    { source: "/mnt/photos/coast", filename: "nested/same.jpg" },
  ])("should reject ambiguous or traversing original location $source / $filename", ({ source, filename }) => {
    expect(() =>
      evaluate(`PhotographyBook.mapOriginal(${JSON.stringify(source)},${JSON.stringify(filename)},"/mnt/photos","/Volumes/Travel")`),
    ).toThrow("Invalid original source mapping");
  });

  it("should reject JavaScript disguised as JSON without executing it", () => {
    expect(() => evaluate('PhotographyBook.parse("{\\"formatVersion\\":(function(){throw new Error(\\"executed\\")})()}")')).toThrow(
      "Invalid print JSON",
    );
  });

  it("should decode plain JSON without inheriting legacy eval behavior", () => {
    expect(evaluate('PhotographyBook.parse("{\\"title\\":\\"Țară\\",\\"values\\":[true,null,4.2]}")')).toEqual({
      title: "Țară",
      values: [true, null, 4.2],
    });
  });

  it.each([
    "",
    "[1,]",
    '{"x":1,}',
    '{"x":1,"x":2}',
    '{"__proto__":{}}',
    '{"constructor":{}}',
    '{"prototype":0}',
    "01",
    "1e999",
    "true false",
    '"bad\nstring"',
    '"\\q"',
    '{"x":[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[0]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]}',
  ])("should reject malformed or unsafe JSON %j", (source) => {
    expect(() => evaluate(`PhotographyBook.parse(${JSON.stringify(source)})`)).toThrow("Invalid print JSON");
  });

  it("should validate the complete single book before native placement", () => {
    const book = nativeBook();
    expect(evaluate(`PhotographyBook.verify(${JSON.stringify(book)})`)).toEqual(book);
  });

  it.each([
    ["unsupported format", "book.formatVersion=2"],
    ["non-array spreads", "book.interior.spreads={}"],
    ["page-count mismatch", "book.interior.pageCount=8"],
    ["missing original", "book.sources=[]"],
    ["duplicate identity", "book.sources.push(book.sources[0])"],
    ["invalid dimensions", "book.sources[0].width=0"],
    ["non-boolean reflection", "book.cover.placements[0].transform.mirrored='false'"],
    ["empty cover slot", "book.cover.placements.pop()"],
    ["unknown cover slot", "book.cover.placements[0].slot='missing'"],
    ["invalid caption corner", "book.interior.spreads[2].placements[0].captionCorner='left'"],
    ["empty interior slot", "book.interior.spreads[2].placements=[]"],
    ["invalid physical frame", "book.interior.spreads[2].slots[0].frame.widthPt=-1"],
    ["missing chapter owner", "book.chapters=[]"],
    ["invalid gear identity", "book.chapters[0].gear=[{camera:'missing',lenses:[]}]"],
  ])("should reject %s before changing a native document", (_label, mutation) => {
    expect(() => evaluate(`var book=${JSON.stringify(nativeBook())};${mutation};PhotographyBook.verify(book)`)).toThrow("Invalid print book");
  });

  it("should retain the proven synopsis and jingle paragraph spacing", () => {
    expect(evaluate('PhotographyBook.parts("A paragraph\\n\\nTwo lines\\nRhyming lines", "synopsis")')).toEqual([
      { text: "Synopsis", role: "synopsisTitle" },
      { text: "", role: "synopsisBody" },
      { text: "", role: "synopsisBody" },
      { text: "A paragraph", role: "synopsisBody" },
      { text: "", role: "synopsisBody" },
      { text: "", role: "synopsisBody" },
      { text: "Two lines", role: "synopsisBody" },
      { text: "Rhyming lines", role: "synopsisBody" },
    ]);
  });

  it("should retain chapter body diacritics and separate its print date and authoritative selected gear", () => {
    expect(evaluate(`PhotographyBook.chapterText(${JSON.stringify(nativeBook().chapters[0])}, {cameras:[],lenses:[]})`)).toEqual(
      "Țară\n\nȘ description\n\nJanuary 2025",
    );
    expect(evaluate('PhotographyBook.parts("Title\\n\\nBody\\n\\nJanuary 2025\\nCamera", "chapter")')).toEqual([
      { text: "Title", role: "title" },
      { text: "", role: "body" },
      { text: "Body", role: "body" },
      { text: "", role: "body" },
      { text: "", role: "body" },
      { text: "January 2025", role: "gear" },
      { text: "Camera", role: "gear" },
    ]);
  });
  function nativeBook() {
    const frame = { xPt: 0, yPt: 0, widthPt: 100, heightPt: 100 };
    const transform = { xPt: 12, yPt: -8, zoom: 1.5, mirrored: true };
    return {
      formatVersion: 1,
      album: { id: "album", title: "Țară", year: 2025, synopsis: "Ș synopsis", authors: ["Cosmin", "Mara"] },
      sources: [{ id: "photo", source: "/NAS/coast", filename: "same.jpg", hash: "hash", width: 4000, height: 3000, caption: "Ș caption" }],
      interior: {
        widthPt: 2381.102,
        heightPt: 765.354,
        pageCount: 6,
        spreads: ["synopsis", "chapter_opening", "photos"].map((kind, index) => ({
          id: `spread-${String(index)}`,
          kind,
          textFrame: kind === "photos" ? null : frame,
          slots: kind === "photos" ? [{ id: "slot", frame, captionFrame: frame }] : [],
          placements: kind === "photos" ? [{ slot: "slot", photo: "photo", transform, captionCorner: "bottom_left" }] : [],
        })),
      },
      chapters: [
        {
          id: "chapter",
          name: "Țară",
          description: "Ș description",
          opening: "spread-1",
          firstDay: "2025-01-01",
          days: 1,
          countries: [{ code: "RO", name: "Romania", flag: "/flags/ro.png" }],
          gear: [],
        },
      ],
      cover: {
        widthPt: 2520,
        heightPt: 816,
        spineStartPt: 1224,
        spineWidthPt: 72,
        spineOwner: "front",
        frontSlot: "front",
        backSlot: "back",
        frontFrame: frame,
        backFrame: frame,
        placements: ["front", "back"].map((slot) => ({ slot, photo: "photo", transform })),
      },
      gear: { cameras: [], lenses: [] },
      warnings: [],
    };
  }

  it("should consume the exported selected camera and lens identities through the shared presentation owner", () => {
    const book = nativeBook();
    const expression = `var book=${JSON.stringify(book)};
      book.gear={cameras:[{id:"camera",name:"Sony α6000"}],lenses:[{id:"lens",name:"Sigma 30mm f/2.8 DN Art"}]};
      book.chapters[0].gear=[{cameraId:"camera",lensIds:["lens"]}];
      PhotographyBook.verify(book); PhotographyBook.chapterText(book.chapters[0],book.gear);`;
    expect(evaluate(expression)).toEqual("Țară\n\nȘ description\n\nJanuary 2025\nSony α6000 w/ Sigma 30mm f/2.8 DN Art");
  });
});
