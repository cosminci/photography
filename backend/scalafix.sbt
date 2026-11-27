ThisBuild / scalafixResolvers += coursierapi.MavenRepository.of(
  "https://raw.githubusercontent.com/cosminci/agentic-workflows/6f59737de4c1b3272e589722e86b0f13163a8f18/"
)
scalafixDependencies += "com.cosminci" % "scalafix-custom-rules_2.13" % "0.3.7"

Compile / scalafixConfig := Some(baseDirectory.value / ".scalafix-codecs.conf")
Test / scalafixConfig    := Some(baseDirectory.value / ".scalafix-test.conf")
