import type { Spread } from "../model/Workspace";

export type Selection = { kind: "cover" } | { kind: "spread"; spread: Spread } | { kind: "invalid" };

export function findSelection(spreads: readonly Spread[], part: string | null): Selection {
  if (part === "cover") return { kind: "cover" };
  const spread =
    part === null
      ? spreads.find((candidate) => candidate.kind === "album_start")
      : spreads.find((candidate) => candidate.id === part || (candidate.chapter === part && candidate.kind === "chapter_start"));
  return spread === undefined ? { kind: "invalid" } : { kind: "spread", spread };
}

export function findReaderNavigation(
  spreads: readonly Spread[],
  selection: Selection,
): { previous: string | null | undefined; next: string | null | undefined } {
  if (selection.kind === "invalid") return { previous: undefined, next: undefined };
  const parts = ["cover", ...spreads.map((spread) => (spread.kind === "album_start" ? null : spread.id))];
  const index = selection.kind === "cover" ? 0 : spreads.indexOf(selection.spread) + 1;
  return { previous: parts[index - 1], next: parts[index + 1] };
}

export function findSpreadNavigation(spreads: readonly Spread[], selection: Selection) {
  const index = selection.kind === "spread" ? spreads.indexOf(selection.spread) : -1;
  const parts = spreads.map(getSpreadPart);
  const canPrevious = index > 0;
  const canNext = index >= 0 && index < parts.length - 1;
  return {
    position: index < 0 ? undefined : index + 1,
    total: parts.length,
    first: canPrevious ? parts[0] : undefined,
    previous: canPrevious ? parts[index - 1] : undefined,
    next: canNext ? parts[index + 1] : undefined,
    last: canNext ? parts.at(-1) : undefined,
  };
}

export function findDeletionSelection(spreads: readonly Pick<Spread, "id" | "kind" | "chapter">[], index: number): string | null {
  const spread = spreads[Math.min(index, spreads.length - 1)];
  if (spread === undefined) throw new Error("Saved composition has no selectable spread");
  return getSpreadPart(spread);
}

export function findSpreadPosition(spreads: readonly Spread[], selected: Spread) {
  const chapterSpreads = spreads.filter((spread) => spread.chapter === selected.chapter);
  return {
    chapter: { position: chapterSpreads.indexOf(selected) + 1, total: chapterSpreads.length },
    album: { position: spreads.indexOf(selected) + 1, total: spreads.length },
  };
}

function getSpreadPart(spread: Pick<Spread, "id" | "kind" | "chapter">): string | null {
  return spread.kind === "album_start" ? null : spread.kind === "chapter_start" ? spread.chapter : spread.id;
}
export function buildChapterReorder(spreads: readonly Spread[], spreadId: string, direction: "earlier" | "later") {
  const target = spreads.find((spread) => spread.id === spreadId);
  if (target === undefined || target.chapter === null) throw new Error("This spread cannot be moved");
  const expectedSpreadIds = spreads.filter((spread) => spread.chapter === target.chapter).map((spread) => spread.id);
  const spreadIds = [...expectedSpreadIds];
  const index = spreadIds.indexOf(spreadId);
  const neighbor = index + (direction === "earlier" ? -1 : 1);
  const adjacent = spreadIds.at(neighbor);
  if (index < 1 || neighbor < 1 || adjacent === undefined) throw new Error("No neighboring photo spread");
  spreadIds[index] = adjacent;
  spreadIds[neighbor] = spreadId;
  return { chapter: target.chapter, expectedSpreadIds, spreadIds };
}
