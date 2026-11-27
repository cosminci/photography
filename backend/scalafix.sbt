ThisBuild / scalafixResolvers += coursierapi.MavenRepository.of(
  "https://raw.githubusercontent.com/cosminci/agentic-workflows/feddd4746900dd463093ecd4133c62fb1ef0f3d5/"
)
scalafixDependencies += "com.cosminci" % "scalafix-custom-rules_2.13" % "0.5.0"

Compile / scalafixConfig := Some(baseDirectory.value / ".scalafix-codecs.conf")
Test / scalafixConfig    := Some(baseDirectory.value / ".scalafix-test.conf")
