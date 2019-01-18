lazy val http4sVersion = "0.20.0-M5"
lazy val micrometerVersion = "1.1.2"
lazy val catsEffectVersion = "1.1.0"
lazy val scalaTestVersion = "3.0.5"

lazy val `http4s-micrometer-metrics` = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.ovoenergy",
      scalaVersion := "2.12.8",
      version      := "0.1.0"
    )),
    name := "http4s-micrometer-metrics",
    scalafmtOnCompile := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.http4s" %% "http4s-core" % http4sVersion,
      "io.micrometer" % "micrometer-core" % micrometerVersion,
      "org.scalatest" % "scalatest_2.12" % scalaTestVersion % Test,
      "org.http4s" %% "http4s-testing" % http4sVersion % Test,
      "org.http4s" %% "http4s-server" % http4sVersion % Test,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
      "org.http4s" %% "http4s-client" % http4sVersion % Test,
    )
  )
