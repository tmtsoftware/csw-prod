package csw.framework.internal.supervisor

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.actor.typed.{ActorRef, Behavior}
import csw.command.client.MiniCRM
import csw.command.client.MiniCRM.MiniCRMMessage
import csw.command.client.MiniCRM.MiniCRMMessage.Print
import csw.command.client.messages.CommandMessage.{Oneway, Submit, Validate}
import csw.command.client.messages.ComponentCommonMessage.{
  GetSupervisorLifecycleState,
  LifecycleStateSubscription2,
  TrackingEventReceived
}
import csw.command.client.messages.RunningMessage.Lifecycle
import csw.command.client.messages.SupervisorContainerCommonMessages.{Restart, Shutdown}
import csw.command.client.messages.SupervisorLockMessage.{Lock, Unlock}
import csw.command.client.messages.{RunningMessage => _, _}
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
import csw.logging.api.scaladsl.Logger
import csw.params.commands.CommandResponse
import csw.params.commands.CommandResponse.{SubmitResponse, ValidateCommandResponse}
import csw.params.core.models.Id
import csw.prefix.models.Prefix

import scala.concurrent.duration.{DurationInt, FiniteDuration}

object SupervisorBehavior2 {

  sealed trait Supervisor2Message extends akka.actor.NoSerializationVerificationNeeded

  private val InitializeTimerKey = "initialize-timer"
  private val ShutdownTimerKey   = "shutdown-timer"
  private val RestartTimerKey    = "restart-timer"

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
  final case object PrintCRM                               extends Supervisor2Message

  final case class WrappedInitializeResponse(response: InitializeResponse) extends Supervisor2Message

  private final case class WrappedLocationManagerResponse(response: SupervisorLocationResponse) extends Supervisor2Message

  private final case class WrappedLockManager2Response(response: LockManager2Response) extends Supervisor2Message

  private final case class WrappedSubmitResponse(response: SubmitResponse) extends Supervisor2Message

  private final case class WrappedValidateResponse(response: ValidateCommandResponse)              extends Supervisor2Message
  private final case class WrappedShutdownResponse(response: ShutdownResponse)                     extends Supervisor2Message
  private final case class WrappedOnlineResponse(response: OnlineResponse)                         extends Supervisor2Message
  private final case class WrappedTopLevelActorCommonMessage(response: TopLevelActorCommonMessage) extends Supervisor2Message
  private final case class WrappedString(response: String)                                         extends Supervisor2Message
  private final case class WrappedSupervisorMessage(response: SupervisorMessage)                   extends Supervisor2Message with akka.actor.NoSerializationVerificationNeeded

  def apply(
             tlaInitBehavior: Behavior[InitializeMessage],
             registrationFactory: RegistrationFactory,
             cswCtx: CswContext
  ): Behavior[SupervisorMessage] = {
    Behaviors.setup { ctx =>
      println("Yes")

      // Stash here saves commands issued during initialization.  They are played back when entering Running
      //Behaviors.withStash(capacity = 10) { buffer =>
      val svrBehavior: Behavior[Supervisor2Message] = make(tlaInitBehavior, registrationFactory, cswCtx, ctx.self)
      val newSuper                                  = ctx.spawn(svrBehavior, "newSuper")

      Behaviors.receiveMessage { msg =>
        println(s"---------------------------------Proxy received: $msg")
        newSuper ! WrappedSupervisorMessage(msg)
        Behaviors.same
      }
    }
  }

  def make(
            tlaInitBehavior: Behavior[InitializeMessage],
            registrationFactory: RegistrationFactory,
            cswCtx: CswContext,
            svr: ActorRef[SupervisorMessage]
  ): Behavior[Supervisor2Message] = {

    Behaviors.setup { superCtx: ActorContext[Supervisor2Message] =>
      val log: Logger = cswCtx.loggerFactory.getLogger
      log.info("DEBUGGER IS WORKING DAMN IT")

      // Stash here saves commands issued during initialization.  They are played back when entering Running
      Behaviors.withStash(capacity = 10) { buffer =>
        println("Sending TLAStart")
        Thread.sleep(200)
        superCtx.self ! TLAStart(tlaInitBehavior, 0, 2.seconds)
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
    val locationHelperResponseMapper: ActorRef[SupervisorLocationResponse] =
      superCtx.messageAdapter(rsp => WrappedLocationManagerResponse(rsp))
    val stringResponseMapper: ActorRef[String] = superCtx.messageAdapter(rsp => WrappedString(rsp))
    val shutdownResponseMapper: ActorRef[ShutdownResponse] =
      superCtx.messageAdapter(rsp => WrappedShutdownResponse(rsp))
    val onlineResponseMapper: ActorRef[OnlineResponse] =
      superCtx.messageAdapter(rsp => WrappedOnlineResponse(rsp))
    val topLevelActorCommonMessageMapper: ActorRef[TopLevelActorCommonMessage] =
      superCtx.messageAdapter(rsp => WrappedTopLevelActorCommonMessage(rsp))

    val supervisorMessageMapper: ActorRef[SupervisorMessage] =
      superCtx.messageAdapter(rsp => WrappedSupervisorMessage(rsp))

    private val stateHandler =
      superCtx.spawn(LifecycleHandler(cswCtx.loggerFactory, svr), "SupervisorStateHandler")
    private val crm = superCtx.spawn(MiniCRM.make(), "CRM")
    private val log = cswCtx.loggerFactory.getLogger

    val MAX_RETRIES = 3

    // This state is entered when there is a problem that is unrecoverable. It is a holding place for an
    // operator to take action.  Actions are Restart, and Shutdown.
    // We should only enter this state when we are unregistered
    def idle(): Behavior[Supervisor2Message] = {
      println(s"Entering idle")
      stateHandler ! UpdateState(SupervisorLifecycleState.Idle)

      Behaviors.withStash(capacity = 10) { buffer =>
        Behaviors.receiveMessage[Supervisor2Message] {
          case wrapped: WrappedSupervisorMessage =>
            wrapped.response match {
              case Restart =>
                println("Got Restart from Idle")
                superCtx.self ! TLAStart(tlaInitBehavior, 0, 2.seconds)
                starting(buffer)
              case Shutdown =>
                println("Received Shutdown from Idle")
                superCtx.system.terminate()
                Behaviors.same
            }
        }
      }
    }

    def starting(buffer: StashBuffer[Supervisor2Message]): Behavior[Supervisor2Message] = {
      println(s"Entering starting with: $buffer")
      Behaviors.receiveMessage[Supervisor2Message] {
        case TLAStart(tlaInitBehavior, retryCount, delayBetween) =>
          println(s"Got the TLAStart with $retryCount")
          val tlaInit = superCtx.spawn(tlaInitBehavior, "tlaInit")
          println(s"tlaInit Actor: $tlaInit")
          superCtx.watchWith(tlaInit, TlATerminated(tlaInitBehavior, retryCount, delayBetween))
          initializing(tlaInit, buffer)
        case wrapped: WrappedSupervisorMessage =>
          wrapped.response match {
            case LifecycleStateSubscription2(subscriber) =>
              stateHandler ! SubscribeState(subscriber)
              Behaviors.same
            case other =>
              println(s"Buffering in starting: $other")
              buffer.stash(wrapped)
              Behaviors.same
          }
      }
    }

    def initializing(
                      tlaInit: ActorRef[InitializeMessage],
                      buffer: StashBuffer[Supervisor2Message]
    ): Behavior[Supervisor2Message] = {
      log.debug("Initialzing")
      println("Initializing")
      val componentInfo = cswCtx.componentInfo

      Behaviors.withTimers { timers =>
        println("Sending iniitalize to TLA")
        tlaInit ! Initialize(initResponseMapper)
        timers.startSingleTimer(InitializeTimerKey, InitializeTimeout, cswCtx.componentInfo.initializeTimeout)

        Behaviors.receiveMessage {
          case wrapped: WrappedInitializeResponse =>
            log.info("Received initialize reponse from component within timeout, cancelling InitializeTimer")
            timers.cancel(InitializeTimerKey)

            wrapped.response match {
              case InitializeSuccess(runningBehavior) =>
                println(s"Received InitializeSuccess from TLA: $tlaInit")
                superCtx.unwatch(tlaInit)
                // Stop the init actor
                superCtx.stop(tlaInit)
                val tlaRunning = superCtx.spawn(runningBehavior, "running")
                println(s"INit actor: $tlaInit")
                println(s"Running actor: $tlaRunning")
                registering(tlaRunning, registrationFactory, cswCtx, buffer)
              case InitializeFailureStop =>
                println("Initialize failure Stop")
                // Unwatch here so restart isn't done
                superCtx.unwatch(tlaInit)
                superCtx.stop(tlaInit)
                log.debug(s"Component: ${componentInfo.prefix} initialize failed with stop. Going idle.")
                idle()
              case InitializeFailureRestart =>
                println(s"Got falure restart")
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
              case save =>
                println(s"Buffering in init: ${save}")
                buffer.stash(wrapped)
                Behaviors.same
            }
          case InitializeTimeout =>
            log.info(s"Component: ${componentInfo.prefix} timed out during initialization.")
            // Gemerate alarm?
            idle()

          case TlATerminated(tlaBehavior, retryCount, delayBetween) =>
            // Don't need to do this since new startTimer will replace, but to be complete...
            timers.cancel(InitializeTimerKey)
            println("TLA Terminated")
            if (retryCount < MAX_RETRIES) {
              println(s"Retry: $retryCount, starting timer for $delayBetween")
              timers.startSingleTimer(RestartTimerKey, TLAStart(tlaBehavior, retryCount + 1, delayBetween), delayBetween)
              starting(buffer)
            }
            else {
              log.error(
                s"Component: ${componentInfo.prefix} failed to initialize.  Tried $retryCount times over ${retryCount * delayBetween}."
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
      val prefix        = componentInfo.prefix

      stateHandler ! UpdateState(SupervisorLifecycleState.Registering /*(prefix)*/)

      val locationHelper =
        superCtx.spawn(SupervisorLocationHelper(registrationFactory, cswCtx), "LocationHelper")

      trackConnections(cswCtx)

      locationHelper ! Register(componentInfo, svr, locationHelperResponseMapper)

      def waiting(expected: Int, count: Int): Behavior[Supervisor2Message] =
        Behaviors.receiveMessage {
          case wrapped: WrappedLocationManagerResponse =>
            wrapped.response match {
              case AkkaRegisterSuccess(componentInfo) =>
                log.info(s"Component: ${componentInfo.prefix} registered Akka successfully.")
                check(expected, count)
              case HttpRegisterSuccess(componentInfo, port) =>
                log.info(s"Component: ${componentInfo.prefix} registered HTTP successfully on port: $port.")
                check(expected, count)
              case RegistrationFailed(componentInfo, connectionType) =>
                log.error(s"Registration of connection type: $connectionType failed for ${componentInfo.prefix}")
                idle()
              case xother =>
                println(s"Got an other message: $xother in waiting")
                Behaviors.same
            }
          case other =>
            // All other messages are stashed until running
            println("Buffering in waiting")
            buffer.stash(other)
            Behaviors.same
        }

      // Checks to see if all the expected succsssful location responses have been returned
      def check(expected: Int, count: Int): Behavior[Supervisor2Message] = {
        if (expected == count + 1) {
          // If all have registered, stop the helper and go to running, else wait for all to complete
          superCtx.stop(locationHelper)
          buffer.unstashAll(running(tla, cswCtx))
        }
        else {
          waiting(expected, count + 1)
        }
      }

      def trackConnections(cswContext: CswContext): Unit = {
        val componentInfo = cswContext.componentInfo
        if (componentInfo.connections.isEmpty) println("No Connections to Track!")
        if (componentInfo.locationServiceUsage == RegisterAndTrackServices) {
          componentInfo.connections.foreach(connection => {
            cswContext.locationService
              .subscribe(connection, trackingEvent => supervisorMessageMapper ! TrackingEventReceived(trackingEvent))
          })
        }
      }

      waiting(expected = cswCtx.componentInfo.registerAs.size, count = 0)
    }

    def running(tla: ActorRef[RunningMessage], cswCtx: CswContext): Behavior[Supervisor2Message] = {
      println("************Got to RUNNING")
      stateHandler ! UpdateState(SupervisorLifecycleState.Running)

      Behaviors.receive { (context, msg) =>
        log.debug(s"Supervisor in lifecycle state :[${SupervisorLifecycleState.Running}] received message :[$msg]")

        msg match {
          case wrapped: WrappedSupervisorMessage =>
            wrapped.response match {
              case v @ Validate(_, replyTo) =>
                val id = Id()
                //cman ! SendValidate(id, cmdMsg.command, replyTo)
                val helper = superCtx.spawnAnonymous(ValidateHelper(id, replyTo))
                tla ! Validate2(id, v.command, helper)
                Behaviors.same
              case o @ Oneway(_, replyTo) =>
                val id = Id()
                superCtx.spawnAnonymous(OnewayHelper(id, tla, o.command, replyTo))
                Behaviors.same
              case s @ Submit(_, replyTo) =>
                val id = Id()
                val cm = superCtx.spawnAnonymous(CommandHelper(id, tla, s.command, crm, replyTo))
                superCtx.watchWith(cm, CommandHelperTerminated(id))
                Behaviors.same
              case Query(runId, replyTo) =>
                crm ! MiniCRMMessage.Query(runId, replyTo)
                Behaviors.same
              case QueryFinal(runId, replyTo) =>
                crm ! MiniCRMMessage.QueryFinal(runId, replyTo)
                Behaviors.same
              case LifecycleStateSubscription2(subscriber) =>
                println(s"Procssing subscription message: $subscriber")
                stateHandler ! SubscribeState(subscriber)
                Behaviors.same
              case GetSupervisorLifecycleState(replyTo) =>
                println("Got GetState in running")
                stateHandler ! SendState(replyTo)
                Behaviors.same
              case Lock(lockPrefix, replyTo, leaseDuration) =>
                println("Got Lock in Running")
                val lockManager = superCtx.spawn(LockManager2(cswCtx.loggerFactory), "lockManager")
                lockManager ! LockComponent(lockPrefix, replyTo, lockResponseMapper, leaseDuration)
                println("Jumping to locking state")
                locked(tla, lockManager, cswCtx, lockPrefix)
              case Unlock(_, replyTo) =>
                replyTo ! ReleasingLockFailed("The component is not currently locked.")
                Behaviors.same
              case Lifecycle(message) =>
                println(s"Got lifecycle: $message")
                //log.info("Invoking lifecycle handler's onGoOnline hook")
                message match {
                  case GoOffline =>
                    tla ! GoOffline2(onlineResponseMapper)
                    Behaviors.same
                  case GoOnline =>
                    tla ! GoOnline2(onlineResponseMapper)
                    Behaviors.same
                }
                Behaviors.same
              case TrackingEventReceived(event) =>
                tla ! TrackingEventReceived2(event)
                Behaviors.same
              case Restart =>
                println("Got Restart")
                unRegistering(tla, registrationFactory, cswCtx, restarting)
              case Shutdown =>
                println("Received SHutdown")
                unRegistering(tla, registrationFactory, cswCtx, shuttingDown)
            }
          case wrapped: WrappedOnlineResponse =>
            wrapped.response match {
              case OfflineSuccess =>
                stateHandler ! UpdateState(SupervisorLifecycleState.RunningOffline)
                Behaviors.same
              case OfflineFailure =>
                println("Offline failure")
                Behaviors.same
              case OnlineSuccess =>
                stateHandler ! UpdateState(SupervisorLifecycleState.Running)
                //log.debug(s"Component TLA is Online")
                Behaviors.same
              case OnlineFailure =>
                println("Online Failure")
                Behaviors.same
            }
          case PrintCRM =>
            println("Got PrintCRM")
            crm ! Print(stringResponseMapper)
            Behaviors.same
          case WrappedString(mess) =>
            println(s"CRM: $mess")
            Behaviors.same
          case CommandHelperTerminated(id) =>
            println(s"CommandHelper Terminated: $id\n\n")
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
      val log = cswCtx.loggerFactory.getLogger
      log.debug(s"Un-registering supervisor from location service")
      println("Un-registering supervisor from location service")

      stateHandler ! UpdateState(SupervisorLifecycleState.Unregistering /*(cswCtx.componentInfo.prefix)*/)

      val locationHelper =
        superCtx.spawn(SupervisorLocationHelper(registrationFactory, cswCtx), "LocationHelper")

      locationHelper ! AkkaUnregister(cswCtx.componentInfo, locationHelperResponseMapper)

      println("Unregistering")
      Behaviors.receiveMessage {
        case wrapped: WrappedLocationManagerResponse =>
          wrapped.response match {
            case AkkaUnregisterSuccess(compId) =>
              println(s"Akka component unregistered successfully: $compId")
              locationHelper ! HttpUnregister(cswCtx.componentInfo, locationHelperResponseMapper)
              Behaviors.same
            case HttpUnregisterSuccess(compId) =>
              println(s"Http component unregistered successfully for: $compId")
              superCtx.stop(locationHelper)
              next(tla, cswCtx)
            case other =>
              println(s"Got unregister other message: $other")
              Behaviors.same
          }
      }
    }

    def restarting(tla: ActorRef[RunningMessage], cswCtx: CswContext): Behavior[Supervisor2Message] = {

      val log = cswCtx.loggerFactory.getLogger
      log.debug(s"Restarting request started")

      stateHandler ! UpdateState(SupervisorLifecycleState.Restart)
      println(s"Sending shutdown to TLA: $tla")

      tla ! Shutdown2(shutdownResponseMapper)

      Behaviors.withStash(capacity = 10) { stash =>
        Behaviors.withTimers { timers =>
          timers.startSingleTimer(ShutdownTimerKey, ShutdownTimeout, cswCtx.componentInfo.initializeTimeout)
          superCtx.watchWith(tla, TLAStart(tlaInitBehavior, 0, 2.seconds))

          Behaviors.receive { (context, message) =>
            message match {
              case wrapped: WrappedShutdownResponse =>
                wrapped.response match {
                  case ShutdownSuccessful =>
                    println("Shutdown during restart successful")
                    //superCtx.watchWith(tla, TLAStart(tlaInitBehavior, 0, 2.seconds))
                    println(s"Stopping: $tla")
                    context.stop(tla) // stop a component actor for a graceful shutdown before shutting down the actor system
                    println("Jumpging to starting")
                    starting(stash)
                  case ShutdownFailed =>
                    println("Shutdown failed damit")
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

      val log = cswCtx.loggerFactory.getLogger
      log.debug(s"Shutting my tla ass down")
      println(s"Got to the shutting down function: $tla")

      tla ! Shutdown2(shutdownResponseMapper)

      Behaviors.withTimers { timers =>
        timers.startSingleTimer(ShutdownTimerKey, ShutdownTimeout, cswCtx.componentInfo.initializeTimeout)

        Behaviors.receiveMessage {
          case wrapped: WrappedShutdownResponse =>
            wrapped.response match {
              case ShutdownSuccessful =>
                println("Shutdown successful")
                timers.cancel(ShutdownTimerKey)
                superCtx.stop(tla) // stop a component actor for a graceful shutdown before shutting down the actor system
                superCtx.system.terminate()
                Behaviors.same
              case ShutdownFailed =>
                println("Shutdown failed damit")
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
        lockPrefix: Prefix
    ): Behavior[Supervisor2Message] = {
      val log = cswCtx.loggerFactory.getLogger
      log.debug(s"Componennt is locked")
      println(s"Into locked with mapper: $lockResponseMapper")
      stateHandler ! UpdateState(SupervisorLifecycleState.Lock)

      Behaviors.receiveMessage {
        case wrapped: WrappedSupervisorMessage =>
          wrapped.response match {
            case Unlock(unlockPrefix, replyTo) =>
              lockManager ! UnlockComponent(unlockPrefix, replyTo, lockResponseMapper)
              Behaviors.same
            case cmdMsg: CommandMessage =>
              if (cmdMsg.command.source == lockPrefix || cmdMsg.command.source == LockManager2.AdminPrefix) {
                cmdMsg match {
                  case Validate(_, replyTo) =>
                    val id     = Id()
                    val helper = superCtx.spawnAnonymous(ValidateHelper(id, replyTo))
                    tla ! Validate2(id, cmdMsg.command, helper)
                    Behaviors.same
                  case Oneway(_, replyTo) =>
                    val id = Id()
                    superCtx.spawnAnonymous(OnewayHelper(id, tla, cmdMsg.command, replyTo))
                    Behaviors.same
                  case Submit(_, replyTo) =>
                    val id = Id()
                    superCtx.spawnAnonymous(CommandHelper(id, tla, cmdMsg.command, crm, replyTo))
                    Behaviors.same
                }
              }
              else {
                println("Command Refused")
                cmdMsg.replyTo ! CommandResponse.Locked(Id())
              }
              Behaviors.same
            case GetSupervisorLifecycleState(replyTo) =>
              println("Got GetState in locked")
              stateHandler ! SendState(replyTo)
              Behaviors.same
          }
        case wrapped: WrappedLockManager2Response =>
          wrapped.response match {
            case Locked =>
              println("****************Got Locked")
              Behaviors.same
            case AcquiringLockFailed2(_, _) =>
              Behaviors.same
            case LockReleased2 =>
              println(s"Lock Released")
              running(tla, cswCtx)
            case other =>
              println(s"Got other: $other")
              Behaviors.same
          }
        case other2 =>
          println(s"Other other: $other2")
          Behaviors.same
      }

    }
  }
}
