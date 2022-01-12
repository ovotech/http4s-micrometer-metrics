lazy val http4sVersion = "0.23.6"
lazy val micrometerVersion = "1.7.8"
lazy val catsEffectVersion = "3.3.0"
lazy val scalaTestVersion = "3.2.10"
lazy val munitVersion = "0.7.29"
lazy val munitCatsEffect3Version = "1.0.7"
lazy val slf4jVersion = "1.7.32"

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
        scalaVersion := "2.13.7",
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
      "org.http4s" %% "http4s-laws" % http4sVersion % Test,
      "org.http4s" %% "http4s-server" % http4sVersion % Test,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
      "org.http4s" %% "http4s-client" % http4sVersion % Test,
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.typelevel" %% "munit-cats-effect-3" % munitCatsEffect3Version % Test,
      "org.slf4j" % "slf4j-nop" % slf4jVersion % Test
    )
  )
  .settings(publishSettings)
