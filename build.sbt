
ThisBuild / scalaVersion := "2.13.2"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.thecookiezen"
ThisBuild / organizationName := "thecookiezen"

lazy val root = (project in file("."))
  .settings(
    name := "scalac-simple-profiler",
    libraryDependencies += "org.scala-lang" % "scala-compiler" % "2.13.2",
    libraryDependencies += "com.lihaoyi" %% "pprint" % "0.5.9",
    libraryDependencies += scalaTest % Test
  )
