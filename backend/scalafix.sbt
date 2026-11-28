ThisBuild / scalafixResolvers += coursierapi.MavenRepository.of(
  "https://raw.githubusercontent.com/cosminci/agentic-workflows/bdfb66074733c29fa5d74f90779d49a7e351ec70/"
)
scalafixDependencies += "com.cosminci" % "scalafix-custom-rules_2.13" % "0.8.0"

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
