import * as Dagger from "@dagger.io/dagger";
import { JDK_IMAGE, TARGET_PLATFORM } from "../buildEnv";
import { buildFrontend } from "./frontend";
import { backendCache } from "./cache";

export function stageBackend(source: Dagger.Directory): Dagger.Directory {
  return backendCache(
    Dagger.dag
      .container({ platform: TARGET_PLATFORM as Dagger.Platform })
      .from(JDK_IMAGE)
      .withUser("root")
      .withEnvVariable("HOME", "/root")
      .withDirectory("/frontend/dist", buildFrontend(source))
      .withDirectory("/work", source.directory("backend"))
      .withWorkdir("/work"),
  )
    .withExec(["sbt", "-batch", "-Dsbt.color=false", "coverageOff; Universal / stage"])
    .directory("/work/target/universal/stage");
}

export function checkBackend(source: Dagger.Directory): Dagger.Container {
  return backendCache(
    Dagger.dag
      .container()
      .from(JDK_IMAGE)
      .withUser("root")
      .withEnvVariable("HOME", "/root")
      .withDirectory("/frontend/dist", buildFrontend(source))
      .withDirectory("/work", source.directory("backend"))
      .withWorkdir("/work"),
    true,
  )
    .withExec(["sh", "-ec", "find target -name 'scoverage.measurements.*' -delete"])
    .withExec([
      "sh",
      "-c",
      `sbt -batch -Dsbt.color=false 'coverage; testFull; coverageReport; scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck'
status=$?
if [ "$status" -ne 0 ]; then
  find target -name scoverage.xml -exec grep 'invocation-count="0"' {} +
fi
exit "$status"`,
    ]);
}
