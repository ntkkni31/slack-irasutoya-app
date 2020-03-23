name := """slack-irasutoya-app"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  jdbc,
  "org.postgresql" % "postgresql" % "9.4-1201-jdbc41",
  "com.h2database" % "h2" % "1.4.192",
  ws,
  guice,
  ehcache,
  cacheApi,
  jcache
)

libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-compiler" % _ )
libraryDependencies += "net.ruippeixotog" %% "scala-scraper" % "2.1.0"

// https://mvnrepository.com/artifact/com.sendgrid/sendgrid-java
libraryDependencies += "com.sendgrid" % "sendgrid-java" % "4.4.5"

// https://mvnrepository.com/artifact/org.jsr107.ri/cache-annotations-ri-guice
libraryDependencies += "org.jsr107.ri" % "cache-annotations-ri-guice" % "1.1.0"

// https://mvnrepository.com/artifact/org.playframework.anorm/anorm
libraryDependencies += "org.playframework.anorm" %% "anorm" % "2.6.5"
