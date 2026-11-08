import * as Dagger from "@dagger.io/dagger";
import { SOURCE_URL, TARGET_PLATFORM } from "../buildEnv";
import { stageBackend } from "./backend";

export interface ImageInfo {
  readonly version: string;
  readonly revision: string;
  readonly created: string;
}

export function runtimeImage(source: Dagger.Directory, info: ImageInfo): Dagger.Container {
  return Dagger.dag
    .directory()
    .withDirectory("app", stageBackend(source))
    .withFile("Dockerfile", source.file("containers/photography/Dockerfile"))
    .dockerBuild({ platform: TARGET_PLATFORM as Dagger.Platform })
    .withLabel("org.opencontainers.image.title", "photography")
    .withLabel("org.opencontainers.image.description", "Photography Album Layout Studio")
    .withLabel("org.opencontainers.image.version", info.version)
    .withLabel("org.opencontainers.image.revision", info.revision)
    .withLabel("org.opencontainers.image.created", info.created)
    .withLabel("org.opencontainers.image.source", SOURCE_URL);
}
