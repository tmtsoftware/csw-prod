package csw.integtration.apps

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{Actor, ActorRef, Props}
import csw.command.messages.CommandMessage.Submit
import csw.integtration.common.TestFutureExtension.RichFuture
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaRegistration, ComponentId, ComponentType, RegistrationResult}
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.location.server.internal.AdminWiring
import csw.logging.scaladsl.LoggingSystemFactory
import csw.params.commands.{CommandName, Setup}
import csw.params.core.models.Prefix

object TromboneHCD {

  val adminWiring: AdminWiring = AdminWiring.make(Some(3553))
  LoggingSystemFactory.start("Assembly", "1.0", adminWiring.clusterSettings.hostname, adminWiring.actorSystem)

  adminWiring.locationHttpService.start().await

  import adminWiring.actorRuntime._

  val tromboneHcdActorRef: ActorRef = actorSystem.actorOf(Props[TromboneHCD], "trombone-hcd")
  val componentId                   = ComponentId("trombonehcd", ComponentType.HCD)
  val connection                    = AkkaConnection(componentId)

  val registration                           = AkkaRegistration(connection, Prefix("nfiraos.ncc.trombone"), tromboneHcdActorRef)
  private val locationService                = HttpLocationServiceFactory.makeLocalClient
  val registrationResult: RegistrationResult = locationService.register(registration).await

  println("Trombone HCD registered")

  def main(args: Array[String]): Unit = {}
}

class TromboneHCD extends Actor {
  import TromboneHCD._

  override def receive: Receive = {
    case Submit(Setup(_, _, CommandName("Unregister"), None, _), _) ⇒ registrationResult.unregister()
  }
}
