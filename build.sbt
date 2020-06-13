scalaVersion := "2.13.2"
version := "0.1.0-SNAPSHOT"
organization := "com.thecookiezen"
organizationName := "thecookiezen"

scalacOptions ++= Seq("-deprecation", "-feature", "-Xfatal-warnings")

lazy val root = (project in file("."))
  .settings(
    name := "scalac-simple-profiler",
    libraryDependencies += "org.scala-lang" % "scala-compiler" % "2.13.2",
    libraryDependencies += "com.lihaoyi" %% "pprint" % "0.5.9",
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.1.1" % Test
  )
