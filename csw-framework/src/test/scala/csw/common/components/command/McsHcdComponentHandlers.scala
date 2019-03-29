package csw.common.components.command

import akka.actor.typed.scaladsl.ActorContext
import csw.common.components.command.ComponentStateForCommand._
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.command.client.messages.CommandResponseManagerMessage.AddOrUpdateCommand
import csw.command.client.messages.TopLevelActorMessage
import csw.params.commands.CommandResponse._
import csw.params.commands.ControlCommand
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandIssue.UnsupportedCommandIssue
import csw.params.core.models.Id

import scala.concurrent.Future
import scala.concurrent.duration.DurationLong

class McsHcdComponentHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._
  override def initialize(): Future[Unit] = Future.unit

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = ???

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    controlCommand.commandName match {
      case `longRunning`               ⇒ Accepted(runId)
      case `mediumRunning`             ⇒ Accepted(runId)
      case `shortRunning`              ⇒ Accepted(runId)
      case `failureAfterValidationCmd` ⇒ Accepted(runId)
      case _ ⇒
        Invalid(Id(), UnsupportedCommandIssue(controlCommand.commandName.name))
    }
  }

  //#addOrUpdateCommand
  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    controlCommand.commandName match {
      case `longRunning` ⇒
        println(s"HCD Updating: $runId with Started")
        ctx.scheduleOnce(5.seconds, commandResponseManager.commandResponseManagerActor, AddOrUpdateCommand(Completed(runId)))
        Started(runId)
      //#addOrUpdateCommand
      case `mediumRunning` ⇒
        ctx.scheduleOnce(3.seconds, commandResponseManager.commandResponseManagerActor, AddOrUpdateCommand(Completed(runId)))
        Started(runId)
      case `shortRunning` ⇒
        ctx.scheduleOnce(1.seconds, commandResponseManager.commandResponseManagerActor, AddOrUpdateCommand(Completed(runId)))
        Started(runId)
      case `failureAfterValidationCmd` ⇒
        commandResponseManager.addOrUpdateCommand(Error(runId, "Failed command"))
        Error(runId, "Failed command")
      case _ ⇒
        Error(runId, "Unknown Command")
    }
  }

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = ???

  override def onShutdown(): Future[Unit] = ???

  override def onGoOffline(): Unit = ???

  override def onGoOnline(): Unit = ???
}
