import { type Container, dag, type Directory } from "@dagger.io/dagger";
import { NODE_IMAGE } from "../buildEnv";

function prepareFrontend(source: Directory): Container {
  return dag
    .container()
    .from(NODE_IMAGE)
    .withMountedCache("/root/.npm", dag.cacheVolume("photography-frontend-npm"))
    .withDirectory("/app/frontend", source.directory("frontend"))
    .withDirectory("/app/scripts/indesign", source.directory("scripts/indesign"))
    .withWorkdir("/app/frontend")
    .withExec(["npm", "ci", "--no-audit", "--no-fund"]);
}

export function buildFrontend(source: Directory): Directory {
  return prepareFrontend(source).withExec(["npm", "run", "build"]).directory("/app/frontend/dist");
}

export function checkFrontend(source: Directory): Container {
  return prepareFrontend(source)
    .withExec(["npm", "run", "verify"])
    .withExec(["npm", "run", "build"]);
}
