package csw.common.components.command

import akka.actor.typed.scaladsl.ActorContext
import csw.common.components.command.ComponentStateForCommand._
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.command.messages.CommandResponseManagerMessage.AddOrUpdateCommand
import csw.command.messages.TopLevelActorMessage
import csw.params.commands.CommandResponse.{Accepted, Completed, Error}
import csw.params.commands.{CommandResponse, ControlCommand}
import csw.location.api.models.TrackingEvent
import csw.messages.CommandResponseManagerMessage.AddOrUpdateCommand
import csw.messages.TopLevelActorMessage
import csw.messages.commands.CommandResponse.{Completed, Error}
import csw.messages.commands.{CommandResponse, ControlCommand, ValidationResponse}
import csw.messages.framework.ComponentInfo
import csw.messages.location.TrackingEvent

import scala.concurrent.Future
import scala.concurrent.duration.DurationLong

class McsHcdComponentHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._
  override def initialize(): Future[Unit] = Future.unit

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = ???

  override def validateCommand(controlCommand: ControlCommand): ValidationResponse = {
    println("HCD Validate")
    controlCommand.commandName match {
      case `longRunning`               ⇒ Accepted(controlCommand.runId)
      case `mediumRunning`             ⇒ Accepted(controlCommand.runId)
      case `shortRunning`              ⇒ Accepted(controlCommand.runId)
      case `failureAfterValidationCmd` ⇒ Accepted(controlCommand.runId)
      case _ ⇒
        Invalid(controlCommand.runId, UnsupportedCommandIssue(controlCommand.commandName.name))
    }
  }

  override def onSubmit(controlCommand: ControlCommand): SubmitResponse = {
    println("HCD ON submit: " + controlCommand)
    controlCommand.commandName match {
      case `longRunning` ⇒
        println("Got long")
        ctx.schedule(5.seconds,
                     commandResponseManager.commandResponseManagerActor,
                     AddOrUpdateCommand(controlCommand.runId, Error(controlCommand.runId, "What")))
        //Error(controlCommand.runId, "WTF1")
        Started(controlCommand.runId)
      case `mediumRunning` ⇒
        println("God medium")
        ctx.schedule(3.seconds,
                     commandResponseManager.commandResponseManagerActor,
                     AddOrUpdateCommand(controlCommand.runId, Error(controlCommand.runId, "two")))
        Started(controlCommand.runId)
      //Error(controlCommand.runId, "WTF2")
      case `shortRunning` ⇒
        println("Got short")
        ctx.schedule(1.seconds,
                     commandResponseManager.commandResponseManagerActor,
                     AddOrUpdateCommand(controlCommand.runId, Error(controlCommand.runId, "three")))
        Started(controlCommand.runId)
//        Error(controlCommand.runId, "WTF3")
      case `failureAfterValidationCmd` ⇒
        commandResponseManager.addOrUpdateCommand(controlCommand.runId, Error(controlCommand.runId, "Failed command"))
        Error(controlCommand.runId, "Failed command")
      case _ ⇒
        Error(controlCommand.runId, "Unknown Command")
    }
  }

  override def onOneway(controlCommand: ControlCommand): Unit = ???

  override def onShutdown(): Future[Unit] = ???

  override def onGoOffline(): Unit = ???

  override def onGoOnline(): Unit = ???
}
