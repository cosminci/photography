import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.{stagingDirectory => packagingStagingDirectory}

ThisBuild / scalaVersion := "3.10.0"
ThisBuild / organization := "com.cosminci"
ThisBuild / version      := "0.1.0"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name                                   := "photography-backend",
    Compile / mainClass                    := Some("photography.app.runtime.Main"),
    Compile / doc / sources                := Seq.empty,
    Compile / packageDoc / publishArtifact := false,
    Universal / packagingStagingDirectory  := baseDirectory.value / "target" / "universal" / "stage",
    Compile / resourceGenerators += Def.task {
      val frontend = baseDirectory.value.getParentFile / "frontend" / "dist"
      val packaged = (Compile / resourceManaged).value / "frontend"
      if (!(frontend / "index.html").isFile)
        sys.error("Built frontend entry point missing; use Dagger for checks or python3 scripts/local.py for local development")
      IO.delete(packaged)
      IO.copyDirectory(frontend, packaged)
      (packaged ** "*").get().filter(_.isFile)
    }.taskValue,
    scalacOptions ++= Seq(
      "-encoding",
      "utf8",
      "-feature",
      "-deprecation",
      "-unchecked",
      "-Werror",
      "-Wunused:all",
      "-Wvalue-discard",
      "-Wnonunit-statement",
      "-Wshadow:all",
      "-Wsafe-init",
      "-java-output-version",
      "21"
    ),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    wartremoverErrors ++= Warts.all,
    Test / scalacOptions ~= (_.filterNot(_ == "-P:wartremover:traverser:org.wartremover.warts.DefaultArguments")),
    coverageMinimumStmtTotal   := 100,
    coverageMinimumBranchTotal := 100,
    coverageFailOnMinimum      := true,
    coverageExcludedPackages   := Seq(
      "photography\\.app\\.runtime\\..*", // composition root; exercised by the packaged runtime, not unit tests
      ".*photography\\.domain\\..*(AbsoluteLocation|AlbumId|ChapterId|CountryId|PhotoId|RelativeLocation|EditorId|SessionId|CameraId|LensId|CoverSlotId|CoverPlacementId|PhotoSpreadSlotId|SpreadId|SpreadPlacementId|SpreadTemplateId|TextSpreadSlotId).*"
    ).mkString(";"),
    libraryDependencies ++= Seq(
      "org.typelevel"           %% "cats-core"                 % "2.13.0",
      "org.typelevel"           %% "cats-effect"               % "3.7.1",
      "org.typelevel"           %% "cats-effect-testkit"       % "3.7.1"   % Test,
      "io.github.iltotore"      %% "iron"                      % "3.4.1",
      "io.github.iltotore"      %% "iron-circe"                % "3.4.1",
      "io.github.iltotore"      %% "iron-doobie"               % "3.4.1",
      "io.github.iltotore"      %% "iron-pureconfig"           % "3.4.1",
      "org.typelevel"           %% "doobie-core"               % "1.0.0-RC13",
      "org.typelevel"           %% "doobie-hikari"             % "1.0.0-RC13",
      "com.github.pureconfig"   %% "pureconfig-core"           % "0.17.11",
      "com.github.pureconfig"   %% "pureconfig-generic-scala3" % "0.17.11",
      "com.github.pureconfig"   %% "pureconfig-cats-effect"    % "0.17.11",
      "com.github.pureconfig"   %% "pureconfig-http4s"         % "0.17.11",
      "com.github.pureconfig"   %% "pureconfig-ip4s"           % "0.17.11",
      "com.github.geirolz"      %% "fly4s"                     % "2.0.0",
      "org.flywaydb"             % "flyway-core"               % "13.11.1",
      "org.xerial"               % "sqlite-jdbc"               % "3.53.4.0",
      "com.drewnoakes"           % "metadata-extractor"        % "2.22.0",
      "com.twelvemonkeys.common" % "common-image"              % "3.15.3",
      "com.networknt"            % "json-schema-validator"     % "3.0.9",
      "org.http4s"              %% "http4s-dsl"                % "0.23.38",
      "org.http4s"              %% "http4s-circe"              % "0.23.38",
      "org.http4s"              %% "http4s-ember-server"       % "0.23.38",
      "org.http4s"              %% "http4s-ember-client"       % "0.23.38" % Test,
      "org.typelevel"           %% "log4cats-slf4j"            % "2.8.0",
      "org.slf4j"                % "slf4j-simple"              % "2.0.20"  % Runtime,
      "org.typelevel"           %% "munit-cats-effect"         % "2.2.1"   % Test,
      "com.tngtech.archunit"     % "archunit"                  % "1.5.1"   % Test
    )
  )
