ThisBuild / scalafixResolvers += coursierapi.MavenRepository.of(
  "https://raw.githubusercontent.com/cosminci/agentic-workflows/89c9644d0c9fedd6619d3c90cabf97eb1345aede/"
)
scalafixDependencies += "com.cosminci" % "scalafix-custom-rules_2.13" % "0.3.8"

Compile / scalafixConfig := Some(baseDirectory.value / ".scalafix-codecs.conf")
Test / scalafixConfig    := Some(baseDirectory.value / ".scalafix-test.conf")
