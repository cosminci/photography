import * as Dagger from "@dagger.io/dagger";
import { JDK_IMAGE, PYTHON_IMAGE, SOURCE_URL, TARGET_PLATFORM } from "../buildEnv";
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

export async function checkImage(
  source: Dagger.Directory,
  image: Dagger.Container,
): Promise<string> {
  const run = crypto.randomUUID();
  const originals = Dagger.dag
    .container({ platform: TARGET_PLATFORM as Dagger.Platform })
    .from(JDK_IMAGE)
    .withFile("/fixture.jsh", source.file(".dagger/test/fixtures/photo.jsh"))
    .withExec(["jshell", "/fixture.jsh"])
    .withExec(["chmod", "-R", "a+rwX", "/fixture"])
    .directory("/fixture");
  const runtime = image
    .withMountedDirectory("/photos", originals, { readOnly: true })
    .withMountedCache("/data", Dagger.dag.cacheVolume(`photography-smoke-data-${run}`), {
      owner: "1000:1000",
    })
    .withMountedCache(
      "/thumbnails",
      Dagger.dag.cacheVolume(`photography-smoke-thumbnails-${run}`),
      { owner: "1000:1000" },
    );
  await runtime
    .withExec([
      "sh",
      "-ec",
      'test "$(id -u)" = 1000; test "$(id -g)" = 1000; test "$(uname -m)" = x86_64; java --list-modules | grep "^java.desktop@"; ! command -v sbt; ! command -v node; ! command -v javac; if touch /photos/forbidden; then echo "original directory is writable" >&2; exit 1; fi; if printf forbidden >> /photos/smoke.jpg; then echo "original file is writable" >&2; exit 1; fi',
    ])
    .stdout();
  const service = runtime.withExposedPort(8080).asService();
  const client = Dagger.dag
    .container()
    .from(PYTHON_IMAGE)
    .withFile("/smoke.py", source.file(".dagger/test/fixtures/container_smoke.py"))
    .withMountedCache("/evidence", Dagger.dag.cacheVolume(`photography-smoke-evidence-${run}`))
    .withServiceBinding("photography", service);
  await service.start();
  try {
    await client.withExec(["python3", "/smoke.py", "create"]).stdout();
  } finally {
    await service.stop();
  }
  await service.start();
  try {
    return await client.withExec(["python3", "/smoke.py", "restart"]).stdout();
  } finally {
    await service.stop();
  }
}
