import com.typesafe.sbt.SbtMultiJvm

val enableCoverage = System.getProperty("enableCoverage", "true")
val plugins:Seq[Plugins] = if(enableCoverage.toBoolean) Seq(Coverage) else Seq.empty

lazy val `csw-prod` = project
  .in(file("."))
  .enablePlugins(UnidocSite, PublishGithub, GitBranchPrompt)
  .aggregate(`csw-location`, `track-location-agent`, docs, integration)
  .settings(Settings.mergeSiteWith(docs))
  .settings(
    unidocProjectFilter in (ScalaUnidoc, unidoc) := inAnyProject -- inProjects(`track-location-agent`, integration),
    aggregate in test := false
  )

lazy val `csw-location` = project
  .enablePlugins(PublishBintray, GenJavadocPlugin)
  .enablePlugins(plugins:_*)
  .configs(MultiJvmKeys.MultiJvm)
  .settings(SbtMultiJvm.multiJvmSettings)
  .settings(
    libraryDependencies ++= Seq(
      Akka.`akka-stream`,
      Akka.`akka-distributed-data`,
      Akka.`akka-remote`,
      Akka.`akka-cluster-tools`,
      Libs.`scala-java8-compat`,
      Libs.`scala-async`,
      Libs.`enumeratum`,
      Libs.`chill-akka`
    ),
    libraryDependencies ++= Seq(
      Akka.`akka-stream-testkit` % Test,
      Libs.`scalatest` % Test,
      Libs.`junit` % Test,
      Libs.`junit-interface` % Test,
      Libs.`mockito-core` % Test,
      Akka.`akka-multi-node-testkit` % Test
    )
  )

lazy val `track-location-agent` = project
  .in(file("apps/track-location-agent"))
  .enablePlugins(DeployApp)
  .dependsOn(`csw-location`)
  .settings(
    libraryDependencies ++= Seq(
      Akka.`akka-actor`,
      Libs.`scopt`,
      Libs.`scalatest` % Test,
      Libs.`scala-logging` % Test
    ),
    sources in (Compile, doc) := Seq.empty
  )

lazy val docs = project
  .enablePlugins(ParadoxSite, NoPublish)

lazy val integration = project
  .enablePlugins(DeployApp)
  .dependsOn(`csw-location`)
  .dependsOn(`track-location-agent`)
  .settings(
    libraryDependencies ++= Seq(
      Libs.`scalatest`
    ),
    sources in Test := (sources in Compile).value
  )
