import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
val enableCoverage         = sys.props.get("enableCoverage").contains("true")
val MaybeCoverage: Plugins = if (enableCoverage) Coverage else Plugins.empty

lazy val aggregatedProjects: Seq[ProjectReference] = Seq(
  `csw-logging`,
  `csw-logging-macros`,
  `csw-admin-server`,
  `csw-location-client`,
  `csw-config-api`,
  `csw-config-client`,
  `csw-config-cli`,
  `csw-config-server`,
  `csw-framework`,
  `csw-command`,
  `csw-event-api`,
  `csw-event-client`,
  `csw-event-cli`,
  `csw-alarm-api`,
  `csw-alarm-client`,
  `csw-alarm-cli`,
  `csw-location-server`,
  `csw-location-agent`,
  `csw-location-api`,
  `csw-location-client`,
  `csw-benchmark`,
  `csw-params-jvm`,
  `csw-params-js`,
  `csw-commons`,
  `integration`,
  `examples`,
  `romaine`
)

lazy val unidocExclusions: Seq[ProjectReference] = Seq(
  `csw-logging-macros`,
  `csw-location-agent`,
  `csw-config-server`,
  `csw-config-cli`,
  `csw-event-cli`,
  `csw-alarm-cli`,
  `csw-commons`,
  `csw-benchmark`,
  `romaine`,
  `examples`,
  `integration`,
  `csw-params-js`
)

lazy val githubReleases: Seq[ProjectReference] = Seq(
  `csw-location-agent`,
  `csw-config-server`,
  `csw-config-cli`,
  `csw-event-cli`
)

//Root project
lazy val `csw` = project
  .in(file("."))
  .enablePlugins(NoPublish, UnidocSite, GithubPublishDocs, GitBranchPrompt, GithubRelease)
  .disablePlugins(BintrayPlugin)
  .aggregate(aggregatedProjects: _*)
  .settings(Settings.mergeSiteWith(docs))
  .settings(Settings.docExclusions(unidocExclusions))
  .settings(GithubRelease.githubReleases(githubReleases))

lazy val `csw-params` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .enablePlugins(PublishBintray, GenJavadocPlugin)
  .settings(
    libraryDependencies ++= Dependencies.Params.value,
    Common.detectCycles := false,
    fork := false
  )

lazy val `csw-params-js` = `csw-params`.js
  .settings(
    libraryDependencies += Libs.`scalajs-java-time`.value
  )

lazy val `csw-params-jvm` = `csw-params`.jvm
  .dependsOn(`csw-commons` % "test->test")
  .settings(
    libraryDependencies ++= Dependencies.ParamsJvm.value
  )

lazy val `csw-logging-macros` = project
  .settings(
    libraryDependencies += Libs.`scala-reflect`
  )

//Logging service
lazy val `csw-logging` = project
  .dependsOn(`csw-logging-macros`)
  .enablePlugins(PublishBintray, GenJavadocPlugin, MaybeCoverage)
  .settings(
    libraryDependencies ++= Dependencies.Logging.value
  )

//Location service related projects
lazy val `csw-location-api` = project
  .dependsOn(
    `csw-logging`,
    `csw-params-jvm`
  )
  .enablePlugins(PublishBintray, GenJavadocPlugin, MaybeCoverage)
  .settings(
    libraryDependencies ++= Dependencies.LocationApi.value
  )

lazy val `csw-location-server` = project
  .dependsOn(
    `csw-location-api`,
    `csw-logging`,
    `csw-location-client` % "test->compile;multi-jvm->compile",
    `csw-commons` % "compile->compile;test->test"
  )
  .enablePlugins(PublishBintray, GenJavadocPlugin, AutoMultiJvm, MaybeCoverage, DeployApp)
  .settings(
    libraryDependencies ++= Dependencies.Location.value
  )

lazy val `csw-location-client` = project
  .dependsOn(`csw-location-api`)
  .enablePlugins(PublishBintray, GenJavadocPlugin, MaybeCoverage)
  .settings(
    libraryDependencies ++= Dependencies.LocationClient.value
  )

lazy val `csw-location-agent` = project
  .dependsOn(
    `csw-location-client`,
    `csw-commons` % "test->test",
    `csw-location-server` % "test->multi-jvm"
  )
  .enablePlugins(DeployApp, MaybeCoverage)
  .settings(
    libraryDependencies ++= Dependencies.LocationAgent.value
  )

lazy val `csw-admin-server` = project
  .dependsOn(
    `csw-location-client`,
    `csw-command`,
    `csw-commons`       % "compile->compile;test->test",
    `csw-framework`     % "test->test",
    `csw-config-server` % "test->test"
  )
  .enablePlugins(DeployApp, MaybeCoverage)
  .settings(
    libraryDependencies ++= Dependencies.Admin.value
  )

//Config service related projects
lazy val `csw-config-api` = project
  .enablePlugins(PublishBintray, GenJavadocPlugin, MaybeCoverage)
  .settings(
    libraryDependencies ++= Dependencies.ConfigApi.value
  )

lazy val `csw-config-server` = project
  .dependsOn(
    `csw-config-api`,
    `csw-location-client`,
    `csw-location-server` % "test->multi-jvm",
    `csw-commons` % "compile->compile;test->test"
  )
  .enablePlugins(DeployApp, MaybeCoverage)
  .settings(
    libraryDependencies ++= Dependencies.ConfigServer.value
  )

lazy val `csw-config-client` = project
  .dependsOn(
    `csw-config-api`,
    `csw-location-api`,
    `csw-commons`       % "compile->compile;test->test",
    `csw-location-server`      % "multi-jvm->multi-jvm",
    `csw-config-server` % "test->test"
  )
  .enablePlugins(PublishBintray, AutoMultiJvm, MaybeCoverage)
  .settings(
    libraryDependencies ++= Dependencies.ConfigClient.value
  )

lazy val `csw-config-cli` = project
  .dependsOn(
    `csw-config-client`,
    `csw-location-client`,
    `csw-location-server`      % "multi-jvm->multi-jvm",
    `csw-config-server` % "test->test",
    `csw-commons`       % "test->test"
  )
  .enablePlugins(DeployApp, AutoMultiJvm, MaybeCoverage)
  .settings(
    libraryDependencies ++= Dependencies.ConfigCli.value
  )

lazy val `csw-command` = project
  .dependsOn(
    `csw-params-jvm`,
    `csw-location-api`,
    `csw-logging`,
    `csw-commons` % "test->test"
  )
  .enablePlugins(PublishBintray, AutoMultiJvm, GenJavadocPlugin, MaybeCoverage)
  .settings(libraryDependencies ++= Dependencies.Command.value)

lazy val `csw-framework` = project
  .dependsOn(
    `csw-params-jvm`,
    `csw-config-client`,
    `csw-logging`,
    `csw-command`,
    `csw-event-api`,
    `csw-event-client`,
    `csw-alarm-client`,
    `csw-location-client`,
    `csw-event-client`  % "test->test",
    `csw-location-server`      % "test->multi-jvm;multi-jvm->multi-jvm",
    `csw-config-server` % "multi-jvm->test",
    `csw-commons`       % "test->test"
  )
  .enablePlugins(PublishBintray, AutoMultiJvm, GenJavadocPlugin, CswBuildInfo, MaybeCoverage)
  .settings(
    libraryDependencies ++= Dependencies.Framework.value
  )

lazy val `csw-event-api` = project
  .dependsOn(`csw-params-jvm`)
  .enablePlugins(PublishBintray, GenJavadocPlugin)
  .settings(libraryDependencies ++= Dependencies.EventApi.value)

lazy val `csw-event-client` = project
  .dependsOn(
    `csw-event-api`,
    `csw-logging`,
    `romaine`,
    `csw-location-api`,
    `csw-location-server` % "test->compile;test->multi-jvm;multi-jvm->multi-jvm",
    `csw-commons`  % "test->test"
  )
  .enablePlugins(PublishBintray, AutoMultiJvm, GenJavadocPlugin, MaybeCoverage)
  .settings(libraryDependencies ++= Dependencies.EventClient.value)
  .settings(
    Common.detectCycles := false,
    PB.targets in Compile := Seq(
      PB.gens.java                        -> (sourceManaged in Compile).value,
      scalapb.gen(javaConversions = true) -> (sourceManaged in Compile).value
    )
  )

lazy val `csw-event-cli` = project
  .dependsOn(
    `csw-location-client`,
    `csw-event-client` % "compile->compile;test->test;test->multi-jvm",
    `csw-commons`      % "test->test",
    `csw-config-server` % "test->test"
  )
  .enablePlugins(DeployApp, MaybeCoverage)
  .settings(libraryDependencies ++= Dependencies.EventCli.value)

lazy val `csw-alarm-api` = project
  .dependsOn(`csw-params-jvm`)
  .enablePlugins(PublishBintray, GenJavadocPlugin)
  .settings(libraryDependencies ++= Dependencies.AlarmApi.value)

lazy val `csw-alarm-client` = project
  .dependsOn(
    `csw-alarm-api`,
    `csw-location-api`,
    `csw-logging`,
    `romaine`,
    `csw-logging` % "test->test",
    `csw-commons`  % "test->test",
    `csw-location-server` % "test->compile;test->multi-jvm"
  )
  .enablePlugins(PublishBintray, GenJavadocPlugin, MaybeCoverage)
  .settings(libraryDependencies ++= Dependencies.AlarmClient.value)

lazy val `csw-alarm-cli` = project
  .dependsOn(
    `csw-alarm-client`,
    `csw-config-client`,
    `csw-location-client`,
    `csw-location-server` % "test->multi-jvm",
    `csw-config-server` % "test->test"
  )
  .enablePlugins(DeployApp, MaybeCoverage)
  .settings(libraryDependencies ++= Dependencies.AlarmCli.value)

lazy val `csw-commons` = project
  .enablePlugins(PublishBintray, GenJavadocPlugin)
  .settings(
    libraryDependencies ++= Dependencies.Commons.value
  )

lazy val `romaine` = project
  .enablePlugins(PublishBintray)
  .settings(
    libraryDependencies ++= Dependencies.Romaine.value
  )

lazy val `csw-benchmark` = project
  .dependsOn(
    `csw-logging`,
    `csw-params-jvm`,
    `csw-command`,
    `csw-location-server` % "compile->multi-jvm",
    `csw-framework` % "compile->compile;test->test"
  )
  .enablePlugins(NoPublish, JmhPlugin)
  .disablePlugins(BintrayPlugin)
  .settings(
    libraryDependencies ++= Dependencies.Benchmark.value
  )

//Integration test project
lazy val integration = project
  .dependsOn(
    `csw-location-server`,
    `csw-command`,
    `csw-location-agent`
  )
  .enablePlugins(DeployApp)
  .settings(
    libraryDependencies ++= Dependencies.Integration.value
  )

//Docs project
lazy val docs = project.enablePlugins(NoPublish, ParadoxSite)

//Example code
lazy val examples = project
  .dependsOn(
    `csw-location-server`,
    `csw-config-client`,
    `csw-config-server` % "test->test",
    `csw-logging`,
    `csw-params-jvm`,
    `csw-framework`
  )
  .enablePlugins(DeployApp)
  .settings(
    libraryDependencies ++= Dependencies.Examples.value
  )

lazy val alarm = taskKey[Unit]("alarm")

alarm := {
  (test in (`csw-alarm-client`, Test)).value
  (test in (`csw-alarm-api`, Test)).value
  (test in (`csw-alarm-cli`, Test)).value
}
