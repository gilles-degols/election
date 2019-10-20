import sbt.Credentials

name := "election"
organization := "net.degols.libs"
version := "1.0.0"

scalacOptions ++= Seq("-deprecation", "-feature", "-language:postfixOps")

scalaVersion := "2.12.8"
lazy val playVersion = "2.6.1"
lazy val akkaVersion = "2.5.2"

libraryDependencies += "com.google.inject" % "guice" % "4.2.2"

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

resolvers += "Akka Snapshot Repository" at "http://repo.akka.io/snapshots/"


// POM settings for Sonatype
sonatypeProfileName := "net.degols"
import xerial.sbt.Sonatype._
sonatypeProjectHosting := Some(GitHubHosting("gilles-degols", "election", "gilles@degols.net"))
licenses += ("MIT License", url("https://opensource.org/licenses/MIT"))
publishMavenStyle := true
publishTo := sonatypePublishToBundle.value
usePgpKeyHex("C0FAC2FE")

lazy val username = Option(System.getenv("SONATYPE_USER")).getOrElse("sonatype_user")
lazy val password = Option(System.getenv("SONATYPE_PASSWORD")).getOrElse("sonatype_password")
credentials += Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", username, password)
