package csw.admin.server.wiring

import akka.actor.typed
import akka.actor.typed.SpawnProtocol
import com.typesafe.config.{Config, ConfigFactory}
import csw.admin.server.log.LogAdmin
import csw.admin.server.log.http.{AdminExceptionHandlers, AdminHttpService, AdminRoutes}
import csw.location.api.scaladsl.LocationService
import csw.location.client.ActorSystemFactory
import csw.location.client.scaladsl.HttpLocationServiceFactory

// $COVERAGE-OFF$
class AdminWiring {
  lazy val config: Config                                = ConfigFactory.load()
  lazy val settings                                      = new Settings(config)
  lazy val actorSystem: typed.ActorSystem[SpawnProtocol] = ActorSystemFactory.remote(SpawnProtocol.behavior, "admin-server")
  lazy val actorRuntime                                  = new ActorRuntime(actorSystem)

  lazy val locationService: LocationService   = HttpLocationServiceFactory.makeLocalClient(actorSystem, actorRuntime.mat)
  lazy val logAdmin: LogAdmin                 = new LogAdmin(locationService, actorRuntime)
  lazy val adminHandlers                      = new AdminExceptionHandlers
  lazy val adminRoutes                        = new AdminRoutes(logAdmin, actorRuntime, adminHandlers)
  lazy val adminHttpService: AdminHttpService = new AdminHttpService(adminRoutes, actorRuntime, settings)
}

object AdminWiring {

  def make(maybeAdminPort: Option[Int], locationHost: String = "localhost"): AdminWiring =
    new AdminWiring {
      override lazy val locationService: LocationService =
        HttpLocationServiceFactory.make(locationHost)(actorSystem, actorRuntime.mat)

      override lazy val settings: Settings = new Settings(config) {
        override val adminPort: Int = maybeAdminPort.getOrElse(super.adminPort)
      }
    }

}
// $COVERAGE-ON$
