ThisBuild / scalafixResolvers += coursierapi.MavenRepository.of(
  "https://raw.githubusercontent.com/cosminci/agentic-workflows/b41bd67c7e401b5ddcb62f6c3b64490e10ed3179/"
)
scalafixDependencies += "com.cosminci" % "scalafix-custom-rules_2.13" % "0.5.1"

Compile / scalafixConfig := Some(baseDirectory.value / ".scalafix-codecs.conf")
Test / scalafixConfig    := Some(baseDirectory.value / ".scalafix-test.conf")
