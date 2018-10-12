package csw.framework.internal.supervisor

import akka.actor.ActorSystem
import akka.actor.typed.ActorRef
import csw.alarm.client.AlarmServiceFactory
import csw.command.messages.ContainerIdleMessage
import csw.command.models.framework.{Component, ComponentInfo, SupervisorInfo}
import csw.event.client.EventServiceFactory
import csw.framework.internal.wiring.CswFrameworkSystem
import csw.framework.models.CswContext
import csw.framework.scaladsl.RegistrationFactory
import csw.location.api.scaladsl.LocationService
import csw.location.client.ActorSystemFactory
import csw.logging.scaladsl.{Logger, LoggerFactory}

import scala.async.Async._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.control.NonFatal

/**
 * The factory for creating supervisor actors of a component specified by [[csw.command.models.framework.ComponentInfo]]
 */
private[framework] class SupervisorInfoFactory(containerName: String) {
  private val log: Logger = new LoggerFactory(containerName).getLogger

  def make(
      containerRef: ActorRef[ContainerIdleMessage],
      componentInfo: ComponentInfo,
      locationService: LocationService,
      eventServiceFactory: EventServiceFactory,
      alarmServiceFactory: AlarmServiceFactory,
      registrationFactory: RegistrationFactory
  ): Future[Option[SupervisorInfo]] = {
    implicit val system: ActorSystem          = ActorSystemFactory.remote(s"${componentInfo.name}-system")
    implicit val ec: ExecutionContextExecutor = system.dispatcher
    val richSystem                            = new CswFrameworkSystem(system)

    async {
      val cswCtxF = CswContext.make(locationService, eventServiceFactory, alarmServiceFactory, componentInfo)(richSystem)
      val supervisorBehavior =
        SupervisorBehaviorFactory.make(Some(containerRef), registrationFactory, await(cswCtxF))
      val actorRefF = richSystem.spawnTyped(supervisorBehavior, componentInfo.name)
      Some(SupervisorInfo(system, Component(await(actorRefF), componentInfo)))
    } recoverWith {
      case NonFatal(exception) ⇒
        async {
          log.error(s"Exception :[${exception.getMessage}] occurred while spawning supervisor: [${componentInfo.name}]",
                    ex = exception)
          await(system.terminate())
          None
        }
    }
  }
}
