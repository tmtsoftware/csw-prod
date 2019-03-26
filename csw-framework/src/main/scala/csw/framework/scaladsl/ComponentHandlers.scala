package csw.framework.scaladsl

import akka.actor.typed.scaladsl.ActorContext
import csw.command.client.messages.TopLevelActorCommonMessage.TrackingEventReceived
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.params.commands.ControlCommand
import csw.location.api.models.{Connection, TrackingEvent}
import csw.params.commands.CommandResponse.{SubmitResponse, ValidateCommandResponse}
import csw.params.core.models.Id

import scala.concurrent.Future

/**
 * Base class for component handlers which will be used by the component actor
 *
 * @param ctx the [[akka.actor.typed.scaladsl.ActorContext]] under which the actor instance of the component, which use these handlers, is created
 * @param cswCtx provides access to csw services e.g. location, event, alarm, etc
 */
abstract class ComponentHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext) {

  /**
   * A component can access this flag, which can be used to determine if the component is in the online or offline state.
   */
  var isOnline: Boolean = false

  /**
   * The initialize handler is invoked when the component is created. This is different than constructor initialization
   * to allow non-blocking asynchronous operations. The component can initialize state such as configuration to be fetched
   * from configuration service, location of components or services to be fetched from location service etc. These vary
   * from component to component.
   *
   * @return a future which completes when the initialization of component completes
   */
  def initialize(): Future[Unit]

  /**
   * The onLocationTrackingEvent handler can be used to take action on the TrackingEvent for a particular connection.
   * This event could be for the connections in ComponentInfo tracked automatically or for the connections tracked
   * explicitly using trackConnection method.
   *
   * @param trackingEvent represents a LocationUpdated or LocationRemoved event received for a tracked connection
   */
  def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit

  /**
   * The validateCommand is invoked when a command is received by this component. If a command can be completed immediately,
   * a CommandResponse indicating the final response for the command can be returned. If a command requires time for processing,
   * the component is required to validate the ControlCommand received and return a validation result as Accepted or Invalid.
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
   * @param runId id that identifies this command submission
   * @param controlCommand represents a command received e.g. Setup, Observe or wait
   */
  def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse

  /**
   * On receiving a command as Oneway, the onOneway handler is invoked for a component only if the validateCommand handler
   * returns Accepted.In case a command is received as a oneway, command response should not be provided to the sender.
   *
   * @param runId id that identifies this command submission
   *
   * @param controlCommand represents a command received e.g. Setup, Observe or wait
   */
  def onOneway(runId: Id, controlCommand: ControlCommand): Unit

  /**
   * The onShutdown handler can be used for carrying out the tasks which will allow the component to shutdown gracefully
   *
   * @return a future which completes when the shutdown completes for component
   */
  def onShutdown(): Future[Unit]

  /**
   * A component can be notified to run in offline mode in case it is not in use. The component can change its behavior
   * if needed as a part of this handler.
   */
  def onGoOffline(): Unit

  /**
   * A component can be notified to run in online mode again in case it was put to run in offline mode. The component can
   * change its behavior if needed as a part of this handler.
   */
  def onGoOnline(): Unit

  /**
   * Track any connection. The handlers for received events are defined in onLocationTrackingEvent() method
   *
   * @param connection to be tracked for location updates
   */
  def trackConnection(connection: Connection): Unit =
    cswCtx.locationService.subscribe(connection, trackingEvent ⇒ ctx.self ! TrackingEventReceived(trackingEvent))
}
