package csw.framework.internal.component

import akka.actor.typed.{Behavior, PostStop}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers2
import csw.framework.scaladsl.TopLevelComponent.RunningMessage
import csw.logging.api.scaladsl.Logger

object ComponentBehavior2 {

  def apply(handlers: ComponentHandlers2, cswCtx: CswContext): Behavior[RunningMessage] = {
    import RunningMessage._

    Behaviors.setup { ctx =>
      import cswCtx._

      val log: Logger = loggerFactory.getLogger(ctx)

      Behaviors.receiveMessage[RunningMessage] {
        case Validate(runId, cmd, svr) =>
          log.info(s"TestComponent TLA received Validate with runId:name: $runId:$cmd")
          svr ! handlers.validateCommand(runId, cmd)
          Behaviors.same
        case Submit(runId, cmd, svr) =>
          log.info(s"TestComponent received Submit: id:name: $runId:$cmd")
          svr ! handlers.onSubmit(runId, cmd)
          Behaviors.same
        case Oneway(runId, cmd) =>
          log.info(s"TestComponent TLA received Oneway: id:name: $runId:$cmd")
          handlers.onOneway(runId, cmd)
          Behaviors.same
        case Shutdown(svr) =>
          log.info("TestComponent TLA got Shutdown--responding success")
          svr ! handlers.onShutdown()
          Behaviors.same
        case GoOnline(svr) =>
          log.info("TLA got GoOnline")
          svr ! handlers.onGoOnline()
          Behaviors.same
        case GoOffline(svr) =>
          log.info("TLA got GoOffline")
          svr ! handlers.onGoOffline()
          Behaviors.same
        case TrackingEventReceived(trackingEvent) =>
          handlers.onLocationTrackingEvent(trackingEvent)
          Behaviors.same
      }.receiveSignal {
        case (_: ActorContext[RunningMessage], PostStop) =>
          log.debug(s"TestComponent TLA Running PostStop signal received")
          Behaviors.same
      }
    }
  }
}
