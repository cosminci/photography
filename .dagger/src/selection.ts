import type { Component, Selection } from "./domain";
import { ALL_COMPONENTS } from "./domain";

/** Split a comma/newline-separated `git diff --name-only` payload into clean paths. */
export function parseChangedPaths(raw: string): readonly string[] {
  return raw
    .split(/[\n,]/)
    .map((line) => line.trim())
    .filter((line) => line !== "");
}

export function findComponentsForPath(path: string): readonly Component[] {
  if (
    path === "dagger.json" ||
    path === ".mise.toml" ||
    path === "scripts/verify" ||
    path.startsWith(".dagger/") ||
    path.startsWith(".githooks/") ||
    path.startsWith(".github/") ||
    path.startsWith("containers/") ||
    path.startsWith("unraid/")
  )
    return ALL_COMPONENTS;
  if (path.startsWith("backend/")) return ["backend"];
  if (path.startsWith("frontend/")) return ["frontend"];
  if (path.startsWith("scripts/indesign/")) return ["frontend"];
  if (path.startsWith("scripts/") || path.startsWith(".agents/skills/sdd/scripts/"))
    return ["pipeline"];
  return [];
}

export function selectAffected(changedPaths: readonly string[]): Selection {
  const affected = new Set<Component>();
  for (const path of changedPaths) {
    for (const component of findComponentsForPath(path)) {
      affected.add(component);
    }
  }
  if (affected.size === 0) return { kind: "none" };
  if (affected.size === ALL_COMPONENTS.length) return { kind: "all" };
  return {
    kind: "some",
    components: ALL_COMPONENTS.filter((component) => affected.has(component)),
  };
}

export function listSelectedComponents(selection: Selection): readonly Component[] {
  switch (selection.kind) {
    case "none":
      return [];
    case "all":
      return ALL_COMPONENTS;
    case "some":
      return selection.components;
  }
}
