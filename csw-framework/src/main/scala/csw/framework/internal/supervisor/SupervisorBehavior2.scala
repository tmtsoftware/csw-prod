package csw.framework.internal.supervisor

import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.Http.ServerBinding
import csw.command.client.MiniCRM
import csw.command.client.MiniCRM.MiniCRMMessage
import csw.command.client.messages.CommandMessage.{Oneway, Submit, Validate}
import csw.command.client.messages.ComponentCommonMessage.{ComponentStateSubscription, GetSupervisorLifecycleState, LifecycleStateSubscription2, TrackingEventReceived}
import csw.command.client.messages.DiagnosticDataMessage.{DiagnosticMode, OperationsMode}
import csw.command.client.messages.RunningMessage.Lifecycle
import csw.command.client.messages.SupervisorContainerCommonMessages.{Restart, Shutdown}
import csw.command.client.messages.SupervisorLockMessage.{Lock, Unlock}
import csw.command.client.messages.{CommandMessage, ContainerIdleMessage, Query, QueryFinal, SupervisorMessage}
import csw.command.client.models.framework.LocationServiceUsage.RegisterAndTrackServices
import csw.command.client.models.framework.LockingResponse.ReleasingLockFailed
import csw.command.client.models.framework.SupervisorLifecycleState
import csw.command.client.models.framework.ToComponentLifecycleMessage.{GoOffline, GoOnline}
import csw.framework.internal.supervisor.LifecycleHandler.{SendState, SubscribeState, UpdateState}
import csw.framework.internal.supervisor.LockManager2.{apply => _, _}
import csw.framework.internal.supervisor.SupervisorLocationHelper._
import csw.framework.models.CswContext
import csw.framework.scaladsl.TopLevelComponent._
import csw.framework.scaladsl.{RegistrationFactory, TopLevelComponent}
import csw.params.commands.CommandResponse
import csw.params.core.models.Id
import csw.prefix.models.Prefix

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object SupervisorBehavior2 {

  sealed trait Supervisor2Message extends akka.actor.NoSerializationVerificationNeeded

  private val ShutdownTimerKey = "shutdown-timer"
  private val RestartTimerKey  = "restart-timer"

  private final case object InitializeTimeout extends Supervisor2Message
  private final case object ShutdownTimeout   extends Supervisor2Message

  private final case class TlATerminated(tlaBehavior: Behavior[InitializeMessage], retryCount: Int, delayBetween: FiniteDuration)
      extends Supervisor2Message
  private[framework] final case class TLAStart(
      tlaBehavior: Behavior[InitializeMessage],
      retryCount: Int,
      delayBetween: FiniteDuration
  )                                                        extends Supervisor2Message
  private final case class CommandHelperTerminated(id: Id) extends Supervisor2Message

  final case class WrappedInitializeResponse(response: InitializeResponse)                   extends Supervisor2Message
  private final case class WrappedRegisterResponse(response: SupervisorRegisterResponse)     extends Supervisor2Message
  private final case class WrappedUnregisterResponse(response: SupervisorUnregisterResponse) extends Supervisor2Message
  private final case class WrappedLockManager2Response(response: LockManager2Response)       extends Supervisor2Message
  private final case class WrappedShutdownResponse(response: ShutdownResponse)               extends Supervisor2Message
  private final case class WrappedOnlineResponse(response: OnlineResponse)                   extends Supervisor2Message
  private final case class WrappedOfflineResponse(response: OfflineResponse)                 extends Supervisor2Message
  private final case class WrappedDiagnosticResponse(response: DiagnosticModeResponse)       extends Supervisor2Message
  private final case class WrappedSupervisorMessage(response: SupervisorMessage)             extends Supervisor2Message

  def apply(tlaInitBehavior: Behavior[InitializeMessage],
      registrationFactory: RegistrationFactory,
      cswCtx: CswContext
  ): Behavior[SupervisorMessage] =
    Behaviors.setup { ctx =>
      val log = cswCtx.loggerFactory.getLogger

      // Creating the actual Supervisor. This actor handles the SupervisorMessage external messages that must
      // be serialized. All messages are wrapped in a Supervisor2Message
      val svrBehavior: Behavior[Supervisor2Message] = make(tlaInitBehavior, registrationFactory, cswCtx, ctx.self)
      val newSuper                                  = ctx.spawn(svrBehavior, "superAdapter")

      Behaviors.receiveMessage { msg =>
        log.debug(s"${cswCtx.componentInfo.prefix} adapter received message: $msg")
        newSuper ! WrappedSupervisorMessage(msg)
        Behaviors.same
      }
    }

  private def getTLAInstance(fullyQualifiedClassName: String, properties: CswContext): Behavior[InitializeMessage] = {
    val clazz = Class.forName(fullyQualifiedClassName)
    val applyMethod = clazz.getDeclaredMethod("apply", classOf[CswContext])
    applyMethod.invoke(applyMethod, properties).asInstanceOf[Behavior[InitializeMessage]]
  }

  def apply(ctx: ActorContext[SupervisorMessage],
            timerScheduler: TimerScheduler[SupervisorMessage],
            maybeContainerRef: Option[ActorRef[ContainerIdleMessage]],
            tlaInitBehavior: Behavior[InitializeMessage],
            registrationFactory: RegistrationFactory,
            cswCtx: CswContext): Behavior[SupervisorMessage] = {
    val tlaInitBehavior = getTLAInstance(cswCtx.componentInfo.behaviorFactoryClassName, cswCtx)
    apply(tlaInitBehavior, registrationFactory, cswCtx)
  }

  def apply(registrationFactory: RegistrationFactory, cswCtx: CswContext): Behavior[SupervisorMessage] = {
    val tlaInitBehavior = getTLAInstance(cswCtx.componentInfo.behaviorFactoryClassName, cswCtx)
    apply(tlaInitBehavior, registrationFactory, cswCtx)
  }

  private def make(
      tlaInitBehavior: Behavior[InitializeMessage],
      registrationFactory: RegistrationFactory,
      cswCtx: CswContext,
      svr: ActorRef[SupervisorMessage]
  ): Behavior[Supervisor2Message] =
    Behaviors.setup { superCtx: ActorContext[Supervisor2Message] =>
      // Waiting a bit (200ms) to give time for state subscribers to get in
      val smallStartupDelay = 200.millis
      Behaviors.withTimers { startTimer =>
        startTimer.startSingleTimer(TLAStart(tlaInitBehavior, retryCount = 0, delayBetween = 2.seconds), smallStartupDelay)
        // Stash here saves commands issued during initialization.  They are played back when entering Running
        Behaviors.withStash(capacity = 10) { buffer =>
          new SupervisorBehavior2(tlaInitBehavior, registrationFactory, cswCtx, svr, superCtx).starting(buffer)
        }
      }
    }

  private class SupervisorBehavior2(
      tlaInitBehavior: Behavior[InitializeMessage],
      registrationFactory: RegistrationFactory,
      cswCtx: CswContext,
      svr: ActorRef[SupervisorMessage],
      superCtx: ActorContext[Supervisor2Message]
  ) {
    val initResponseMapper: ActorRef[TopLevelComponent.InitializeResponse] =
      superCtx.messageAdapter(rsp => WrappedInitializeResponse(rsp))
    val lockResponseMapper: ActorRef[LockManager2Response] =
      superCtx.messageAdapter(rsp => WrappedLockManager2Response(rsp))
    val locationRegisterResponseMapper: ActorRef[SupervisorRegisterResponse] =
      superCtx.messageAdapter(rsp => WrappedRegisterResponse(rsp))
    val locationUnregisterResponseMapper: ActorRef[SupervisorUnregisterResponse] =
      superCtx.messageAdapter(rsp => WrappedUnregisterResponse(rsp))
    val shutdownResponseMapper: ActorRef[ShutdownResponse] =
      superCtx.messageAdapter(rsp => WrappedShutdownResponse(rsp))
    val onlineResponseMapper: ActorRef[OnlineResponse] =
      superCtx.messageAdapter(rsp => WrappedOnlineResponse(rsp))
    val offlineResponseMapper: ActorRef[OfflineResponse] =
      superCtx.messageAdapter(rsp => WrappedOfflineResponse(rsp))
    val diagnosticModeResponseMapper: ActorRef[DiagnosticModeResponse] =
      superCtx.messageAdapter(rsp => WrappedDiagnosticResponse(rsp))

    private val stateHandler =
      superCtx.spawn(LifecycleHandler(cswCtx.loggerFactory, svr), "SupervisorStateHandler")
    private val crm = superCtx.spawn(MiniCRM.make(), "CRM")

    // Provided by cswContext
    private val currentStatePublisher = cswCtx.currentStatePublisher

    // This is needed to shutdown the HTTP server if it is used. TODO: Remove this?
    private var embeddedServer: Option[ServerBinding] = None

    private val prefix = cswCtx.componentInfo.prefix
    private val log    = cswCtx.loggerFactory.getLogger(superCtx)

    val MAX_RETRIES = 3 // TODO: Get this from config?

    /**
     * This state is entered when there is a problem that is unrecoverable. It is a holding place for an
     * operator to take action.  Actions are Restart, and Shutdown.
     * We should only enter this state when we are unregistered.
     */
    def idle(): Behavior[Supervisor2Message] = {
      log.debug(s"Entering Supervisor idle state")
      stateHandler ! UpdateState(SupervisorLifecycleState.Idle)

      Behaviors.withStash(capacity = 10) { buffer =>
        Behaviors.receiveMessagePartial {
          case wrapped: WrappedSupervisorMessage =>
            wrapped.response match {
              case Restart =>
                log.info(s"Component: $prefix received a Restart while in the Idle state.")
                superCtx.self ! TLAStart(tlaInitBehavior, retryCount = 0, delayBetween = 2.seconds)
                starting(buffer)
              case Shutdown =>
                log.info(s"Component: $prefix received a Shutdown while in the Idle state causing exit.")
                superCtx.system.terminate()
                Behaviors.same
            }
        }
      }
    }

    def starting(buffer: StashBuffer[Supervisor2Message]): Behavior[Supervisor2Message] =
      Behaviors.receiveMessagePartial {
        case TLAStart(tlaInitBehavior, retryCount, delayBetween) =>
          log.debug(s"Received TLAStart in starting with retryCount=$retryCount")
          val tlaInit = superCtx.spawn(tlaInitBehavior, "tlaInit")
          // This watches init actor and sends a TLATerminated to initializing behavior if crashes
          superCtx.watchWith(tlaInit, TlATerminated(tlaInitBehavior, retryCount, delayBetween))
          initializing(tlaInit, buffer)
        case wrapped: WrappedSupervisorMessage =>
          wrapped.response match {
            case LifecycleStateSubscription2(subscriber) =>
              stateHandler ! SubscribeState(subscriber)
              Behaviors.same
            case _ =>
              // stash any other SupervisorMessages for later during Running
              buffer.stash(wrapped)
              Behaviors.same
          }
      }

    def initializing(
        tlaInit: ActorRef[InitializeMessage],
        buffer: StashBuffer[Supervisor2Message]
    ): Behavior[Supervisor2Message] = {
      val InitializeTimerKey = "initialize-timer"

      stateHandler ! UpdateState(SupervisorLifecycleState.Initializing /*(prefix)*/ )

      Behaviors.withTimers { timers =>
        log.info(s"Supervisor initializing TLA: $prefix")

        tlaInit ! InitializeMessage.Initialize(initResponseMapper)
        timers.startSingleTimer(InitializeTimerKey, InitializeTimeout, cswCtx.componentInfo.initializeTimeout)

        Behaviors.receiveMessagePartial {
          case wrapped: WrappedInitializeResponse =>
            log.info("Received initialize response from TLA within timeout, cancelling InitializeTimer")
            timers.cancel(InitializeTimerKey)

            wrapped.response match {
              case InitializeSuccess(runningBehavior) =>
                log.debug(s"Component: $prefix received InitializeSuccess from TLA: $tlaInit")
                superCtx.unwatch(tlaInit)
                // Stop the init actor and start the provided running behavior
                superCtx.stop(tlaInit)
                val tlaRunning = superCtx.spawn(runningBehavior, "running")
                // Success: jump to registering
                registering(tlaRunning, registrationFactory, cswCtx, buffer)
              case InitializeSuccess2(runningBehavior) =>
                log.debug(s"Component: $prefix received InitializeSuccess from TLA: $tlaInit")
                //superCtx.unwatch(tlaInit)
                // Stop the init actor and start the provided running behavior
                //superCtx.stop(tlaInit)
                //val tlaRunning:Behavior[RunningMessage] = runningBehavior.narrow
                val tlaRunning = superCtx.spawn(runningBehavior, "running")
                // Success: jump to registering
                registering(tlaRunning, registrationFactory, cswCtx, buffer)
              case InitializeFailureStop =>
                log.info(s"Supervisor received InitializeFailureStop from TLA: $prefix. Going Idle.")
                // Unwatch here so restart isn't done
                superCtx.unwatch(tlaInit)
                superCtx.stop(tlaInit)
                idle()
              case InitializeFailureRestart =>
                log.error(s"Supervisor received InitializeFailureRestart from TLA: $prefix. Restarting.")
                // Causes TLATerminated
                superCtx.stop(tlaInit)
                Behaviors.same
            }
          case wrapped: WrappedSupervisorMessage =>
            wrapped.response match {
              case LifecycleStateSubscription2(subscriber) =>
                stateHandler ! SubscribeState(subscriber)
                Behaviors.same
              case GetSupervisorLifecycleState(replyTo) =>
                stateHandler ! SendState(replyTo)
                Behaviors.same
              case _ =>
                // Save any other messages for un-stashing in running
                buffer.stash(wrapped)
                Behaviors.same
            }
          case InitializeTimeout =>
            log.info(s"Component: $prefix timed out during initialization.")
            // Generate alarm?
            idle()

          case TlATerminated(tlaBehavior, retryCount, delayBetween) =>
            // Don't need to do this since new startTimer will replace, but to be complete...
            timers.cancel(InitializeTimerKey)
            if (retryCount < MAX_RETRIES) {
              log.error(s"TLA terminated. Restart: $retryCount, but first wait: $delayBetween")
              timers.startSingleTimer(RestartTimerKey, TLAStart(tlaBehavior, retryCount + 1, delayBetween), delayBetween)
              starting(buffer)
            }
            else {
              log.error(
                s"Component: $prefix failed to initialize. Tried $retryCount times over ${retryCount * delayBetween}."
              )
              idle()
            }
        }
      }
    }

    def registering(
        tla: ActorRef[RunningMessage],
        registrationFactory: RegistrationFactory,
        cswCtx: CswContext,
        buffer: StashBuffer[Supervisor2Message]
    ): Behavior[Supervisor2Message] = {
      val componentInfo = cswCtx.componentInfo

      stateHandler ! UpdateState(SupervisorLifecycleState.Registering /*(prefix)*/ )

      val locationHelper =
        superCtx.spawn(SupervisorLocationHelper(registrationFactory, cswCtx), "LocationHelper")

      // If there are any connections in the componentInfo, track them
      trackConnections(cswCtx)

      locationHelper ! Register(componentInfo, svr, locationRegisterResponseMapper)

      def waiting(expected: Int, count: Int): Behavior[Supervisor2Message] =
        Behaviors.receiveMessage {
          case wrapped: WrappedRegisterResponse =>
            wrapped.response match {
              case AkkaRegisterSuccess(componentInfo) =>
                log.info(s"Component: ${componentInfo.prefix} registered Akka successfully.")
                check(expected, count)
              case HttpRegisterSuccess(componentInfo, binding) =>
                val port = binding.localAddress.getPort
                embeddedServer = Some(binding)
                log.info(s"Component: ${componentInfo.prefix} registered HTTP successfully on port: $port.")
                check(expected, count)
              case RegistrationFailed(componentInfo, connectionType) =>
                log.error(s"Component: ${componentInfo.prefix} registration of connection type: $connectionType failed.")
                idle()
              case RegistrationNotRequired(componentInfo, connectionType) =>
                log.debug(s"Component: ${componentInfo.prefix} does not register with: $connectionType")
                Behaviors.same
            }
          case other =>
            // All other Supervisor2Messages are stashed until running state while registering
            buffer.stash(other)
            Behaviors.same
        }

      // Checks to see if all the expected successful location responses have been returned
      def check(expected: Int, count: Int): Behavior[Supervisor2Message] = {
        if (expected == count + 1) {
          // If all have registered, stop the helper and go to running, else wait for all to complete
          superCtx.stop(locationHelper)
          buffer.unstashAll(running(tla, cswCtx, online = true))
        }
        else {
          waiting(expected, count + 1)
        }
      }

      def trackConnections(cswContext: CswContext): Unit = {
        val componentInfo = cswContext.componentInfo
        if (componentInfo.connections.isEmpty)
          log.debug(s"No Connections to track for component: ${cswContext.componentInfo.prefix}")

        if (componentInfo.locationServiceUsage == RegisterAndTrackServices) {
          componentInfo.connections.foreach(connection => {
            cswContext.locationService
              .subscribe(connection, trackingEvent => svr ! TrackingEventReceived(trackingEvent))
          })
        }
      }

      waiting(expected = cswCtx.componentInfo.registerAs.size, count = 0)
    }

    def running(tla: ActorRef[RunningMessage], cswCtx: CswContext, online: Boolean): Behavior[Supervisor2Message] = {
      if (online)
        stateHandler ! UpdateState(SupervisorLifecycleState.Running)
      else
        stateHandler ! UpdateState(SupervisorLifecycleState.RunningOffline)

      Behaviors.receiveMessagePartial { msg =>
        log.debug(s"Supervisor in lifecycle state :[${SupervisorLifecycleState.Running}] received message :[$msg]")

        msg match {
          case wrapped: WrappedSupervisorMessage =>
            wrapped.response match {
              case v @ Validate(_, replyTo) =>
                val id     = Id()
                val helper = superCtx.spawnAnonymous(ValidateHelper(id, cswCtx.loggerFactory, replyTo))
                tla ! RunningMessage.Validate(id, v.command, helper)
                Behaviors.same
              case o @ Oneway(_, replyTo) =>
                val id = Id()
                superCtx.spawnAnonymous(OnewayHelper(id, tla, o.command, cswCtx.loggerFactory, replyTo))
                Behaviors.same
              case s @ Submit(_, replyTo) =>
                val id = Id()
                val cm = superCtx.spawnAnonymous(CommandHelper(id, tla, s.command, crm, cswCtx.loggerFactory, replyTo))
                superCtx.watchWith(cm, CommandHelperTerminated(id))
                Behaviors.same
              case Query(runId, replyTo) =>
                crm ! MiniCRMMessage.Query(runId, replyTo)
                Behaviors.same
              case QueryFinal(runId, replyTo) =>
                crm ! MiniCRMMessage.QueryFinal(runId, replyTo)
                Behaviors.same
              case LifecycleStateSubscription2(subscriber) =>
                stateHandler ! SubscribeState(subscriber)
                Behaviors.same
              case GetSupervisorLifecycleState(replyTo) =>
                stateHandler ! SendState(replyTo)
                Behaviors.same
              case Lock(lockPrefix, replyTo, leaseDuration) =>
                val lockManager = superCtx.spawn(LockManager2(cswCtx.loggerFactory), "lockManager")
                lockManager ! LockComponent(lockPrefix, replyTo, lockResponseMapper, leaseDuration)
                locked(tla, lockManager, cswCtx, lockPrefix, isLocked = false)
              case Unlock(_, replyTo) =>
                replyTo ! ReleasingLockFailed("The component is not currently locked.")
                Behaviors.same
              case Lifecycle(message) =>
                log.info(s"Component: $prefix received lifecycle: $message")
                message match {
                  case GoOffline =>
                    tla ! RunningMessage.GoOffline(offlineResponseMapper)
                    Behaviors.same
                  case GoOnline =>
                    tla ! RunningMessage.GoOnline(onlineResponseMapper)
                    Behaviors.same
                }
                Behaviors.same
              case DiagnosticMode(startTime, hint) =>
                tla ! RunningMessage.DiagnosticMode(startTime, hint, diagnosticModeResponseMapper)
                Behaviors.same
              case OperationsMode =>
                tla ! RunningMessage.OperationsMode(diagnosticModeResponseMapper)
                Behaviors.same
               case ComponentStateSubscription(subscriberMessage) =>
                currentStatePublisher.publisherActor.unsafeUpcast ! subscriberMessage
                Behaviors.same
              case TrackingEventReceived(event) =>
                tla ! RunningMessage.TrackingEventReceived(event)
                Behaviors.same
              case Restart =>
                unRegistering(tla, registrationFactory, cswCtx, restarting)
              case Shutdown =>
                log.info("Supervisor")
                unRegistering(tla, registrationFactory, cswCtx, shuttingDown)
            }
          case wrapped: WrappedOnlineResponse =>
            wrapped.response match {
              case OnlineSuccess =>
                log.debug(s"Component TLA transitioning to Online")
                running(tla, cswCtx, online = true)
              case OnlineFailure =>
                println("Online Failure")
                Behaviors.same
            }
          case wrapped: WrappedOfflineResponse =>
            wrapped.response match {
              case OfflineSuccess =>
                log.debug("Transitioning to running offline")
                running(tla, cswCtx, online = false)
              case OfflineFailure =>
                println("Offline failure")
                Behaviors.same
            }
          case CommandHelperTerminated(id) =>
            log.debug(s"Supervisor notes CommandHelper for: $id terminated")
            // Retaining for now in case need to keep/remove command runIds
            Behaviors.same
        }
      }
    }

    def unRegistering(
        tla: ActorRef[RunningMessage],
        registrationFactory: RegistrationFactory,
        cswCtx: CswContext,
        next: (ActorRef[RunningMessage], CswContext) => Behavior[Supervisor2Message]
    ): Behavior[Supervisor2Message] = {
      val componentInfo = cswCtx.componentInfo

      val log = cswCtx.loggerFactory.getLogger
      log.debug(s"Unregistering component: ${componentInfo.prefix}")

      stateHandler ! UpdateState(SupervisorLifecycleState.Unregistering /*(cswCtx.componentInfo.prefix)*/ )

      val locationHelper =
        superCtx.spawn(SupervisorLocationHelper(registrationFactory, cswCtx), "LocationHelper")

      locationHelper ! Unregister(componentInfo, locationUnregisterResponseMapper)

      def waiting(expected: Int, count: Int): Behavior[Supervisor2Message] =
        Behaviors.receiveMessagePartial {
          case wrapped: WrappedUnregisterResponse =>
            wrapped.response match {
              case AkkaUnregisterSuccess(componentInfo) =>
                log.info(s"Component: ${componentInfo.prefix} unregistered Akka successfully.")
                check(expected, count)
              case HttpUnregisterSuccess(componentInfo) =>
                implicit val ec = superCtx.system.executionContext
                log.info(s"Component: ${componentInfo.prefix} unregistered HTTP successfully.")
                // Shutdown the embedded server
                val embeddedServerTerminationResult =
                  embeddedServer.map(_.terminate(20.seconds).map(_ => Done)).getOrElse(Future.successful(Done))
                log.debug(s"Embedded server shutdown result: $embeddedServerTerminationResult")
                check(expected, count)
              case UnregisterFailed(componentInfo, connectionType) =>
                log.error(s"Component: ${componentInfo.prefix} unregister of connection type: $connectionType failed.")
                check(expected, count)
            }
        }

      // Checks to see if all the expected successful location responses have been returned
      def check(expected: Int, count: Int): Behavior[Supervisor2Message] = {
        if (expected == count + 1) {
          // If all have registered, stop the helper and go to running, else wait for all to complete
          superCtx.stop(locationHelper)
          next(tla, cswCtx)
        }
        else {
          waiting(expected, count + 1)
        }
      }

      waiting(expected = cswCtx.componentInfo.registerAs.size, count = 0)
    }

    def restarting(tla: ActorRef[RunningMessage], cswCtx: CswContext): Behavior[Supervisor2Message] = {

      log.debug(s"Restarting request started")
      stateHandler ! UpdateState(SupervisorLifecycleState.Restart)

      tla ! RunningMessage.Shutdown(shutdownResponseMapper)

      Behaviors.withStash(capacity = 10) { stash =>
        Behaviors.withTimers { timers =>
          timers.startSingleTimer(ShutdownTimerKey, ShutdownTimeout, cswCtx.componentInfo.initializeTimeout)
          superCtx.watchWith(tla, TLAStart(tlaInitBehavior, 0, 2.seconds))

          Behaviors.receive { (context, message) =>
            message match {
              case wrapped: WrappedShutdownResponse =>
                wrapped.response match {
                  case ShutdownSuccessful =>
                    log.info(s"Supervisor stopping TLA: $tla for requested restart")
                    context.stop(tla) // stop a component actor for a graceful shutdown before shutting down the actor system
                    starting(stash)
                  case ShutdownFailed =>
                    log.error(s"Request to restart $prefix failed due to TLA returning ShutdownFailed.")
                    idle()
                }
              case ShutdownTimeout =>
                println("Shutdown failed to return and timed out.")
                superCtx.system.terminate()
                Behaviors.same
              case other =>
                println(s"Shutdown got other: $other")
                Behaviors.same
            }
          }
        }
      }
    }

    def shuttingDown(
        tla: ActorRef[RunningMessage],
        cswCtx: CswContext
    ): Behavior[Supervisor2Message] = {

      stateHandler ! UpdateState(SupervisorLifecycleState.Shutdown)

      log.debug(s"Shutting my tla ass down")

      tla ! RunningMessage.Shutdown(shutdownResponseMapper)

      Behaviors.withTimers { timers =>
        timers.startSingleTimer(ShutdownTimerKey, ShutdownTimeout, cswCtx.componentInfo.initializeTimeout)

        Behaviors.receiveMessagePartial {
          case wrapped: WrappedShutdownResponse =>
            wrapped.response match {
              case ShutdownSuccessful =>
                println("Shutdown successful")
                timers.cancel(ShutdownTimerKey)
                superCtx.stop(tla) // stop a component actor for a graceful shutdown before shutting down the actor system
                superCtx.system.terminate()
                Behaviors.same
              case ShutdownFailed =>
                println("Shutdown failed!")
                Behaviors.same
            }
          case ShutdownTimeout =>
            println("Shutdown failed to return and timed out.")
            superCtx.system.terminate()
            Behaviors.same
        }
      }
    }

    def locked(
        tla: ActorRef[RunningMessage],
        lockManager: ActorRef[LockManager2Message],
        cswCtx: CswContext,
        lockPrefix: Prefix,
        isLocked: Boolean
    ): Behavior[Supervisor2Message] = {
      val log = cswCtx.loggerFactory.getLogger

      Behaviors.receiveMessagePartial {
        case wrapped: WrappedSupervisorMessage =>
          wrapped.response match {
            case Unlock(unlockPrefix, replyTo) =>
              lockManager ! UnlockComponent(unlockPrefix, replyTo, lockResponseMapper)
              Behaviors.same
            case Lock(lockPrefix, replyTo, leaseDuration) =>
              // Reup the lock if it is the correct prefix
              lockManager ! LockComponent(lockPrefix, replyTo, lockResponseMapper, leaseDuration)
              Behaviors.same
            case cmdMsg: CommandMessage =>
              if (cmdMsg.command.source == lockPrefix || cmdMsg.command.source == LockManager2.AdminPrefix) {
                cmdMsg match {
                  case Validate(_, replyTo) =>
                    val id     = Id()
                    val helper = superCtx.spawnAnonymous(ValidateHelper(id, cswCtx.loggerFactory, replyTo))
                    tla ! RunningMessage.Validate(id, cmdMsg.command, helper)
                    Behaviors.same
                  case Oneway(_, replyTo) =>
                    val id = Id()
                    superCtx.spawnAnonymous(OnewayHelper(id, tla, cmdMsg.command, cswCtx.loggerFactory, replyTo))
                    Behaviors.same
                  case Submit(_, replyTo) =>
                    val id = Id()
                    superCtx.spawnAnonymous(CommandHelper(id, tla, cmdMsg.command, crm, cswCtx.loggerFactory, replyTo))
                    Behaviors.same
                }
              }
              else {
                log.info(s"Command ${cmdMsg.command.commandName} for $prefix rejected due to lock")
                cmdMsg.replyTo ! CommandResponse.Locked(Id())
              }
              Behaviors.same
            case GetSupervisorLifecycleState(replyTo) =>
              stateHandler ! SendState(replyTo)
              Behaviors.same
          }
        case wrapped: WrappedLockManager2Response =>
          wrapped.response match {
            case LockManager2.Locked(lockPrefix) =>
              log.info(s"Component: $prefix is locked by $lockPrefix")
              stateHandler ! UpdateState(SupervisorLifecycleState.Lock)
              locked(tla, lockManager, cswCtx, lockPrefix, isLocked = true)
            case LockManager2.Unlocked =>
              log.info(s"Component: $prefix is no longer locked")
              running(tla, cswCtx, online = true)
          }
      }

    }
  }
}
