import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbt.Def.{setting => dep}
import sbt._

object Libs {
  val ScalaVersion    = "2.13.0"
  val silencerVersion = "1.4.2"

  val `scalatest` = dep("org.scalatest" %%% "scalatest" % "3.0.8") //Apache License 2.0

  val `scala-java8-compat` = "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0" //BSD 3-clause "New" or "Revised" License
  val `scala-async`        = dep("org.scala-lang.modules" %% "scala-async" % "0.10.0") //BSD 3-clause "New" or "Revised" License
  val `scopt`              = "com.github.scopt" %% "scopt" % "3.7.1" //MIT License
  val `junit`              = "junit" % "junit" % "4.12" //Eclipse Public License 1.0
  val `silencer-plugin`    = compilerPlugin("com.github.ghik" %% "silencer-plugin" % silencerVersion)
  val `silencer-lib`       = "com.github.ghik" %% "silencer-lib" % silencerVersion % Compile
  val `mockito-scala`      = "org.mockito" %% "mockito-scala" % "1.5.15"
  //MIT License
  val `embedded-keycloak`            = "com.github.tmtsoftware.embedded-keycloak" %% "embedded-keycloak"            % "0.1.6" //Apache 2.0
  val `logback-classic`              = "ch.qos.logback"                           % "logback-classic"               % "1.2.3" //Dual license: Either, Eclipse Public License v1.0 or GNU Lesser General Public License version 2.1
  val `akka-management-cluster-http` = "com.lightbend.akka.management"            %% "akka-management-cluster-http" % "1.0.3"
  val `svnkit`                       = "org.tmatesoft.svnkit"                     % "svnkit"                        % "1.10.1" //TMate Open Source License
  val `commons-codec`                = "commons-codec"                            % "commons-codec"                 % "1.10" //Apache 2.0
  val `scala-reflect`                = "org.scala-lang"                           % "scala-reflect"                 % ScalaVersion //BSD-3
  val `gson`                         = "com.google.code.gson"                     % "gson"                          % "2.8.5" //Apache 2.0

  val `play-json` = dep("com.typesafe.play" %%% "play-json" % "2.7.4") //Apache 2.0

  val `akka-http-play-json` = "de.heikoseeberger"   %% "akka-http-play-json" % "1.27.0" //Apache 2.0
  val `jwt-play-json`       = "com.pauldijou"       %% "jwt-play-json"       % "4.0.0"
  val `lettuce`             = "io.lettuce"          % "lettuce-core"         % "5.1.8.RELEASE"
  val `reactor-core`        = "io.projectreactor"   % "reactor-core"         % "3.2.12.RELEASE"
  val `reactive-streams`    = "org.reactivestreams" % "reactive-streams"     % "1.0.3"
  val `akka-stream-kafka`   = "com.typesafe.akka"   %% "akka-stream-kafka"   % "1.0.5"
//  val `embedded-kafka`      = "io.github.embeddedkafka" %% "embedded-kafka"      % "2.3.0"
  val `embedded-redis` = "com.github.kstyrc" % "embedded-redis" % "0.6"
  val `scala-compiler` = "org.scala-lang"    % "scala-compiler" % ScalaVersion
  val `HdrHistogram`   = "org.hdrhistogram"  % "HdrHistogram"   % "2.1.11"
  val `testng`         = "org.testng"        % "testng"         % "6.14.3"

  val `scala-csv`                = "com.github.tototoshi" %% "scala-csv" % "1.3.6"
  val `scalajs-java-time`        = dep("org.scala-js" %%% "scalajs-java-time" % "0.2.5")
  val `json-schema-validator`    = "com.github.fge" % "json-schema-validator" % "2.2.11" //LGPL/ASL
  val `play-json-derived-codecs` = dep("org.julienrf" %%% "play-json-derived-codecs" % "6.0.0")

  val `jna`             = "net.java.dev.jna"              % "jna"             % "5.4.0"
  val `postgresql`      = "org.postgresql"                % "postgresql"      % "42.2.6"
  val `hikaricp`        = "com.zaxxer"                    % "HikariCP"        % "3.3.1" //Apache License 2.0
  val `otj-pg-embedded` = "com.opentable.components"      % "otj-pg-embedded" % "0.13.1"
  val httpclient        = "org.apache.httpcomponents"     % "httpclient"      % "4.5.9"
  val `jboss-logging`   = "org.jboss.logging"             % "jboss-logging"   % "3.4.1.Final"
  val `config`          = "com.typesafe"                  % "config"          % "1.3.4"
  val `os-lib`          = "com.lihaoyi"                   %% "os-lib"         % "0.3.0"
  val `caffeine`        = "com.github.ben-manes.caffeine" % "caffeine"        % "2.8.0"

}

object Borer {
  val Version = "0.11.1"
  val Org     = "io.bullet"

  val `borer-core`        = dep(Org %%% "borer-core"        % Version)
  val `borer-derivation`  = dep(Org %%% "borer-derivation"  % Version)
  val `borer-compat-akka` = dep(Org %%% "borer-compat-akka" % Version)
}

object Jackson {
  val Version                = "2.9.9"
  val `jackson-core`         = "com.fasterxml.jackson.core" % "jackson-core" % Version
  val `jackson-databind`     = "com.fasterxml.jackson.core" % "jackson-databind" % Version
  val `jackson-module-scala` = "com.fasterxml.jackson.module" %% "jackson-module-scala" % Version
}

object Enumeratum {
  val `enumeratum`           = dep("com.beachape" %%% "enumeratum"           % "1.5.13") //MIT License
  val `enumeratum-play-json` = dep("com.beachape" %%% "enumeratum-play-json" % "1.5.16") //MIT License
}

object Akka {
  val Version                    = "2.5.25" //all akka is Apache License 2.0
  val `akka-stream`              = "com.typesafe.akka" %% "akka-stream" % Version
  val `akka-stream-typed`        = "com.typesafe.akka" %% "akka-stream-typed" % Version
  val `akka-remote`              = "com.typesafe.akka" %% "akka-remote" % Version
  val `akka-stream-testkit`      = "com.typesafe.akka" %% "akka-stream-testkit" % Version
  val `akka-actor`               = "com.typesafe.akka" %% "akka-actor" % Version
  val `akka-actor-typed`         = "com.typesafe.akka" %% "akka-actor-typed" % Version
  val `akka-actor-testkit-typed` = "com.typesafe.akka" %% "akka-actor-testkit-typed" % Version
  val `akka-distributed-data`    = "com.typesafe.akka" %% "akka-distributed-data" % Version
  val `akka-multi-node-testkit`  = "com.typesafe.akka" %% "akka-multi-node-testkit" % Version
  val `akka-cluster-tools`       = "com.typesafe.akka" %% "akka-cluster-tools" % Version
  val `akka-cluster`             = "com.typesafe.akka" %% "akka-cluster" % Version
  val `akka-cluster-typed`       = "com.typesafe.akka" %% "akka-cluster-typed" % Version
  val `akka-slf4j`               = "com.typesafe.akka" %% "akka-slf4j" % Version
  val `cluster-sharding`         = "com.typesafe.akka" %% "akka-cluster-sharding" % Version
  val `akka-persistence`         = "com.typesafe.akka" %% "akka-persistence" % Version
}

object AkkaHttp {
  val Version             = "10.1.9"
  val `akka-http`         = "com.typesafe.akka" %% "akka-http" % Version //ApacheV2
  val `akka-http-testkit` = "com.typesafe.akka" %% "akka-http-testkit" % Version //ApacheV2
  val `akka-http-cors`    = "ch.megard" %% "akka-http-cors" % "0.4.1"
}

object Keycloak {
  val Version                 = "7.0.0"
  val `keycloak-adapter-core` = "org.keycloak" % "keycloak-adapter-core" % Version
  val `keycloak-core`         = "org.keycloak" % "keycloak-core" % Version
  val `keycloak-installed`    = "org.keycloak" % "keycloak-installed-adapter" % Version
  val `keycloak-authz`        = "org.keycloak" % "keycloak-authz-client" % Version
  val `keycloak-test-helper`  = "org.keycloak" % "keycloak-test-helper" % Version
}

object Jooq {
  val Version        = "3.12.1"
  val `jooq`         = "org.jooq" % "jooq" % Version
  val `jooq-meta`    = "org.jooq" % "jooq-meta" % Version
  val `jooq-codegen` = "org.jooq" % "jooq-codegen" % Version
}
