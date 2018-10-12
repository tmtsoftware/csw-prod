package csw.framework.internal.container

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.Reason
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.actor.typed.scaladsl.{ActorContext, MutableBehavior}
import akka.actor.typed.{ActorRef, Behavior, PostStop, Signal, Terminated}
import csw.alarm.client.AlarmServiceFactory
import csw.command.messages.ContainerCommonMessage.{GetComponents, GetContainerLifecycleState}
import csw.command.messages.ContainerIdleMessage.SupervisorsCreated
import csw.command.messages.FromSupervisorMessage.SupervisorLifecycleStateChanged
import csw.command.messages.RunningMessage.Lifecycle
import csw.command.messages.SupervisorContainerCommonMessages.{Restart, Shutdown}
import csw.command.messages.{ComponentMessage, ContainerActorMessage, ContainerCommonMessage, ContainerIdleMessage}
import csw.command.models.framework._
import csw.event.client.EventServiceFactory
import csw.framework.commons.CoordinatedShutdownReasons.{
  AllActorsWithinContainerTerminatedReason,
  FailedToCreateSupervisorsReason
}
import csw.framework.internal.supervisor.SupervisorInfoFactory
import csw.framework.models._
import csw.framework.scaladsl.RegistrationFactory
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaRegistration, ComponentId, ComponentType}
import csw.location.api.scaladsl.LocationService
import csw.logging.scaladsl.{Logger, LoggerFactory}
import csw.params.core.models.Prefix
import csw.params.core.models.Subsystem.Container

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * The Behavior of a Container of one or more components, represented as a mutable behavior.
 *
 * @param ctx                   the [[akka.actor.typed.scaladsl.ActorContext]] under which the actor instance of this behavior is created
 * @param containerInfo         container related information as described in the configuration file
 * @param supervisorInfoFactory the factory for creating the Supervisors for components described in ContainerInfo
 * @param registrationFactory   the factory for creating a typed [[csw.location.api.models.AkkaRegistration]] from
 * [[csw.location.api.models.Connection.AkkaConnection]]
 * @param eventServiceFactory   the factory to create instance of event service to be used by components to use and/or create publishers and subscribers
 * @param locationService       the single instance of Location service created for a running application
 * @param loggerFactory         factory to create suitable logger instance
 */
private[framework] final class ContainerBehavior(
    ctx: ActorContext[ContainerActorMessage],
    containerInfo: ContainerInfo,
    supervisorInfoFactory: SupervisorInfoFactory,
    registrationFactory: RegistrationFactory,
    locationService: LocationService,
    eventServiceFactory: EventServiceFactory,
    alarmServiceFactory: AlarmServiceFactory,
    loggerFactory: LoggerFactory
) extends MutableBehavior[ContainerActorMessage] {

  import ctx.executionContext
  private val log: Logger                        = loggerFactory.getLogger(ctx)
  private val containerPrefix                    = Prefix(s"${Container.entryName}.${containerInfo.name}")
  private val akkaConnection                     = AkkaConnection(ComponentId(containerInfo.name, ComponentType.Container))
  private val akkaRegistration: AkkaRegistration = registrationFactory.akkaTyped(akkaConnection, containerPrefix, ctx.self)

  // Set of successfully created supervisors for components
  var supervisors: Set[SupervisorInfo] = Set.empty

  // Set of created supervisors which moved into Running state
  var runningComponents: Set[ActorRef[ComponentMessage]] = Set.empty
  var lifecycleState: ContainerLifecycleState            = ContainerLifecycleState.Idle

  registerWithLocationService()

  // Failure in registration above doesn't affect creation of components
  createComponents(containerInfo.components)

  /**
   * Defines processing for a [[csw.command.messages.ContainerActorMessage]] received by the actor instance.
   *
   * @param msg containerMessage received
   * @return the existing behavior
   */
  override def onMessage(msg: ContainerActorMessage): Behavior[ContainerActorMessage] = {
    log.debug(s"Container in lifecycle state :[$lifecycleState] received message :[$msg]")
    (lifecycleState, msg) match {
      case (_, msg: ContainerCommonMessage)                          ⇒ onCommon(msg)
      case (ContainerLifecycleState.Idle, msg: ContainerIdleMessage) ⇒ onIdle(msg)
      case (ContainerLifecycleState.Running, msg: Lifecycle)         ⇒ supervisors.foreach(_.component.supervisor ! msg)
      case (_, message) ⇒
        log.error(s"Unexpected message :[$message] received by container in lifecycle state :[$lifecycleState]")
    }
    this
  }

  /**
   * Defines processing for a [[akka.actor.typed.Signal]] received by the actor instance
   *
   * @return the existing behavior
   */
  override def onSignal: PartialFunction[Signal, Behavior[ContainerActorMessage]] = {
    case Terminated(supervisor) ⇒
      log.warn(
        s"Container in lifecycle state :[$lifecycleState] received terminated signal from supervisor :[$supervisor]"
      )
      supervisors = supervisors.filterNot(_.component.supervisor == supervisor)
      if (supervisors.isEmpty) {
        log.warn("All supervisors from this container are terminated. Initiating co-ordinated shutdown.")
        coordinatedShutdown(AllActorsWithinContainerTerminatedReason)
      }
      this
    case PostStop ⇒
      log.warn(s"Un-registering container from location service")
      locationService.unregister(akkaConnection)
      this
  }

  /**
   * Defines action for messages which can be received in any [[csw.command.models.framework.ContainerLifecycleState]] state
   *
   * @param commonMessage message representing a message received in any lifecycle state
   */
  private def onCommon(commonMessage: ContainerCommonMessage): Unit = commonMessage match {
    case GetComponents(replyTo) ⇒
      replyTo ! Components(supervisors.map(_.component))
    case GetContainerLifecycleState(replyTo) ⇒
      replyTo ! lifecycleState
    case Restart ⇒
      log.debug(s"Container is changing lifecycle state from [$lifecycleState] to [${ContainerLifecycleState.Idle}]")
      lifecycleState = ContainerLifecycleState.Idle
      runningComponents = Set.empty
      supervisors.foreach(_.component.supervisor ! Restart)
    case Shutdown ⇒
      log.debug(s"Container is changing lifecycle state from [$lifecycleState] to [${ContainerLifecycleState.Idle}]")
      lifecycleState = ContainerLifecycleState.Idle
      supervisors.foreach(_.component.supervisor ! Shutdown)
  }

  /**
   * Defines action for messages which can be received in [[csw.command.models.framework.ContainerLifecycleState.Idle]] state
   *
   * @param idleMessage message representing a message received in [[csw.command.models.framework.ContainerLifecycleState.Idle]] state
   */
  private def onIdle(idleMessage: ContainerIdleMessage): Unit = idleMessage match {
    case SupervisorsCreated(supervisorInfos) ⇒
      if (supervisorInfos.isEmpty) {
        log.error(s"Failed to spawn supervisors for ComponentInfo's :[${containerInfo.components.mkString(", ")}]")
        coordinatedShutdown(FailedToCreateSupervisorsReason)
      } else {
        supervisors = supervisorInfos
        log.info(s"Container created following supervisors :[${supervisors.map(_.component.supervisor).mkString(",")}]")
        supervisors.foreach(supervisorInfo ⇒ ctx.watch(supervisorInfo.component.supervisor))
        updateContainerStateToRunning()
      }
    case SupervisorLifecycleStateChanged(supervisor, supervisorLifecycleState) ⇒
      if (supervisorLifecycleState == SupervisorLifecycleState.Running) {
        runningComponents = runningComponents + supervisor
        updateContainerStateToRunning()
      }
  }

  /**
   * Create supervisors for all components and return a set of all successfully created supervisors as a message to self
   *
   * @param componentInfos components to be created as specified in the configuration file
   */
  private def createComponents(componentInfos: Set[ComponentInfo]): Unit = {
    log.info(s"Container is creating following components :[${componentInfos.map(_.name).mkString(", ")}]")
    Future
      .traverse(componentInfos) { ci ⇒
        supervisorInfoFactory.make(ctx.self, ci, locationService, eventServiceFactory, alarmServiceFactory, registrationFactory)
      }
      .foreach(x ⇒ {
        ctx.self ! SupervisorsCreated(x.flatten)
      })
  }

  /**
   * Updates ContainerLifecycleState to running if all successfully created supervisors move into running state
   */
  private def updateContainerStateToRunning(): Unit = {
    if (runningComponents.size == supervisors.size) {
      log.debug(s"Container is changing lifecycle state from [$lifecycleState] to [${ContainerLifecycleState.Running}]")
      lifecycleState = ContainerLifecycleState.Running
    }
  }

  private def registerWithLocationService(): Unit = {
    log.debug(
      s"Container with connection :[${akkaRegistration.connection.name}] is registering with location service with ref :[${akkaRegistration.actorRef}]"
    )
    locationService.register(akkaRegistration).onComplete {
      case Success(_)         ⇒ log.info(s"Container Registration successful with connection: [$akkaConnection]")
      case Failure(throwable) ⇒ log.error(throwable.getMessage, ex = throwable)
    }
  }

  private def coordinatedShutdown(reason: Reason): Future[Done] = CoordinatedShutdown(ctx.system.toUntyped).run(reason)
}
