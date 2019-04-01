package csw.common.components.command

import akka.actor.Scheduler
import akka.actor.typed.scaladsl.ActorContext
import akka.util.Timeout
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.command.client.messages.TopLevelActorMessage
import csw.common.components.command.ComponentStateForCommand.{longRunningCmdCompleted, _}
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.{AkkaLocation, TrackingEvent}
import csw.params.commands.CommandIssue.UnsupportedCommandIssue
import csw.params.commands.CommandResponse._
import csw.params.commands.{CommandIssue, ControlCommand, Setup}
import csw.params.core.models.Id
import csw.params.core.states.{CurrentState, StateName}

import scala.concurrent.duration.DurationDouble
import scala.concurrent.{ExecutionContext, Future}

class McsAssemblyComponentHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends ComponentHandlers(ctx, cswCtx) {

  implicit val timeout: Timeout     = 10.seconds
  implicit val scheduler: Scheduler = ctx.system.scheduler
  implicit val ec: ExecutionContext = ctx.executionContext
  var hcdComponent: CommandService  = _
  //var runId: Id                     = _
  var shortSetup: Setup  = _
  var mediumSetup: Setup = _
  var longSetup: Setup   = _

  import cswCtx._

  override def initialize(): Future[Unit] = {
    componentInfo.connections.headOption match {
      case Some(hcd) ⇒
        cswCtx.locationService.resolve(hcd.of[AkkaLocation], 5.seconds).map {
          case Some(akkaLocation) ⇒ hcdComponent = CommandServiceFactory.make(akkaLocation)(ctx.system)
          case None               ⇒ throw new RuntimeException("Could not resolve hcd location, Initialization failure.")
        }
      case None ⇒ Future.successful(Unit)
    }
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = Unit

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    controlCommand.commandName match {
      case `longRunning` ⇒ Accepted(runId)
      case `moveCmd`     ⇒ Accepted(runId)
      case `initCmd`     ⇒ Accepted(runId)
      case `invalidCmd`  ⇒ Invalid(runId, CommandIssue.OtherIssue("Invalid"))
      case _             ⇒ Invalid(runId, UnsupportedCommandIssue(controlCommand.commandName.name))
    }
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    controlCommand.commandName match {
      case `longRunning` ⇒
        val parentId = runId

        // DEOPSCSW-371: Provide an API for CommandResponseManager that hides actor based interaction
        //#addSubCommand
        // When receiving the command, onSubmit adds three subCommands
        shortSetup = Setup(prefix, shortRunning, controlCommand.maybeObsId)
        // commandResponseManager.addSubCommand(parentId, shortId)

        mediumSetup = Setup(prefix, mediumRunning, controlCommand.maybeObsId)
        //commandResponseManager.addSubCommand(parentId, mediumId)

        longSetup = Setup(prefix, longRunning, controlCommand.maybeObsId)
        //commandResponseManager.addSubCommand(parentId, longId)
        //#addSubCommand

        // this is to simulate that assembly is splitting command into three sub commands and forwarding same to hcd
        // longSetup takes 5 seconds to finish
        // shortSetup takes 1 second to finish
        // mediumSetup takes 3 seconds to finish
        processCommand(parentId, longSetup)
        processCommand(parentId, shortSetup)
        processCommand(parentId, mediumSetup)

        // DEOPSCSW-371: Provide an API for CommandResponseManager that hides actor based interaction
        //#subscribe-to-command-response-manager
        // query the status of original command received and publish the state when its status changes to
        // Completed
        commandResponseManager
          .queryFinal(parentId)
          .foreach {
            case Completed(_) ⇒
              currentStatePublisher.publish(
                CurrentState(controlCommand.source, StateName("testStateName"), Set(choiceKey.set(longRunningCmdCompleted)))
              )
            case _ ⇒
          }
        //#subscribe-to-command-response-manager

        //#query-command-response-manager
        // query CommandResponseManager to get the current status of Command, for example: Accepted/Completed/Invalid etc.
        commandResponseManager
          .query(parentId)
          .map(
            _ ⇒ () // may choose to publish current state to subscribers or do other operations
          )
        // Return response
        Started(parentId)
      //#query-command-response-manager

      case `initCmd` ⇒ Completed(Id())

      case `moveCmd` ⇒ Completed(Id())

      case _ ⇒ //do nothing
        Completed(Id())

    }
  }

  private def processCommand(parentId: Id, controlCommand: ControlCommand) = {
    println(s"Sending: $controlCommand")
    val xx = hcdComponent.submitOnly(controlCommand)
    xx map { cr =>
      println(s"GOT: $cr")
      commandResponseManager.addSubCommand(parentId, cr.runId)
    }

      // DEOPSCSW-371: Provide an API for CommandResponseManager that hides actor based interaction
      //#updateSubCommand
      // An original command is split into sub-commands and sent to a component.
      // The current state publishing is not relevant to the updateSubCommand usage.
      hcdComponent.whenFinal(xx) match {
        case _: Completed ⇒
          controlCommand.commandName match {
            case cn if cn == shortRunning ⇒
              println("It's short running")
              currentStatePublisher
                .publish(CurrentState(shortSetup.source, StateName("testStateName"), Set(choiceKey.set(shortCmdCompleted))))
              // As the commands get completed, the results are updated in the commandResponseManager
              commandResponseManager.updateSubCommand(Completed(xx.runId))
            case cn if cn == mediumRunning ⇒
              println("It's medium running")
              currentStatePublisher
                .publish(CurrentState(mediumSetup.source, StateName("testStateName"), Set(choiceKey.set(mediumCmdCompleted))))
              commandResponseManager.updateSubCommand(Completed(cr.runId))
            case cn if cn == longRunning ⇒
              println("It's long running")
              currentStatePublisher
                .publish(CurrentState(longSetup.source, StateName("testStateName"), Set(choiceKey.set(longCmdCompleted))))
              commandResponseManager.updateSubCommand(Completed(cr.runId))
          }
        //#updateSubCommand
        case _ ⇒ // Do nothing
      }

  }

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = ???

  override def onShutdown(): Future[Unit] = Future.successful(Unit)

  override def onGoOffline(): Unit = ???

  override def onGoOnline(): Unit = ???
}
