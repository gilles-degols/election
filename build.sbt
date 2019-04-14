import sbt.Credentials

name := "election"
organization := "net.degols.libs"
version := "1.0.0"

scalacOptions ++= Seq("-deprecation", "-feature", "-language:postfixOps")

scalaVersion := "2.12.1"
lazy val playVersion = "2.6.1"
lazy val akkaVersion = "2.5.2"

libraryDependencies += "com.google.inject" % "guice" % "3.0"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion exclude("log4j", "log4j") exclude("org.slf4j","slf4j-log4j12")
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-actor" % akkaVersion
)

libraryDependencies += "joda-time" % "joda-time" % "2.10"

// Akka Remoting
libraryDependencies += "com.typesafe.akka" %% "akka-remote" % akkaVersion


// Allow temporary overwrite
// publishConfiguration := publishConfiguration.value.withOverwrite(true)
// publishLocalConfiguration := publishLocalConfiguration.value.withOverwrite(true)
publishTo := Some("gd-maven" at s"http://localhost:8081/repository/maven-gd")
isSnapshot := false
credentials += Credentials("Sonatype Nexus Repository Manager", "localhost", "admin", "admin123")
resolvers += "gd-maven" at "http://localhost:8081/repository/maven-gd/"
