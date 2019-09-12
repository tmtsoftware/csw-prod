addSbtPlugin("com.typesafe.sbt"                  % "sbt-site"                   % "1.3.3")
addSbtPlugin("io.github.jonas"                   % "sbt-paradox-material-theme" % "0.6.0")
addSbtPlugin("org.scalastyle"                    %% "scalastyle-sbt-plugin"     % "1.0.0")
addSbtPlugin("org.scalameta"                     % "sbt-scalafmt"               % "2.0.4")
addSbtPlugin("com.dwijnand"                      % "sbt-dynver"                 % "4.0.0")
addSbtPlugin("com.eed3si9n"                      % "sbt-unidoc"                 % "0.4.2")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings"           % "3.0.0")
addSbtPlugin("org.foundweekends"                 % "sbt-bintray"                % "0.5.5")
addSbtPlugin("com.timushev.sbt"                  % "sbt-updates"                % "0.4.2")
addSbtPlugin("com.typesafe.sbt"                  % "sbt-ghpages"                % "0.6.3")
addSbtPlugin("org.scoverage"                     % "sbt-scoverage"              % "1.6.0")
addSbtPlugin("com.typesafe.sbt"                  % "sbt-native-packager"        % "1.4.1")
addSbtPlugin("com.typesafe.sbt"                  % "sbt-multi-jvm"              % "0.4.0")
addSbtPlugin("com.eed3si9n"                      % "sbt-buildinfo"              % "0.9.0")
addSbtPlugin("pl.project13.scala"                % "sbt-jmh"                    % "0.3.7")
addSbtPlugin("com.orrsella"                      % "sbt-stats"                  % "1.0.7")
addSbtPlugin("de.johoop"                         % "sbt-testng-plugin"          % "3.1.1")
addSbtPlugin("io.spray"                          % "sbt-revolver"               % "0.9.1")
addSbtPlugin("com.typesafe.sbt"                  % "sbt-git"                    % "1.0.0")
addSbtPlugin("org.portable-scala"                % "sbt-scalajs-crossproject"   % "0.6.1")
addSbtPlugin("org.scala-js"                      % "sbt-scalajs"                % "0.6.28")
addSbtPlugin("com.github.cb372"                  % "sbt-explicit-dependencies"  % "0.2.10")

resolvers += "Jenkins repo" at "https://repo.jenkins-ci.org/public/"
addSbtPlugin("ohnosequences" % "sbt-github-release" % "0.7.0")

classpathTypes += "maven-plugin"

libraryDependencies += "com.sun.activation" % "javax.activation" % "1.2.0"

scalacOptions ++= Seq(
  "-encoding",
  "UTF-8",
  "-feature",
  "-unchecked",
  "-deprecation",
  //"-Xfatal-warnings",
  "-Xlint:-unused,_",
  "-Ywarn-dead-code"
)
