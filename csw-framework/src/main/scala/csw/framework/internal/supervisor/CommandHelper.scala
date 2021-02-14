package csw.framework.internal.supervisor

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import csw.command.client.MiniCRM.CRMMessage
import csw.command.client.MiniCRM.MiniCRMMessage.{AddResponse, AddStarted}
import csw.framework.scaladsl.TopLevelComponent
import csw.logging.client.scaladsl.LoggerFactory
import csw.params.commands.CommandResponse._
import csw.params.commands.{CommandResponse, ControlCommand}
import csw.params.core.models.Id

object CommandHelper {
  import TopLevelComponent.RunningMessage

  def apply(
      id: Id,
      tla: ActorRef[RunningMessage],
      cmdMsg: ControlCommand,
      crm: ActorRef[CRMMessage],
      loggerFactory: LoggerFactory,
      replyTo: ActorRef[SubmitResponse]
  ): Behavior[CommandResponse] = {
    val log = loggerFactory.getLogger

    log.debug(s"Started CommandHelper for command: $cmdMsg with id: $id and replyTo: $replyTo")

    Behaviors.setup { context =>
      // First send the validate request
      tla ! RunningMessage.Validate(id, cmdMsg, context.self.narrow[ValidateCommandResponse])

      Behaviors.receiveMessage[CommandResponse] {
        case _: Accepted =>
          log.debug(s"Command with id: $id Accepted")
          tla ! RunningMessage.Submit(id, cmdMsg, context.self)
          Behaviors.same
        case invalid: Invalid =>
          replyTo ! invalid
          Behaviors.stopped
        case c: Completed =>
          crm ! AddResponse(c)
          replyTo ! c
          Behaviors.stopped
        case e: Error =>
          crm ! AddResponse(e)
          replyTo ! e
          Behaviors.stopped
        case c: Cancelled =>
          crm ! AddResponse(c)
          replyTo ! c
          Behaviors.stopped
        case l: Locked =>
          crm ! AddResponse(l)
          Behaviors.stopped
        case s: Started =>
          crm ! AddStarted(s)
          replyTo ! s
          Behaviors.same
      } /*.receiveSignal {
        case (context: ActorContext[CommandResponse], PostStop) =>
          println(s"PostStop signal for command $id received")
          Behaviors.same
      }
       */
    }
  }
}

object ValidateHelper {

  def apply(id: Id, loggerFactory: LoggerFactory, replyTo: ActorRef[ValidateResponse]): Behavior[ValidateCommandResponse] = {
    val log = loggerFactory.getLogger

    log.debug(s"Started ValidateHelper for: $Id:$replyTo")

    Behaviors
      .receiveMessage[ValidateCommandResponse] {
        vr: ValidateCommandResponse =>
          replyTo ! vr.asInstanceOf[ValidateResponse]
          Behaviors.stopped
      }
      .receiveSignal {
        case (_: ActorContext[ValidateCommandResponse], PostStop) =>
          log.debug(s"ValidateHelper received postStop signal for: $id")
          Behaviors.same
      }
  }
}

object OnewayHelper {
  import TopLevelComponent.RunningMessage

  def apply(
      id: Id,
      tla: ActorRef[RunningMessage],
      cmdMsg: ControlCommand,
      loggerFactory: LoggerFactory,
      replyTo: ActorRef[OnewayResponse]
  ): Behavior[ValidateCommandResponse] = {
    val log = loggerFactory.getLogger

    log.debug(s"Started Oneway Helper for: $Id:$replyTo")

    Behaviors.setup { context =>
      tla ! RunningMessage.Validate(id, cmdMsg, context.self.narrow[ValidateCommandResponse])

      Behaviors
        .receiveMessage[ValidateCommandResponse] {
          case r @ Accepted(id) =>
            log.debug(s"Oneway with id: $id Accepted")
            replyTo ! r.asInstanceOf[OnewayResponse]
            tla ! RunningMessage.Oneway(id, cmdMsg)
            Behaviors.stopped
          case r:Invalid =>
            replyTo ! r.asInstanceOf[OnewayResponse]
            Behaviors.stopped
        }
        .receiveSignal {
          case (_: ActorContext[ValidateCommandResponse], PostStop) =>
            log.debug(s"OnewayHelper received postStop signal for: $id")
            Behaviors.same
        }
    }

  }
}
