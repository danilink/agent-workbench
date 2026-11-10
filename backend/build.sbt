ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "dev.nilo"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    name := "agent-workbench",
    libraryDependencies ++= Seq(
      guice,
      jdbc,
      evolutions,
      "org.postgresql"          % "postgresql"         % "42.7.4",
      "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test
    ),
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")
  )
