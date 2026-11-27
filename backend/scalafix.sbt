ThisBuild / scalafixResolvers += coursierapi.MavenRepository.of(
  "https://raw.githubusercontent.com/cosminci/agentic-workflows/f91228633ae378f6ef5d685fadea9ff9f6bf8231/"
)
scalafixDependencies += "com.cosminci" % "scalafix-custom-rules_2.13" % "0.4.0"

Compile / scalafixConfig := Some(baseDirectory.value / ".scalafix-codecs.conf")
Test / scalafixConfig    := Some(baseDirectory.value / ".scalafix-test.conf")
