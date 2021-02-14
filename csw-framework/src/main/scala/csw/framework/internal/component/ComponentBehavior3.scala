package csw.framework.internal.component

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{Behavior, PostStop, Signal}
import csw.framework.models.CswContext
import csw.framework.scaladsl.TopLevelComponent
import csw.location.api.models.{Connection, TrackingEvent}
import csw.framework.scaladsl.TopLevelComponent.{OfflineResponse, OnlineResponse, RunningMessage, ShutdownResponse, TopLevelComponentMessage}
import csw.params.commands.CommandResponse.{SubmitResponse, ValidateCommandResponse}
import csw.params.commands.ControlCommand
import csw.params.core.models.Id
import csw.time.core.models.UTCTime

object ComponentBehavior3 {

  abstract class ComponentBehavior3(ctx: ActorContext[TopLevelComponentMessage], cswCtx: CswContext) extends AbstractBehavior[TopLevelComponentMessage](ctx) {

    import csw.framework.scaladsl.TopLevelComponent.RunningMessage._

    private val log = cswCtx.loggerFactory.getLogger
    protected var isOnline: Boolean = false

    override def onMessage(msg: TopLevelComponentMessage): Behavior[TopLevelComponentMessage] = {
      val xx:RunningMessage = msg.asInstanceOf[RunningMessage]
      xx match {
        case Validate(runId, cmd, svr) =>
          log.info(s"CB3 received Validate with runId:name: $runId:$cmd")
          svr ! validateCommand(runId, cmd)
          Behaviors.same
        case Submit(runId, cmd, svr) =>
          log.info(s"TestComponent received Submit: id:name: $runId:$cmd")
          svr ! onSubmit(runId, cmd)
          Behaviors.same
        case Oneway(runId, cmd) =>
          log.info(s"TestComponent TLA received Oneway: id:name: $runId:$cmd")
          onOneway(runId, cmd)
          Behaviors.same
        case Shutdown(svr) =>
          log.info("TestComponent TLA got Shutdown--responding success")
          svr ! onShutdown()
          Behaviors.same
        case GoOnline(svr) =>
          log.info("TLA got GoOnline")
          isOnline = true
          svr ! onGoOnline()
          Behaviors.same
        case GoOffline(svr) =>
          log.info("TLA got GoOffline")
          isOnline = false
          svr ! onGoOffline()
          Behaviors.same
        case TrackingEventReceived(trackingEvent) =>
          onLocationTrackingEvent(trackingEvent)
          Behaviors.same
      }
    }
/*
    override def onSignal: PartialFunction[Signal, Behavior[RunningMessage]] = {
      case PostStop =>
        log.info("Crash")
        this
    }
*/

    // Helpers
    def trackConnection(connection: Connection): Unit =
      cswCtx.locationService.subscribe(connection, trackingEvent => ctx.self ! TrackingEventReceived(trackingEvent))

    def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit

    def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse

    def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse

    def onOneway(runId: Id, controlCommand: ControlCommand): Unit

    def onDiagnosticMode(startTime: UTCTime, hint: String): Unit

    def onOperationsMode(): Unit

    def onShutdown(): ShutdownResponse

    def onGoOffline(): OfflineResponse

    def onGoOnline(): OnlineResponse



  }

}

  /*
        case (_: ActorContext[RunningMessage], PostStop) =>
          log.debug(s"TestComponent TLA Running PostStop signal received")
          Behaviors.same
      }
    }
  }
}

     */
