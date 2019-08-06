import Dependencies._

ThisBuild / scalaVersion     := "2.13.0"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "com.astutebits"
ThisBuild / organizationName := "akka-static-cluster-distribution"

lazy val root = (project in file("."))
  .settings(
    name := "akka-static-cluster-distribution",
    libraryDependencies += scalaTest % Test
  )

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
