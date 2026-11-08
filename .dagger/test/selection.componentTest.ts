import { describe, expect, it } from "vitest";
import * as Selection from "../src/selection";

describe("parseChangedPaths", () => {
  it("should split comma and newline separated paths and drop blanks", () => {
    const paths = Selection.parseChangedPaths("backend/a.scala,\nfrontend/index.html,  , ");

    expect(paths).toEqual(["backend/a.scala", "frontend/index.html"]);
  });
});

describe("findComponentsForPath", () => {
  it.each([
    ["backend/build.sbt", ["backend"]],
    ["frontend/index.html", ["frontend"]],
    ["scripts/indesign/crop.js", ["frontend"]],
    ["scripts/indesign/place-spreads.jsx", ["frontend"]],
  ] as const)("should select the owning component for %s", (path, components) => {
    expect(Selection.findComponentsForPath(path)).toEqual(components);
  });

  it("should map pipeline paths to the pipeline component", () => {
    const paths = ["scripts/local.py", ".agents/skills/sdd/scripts/check_markdown_wrapping.py"];

    for (const path of paths) {
      expect(Selection.findComponentsForPath(path), path).toEqual(["pipeline"]);
    }
  });

  it.each([
    ".dagger/src/hooks/backend.ts",
    ".dagger/src/hooks/frontend.ts",
    ".dagger/src/buildEnv.ts",
    ".dagger/src/index.ts",
    ".dagger/package-lock.json",
    "dagger.json",
    ".mise.toml",
    "scripts/verify",
    ".github/workflows/build.yml",
    ".github/workflows/verify-main.yml",
    ".githooks/pre-push",
    "unraid/album-layout-studio.xml",
    "containers/photography/Dockerfile",
  ])("should run every component when shared verification changes at %s", (path) => {
    expect(Selection.selectAffected([path])).toEqual({ kind: "all" });
  });

  it("should ignore documentation and unrelated paths", () => {
    expect(Selection.findComponentsForPath("README.md")).toEqual([]);
  });
});

describe("selectAffected", () => {
  it("should return none when no checked component changed", () => {
    expect(Selection.selectAffected(["README.md"])).toEqual({ kind: "none" });
  });

  it("should select only frontend for frontend and proposal changes", () => {
    const paths = ["frontend/index.html", "specs/changes/spread-lifecycle/proposal.md"];
    const expected = {
      kind: "some",
      components: ["frontend"],
    };

    expect(Selection.selectAffected(paths)).toEqual(expected);
  });

  it("should select both application components when both surfaces change", () => {
    const paths = ["frontend/index.html", "backend/build.sbt"];
    const expected = { kind: "some", components: ["backend", "frontend"] };

    expect(Selection.selectAffected(paths)).toEqual(expected);
  });

  it("should return all when every component changed", () => {
    const selection = Selection.selectAffected([
      "backend/build.sbt",
      "frontend/index.html",
      ".dagger/x.ts",
    ]);

    expect(selection).toEqual({ kind: "all" });
  });
});

describe("listSelectedComponents", () => {
  it("should expand each selection variant", () => {
    const none = Selection.listSelectedComponents({ kind: "none" });
    const all = Selection.listSelectedComponents({ kind: "all" });
    const some = Selection.listSelectedComponents({ kind: "some", components: ["backend"] });

    expect(none).toEqual([]);
    expect(all).toEqual(["backend", "frontend", "pipeline"]);
    expect(some).toEqual(["backend"]);
  });
});
