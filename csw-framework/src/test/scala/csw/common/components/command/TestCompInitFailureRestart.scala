package csw.common.components.command

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Behavior
import csw.framework.models.CswContext
import csw.framework.scaladsl.TopLevelComponent._

object TestCompInitFailureRestart {

  def apply(cswCtx: CswContext): Behavior[InitializeMessage] = {
    import InitializeMessage._

    val log = cswCtx.loggerFactory.getLogger

    Behaviors.receiveMessage[InitializeMessage] {
      case Initialize(ref) =>
        log.debug("Initializing InitFailureRestart test")
        ref ! InitializeFailureRestart
        log.info(s"TestCompInitFailureRestart sends InitializeFailureRestart to: $ref")
        Behaviors.same
      case _ =>
        log.debug("InitFailureRestart test should never get here!")
        Behaviors.same
    }
  }
}
