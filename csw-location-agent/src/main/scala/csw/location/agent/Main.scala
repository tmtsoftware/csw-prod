package csw.location.agent

import csw.location.agent.commons.LocationAgentLogger
import csw.location.agent.models.Command
import csw.location.agent.utils.ArgsParser
import csw.location.api.commons.ClusterAwareSettings
import csw.location.client.ActorSystemFactory
import csw.logging.scaladsl.{Logger, LoggingSystemFactory}
import csw.services.BuildInfo

import scala.sys.process.Process

// $COVERAGE-OFF$
object Main extends App {
  if (ClusterAwareSettings.seedNodes.isEmpty) {
    println(
      "clusterSeeds setting is not specified either as env variable or system property. Please check online documentation for this set-up."
    )
  } else {
    new Main(startLogging = true).start(args)
  }
}
// $COVERAGE-ON$

/**
 * Application object allowing program execution from command line, also facilitates an entry point for Component level testing.
 */
class Main(startLogging: Boolean) {
  private val name        = BuildInfo.name
  private val log: Logger = LocationAgentLogger.getLogger

  def start(args: Array[String]): Option[Process] =
    new ArgsParser(name).parse(args).map { options =>
      val actorSystem = ActorSystemFactory.remote("csw-location-agent")

      if (startLogging)
        LoggingSystemFactory.start(name, BuildInfo.version, ClusterAwareSettings.hostname, actorSystem)

      val command = Command.parse(options)

      log.info(s"commandText: ${command.commandText}, command: ${command.toString}")
      val locationAgent = new LocationAgent(options.names, command, actorSystem)
      locationAgent.run()
    }
}
