package csw.integtration.apps

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{Actor, ActorPath, ActorRef, Props}
import akka.serialization.Serialization
import csw.integtration.common.TestFutureExtension.RichFuture
import csw.location.api.commons.ClusterAwareSettings
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaRegistration, ComponentId, ComponentType, RegistrationResult}
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.location.server.internal.AdminWiring
import csw.logging.scaladsl.LoggingSystemFactory
import csw.params.core.models.Prefix

object AssemblyApp {

  val adminWiring: AdminWiring = AdminWiring.make(ClusterAwareSettings.onPort(3553).withInterface("eth1"))
  LoggingSystemFactory.start("Assembly", "1.0", adminWiring.clusterSettings.hostname, adminWiring.actorSystem)
  adminWiring.locationHttpService.start().await

  import adminWiring.actorRuntime._

  val assemblyActorRef: ActorRef = actorSystem.actorOf(Props[AssemblyApp], "assembly")
  val componentId                = ComponentId("assembly", ComponentType.Assembly)
  val connection                 = AkkaConnection(componentId)

  val actorPath: ActorPath                   = ActorPath.fromString(Serialization.serializedActorPath(assemblyActorRef))
  val registration                           = AkkaRegistration(connection, Prefix("nfiraos.ncc.trombone"), assemblyActorRef)
  val registrationResult: RegistrationResult = HttpLocationServiceFactory.makeLocalClient.register(registration).await

  def main(args: Array[String]): Unit = {}

}

class AssemblyApp extends Actor {
  override def receive: Receive = {
    case "Unregister" => AssemblyApp.registrationResult.unregister()
  }
}
