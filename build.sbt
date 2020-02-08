lazy val http4sVersion = "0.21.0-RC5"
lazy val micrometerVersion = "1.3.3"
lazy val catsEffectVersion = "2.1.1"
lazy val scalaTestVersion = "3.1.0"

lazy val `http4s-micrometer-metrics` = (project in file(".")).settings(
  inThisBuild(
    List(
      organization := "com.ovoenergy",
      scalaVersion := "2.12.10"
    )
  ),
  name := "http4s-micrometer-metrics",
  organizationName := "OVO Energy",
  organizationHomepage := Some(url("https://www.ovoenergy.com/")),
  homepage := Some(url("https://github.com/ovotech/http4s-micrometer-metrics")),
  startYear := Some(2019),
  licenses := Seq(("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/ovotech/http4s-micrometer-metrics"),
      "git@github.com:ovotech/http4s-micrometer-metrics.git"
    )
  ),
  // TODO Find a way to extract those from github (sbt plugin)
  developers := List(
    Developer(
      "filippo.deluca",
      "Filippo De Luca",
      "filippo.deluca@ovoenergy.com",
      url("https://github.com/filosganga")
    )
  ),
  scalafmtOnCompile := true,
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect" % catsEffectVersion,
    "org.http4s" %% "http4s-core" % http4sVersion,
    "io.micrometer" % "micrometer-core" % micrometerVersion,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "org.http4s" %% "http4s-testing" % http4sVersion % Test,
    "org.http4s" %% "http4s-server" % http4sVersion % Test,
    "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
    "org.http4s" %% "http4s-client" % http4sVersion % Test
  ),
  bintrayOrganization := Some("ovotech"),
  bintrayRepository := "maven",
  bintrayPackageLabels := Seq(
    "http4s",
    "metrics",
    "micrometer"
  ),
  releaseEarlyWith := BintrayPublisher,
  releaseEarlyNoGpg := true,
  releaseEarlyEnableSyncToMaven := false
)
