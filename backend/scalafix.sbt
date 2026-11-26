ThisBuild / scalafixResolvers += coursierapi.MavenRepository.of(
  "https://raw.githubusercontent.com/cosminci/agentic-workflows/c713e492fe28f91cd9948cde16eccff9004f8d73/"
)
scalafixDependencies += "com.cosminci" % "scalafix-custom-rules_2.13" % "0.3.2"

Compile / scalafixConfig := Some(baseDirectory.value / ".scalafix-codecs.conf")
Test / scalafixConfig    := Some(baseDirectory.value / ".scalafix-test.conf")
