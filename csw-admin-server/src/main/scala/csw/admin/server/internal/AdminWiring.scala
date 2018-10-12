package csw.admin.server.internal

import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import csw.admin.server.log.LogAdmin
import csw.admin.server.log.http.{AdminExceptionHandlers, AdminHttpService, AdminRoutes}
import csw.location.api.commons.ClusterSettings
import csw.location.api.scaladsl.LocationService
import csw.location.client.scaladsl.HttpLocationServiceFactory

// $COVERAGE-OFF$
private[admin] class AdminWiring {
  lazy val config: Config           = ConfigFactory.load()
  lazy val settings                 = new Settings(config)
  lazy val clusterSettings          = ClusterSettings()
  lazy val actorSystem: ActorSystem = clusterSettings.system
  lazy val actorRuntime             = new ActorRuntime(actorSystem)

  //TODO: Decide on whether to make local or remote http location client.
  lazy val locationService: LocationService   = HttpLocationServiceFactory.makeLocalClient(actorSystem, actorRuntime.mat)
  lazy val logAdmin: LogAdmin                 = new LogAdmin(locationService, actorRuntime)
  lazy val adminHandlers                      = new AdminExceptionHandlers
  lazy val adminRoutes                        = new AdminRoutes(logAdmin, actorRuntime, adminHandlers)
  lazy val adminHttpService: AdminHttpService = new AdminHttpService(adminRoutes, actorRuntime, settings)
}

private[admin] object AdminWiring {

  def make(maybeAdminPort: Option[Int]): AdminWiring =
    new AdminWiring {
      override lazy val settings: Settings = new Settings(config) {
        override val adminPort: Int = maybeAdminPort.getOrElse(super.adminPort)
      }
    }

  def make(_clusterSettings: ClusterSettings, maybeAdminPort: Option[Int]): AdminWiring =
    new AdminWiring {
      override lazy val clusterSettings: ClusterSettings = _clusterSettings
      override lazy val settings: Settings = new Settings(config) {
        override val adminPort: Int = maybeAdminPort.getOrElse(super.adminPort)
      }
    }

}
// $COVERAGE-ON$
