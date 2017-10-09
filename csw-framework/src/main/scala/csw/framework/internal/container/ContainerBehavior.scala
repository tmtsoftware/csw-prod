package csw.framework.internal.container

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.typed.scaladsl.ActorContext
import akka.typed.scaladsl.adapter.TypedActorSystemOps
import akka.typed.{ActorRef, Behavior, PostStop, Signal, Terminated}
import csw.framework.internal.supervisor.SupervisorInfoFactory
import csw.framework.models._
import csw.messages.ContainerCommonMessage.{GetComponents, GetContainerLifecycleState}
import csw.messages.ContainerIdleMessage.SupervisorsCreated
import csw.messages.FromSupervisorMessage.SupervisorLifecycleStateChanged
import csw.messages.RunningMessage.Lifecycle
import csw.messages._
import csw.messages.framework.{ComponentInfo, ContainerLifecycleState, SupervisorLifecycleState}
import csw.messages.location.Connection.AkkaConnection
import csw.messages.location.{ComponentId, ComponentType}
import csw.services.location.models._
import csw.services.location.scaladsl.{LocationService, RegistrationFactory}
import csw.services.logging.internal.LogControlMessages
import csw.services.logging.scaladsl.ComponentLogger

import scala.concurrent.Future
import scala.util.{Failure, Success}

class ContainerBehavior(
    ctx: ActorContext[ContainerMessage],
    containerInfo: ContainerInfo,
    supervisorInfoFactory: SupervisorInfoFactory,
    registrationFactory: RegistrationFactory,
    locationService: LocationService,
    logAdminActorRef: ActorRef[LogControlMessages]
) extends ComponentLogger.TypedActor[ContainerMessage](ctx, Some(containerInfo.name)) {

  import ctx.executionContext

  val akkaConnection                                              = AkkaConnection(ComponentId(containerInfo.name, ComponentType.Container))
  val akkaRegistration: AkkaRegistration                          = registrationFactory.akkaTyped(akkaConnection, ctx.self, logAdminActorRef)
  var supervisors: Set[SupervisorInfo]                            = Set.empty
  var runningComponents: Set[ActorRef[SupervisorExternalMessage]] = Set.empty
  var lifecycleState: ContainerLifecycleState                     = ContainerLifecycleState.Idle

  registerWithLocationService()
  createComponents(containerInfo.components)

  override def onMessage(msg: ContainerMessage): Behavior[ContainerMessage] = {
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

  override def onSignal: PartialFunction[Signal, Behavior[ContainerMessage]] = {
    case Terminated(supervisor) ⇒
      log.warn(
        s"Container in lifecycle state :[$lifecycleState] received terminated signal from supervisor :[$supervisor]"
      )
      supervisors = supervisors.filterNot(_.component.supervisor == supervisor)
      if (supervisors.isEmpty) {
        log.warn("All supervisors from this container are terminated. Initiating co-ordinated shutdown.")
        coordinatedShutdown()
      }
      this
    case PostStop ⇒
      log.warn(s"Un-registering container from location service")
      locationService.unregister(akkaConnection)
      this
  }

  def onCommon(commonContainerMessage: ContainerCommonMessage): Unit = commonContainerMessage match {
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

  def onIdle(idleContainerMessage: ContainerIdleMessage): Unit = idleContainerMessage match {
    case SupervisorsCreated(supervisorInfos) ⇒
      if (supervisorInfos.isEmpty) {
        log.error(s"Failed to spawn supervisors for ComponentInfo's :[${containerInfo.components.mkString(", ")}]")
        coordinatedShutdown()
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

  private def createComponents(componentInfos: Set[ComponentInfo]): Unit = {
    log.info(s"Container is creating following components :[${componentInfos.map(_.name).mkString(", ")}]")
    Future
      .traverse(componentInfos) { ci ⇒
        supervisorInfoFactory.make(ctx.self, ci, locationService, logAdminActorRef)
      }
      .foreach(x ⇒ ctx.self ! SupervisorsCreated(x.flatten))
  }

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
      case Success(registrationResult) ⇒
        log.info(s"Container Registration successful with connection: [$akkaConnection]")
      case Failure(throwable) ⇒ log.error(throwable.getMessage, ex = throwable)
    }
  }

  private def coordinatedShutdown(): Future[Done] = CoordinatedShutdown(ctx.system.toUntyped).run()
}
