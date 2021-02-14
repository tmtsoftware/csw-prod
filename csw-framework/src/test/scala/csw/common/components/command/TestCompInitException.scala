package csw.common.components.command

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Behavior
import csw.framework.exceptions.FailureRestart
import csw.framework.models.CswContext
import csw.framework.scaladsl.TopLevelComponent._

object TestCompInitException {

  case class MyFailure(msg: String) extends FailureRestart(s"What the Fuck!! + $msg")

  def apply(cswCtx: CswContext): Behavior[InitializeMessage] = {
    import InitializeMessage._

    val log = cswCtx.loggerFactory.getLogger

      Behaviors.receiveMessage[InitializeMessage] {
        case Initialize(_) =>
          log.debug("Initializing InitCompInitException test")
          log.info(s"Throwing MyFailure exception")

          throw MyFailure("WTF")
          Behaviors.same
        case _ =>
          Behaviors.same
      }.receiveSignal {
        case (_, signal) =>
          log.debug(s"InitCompInitException test got signal: $signal")
          Behaviors.same
      }
    }
}
