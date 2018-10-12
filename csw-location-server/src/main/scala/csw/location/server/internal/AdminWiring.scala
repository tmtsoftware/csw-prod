package csw.location.server.internal

import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import csw.location.api.commons.{ClusterAwareSettings, ClusterSettings}
import csw.location.api.scaladsl.LocationService
import csw.location.server.http.{LocationExceptionHandler, LocationHttpService, LocationRoutes}

// $COVERAGE-OFF$
private[csw] class AdminWiring {
  lazy val config: Config                   = ConfigFactory.load()
  lazy val settings                         = new Settings(config)
  lazy val clusterSettings: ClusterSettings = ClusterAwareSettings.onPort(settings.clusterPort)
  lazy val actorSystem: ActorSystem         = clusterSettings.system
  lazy val actorRuntime                     = new ActorRuntime(actorSystem)
  lazy val locationService: LocationService = LocationServiceFactory.withSystem(actorSystem)
  lazy val locationExceptionHandler         = new LocationExceptionHandler
  lazy val locationRoutes                   = new LocationRoutes(locationService, locationExceptionHandler, actorRuntime)
  lazy val locationHttpService              = new LocationHttpService(locationRoutes, actorRuntime, settings)
}

private[csw] object AdminWiring {

  def make(maybeClusterPort: Option[Int]): AdminWiring =
    new AdminWiring {
      override lazy val settings: Settings = new Settings(config) {
        override val clusterPort: Int = maybeClusterPort.getOrElse(super.clusterPort)
      }
    }

  def make(maybeClusterPort: Option[Int], mayBeHttpPort: Option[Int]): AdminWiring =
    new AdminWiring {
      override lazy val settings: Settings = {
        new Settings(config) {
          override val clusterPort: Int = maybeClusterPort.getOrElse(super.clusterPort)
          override val httpPort: Int    = mayBeHttpPort.getOrElse(super.httpPort)
        }
      }
    }

  def make(_clusterSettings: ClusterSettings): AdminWiring =
    new AdminWiring {
      override lazy val clusterSettings: ClusterSettings = _clusterSettings
    }
}
// $COVERAGE-ON$
