import type * as Dagger from "@dagger.io/dagger";
import { argument, func, object } from "@dagger.io/dagger";
import { GHCR_REPOSITORY, GHCR_USER, WORKSPACE_IGNORE } from "./buildEnv";
import { checkImage, runtimeImage } from "./hooks/image";
import { assertReleaseVersion, releaseVersion } from "./version";
import { checkBackend } from "./hooks/backend";
import { checkFrontend } from "./hooks/frontend";
import { checkLocalSetup, checkPipeline } from "./hooks/pipeline";
import * as Git from "./hooks/git";
import * as Selection from "./selection";

/** Affected-component CI for the photography repository. */
@object()
export class Photography {
  /** Maps changed paths relative to `base` (default: the merge-base with origin/main). */
  @func()
  async changed(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
    base = "",
  ): Promise<string> {
    const components = Selection.listSelectedComponents(
      Selection.selectAffected(
        Selection.parseChangedPaths(await Git.readChangedPaths(source, base)),
      ),
    );
    return components.length === 0 ? "none" : components.join(",");
  }

  /** Runs checks for affected components, or every component when `all` is true. */
  @func()
  async verify(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
    base = "",
    all = false,
  ): Promise<string> {
    const selection = all
      ? ({ kind: "all" } as const)
      : Selection.selectAffected(
          Selection.parseChangedPaths(await Git.readChangedPaths(source, base)),
        );
    const components = Selection.listSelectedComponents(selection);
    const done: string[] = [];
    if (components.includes("backend")) {
      await this.backendCheck(source);
      done.push("backend");
    }
    if (components.includes("frontend")) {
      await this.frontendCheck(source);
      done.push("frontend");
    }
    if (components.includes("pipeline")) {
      await this.pipelineCheck(source);
      await checkLocalSetup(source).stdout();
      done.push("pipeline");
    }
    if (components.length > 0) {
      await this.imageCheck(source);
      done.push("image");
    }
    return done.length === 0 ? "verify: nothing affected" : `verify: ${done.join(", ")} ok`;
  }

  @func()
  backendCheck(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
  ): Promise<string> {
    return checkBackend(source).stdout();
  }

  @func()
  frontendCheck(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
  ): Promise<string> {
    return checkFrontend(source).stdout();
  }

  @func()
  pipelineCheck(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
  ): Promise<string> {
    return checkPipeline(source).stdout();
  }

  @func()
  async build(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
    version = "dev",
  ): Promise<Dagger.Container> {
    return runtimeImage(source, {
      version,
      revision: (await Git.readHeadSha(source)).trim(),
      created: new Date().toISOString(),
    });
  }

  @func({ cache: "never" })
  releaseVersion(): string {
    return releaseVersion(new Date());
  }

  @func()
  async imageCheck(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
  ): Promise<string> {
    return checkImage(source, await this.build(source));
  }

  @func({ cache: "never" })
  async publish(
    @argument({ defaultPath: "/", ignore: WORKSPACE_IGNORE }) source: Dagger.Directory,
    tag: string,
    token: Dagger.Secret,
    registryUser = GHCR_USER,
  ): Promise<string> {
    assertReleaseVersion(tag);
    return (await this.build(source, tag))
      .withRegistryAuth("ghcr.io", registryUser, token)
      .publish(`${GHCR_REPOSITORY}:${tag}`);
  }
}
