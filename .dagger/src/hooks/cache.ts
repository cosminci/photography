import { type Container, dag, type Directory } from "@dagger.io/dagger";

const dependencies = [
  ["photography-coursier", "/root/.cache/coursier"],
  ["photography-sbt-boot", "/root/.sbt/boot"],
] as const;

const compiler = [
  ["photography-sbt-actions", "/root/.cache/sbt/v2"],
  ["photography-backend-coverage", "/work/target"],
  ["photography-backend-project", "/work/project/target"],
  ["photography-backend-meta-project", "/work/project/project/target"],
] as const;

const volumes = [...dependencies, ["photography-frontend-npm", "/root/.npm"], ...compiler] as const;

export function backendCache(container: Container, coverage = false): Container {
  return [...dependencies, ...(coverage ? compiler : [])].reduce(
    (current, [name, path]) => current.withMountedCache(path, dag.cacheVolume(name)),
    container,
  );
}

function transfer(): Container {
  return volumes.reduce(
    (container, [name]) => container.withMountedCache(`/volumes/${name}`, dag.cacheVolume(name)),
    dag.container().from("alpine:3.23.3").withEnvVariable("CACHE_TRANSFER", crypto.randomUUID()),
  );
}

export function restoreCache(source: Directory): Promise<string> {
  return transfer()
    .withMountedDirectory("/snapshot", source)
    .withExec(["sh", "-ec", "cp -a /snapshot/. /volumes/"])
    .stdout();
}

export function cacheSnapshot(): Directory {
  return transfer()
    .withExec(["sh", "-ec", "mkdir /snapshot; cp -a /volumes/. /snapshot/; du -sk /snapshot/*"])
    .directory("/snapshot");
}
