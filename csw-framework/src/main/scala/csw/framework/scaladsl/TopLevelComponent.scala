package csw.framework.scaladsl

import akka.actor.typed.{ActorRef, Behavior}
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandResponse.{SubmitResponse, ValidateCommandResponse}
import csw.params.commands.ControlCommand
import csw.params.core.models.Id
import csw.time.core.models.UTCTime

object TopLevelComponent {

  sealed trait TopLevelComponentMessage extends akka.actor.NoSerializationVerificationNeeded

  sealed trait InitializeMessage extends TopLevelComponentMessage

  object InitializeMessage {
    final case class Initialize(replyTo: ActorRef[InitializeResponse]) extends InitializeMessage
  }

  sealed trait InitializeResponse extends TopLevelComponentMessage
  final case class InitializeSuccess(running: Behavior[RunningMessage]) extends InitializeResponse
  final case class InitializeSuccess2(running: Behavior[TopLevelComponentMessage]) extends InitializeResponse
  final case object InitializeFailureStop                               extends InitializeResponse
  final case object InitializeFailureRestart                            extends InitializeResponse

  sealed trait RunningMessage extends TopLevelComponentMessage
  object RunningMessage {

    final case class Validate(runId: Id, cmd: ControlCommand, svr: ActorRef[ValidateCommandResponse]) extends RunningMessage

    final case class Oneway(runId: Id, cmd: ControlCommand) extends RunningMessage

    final case class Submit(runId: Id, cmd: ControlCommand, svr: ActorRef[SubmitResponse]) extends RunningMessage

    final case class Shutdown(svr: ActorRef[ShutdownResponse]) extends RunningMessage

    final case class GoOnline(svr: ActorRef[OnlineResponse]) extends RunningMessage
    final case class GoOffline(svr: ActorRef[OfflineResponse]) extends RunningMessage

    final case class DiagnosticMode(startTime: UTCTime, hint: String, replyTo: ActorRef[DiagnosticModeResponse]) extends RunningMessage
    final case class OperationsMode(replyTo: ActorRef[DiagnosticModeResponse]) extends RunningMessage

    final case class TrackingEventReceived(trackingEvent: TrackingEvent) extends RunningMessage
  }

  sealed trait ShutdownResponse extends TopLevelComponentMessage
  final case object ShutdownSuccessful extends ShutdownResponse
  final case object ShutdownFailed extends ShutdownResponse


  sealed trait OnlineResponse extends TopLevelComponentMessage
  final case object OnlineSuccess extends OnlineResponse
  final case object OnlineFailure extends OnlineResponse

  sealed trait OfflineResponse extends TopLevelComponentMessage
  final case object OfflineSuccess extends OfflineResponse
  final case object OfflineFailure extends OfflineResponse

  sealed trait DiagnosticModeResponse extends TopLevelComponentMessage
  final case object DiagnosticModeSuccess extends DiagnosticModeResponse
  final case object DiagnosticModeFailure extends DiagnosticModeResponse
}
