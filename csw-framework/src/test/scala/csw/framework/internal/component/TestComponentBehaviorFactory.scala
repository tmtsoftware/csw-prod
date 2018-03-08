package csw.framework.internal.component

import akka.actor.typed.scaladsl.ActorContext
import csw.framework.scaladsl.{ComponentBehaviorFactory, ComponentHandlers, CurrentStatePublisher}
import csw.messages.TopLevelActorMessage
import csw.messages.framework.ComponentInfo
import csw.services.ccs.scaladsl.CommandResponseManager
import csw.services.location.scaladsl.LocationService
import csw.services.logging.scaladsl.LoggerFactory

class TestComponentBehaviorFactory(componentHandlers: ComponentHandlers) extends ComponentBehaviorFactory {

  override protected def handlers(
      ctx: ActorContext[TopLevelActorMessage],
      componentInfo: ComponentInfo,
      commandResponseManager: CommandResponseManager,
      currentStatePublisher: CurrentStatePublisher,
      locationService: LocationService,
      loggerFactory: LoggerFactory
  ): ComponentHandlers = componentHandlers
}
