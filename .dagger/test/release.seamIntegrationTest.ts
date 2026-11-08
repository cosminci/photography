import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const workflow = readFileSync(
  new URL("../../.github/workflows/release.yml", import.meta.url),
  "utf8",
);
const version = "2026.11.8-T010203";
const mock = `#!/usr/bin/env node
const fs = require("node:fs");
const path = require("node:path");
const args = process.argv.slice(2);
const name = path.basename(process.argv[1]);
const root = process.env.RUNNER_TEMP;
const action = name + " " + args.join(" ");
fs.appendFileSync(path.join(root, "commands"), action.replaceAll(process.env.GITHUB_TOKEN, "<secret>") + "\\n");
if (process.env.FAIL && action.includes(process.env.FAIL)) process.exit(1);
const published = fs.existsSync(path.join(root, "published"));
if (name === "git" && args[0] === "show-ref") {
  process.exit(process.env.GIT_LOOKUP_ERROR ? 128 : process.env.DUPLICATE_GIT ? 0 : 1);
}
if (name === "dagger") {
  if (args[1] === "release-version") console.log("${version}");
  if (args[1] === "publish") fs.writeFileSync(path.join(root, "published"), "");
}
if (name === "curl") {
  const url = args.at(-1);
  if (url.includes("api.github.com")) {
    const body = JSON.stringify({visibility: process.env[published ? "POST_VISIBILITY" : "VISIBILITY"] || "public"});
    if (args.includes("-o")) {
      fs.writeFileSync(args[args.indexOf("-o") + 1], body);
      console.log(process.env.PACKAGE_STATUS || "200");
    } else console.log(body);
  } else if (url.endsWith("/token")) console.log('{"token":"fixture-registry-token"}');
  else if (url.includes("/manifests/")) {
    if (published) process.exit(process.env.ANONYMOUS_FAILURE ? 22 : 0);
    console.log(process.env.MANIFEST_STATUS || "404");
  }
}
if (name === "jq") {
  const file = args.at(-1);
  const body = JSON.parse(fs.readFileSync(fs.existsSync(file) ? file : 0, "utf8"));
  if (args.some(arg => arg.includes(".token"))) {
    if (!body.token) process.exit(1);
    console.log(body.token);
  } else {
    console.log(body.visibility === "public");
    process.exit(body.visibility === "public" ? 0 : 1);
  }
}
`;

function run(settings: Partial<Record<string, string>> = {}) {
  const path = process.env.PATH;
  if (path === undefined) throw new Error("Release test requires PATH");
  const root = mkdtempSync(join(tmpdir(), "photography-release-test-"));
  try {
    for (const name of ["npm", "dagger", "curl", "git", "jq"]) {
      writeFileSync(join(root, name), mock, { mode: 0o755 });
    }
    const steps = ["Require main", "Verify and publish"].map((name) => {
      const step = workflow.split(`      - name: ${name}\n`)[1]?.split("\n      - ")[0];
      const body = step?.split("        run: |\n")[1];
      expect(body, name).toBeDefined();
      if (body === undefined) throw new Error(`Missing workflow step: ${name}`);
      return body.replace(/^ {10}/gm, "");
    });
    const secret = "release-test-secret";
    const result = spawnSync("bash", ["-euo", "pipefail", "-c", steps.join("\n")], {
      cwd: root,
      encoding: "utf8",
      env: {
        ...process.env,
        PATH: root + ":" + path,
        RUNNER_TEMP: root,
        GITHUB_REF: "refs/heads/main",
        GITHUB_TOKEN: secret,
        GITHUB_ACTOR: "fixture",
        ...settings,
      },
    });
    if (result.error) throw result.error;
    const commands = existsSync(join(root, "commands"))
      ? readFileSync(join(root, "commands"), "utf8")
      : "";
    expect(result.stdout + result.stderr + commands).not.toContain(secret);
    return { ...result, commands };
  } finally {
    rmSync(root, { recursive: true });
  }
}

describe("release workflow", () => {
  it("requires main before running any command", () => {
    const result = run({ GITHUB_REF: "refs/heads/feature" });
    expect(result.status).not.toBe(0);
    expect(result.commands).toBe("");
  });

  it("verifies before publishing and tags the same immutable version", () => {
    const result = run();
    expect(result.status, result.stderr).toBe(0);
    const commands = [
      "npm ci",
      "dagger develop",
      "npm --prefix .dagger run verify",
      "dagger call verify --all",
      "dagger call release-version",
      `dagger call publish --tag=${version}`,
      "curl -fsS -o /dev/null -I",
      `tag -a v${version}`,
      `git push origin refs/tags/v${version}`,
    ];
    const positions = commands.map((command) => result.commands.indexOf(command));
    expect(positions.every((position) => position >= 0)).toBe(true);
    expect(positions).toEqual([...positions].sort((a, b) => a - b));
    expect(workflow).not.toContain(":latest");
    expect(workflow).toContain("permissions: {}");
    expect(workflow).toContain("persist-credentials: false");
  });

  it.each([
    { FAIL: "npm ci" },
    { FAIL: "dagger develop" },
    { FAIL: "npm --prefix .dagger run verify" },
    { FAIL: "dagger call verify --all" },
    { DUPLICATE_GIT: "true" },
    { GIT_LOOKUP_ERROR: "true" },
    { MANIFEST_STATUS: "200" },
    { MANIFEST_STATUS: "500" },
    { PACKAGE_STATUS: "403" },
    { VISIBILITY: "private" },
  ])("blocks publication when verification or existing state is invalid: %j", (settings) => {
    const result = run(settings);
    expect(result.status).not.toBe(0);
    expect(result.commands).not.toContain("dagger call publish");
  });

  it.each([
    { FAIL: "dagger call publish" },
    { PACKAGE_STATUS: "404", POST_VISIBILITY: "private" },
    { ANONYMOUS_FAILURE: "true" },
  ])("blocks release tagging after failed publication or public access: %j", (settings) => {
    const result = run(settings);
    expect(result.status).not.toBe(0);
    expect(result.commands).not.toContain("tag -a");
  });

  it("reports failed Git tagging without republishing", () => {
    const result = run({ FAIL: "git push" });
    expect(result.status).not.toBe(0);
    expect(result.commands.match(/dagger call publish/g)).toHaveLength(1);
  });

  it("keeps originals read-only, durable state writable and timestamped updates bounded", () => {
    const template = readFileSync(new URL("../../unraid/photography.xml", import.meta.url), "utf8");
    for (const target of ["/data", "/thumbnails"]) {
      expect(template).toMatch(
        new RegExp(`<Config\\b(?=[^>]*Target="${target}")(?=[^>]*Mode="rw")[^>]*>`),
      );
    }
    expect(template).toMatch(/<Config\b(?=[^>]*Target="\/photos")(?=[^>]*Mode="ro")[^>]*>/);
    for (const required of [
      "<Privileged>false</Privileged>",
      "<Network>br_docker</Network>",
      "REPLACE_WITH_PUBLISHED_VERSION",
      "--restart=unless-stopped",
      "--log-opt max-size=10m",
      "--log-opt max-file=5",
      "wud.watch=true",
      String.raw`wud.tag.include=^\d{4}\.\d{1,2}\.\d{1,2}-T\d{6}$`,
    ])
      expect(template).toContain(required);
  });
});
