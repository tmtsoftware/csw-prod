package csw.framework.internal.supervisor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import csw.command.client.messages.{ComponentMessage, ContainerIdleMessage, SupervisorMessage}
import csw.framework.models.CswContext
import csw.framework.scaladsl.TopLevelComponent.InitializeMessage
import csw.framework.scaladsl.{ComponentBehaviorFactory, RegistrationFactory}

/**
 * The factory for creating [[akka.actor.typed.scaladsl.AbstractBehavior]] of the supervisor of a component
 */
private[framework] object SupervisorBehavior2Factory {

  private def getTLAInstance(fullyQualifiedClassName: String, properties: CswContext): Behavior[InitializeMessage] = {
    val clazz = Class.forName(fullyQualifiedClassName)
    val applyMethod = clazz.getDeclaredMethod("apply", classOf[CswContext])
    applyMethod.invoke(applyMethod, properties).asInstanceOf[Behavior[InitializeMessage]]
  }

  def make(
      containerRef: Option[ActorRef[ContainerIdleMessage]],
      registrationFactory: RegistrationFactory,
      cswCtx: CswContext
  ): Behavior[ComponentMessage] = {
    val tlaInitBehavior = getTLAInstance(cswCtx.componentInfo.behaviorFactoryClassName, cswCtx)

    make(
      containerRef,
      registrationFactory,
      tlaInitBehavior,
      cswCtx
    )
  }

  // This method is used by test
  def make(
      containerRef: Option[ActorRef[ContainerIdleMessage]],
      registrationFactory: RegistrationFactory,
      tlaInitBehavior: Behavior[InitializeMessage],
      cswCtx: CswContext
  ): Behavior[ComponentMessage] = {
    Behaviors
      .withTimers[SupervisorMessage](timerScheduler =>
        Behaviors
          .setup[SupervisorMessage](ctx =>
            SupervisorBehavior2(
              ctx,
              timerScheduler,
              containerRef,
              tlaInitBehavior,
              registrationFactory,
              cswCtx
            )
          )
      )
      .narrow
  }
}
