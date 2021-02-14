package csw.framework.scaladsl

import csw.framework.models.CswContext
import csw.framework.scaladsl.TopLevelComponent.{OfflineResponse, OnlineResponse, ShutdownResponse}
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandResponse.{SubmitResponse, ValidateCommandResponse}
import csw.params.commands.ControlCommand
import csw.params.core.models.Id
import csw.time.core.models.UTCTime

abstract class ComponentHandlers2(cswCtx: CswContext) {

  /**
   * A component can access this flag, which can be used to determine if the component is in the online or offline state.
   */
  var isOnline: Boolean = false

  /**
   * The onLocationTrackingEvent handler can be used to take action on the TrackingEvent for a particular connection.
   * This event could be for the connections in ComponentInfo tracked automatically or for the connections tracked
   * explicitly using trackConnection method.
   *
   * @param trackingEvent represents a LocationUpdated or LocationRemoved event received for a tracked connection
   */
  def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit

  /**
   * The validateCommand is invoked when a command is received by this component.
   * The component is required to validate the ControlCommand received and return a validation result as Accepted or Invalid.
   *
   * @param controlCommand represents a command received e.g. Setup, Observe or wait
   * @return a CommandResponse after validation
   */
  def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse

  /**
   * On receiving a command as Submit, the onSubmit handler is invoked for a component only if the validateCommand handler
   * returns Accepted. In case a command is received as a submit, command response should be updated in the CommandResponseManager.
   * CommandResponseManager is an actor whose reference commandResponseManager is available in the ComponentHandlers.
   *
   * @param controlCommand represents a command received e.g. Setup, Observe or wait
   */
  def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse

  /**
   * On receiving a command as Oneway, the onOneway handler is invoked for a component only if the validateCommand handler
   * returns Accepted. In case a command is received as a oneway, command response should not be provided to the sender.
   *
   * @param controlCommand represents a command received e.g. Setup, Observe or wait
   */
  def onOneway(runId: Id, controlCommand: ControlCommand): Unit

  /**
   * On receiving a diagnostic data command, the component goes into a diagnostic data mode based on hint at the specified startTime.
   * Validation of supported hints need to be handled by the component writer.
   * @param startTime represents the time at which the diagnostic mode actions will take effect
   * @param hint represents supported diagnostic data mode for a component
   */
  def onDiagnosticMode(startTime: UTCTime, hint: String): Unit

  /**
   * On receiving a operations mode command, the current diagnostic data mode is halted.
   */
  def onOperationsMode(): Unit

  /**
   * The onShutdown handler can be used for carrying out the tasks which will allow the component to shutdown gracefully
   *
   * @return when the shutdown completes for component
   */
  def onShutdown(): ShutdownResponse

  /**
   * A component can be notified to run in offline mode in case it is not in use. The component can change its behavior
   * if needed as a part of this handler.
   */
  def onGoOffline(): OfflineResponse

  /**
   * A component can be notified to run in online mode again in case it was put to run in offline mode. The component can
   * change its behavior if needed as a part of this handler.
   */
  def onGoOnline(): OnlineResponse

  /**
   * Track any connection. The handlers for received events are defined in onLocationTrackingEvent() method
   *
   * @param connection to be tracked for location updates
   */
//  def trackConnection(connection: Connection): Unit =
 //   cswCtx.locationService.subscribe(connection, trackingEvent => ctx.self ! TrackingEventReceived(trackingEvent))
}
