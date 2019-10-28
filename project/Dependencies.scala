import sbt._

object Versions {
  lazy val akka = "2.5.26"
}

object Dependencies {
  // Akka Typed
  lazy val akkaActor = "com.typesafe.akka" %% "akka-actor-typed" % Versions.akka
  lazy val akkaStream = "com.typesafe.akka" %% "akka-stream-typed" % Versions.akka
  lazy val akkaClusterSharding = "com.typesafe.akka" %% "akka-cluster-sharding-typed" % Versions.akka

  // Scala test
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.8"
}
