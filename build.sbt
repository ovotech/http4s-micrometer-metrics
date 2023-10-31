lazy val http4sVersion = "0.23.17"
lazy val micrometerVersion = "1.7.5"
lazy val meters4sVersion = "1.1.2"
lazy val catsEffectVersion = "3.3.14"
lazy val scalaTestVersion = "3.2.10"
lazy val munitVersion = "1.0.0-M7"
lazy val munitCatsEffectVersion = "2.0.0-M3"
lazy val slf4jVersion = "1.7.32"

Global / onChangedBuildSource := ReloadOnSourceChanges
Global / excludeLintKeys += Compile / console / scalacOptions

ThisBuild / organization := "com.kaluza"
ThisBuild / organizationName := "Kaluza Ltd"
ThisBuild / organizationHomepage := Some(url("https://www.kaluza.com/"))
ThisBuild / homepage := Some(url("https://github.com/ovotech/http4s-micrometer-metrics"))
ThisBuild / startYear := Some(2023)
ThisBuild / scalaVersion := "3.2.2"
ThisBuild / crossScalaVersions ++= List("2.13.10", "2.12.10")
ThisBuild / licenses := Seq(("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/ovotech/http4s-micrometer-metrics"),
    "git@github.com:ovotech/http4s-micrometer-metrics.git"
  )
)
// TODO Find a way to extract those from github (sbt plugin)
ThisBuild / developers := List(
  Developer(
    "filippo.deluca",
    "Filippo De Luca",
    "filippo.deluca@kaluza.com",
    url("https://github.com/filosganga")
  )
)

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

lazy val publicArtifactory = "Artifactory Realm" at "https://kaluza.jfrog.io/artifactory/maven"

lazy val publishSettings = Seq(
  publishTo := Some(publicArtifactory),
  credentials += {
    for {
      usr <- sys.env.get("ARTIFACTORY_USER")
      password <- sys.env.get("ARTIFACTORY_PASS")
    } yield Credentials("Artifactory Realm", "kaluza.jfrog.io", usr, password)
  }.getOrElse(Credentials(Path.userHome / ".ivy2" / ".credentials"))
)

lazy val `http4s-meters4s-metrics` = (project in file("."))
  .settings(
    name := "http4s-meters4s-metrics",
    scalacOptions -= "-Xfatal-warnings",
    scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, _)) => List("-Xsource:3")
        case _ => List.empty
      }
    },
    scalafmtOnCompile := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.http4s" %% "http4s-core" % http4sVersion,
      "com.ovoenergy" %% "meters4s" % meters4sVersion,
      "io.micrometer" % "micrometer-core" % micrometerVersion % Test,
      "org.http4s" %% "http4s-laws" % http4sVersion % Test,
      "org.http4s" %% "http4s-server" % http4sVersion % Test,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
      "org.http4s" %% "http4s-client" % http4sVersion % Test,
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
      "org.slf4j" % "slf4j-nop" % slf4jVersion % Test
    )
  )
  .settings(publishSettings)
