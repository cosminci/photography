ThisBuild / scalafixResolvers += coursierapi.MavenRepository.of(
  "https://raw.githubusercontent.com/cosminci/agentic-workflows/081ad16b52173c53fbb47c7592130c52e68f973a/"
)
scalafixDependencies += "com.cosminci" % "scalafix-custom-rules_2.13" % "0.7.0"

Compile / scalafixConfig := Some(baseDirectory.value / ".scalafix-codecs.conf")
Test / scalafixConfig    := Some(baseDirectory.value / ".scalafix-test.conf")
