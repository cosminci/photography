ThisBuild / scalafixResolvers += coursierapi.MavenRepository.of(
  "https://raw.githubusercontent.com/cosminci/agentic-workflows/2a1312aeaeddaec208f987f2c34214712c042cee/"
)
scalafixDependencies += "com.cosminci" % "scalafix-custom-rules_2.13" % "0.3.6"

Compile / scalafixConfig := Some(baseDirectory.value / ".scalafix-codecs.conf")
Test / scalafixConfig    := Some(baseDirectory.value / ".scalafix-test.conf")
