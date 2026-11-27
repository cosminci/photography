ThisBuild / scalafixResolvers += coursierapi.MavenRepository.of(
  "https://raw.githubusercontent.com/cosminci/agentic-workflows/7f4d422988dffa12cb1f1f54106f4a98a39da4bb/"
)
scalafixDependencies += "com.cosminci" % "scalafix-custom-rules_2.13" % "0.6.0"

Compile / scalafixConfig := Some(baseDirectory.value / ".scalafix-codecs.conf")
Test / scalafixConfig    := Some(baseDirectory.value / ".scalafix-test.conf")
