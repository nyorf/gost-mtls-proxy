ThisBuild / scalaVersion := "3.8.4"
ThisBuild / version := "1.0.1"

lazy val http4sVersion = "0.23.36"
lazy val circeVersion = "0.14.16"

lazy val root = (project in file("."))
  .settings(
    name := "gost-mtls-proxy",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.slf4j" % "slf4j-api" % "2.0.18",
      "org.scalameta" %% "munit" % "1.3.4" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.2.0" % Test
    ),
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    assembly / mainClass := Some("proxy.Main"),
    assembly / assemblyJarName := "gost-mtls-proxy.jar",
    assembly / packageOptions += Package.ManifestAttributes("Implementation-Version" -> version.value),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*)             => MergeStrategy.discard
      case _                                    => MergeStrategy.first
    }
  )
