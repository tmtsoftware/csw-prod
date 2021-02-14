package csw.common.components.command

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.Behavior
import csw.common.components.command.CommandComponentState._
import csw.common.components.command.TestComponent.MyInitState
import csw.framework.internal.component.ComponentBehavior3.ComponentBehavior3
import csw.framework.models.CswContext
import csw.framework.scaladsl.TopLevelComponent._
import csw.location.api.models.TrackingEvent
import csw.params.commands.{CommandIssue, ControlCommand}
import csw.params.commands.CommandResponse.{Accepted, Completed, Invalid, Started, SubmitResponse, ValidateCommandResponse}
import csw.params.core.models.Id
import csw.time.core.models.UTCTime

object TestComponent2 {

  private case class MyInitState2(val1: String, val2: String)

  def apply(cswCtx: CswContext): Behavior[TopLevelComponentMessage] = {
    import InitializeMessage._

    val log = cswCtx.loggerFactory.getLogger

    Behaviors.setup { ctx =>
      Behaviors
        .receiveMessage[InitializeMessage] {
          case Initialize(ref) =>
            log.info(s"Initializing TestComponent for: $ref")

            //val myState = MyInitState2("go", "forward")
            val myState = "Kim Gillies"

            val myClass = MyRunningBehavior(ctx, cswCtx, myState)
            ref ! InitializeSuccess2(myClass)

            log.info(s"TestComponent TLA sent InitializeSuccess to: $ref")
            Behaviors.same
        }
      Behaviors.same
    }
  }
}

  case class MyRunningBehavior(ctx: ActorContext[TopLevelComponentMessage], cswCtx: CswContext, state: String)
      extends ComponentBehavior3(ctx, cswCtx) {

    private val log = cswCtx.loggerFactory.getLogger(ctx)

    def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {}

    /**
     * The validateCommand is invoked when a command is received by this component.
     * The component is required to validate the ControlCommand received and return a validation result as Accepted or Invalid.
     *
     * @param controlCommand represents a command received e.g. Setup, Observe or wait
     * @return a CommandResponse after validation
     */
    def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse =
      controlCommand.commandName match {
        case `invalidCmd` => Invalid(runId, CommandIssue.OtherIssue("Invalid"))
        case _            => Accepted(runId)
      }

    /**
     * On receiving a command as Submit, the onSubmit handler is invoked for a component only if the validateCommand handler
     * returns Accepted. In case a command is received as a submit, command response should be updated in the CommandResponseManager.
     * CommandResponseManager is an actor whose reference commandResponseManager is available in the ComponentHandlers.
     *
     * @param controlCommand represents a command received e.g. Setup, Observe or wait
     */
    def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = Completed(runId)

    /**
     * On receiving a command as Oneway, the onOneway handler is invoked for a component only if the validateCommand handler
     * returns Accepted. In case a command is received as a oneway, command response should not be provided to the sender.
     *
     * @param controlCommand represents a command received e.g. Setup, Observe or wait
     */
    def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {
      log.info(s"TestComponent TLA received Oneway: id:$runId: $runId:$controlCommand")
    }

    /**
     * On receiving a diagnostic data command, the component goes into a diagnostic data mode based on hint at the specified startTime.
     * Validation of supported hints need to be handled by the component writer.
     * @param startTime represents the time at which the diagnostic mode actions will take effect
     * @param hint represents supported diagnostic data mode for a component
     */
    def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

    /**
     * On receiving a operations mode command, the current diagnostic data mode is halted.
     */
    def onOperationsMode(): Unit = {}

    /**
     * The onShutdown handler can be used for carrying out the tasks which will allow the component to shutdown gracefully
     *
     * @return when the shutdown completes for component
     */
    def onShutdown(): ShutdownResponse = ShutdownSuccessful

    /**
     * A component can be notified to run in offline mode in case it is not in use. The component can change its behavior
     * if needed as a part of this handler.
     */
    def onGoOffline(): OfflineResponse = OfflineSuccess

    /**
     * A component can be notified to run in online mode again in case it was put to run in offline mode. The component can
     * change its behavior if needed as a part of this handler.
     */
    def onGoOnline(): OnlineResponse = OnlineSuccess


}
