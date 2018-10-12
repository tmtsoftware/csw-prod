package csw.admin.server

import akka.Done
import akka.actor.CoordinatedShutdown
import csw.admin.server.cli.{ArgsParser, Options}
import csw.admin.server.internal.AdminWiring
import csw.location.api.commons.ClusterAwareSettings
import csw.services.BuildInfo
import scala.concurrent.duration.DurationDouble

/**
 * responsible for starting http server which exposes http end point to change/get the log level of components dynamically
 *
 * */
// $COVERAGE-OFF$
object Main extends App {
  private val name = BuildInfo.name

  new ArgsParser(name).parse(args).foreach {
    case Options(maybeAdminPort) =>
      if (ClusterAwareSettings.seedNodes.isEmpty) {
        println(
          "[ERROR] clusterSeeds setting is not specified either as env variable or system property. Please check online documentation for this set-up."
        )
      } else {
        val wiring = AdminWiring.make(maybeAdminPort)
        import wiring._
        import actorRuntime._
        startLogging(name)

        val logAdminBindingF = adminHttpService.registeredLazyBinding

        coordinatedShutdown.addTask(
          CoordinatedShutdown.PhaseServiceUnbind,
          "unbind-services"
        ) { () ⇒
          val hardDeadline = 30.seconds
          logAdminBindingF.flatMap(_.terminate(hardDeadline)).map(_ ⇒ Done)
        }
      }
  }
}
// $COVERAGE-ON$
