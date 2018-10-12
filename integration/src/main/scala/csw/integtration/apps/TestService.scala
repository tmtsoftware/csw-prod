package csw.integtration.apps

import csw.integtration.common.TestFutureExtension.RichFuture
import csw.location.api.models.Connection.HttpConnection
import csw.location.api.models.{ComponentId, ComponentType, HttpRegistration, RegistrationResult}
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.location.server.internal.AdminWiring
import csw.logging.scaladsl.LoggingSystemFactory

object TestService {
  val componentId = ComponentId("redisservice", ComponentType.Service)
  val connection  = HttpConnection(componentId)

  private val Path = "redisservice.org/test"
  private val Port = 9999

  val adminWiring: AdminWiring = AdminWiring.make(Some(3553))
  LoggingSystemFactory.start("Assembly", "1.0", adminWiring.clusterSettings.hostname, adminWiring.actorSystem)

  adminWiring.locationHttpService.start().await

  import adminWiring.actorRuntime._

  val registration                           = HttpRegistration(connection, Port, Path)
  val registrationResult: RegistrationResult = HttpLocationServiceFactory.makeLocalClient.register(registration).await

  print("Redis Service Registered")

  def main(args: Array[String]): Unit = {}
}
