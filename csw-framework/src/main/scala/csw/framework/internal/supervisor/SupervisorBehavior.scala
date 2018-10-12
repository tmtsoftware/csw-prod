package csw.framework.internal.supervisor

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.Reason
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, MutableBehavior, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, PostStop, Signal, SupervisorStrategy, Terminated}
import csw.command.messages.CommandMessage.{Oneway, Submit}
import csw.command.messages.CommandResponseManagerMessage.{Query, Subscribe, Unsubscribe}
import csw.command.messages.ComponentCommonMessage.{
  ComponentStateSubscription,
  GetSupervisorLifecycleState,
  LifecycleStateSubscription
}
import csw.command.messages.FromComponentLifecycleMessage.Running
import csw.command.messages.FromSupervisorMessage.SupervisorLifecycleStateChanged
import csw.command.messages.RunningMessage.Lifecycle
import csw.command.messages.SupervisorContainerCommonMessages.{Restart, Shutdown}
import csw.command.messages.SupervisorIdleMessage.InitializeTimeout
import csw.command.messages.SupervisorInternalRunningMessage.{RegistrationFailed, RegistrationNotRequired, RegistrationSuccess}
import csw.command.messages.SupervisorLockMessage.{Lock, Unlock}
import csw.command.messages.SupervisorRestartMessage.{UnRegistrationComplete, UnRegistrationFailed}
import csw.command.messages._
import csw.command.models.framework.LocationServiceUsage.DoNotRegister
import csw.command.models.framework.LockingResponses.{LockExpired, LockExpiringShortly}
import csw.command.models.framework.PubSub.Publish
import csw.command.models.framework.ToComponentLifecycleMessages.{GoOffline, GoOnline}
import csw.command.models.framework._
import csw.framework.commons.CoordinatedShutdownReasons.ShutdownMessageReceivedReason
import csw.framework.exceptions.{FailureRestart, InitializationFailed}
import csw.framework.internal.pubsub.PubSubBehaviorFactory
import csw.framework.models.CswContext
import csw.framework.scaladsl.{ComponentBehaviorFactory, RegistrationFactory}
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaRegistration, ComponentId}
import csw.logging.internal.LogAdminUtil
import csw.logging.scaladsl.Logger
import csw.params.commands.CommandResponse.Locked
import csw.params.core.models.Prefix

import scala.concurrent.Future
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success}

private[framework] object SupervisorBehavior {
  val PubSubComponentActor            = "pub-sub-component"
  val PubSubLifecycleActor            = "pub-sub-lifecycle"
  val InitializeTimerKey              = "initialize-timer"
  val ComponentActorNameSuffix        = "component-actor"
  val CommandResponseManagerActorName = "command-response-manager"
  val lockNotificationKey             = "lockNotification"
  val lockExpirationKey               = "lockExpiration"
}

/**
 * The Behavior of a Supervisor of a component actor, represented as a mutable behavior
 *
 * @param ctx                      the [[akka.actor.typed.scaladsl.ActorContext]] under which the actor instance of this behavior is created
 * @param timerScheduler           provides support for scheduled `self` messages in an actor
 * @param maybeContainerRef        the container ref of the container under which this supervisor is started if
 *                                 it's not running in standalone mode
 * @param componentBehaviorFactory the factory for creating the component supervised by this Supervisor
 */
private[framework] final class SupervisorBehavior(
    ctx: ActorContext[SupervisorMessage],
    timerScheduler: TimerScheduler[SupervisorMessage],
    maybeContainerRef: Option[ActorRef[ContainerIdleMessage]],
    componentBehaviorFactory: ComponentBehaviorFactory,
    registrationFactory: RegistrationFactory,
    cswCtx: CswContext
) extends MutableBehavior[SupervisorMessage] {

  import SupervisorBehavior._
  import cswCtx._
  import ctx.executionContext

  private val log: Logger                                  = loggerFactory.getLogger(ctx)
  private val componentName: String                        = componentInfo.name
  private val componentActorName: String                   = s"$componentName-$ComponentActorNameSuffix"
  private val akkaConnection: AkkaConnection               = AkkaConnection(ComponentId(componentName, componentInfo.componentType))
  private val prefix: Prefix                               = componentInfo.prefix
  private val akkaRegistration: AkkaRegistration           = registrationFactory.akkaTyped(akkaConnection, prefix, ctx.self)
  private val isStandalone: Boolean                        = maybeContainerRef.isEmpty
  private[framework] val initializeTimeout: FiniteDuration = componentInfo.initializeTimeout

  private val pubSubBehaviorFactory: PubSubBehaviorFactory                        = new PubSubBehaviorFactory
  private[framework] val pubSubLifecycle: ActorRef[PubSub[LifecycleStateChanged]] = makePubSubLifecycle()

  private var runningComponent: Option[ActorRef[RunningMessage]]  = None
  private var lockManager: LockManager                            = new LockManager(None, loggerFactory)
  private[framework] var lifecycleState: SupervisorLifecycleState = SupervisorLifecycleState.Idle
  private[framework] var component: Option[ActorRef[Nothing]]     = None

  spawnAndWatchComponent()

  /**
   * Defines processing for a [[SupervisorMessage]] received by the actor instance
   *
   * @param msg supervisorMessage received
   * @return the existing behavior
   */
  override def onMessage(msg: SupervisorMessage): Behavior[SupervisorMessage] = {
    log.debug(s"Supervisor in lifecycle state :[$lifecycleState] received message :[$msg]")
    (lifecycleState, msg) match {
      case (SupervisorLifecycleState.Lock, LockAboutToTimeout(replyTo))                        ⇒ replyTo ! LockExpiringShortly
      case (SupervisorLifecycleState.Lock, LockTimedout(replyTo))                              ⇒ replyTo ! LockExpired; onLockTimeout()
      case (SupervisorLifecycleState.Lock, lockMessage: SupervisorLockMessage)                 ⇒ onRunning(lockMessage)
      case (SupervisorLifecycleState.Lock, message)                                            ⇒ ignore(message)
      case (_, commonMessage: ComponentCommonMessage)                                          ⇒ onCommon(commonMessage)
      case (SupervisorLifecycleState.Idle, idleMessage: SupervisorIdleMessage)                 ⇒ onIdle(idleMessage)
      case (SupervisorLifecycleState.Restart, restartMessage: SupervisorRestartMessage)        ⇒ onRestarting(restartMessage)
      case (SupervisorLifecycleState.Running, message: SupervisorInternalRunningMessage)       ⇒ onInternalRunning(message)
      case (SupervisorLifecycleState.Running, runningMessage: SupervisorRunningMessage)        ⇒ onRunning(runningMessage)
      case (SupervisorLifecycleState.RunningOffline, runningMessage: SupervisorRunningMessage) ⇒ onRunning(runningMessage)
      case (_, GetComponentLogMetadata(compName, replyTo))                                     ⇒ replyTo ! LogAdminUtil.getLogMetadata(compName)
      case (_, SetComponentLogLevel(compName, logLevel))                                       ⇒ LogAdminUtil.setComponentLogLevel(compName, logLevel)
      case (_, message)                                                                        ⇒ ignore(message)
    }
    this
  }

  /**
   * Defines processing for a [[akka.actor.typed.Signal]] received by the actor instance
   *
   * @return the existing behavior
   */
  override def onSignal: PartialFunction[Signal, Behavior[SupervisorMessage]] = {
    case Terminated(componentRef) ⇒
      log.warn(s"Supervisor in lifecycle state :[$lifecycleState] received terminated signal from component :[$componentRef]")
      timerScheduler.cancel(InitializeTimerKey)

      lifecycleState match {
        case SupervisorLifecycleState.Restart  ⇒ spawn()
        case SupervisorLifecycleState.Shutdown ⇒ coordinatedShutdown(ShutdownMessageReceivedReason)
        case SupervisorLifecycleState.Idle     ⇒ if (isStandalone) throw InitializationFailed
        case _                                 ⇒ updateLifecycleState(SupervisorLifecycleState.Idle) // Change to idle and expect Restart/Shutdown from outside
      }
      this
    case PostStop ⇒
      log.warn("Supervisor is shutting down. Un-registering supervisor from location service")
      locationService.unregister(akkaConnection)
      this
  }

  /**
   * Defines action for messages which can be received in any [[SupervisorLifecycleState]] state
   *
   * @param commonMessage message representing a message received in any lifecycle state
   */
  private def onCommon(commonMessage: ComponentCommonMessage): Unit = commonMessage match {
    case LifecycleStateSubscription(subscriberMessage) ⇒ pubSubLifecycle ! subscriberMessage
    case ComponentStateSubscription(subscriberMessage) ⇒ currentStatePublisher.publisherActor.upcast ! subscriberMessage
    case GetSupervisorLifecycleState(replyTo)          ⇒ replyTo ! lifecycleState
    case Restart                                       ⇒ onRestart()
    case Shutdown                                      ⇒ onShutdown()
  }

  /**
   * Defines action for messages which can be received in [[SupervisorLifecycleState.Idle]] state
   *
   * @param idleMessage message representing a message received in [[SupervisorLifecycleState.Idle]] state
   */
  private def onIdle(idleMessage: SupervisorIdleMessage): Unit = idleMessage match {
    case Running(componentRef) ⇒ onComponentRunning(componentRef)
    case InitializeTimeout     ⇒ onInitializeTimeout()
  }

  /**
   * Defines action for messages which can be received in [[SupervisorLifecycleState.Restart]] state
   *
   * @param restartMessage message representing a message received in [[SupervisorLifecycleState.Restart]] state
   */
  private def onRestarting(restartMessage: SupervisorRestartMessage): Unit = restartMessage match {
    case UnRegistrationComplete ⇒
      log.info("Supervisor unregistered itself from location service")
      respawnComponent()
    case UnRegistrationFailed(throwable) ⇒
      log.error(throwable.getMessage, ex = throwable)
      respawnComponent()
  }

  /**
   * Defines action for messages which can be received in [[SupervisorLifecycleState.Running]] state
   *
   * @param internalRunningMessage message representing a message received in [[SupervisorLifecycleState.Running]] state
   */
  private def onInternalRunning(internalRunningMessage: SupervisorInternalRunningMessage): Unit = internalRunningMessage match {
    case RegistrationSuccess(componentRef)     ⇒ onRegistrationComplete(componentRef)
    case RegistrationNotRequired(componentRef) ⇒ onRegistrationComplete(componentRef)
    case RegistrationFailed(throwable)         ⇒ onRegistrationFailed(throwable)
  }

  /**
   * Defines action for messages which can be received in [[SupervisorLifecycleState.Running]] state
   *
   * @param runningMessage message representing a message received in [[SupervisorLifecycleState.Running]] state
   */
  private def onRunning(runningMessage: SupervisorRunningMessage): Unit = runningMessage match {
    case Query(runId, replyTo)                ⇒ commandResponseManager.commandResponseManagerActor ! Query(runId, replyTo)
    case Subscribe(runId, replyTo)            ⇒ commandResponseManager.commandResponseManagerActor ! Subscribe(runId, replyTo)
    case Unsubscribe(runId, replyTo)          ⇒ commandResponseManager.commandResponseManagerActor ! Unsubscribe(runId, replyTo)
    case Lock(source, replyTo, leaseDuration) ⇒ lockComponent(source, replyTo, leaseDuration)
    case Unlock(source, replyTo)              ⇒ unlockComponent(source, replyTo)
    case command: CommandMessage ⇒
      if (lockManager.allowCommand(command)) {
        runningComponent.get ! command
      } else {
        // Both types of command message have their own responses, so while it is not elegant, it is necessary
        // to check for the type of message upon failure, and send to the currect replyTo
        command match {
          case s: Submit => s.replyTo ! Locked(s.command.runId)
          case o: Oneway => o.replyTo ! Locked(o.command.runId)
        }
      }
    case runningMessage: RunningMessage ⇒ handleRunningMessage(runningMessage)
    case msg @ Running(_)               ⇒ log.info(s"Ignoring [$msg] message received from TLA as Supervisor already in Running state")
  }

  private def lockComponent(source: Prefix, replyTo: ActorRef[LockingResponse], leaseDuration: FiniteDuration): Unit = {
    lockManager = lockManager.lockComponent(source, replyTo) {
      timerScheduler.startSingleTimer(lockNotificationKey, LockAboutToTimeout(replyTo), leaseDuration - (leaseDuration / 10))
      timerScheduler.startSingleTimer(lockExpirationKey, LockTimedout(replyTo), leaseDuration)
    }
    if (lockManager.isLocked) updateLifecycleState(SupervisorLifecycleState.Lock)
  }

  private def unlockComponent(source: Prefix, replyTo: ActorRef[LockingResponse]): Unit = {
    lockManager = lockManager.unlockComponent(source, replyTo) {
      timerScheduler.cancel(lockNotificationKey)
      timerScheduler.cancel(lockExpirationKey)
    }
    if (lockManager.isUnLocked) updateLifecycleState(SupervisorLifecycleState.Running)
  }

  private def onLockTimeout(): Unit = {
    lockManager = lockManager.releaseLockOnTimeout()
    updateLifecycleState(SupervisorLifecycleState.Running)
  }

  private def handleRunningMessage(runningMessage: RunningMessage): Unit = {
    runningMessage match {
      case Lifecycle(message) ⇒ onLifeCycle(message)
      case _                  ⇒
    }
    runningComponent.get ! runningMessage
  }

  private def onRestart(): Unit = {
    updateLifecycleState(SupervisorLifecycleState.Restart)
    unRegisterFromLocationService()
  }

  private def onShutdown() = {
    updateLifecycleState(SupervisorLifecycleState.Shutdown)
    ctx.child(componentActorName) match {
      case Some(ref) ⇒ ctx.stop(ref) // stop component actor for a graceful shutdown before shutting down the actor system
      case None      ⇒ coordinatedShutdown(ShutdownMessageReceivedReason)
    }
  }

  private def respawnComponent(): Unit = {
    log.info("Supervisor re-spawning component")
    ctx.child(componentActorName) match {
      case Some(_) ⇒ component.foreach(ctx.stop) // stop component actor for a graceful shutdown before restart
      case None    ⇒ spawn()
    }
  }

  private def spawn(): Unit = {
    updateLifecycleState(SupervisorLifecycleState.Idle)
    spawnAndWatchComponent()
  }

  private def onLifeCycle(message: ToComponentLifecycleMessage): Unit = message match {
    case GoOffline ⇒
      if (lifecycleState == SupervisorLifecycleState.Running) updateLifecycleState(SupervisorLifecycleState.RunningOffline)
    case GoOnline ⇒
      if (lifecycleState == SupervisorLifecycleState.RunningOffline) updateLifecycleState(SupervisorLifecycleState.Running)
  }

  private def registerWithLocationService(componentRef: ActorRef[RunningMessage]): Unit = {
    if (componentInfo.locationServiceUsage == DoNotRegister) //Honour DoNotRegister received in componentInfo
      ctx.self ! RegistrationNotRequired(componentRef)
    else
      locationService.register(akkaRegistration).onComplete {
        case Success(_)         ⇒ ctx.self ! RegistrationSuccess(componentRef)
        case Failure(throwable) ⇒ ctx.self ! RegistrationFailed(throwable)
      }
  }

  private def unRegisterFromLocationService(): Unit = {
    log.debug(s"Un-registering supervisor from location service")
    locationService.unregister(akkaConnection).onComplete {
      case Success(_)         ⇒ ctx.self ! UnRegistrationComplete
      case Failure(throwable) ⇒ ctx.self ! UnRegistrationFailed(throwable)
    }
  }

  private def onRegistrationComplete(componentRef: ActorRef[RunningMessage]): Unit = {
    maybeContainerRef.foreach { container ⇒
      container ! SupervisorLifecycleStateChanged(ctx.self, lifecycleState)
      log.debug(s"Supervisor acknowledged container :[$container] for lifecycle state :[$lifecycleState]")
    }
    pubSubLifecycle ! Publish(LifecycleStateChanged(ctx.self, SupervisorLifecycleState.Running))
  }

  private def spawnAndWatchComponent(): Unit = {
    log.debug(s"Supervisor is spawning component TLA")
    component = Some(createTLA())
    log.info(s"Starting InitializeTimer for $initializeTimeout")
    timerScheduler.startSingleTimer(InitializeTimerKey, InitializeTimeout, initializeTimeout)
    component.foreach(ctx.watch) // watch created component to get notified when it’s terminated.
  }

  private def createTLA(): ActorRef[Nothing] = {
    val behavior = Behaviors
      .supervise[Nothing](componentBehaviorFactory.make(ctx.self, cswCtx))
      .onFailure[FailureRestart](SupervisorStrategy.restartWithLimit(3, Duration.Zero).withLoggingEnabled(true))

    ctx.spawn[Nothing](behavior, componentActorName)
  }

  private def coordinatedShutdown(reason: Reason): Future[Done] = CoordinatedShutdown(ctx.system.toUntyped).run(reason)

  private def makePubSubLifecycle(): ActorRef[PubSub[LifecycleStateChanged]] =
    ctx.spawn(pubSubBehaviorFactory.make[LifecycleStateChanged](PubSubComponentActor, loggerFactory),
              SupervisorBehavior.PubSubLifecycleActor)

  private def ignore(message: SupervisorMessage): Unit =
    log.error(s"Unexpected message :[$message] received by supervisor in lifecycle state :[$lifecycleState]")

  private def onInitializeTimeout(): Unit = {
    log.error("Component TLA initialization timed out")
    component.foreach(ctx.stop)
  }

  private def onComponentRunning(componentRef: ActorRef[RunningMessage]): Unit = {
    log.info("Received Running message from component within timeout, cancelling InitializeTimer")
    timerScheduler.cancel(InitializeTimerKey)

    updateLifecycleState(SupervisorLifecycleState.Running)
    runningComponent = Some(componentRef)
    registerWithLocationService(componentRef)
  }

  private def onRegistrationFailed(throwable: Throwable) = {
    updateLifecycleState(SupervisorLifecycleState.Idle)
    runningComponent = None
    log.error(throwable.getMessage, ex = throwable)
    throw throwable
  }

  private def updateLifecycleState(state: SupervisorLifecycleState): Unit = {
    log.debug(s"Supervisor is changing lifecycle state from [$lifecycleState] to [$state]")
    lifecycleState = state
  }
}
