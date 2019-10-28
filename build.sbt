import Dependencies._

ThisBuild / scalaVersion     := "2.13.1"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "com.astutebits"
ThisBuild / organizationName := "akka-static-cluster-distribution"

lazy val root = (project in file("."))
  .settings(
    name := "akka-static-cluster-distribution",
    libraryDependencies ++= Seq(
      akkaActor, akkaStream, akkaClusterSharding, scalaTest % Test
    )
  )

