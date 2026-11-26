ThisBuild / scalafixResolvers += coursierapi.MavenRepository.of(
  "https://raw.githubusercontent.com/cosminci/agentic-workflows/b7332d13fe695a29b529a27b54c7794bdf8680ab/"
)
scalafixDependencies += "com.cosminci" % "scalafix-custom-rules_2.13" % "0.3.5"

Compile / scalafixConfig := Some(baseDirectory.value / ".scalafix-codecs.conf")
Test / scalafixConfig    := Some(baseDirectory.value / ".scalafix-test.conf")
