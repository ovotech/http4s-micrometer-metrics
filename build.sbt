lazy val http4sVersion = "0.21.26"
lazy val micrometerVersion = "1.7.0"
lazy val catsEffectVersion = "2.5.1"
lazy val scalaTestVersion = "3.2.9"

Global / excludeLintKeys += Compile / console / scalacOptions

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

lazy val `http4s-micrometer-metrics` = (project in file("."))
  .settings(
    inThisBuild(
      List(
        organization := "com.ovoenergy",
        scalaVersion := "2.13.6",
        crossScalaVersions += "2.12.10",
        Compile / console / scalacOptions -= "-Ywarn-unused-import"
      )
    ),
    scalacOptions -= "-Xfatal-warnings",
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
    )
  )
  .settings(publishSettings)
