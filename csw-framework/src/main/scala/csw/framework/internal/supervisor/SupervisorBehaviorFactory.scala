package csw.framework.internal.supervisor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import csw.command.messages.{ComponentMessage, ContainerIdleMessage, SupervisorMessage}
import csw.framework.models.CswContext
import csw.framework.scaladsl.{ComponentBehaviorFactory, RegistrationFactory}

/**
 * The factory for creating [[akka.actor.typed.scaladsl.MutableBehavior]] of the supervisor of a component
 */
private[framework] object SupervisorBehaviorFactory {

  def make(
      containerRef: Option[ActorRef[ContainerIdleMessage]],
      registrationFactory: RegistrationFactory,
      cswCtx: CswContext
  ): Behavior[ComponentMessage] = {
    val componentWiringClass = Class.forName(cswCtx.componentInfo.behaviorFactoryClassName)
    val componentBehaviorFactory =
      componentWiringClass.getDeclaredConstructor().newInstance().asInstanceOf[ComponentBehaviorFactory]

    make(
      containerRef,
      registrationFactory,
      componentBehaviorFactory,
      cswCtx
    )
  }

  // This method is used by test
  def make(
      containerRef: Option[ActorRef[ContainerIdleMessage]],
      registrationFactory: RegistrationFactory,
      componentBehaviorFactory: ComponentBehaviorFactory,
      cswCtx: CswContext
  ): Behavior[ComponentMessage] = {
    Behaviors
      .withTimers[SupervisorMessage](
        timerScheduler ⇒
          Behaviors
            .setup[SupervisorMessage](
              ctx =>
                new SupervisorBehavior(
                  ctx,
                  timerScheduler,
                  containerRef,
                  componentBehaviorFactory,
                  registrationFactory,
                  cswCtx
              )
          )
      )
      .narrow
  }
}
