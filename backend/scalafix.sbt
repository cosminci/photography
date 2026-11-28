ThisBuild / scalafixResolvers += coursierapi.MavenRepository.of(
  "https://raw.githubusercontent.com/cosminci/agentic-workflows/9437dd920f00709ea55ca0e1f18d2ba81676dde7/"
)
scalafixDependencies += "com.cosminci" % "scalafix-custom-rules_2.13" % "0.9.0"

Compile / scalafixConfig := Some(baseDirectory.value / ".scalafix-codecs.conf")
Test / scalafixConfig    := Some(baseDirectory.value / ".scalafix-test.conf")

lazy val compileSemanticdbJar = taskKey[File]("Package production SemanticDB for Test Scalafix")

compileSemanticdbJar := Def.uncached {
  (Compile / compile).value
  val root    = (Compile / semanticdbTargetRoot).value
  val jar     = target.value / "scalafix-compile-semanticdb.jar"
  val entries = (root ** "*.semanticdb").get().map(file => file -> root.toPath.relativize(file.toPath).toString)
  IO.zip(entries, jar, Some(0L))
  jar
}

Test / dependencyClasspath +=
  Attributed.blank[xsbti.HashedVirtualFileRef](fileConverter.value.toVirtualFile(compileSemanticdbJar.value.toPath))
