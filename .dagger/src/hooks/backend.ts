import * as Dagger from "@dagger.io/dagger";
import { JDK_IMAGE, NODE_IMAGE } from "../buildEnv";

export function checkBackend(source: Dagger.Directory): Dagger.Container {
  const frontendAssets = Dagger.dag
    .container()
    .from(NODE_IMAGE)
    .withMountedCache("/root/.npm", Dagger.dag.cacheVolume("photography-frontend-npm"))
    .withDirectory("/app/frontend", source.directory("frontend"))
    .withDirectory("/app/scripts/indesign", source.directory("scripts/indesign"))
    .withWorkdir("/app/frontend")
    .withExec(["npm", "ci", "--no-audit", "--no-fund"])
    .withExec(["npm", "run", "build"])
    .directory("/app/frontend/dist");

  return Dagger.dag
    .container()
    .from(JDK_IMAGE)
    .withUser("root")
    .withEnvVariable("HOME", "/root")
    .withMountedCache("/root/.cache/coursier", Dagger.dag.cacheVolume("photography-coursier"))
    .withMountedCache("/root/.sbt", Dagger.dag.cacheVolume("photography-sbt"))
    .withMountedCache("/root/.ivy2", Dagger.dag.cacheVolume("photography-ivy2"))
    .withDirectory("/frontend/dist", frontendAssets)
    .withDirectory("/work", source.directory("backend"))
    .withWorkdir("/work")
    .withExec([
      "sbt",
      "-batch",
      "-Dsbt.color=false",
      "coverage; test; coverageReport; scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck",
    ]);
}
