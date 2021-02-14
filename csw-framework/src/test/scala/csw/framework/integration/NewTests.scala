package csw.framework.integration

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.{ActorSystem, Behavior, Scheduler, SpawnProtocol}
import csw.command.client.CommandResponseManager
import csw.command.client.messages.CommandMessage.{Oneway, Submit, Validate}
import csw.command.client.messages.ComponentCommonMessage.{GetSupervisorLifecycleState, LifecycleStateSubscription2}
import csw.command.client.messages.DiagnosticDataMessage.DiagnosticMode
import csw.command.client.messages.RunningMessage.Lifecycle
import csw.command.client.messages.SupervisorLockMessage.{Lock, Unlock}
import csw.command.client.messages.{DiagnosticDataMessage, Query, QueryFinal, SupervisorContainerCommonMessages, SupervisorLockMessage, SupervisorMessage}
import csw.command.client.models.framework.LocationServiceUsage.RegisterOnly
import csw.command.client.models.framework.LockingResponse.{LockAcquired, LockExpired, LockExpiringShortly, LockReleased, ReleasingLockFailed}
import csw.command.client.models.framework.ToComponentLifecycleMessage.{GoOffline, GoOnline}
import csw.command.client.models.framework.{ComponentInfo, LifecycleStateChanged, LockingResponse, SupervisorLifecycleState}
import csw.common.components.command
import csw.common.components.command.CommandComponentState.{immediateCmd, invalidCmd, longRunningCmd}
import csw.common.components.command.{TestComponent2, TestComponent3}
import csw.framework.FrameworkTestMocks
import csw.framework.internal.supervisor.{SupervisorBehavior2, SupervisorBehavior2Factory}
import csw.framework.models.CswContext
import csw.location.api.models.ComponentType.Assembly
import csw.location.api.models.ConnectionType.{AkkaType, HttpType}
import csw.location.client.ActorSystemFactory
import csw.logging.client.scaladsl.{LoggerFactory, LoggingSystemFactory}
import csw.params.commands.CommandResponse._
import csw.params.commands.Setup
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem.ESW
import csw.time.core.models.UTCTime
import csw.time.scheduler.TimeServiceSchedulerFactory
import org.mockito.MockitoSugar.mock
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class MyFrameworkMocks {
  implicit val typedSystem: ActorSystem[SpawnProtocol.Command] = ActorSystemFactory.remote(SpawnProtocol(), "testSystem")
  def frameworkTestMocks(): FrameworkTestMocks                 = new FrameworkTestMocks()

  LoggingSystemFactory.start("logging", "1", "localhost", typedSystem)

  //private val manualTime                    = ManualTime()(system)
  //private val jitter                        = 10
  private implicit val scheduler: Scheduler = typedSystem.scheduler
  private implicit val ec: ExecutionContext = typedSystem.executionContext

  def createContext(componentInfo: ComponentInfo): CswContext = {
    val mocks = frameworkTestMocks()
    new CswContext(
      mocks.locationService,
      mocks.eventService,
      mocks.alarmService,
      new TimeServiceSchedulerFactory().make(),
      new LoggerFactory(componentInfo.prefix),
      mocks.configClientService,
      mocks.currentStatePublisher,
      mock[CommandResponseManager],
      componentInfo
    )
  }
}

class NewTests extends ScalaTestWithActorTestKit with AnyFunSuiteLike with BeforeAndAfterEach {
  private val clientPrefix:Prefix = Prefix(ESW, "engUI")
  private val invalidPrefix = Prefix("WFOS.invalid.engUI")

  val assemblyInfo: ComponentInfo = ComponentInfo(
    Prefix("WFOS.SampleAssembly"),
    Assembly,
    "csw.common.components.command.TestComponent",
    RegisterOnly,
    Set(AkkaType, HttpType)
  )

  val assemblyInfo2: ComponentInfo = ComponentInfo(
    Prefix("WFOS.SampleAssembly"),
    Assembly,
    "csw.common.components.command.TestComponent3",
    RegisterOnly,
    Set(AkkaType)
  )

  def waitForState(probe: TestProbe[LifecycleStateChanged],
                   finalState: SupervisorLifecycleState,
                   timeout: FiniteDuration = 5.seconds): List[SupervisorLifecycleState] = {
    var states:List[SupervisorLifecycleState] = List.empty
    probe.fishForMessage(timeout) {
      case LifecycleStateChanged(_, state) =>
        states = states :+ state
        if (state == finalState) {
          FishingOutcome.Complete
        } else FishingOutcome.Continue
    }
    states
  }

  test("should create one") {
    import SupervisorLifecycleState._
    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)

    //val testSuperB:Behavior[SupervisorMessage] = SupervisorBehavior2Factory.make(testMocks.frameworkTestMocks().registrationFactory, cswContext)

    val testSuper = spawn(SupervisorBehavior2(testMocks.frameworkTestMocks().registrationFactory, cswContext))

    //    val testSuper =
//          spawn(SupervisorBehavior2(testTLA, testMocks.frameworkTestMocks().registrationFactory, cswContext))

    //val testSuper =
//      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))
    //val testSuper = spawn(testSuperB)

    val stateChangeProbe = TestProbe[LifecycleStateChanged]

    testSuper ! LifecycleStateSubscription2(stateChangeProbe.ref)

    val states = waitForState(stateChangeProbe, Running)
    assert(states.size == 4)
    assert(states.contains(Running))

    // Unneeded wait 1 seconds") for things to happen
    Thread.sleep(1000)
  }

  test("failure with restart") {
    import SupervisorLifecycleState._
    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestCompInitFailureRestart(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    val stateChangeProbe = TestProbe[LifecycleStateChanged]
    testSuper ! LifecycleStateSubscription2(stateChangeProbe.ref)

    var states = waitForState(stateChangeProbe, Idle)
    assert(states.size == 1)  // First start and then 3 restarts

    states = waitForState(stateChangeProbe, Idle, 10.seconds)
    assert(states.size == 5)  // 4 Initializing + 1 Idle
  }


  test("Lock/unlock the component") {
    val longDuration = 5.seconds
    val lockingResponseProbe = testKit.createTestProbe[LockingResponse]
    val lifecycleStateProbe = testKit.createTestProbe[SupervisorLifecycleState]
    val stateChangeProbe = TestProbe[LifecycleStateChanged]

    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    testSuper ! LifecycleStateSubscription2(stateChangeProbe.ref)

    // Sending lock request (note not yet running)
    testSuper ! SupervisorLockMessage.Lock(clientPrefix, lockingResponseProbe.ref, longDuration)
    lockingResponseProbe.expectMessage(LockAcquired)

    testSuper ! SupervisorLockMessage.Unlock(clientPrefix, lockingResponseProbe.ref)
    lockingResponseProbe.expectMessage(LockReleased)

    val expectedNumberOfStates = 6
    val states = stateChangeProbe.receiveMessages(expectedNumberOfStates, 10.seconds)

    assert(states.map(_.state).count(_ == SupervisorLifecycleState.Running) == 2)

    assert(states.map(_.state).contains(SupervisorLifecycleState.Lock))

    testSuper ! GetSupervisorLifecycleState(lifecycleStateProbe.ref)
    lifecycleStateProbe.expectMessage(SupervisorLifecycleState.Running)
  }

  test("Lock then wait for first message and re-lock") {
    val longDuration = 3.seconds
    val lockingResponseProbe = testKit.createTestProbe[LockingResponse]
    val lifecycleStateProbe = testKit.createTestProbe[SupervisorLifecycleState]

    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    // Sending lock request (note not yet running)
    testSuper ! SupervisorLockMessage.Lock(clientPrefix, lockingResponseProbe.ref, longDuration)
    lockingResponseProbe.expectMessage(LockAcquired)

    // When LockExpiringShortly is received, re-lock - should get this before longDuration
    lockingResponseProbe.expectMessage(longDuration, LockExpiringShortly)

    testSuper ! SupervisorLockMessage.Lock(clientPrefix, lockingResponseProbe.ref, longDuration)
    lockingResponseProbe.expectMessage(LockAcquired)
    // If re-lock worked, we shouldn't get a LockExpired
    lockingResponseProbe.expectNoMessage(2.second)

    // Should get another expiring shortly
    lockingResponseProbe.expectMessage(longDuration, LockExpiringShortly)

    // Wait for expire
    lockingResponseProbe.expectMessage(LockExpired)

    // Now lock is expired, supervisor is in running state so Unlock should give ReleaseLockFailed
    testSuper ! SupervisorLockMessage.Unlock(clientPrefix, lockingResponseProbe.ref)
    lockingResponseProbe.expectMessageType[ReleasingLockFailed]

    testSuper ! GetSupervisorLifecycleState(lifecycleStateProbe.ref)
    lifecycleStateProbe.expectMessage(SupervisorLifecycleState.Running)
  }

  test("send a validate and get response only") {
    val testMocks = new MyFrameworkMocks()
    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    val validateResponseProbe = testKit.createTestProbe[ValidateResponse]

    val setupAccepted = Setup(clientPrefix, immediateCmd, None)
    testSuper ! Validate(setupAccepted, validateResponseProbe.ref)
    validateResponseProbe.expectMessageType[Accepted]

    val setupInvalid = Setup(clientPrefix, invalidCmd, None)
    testSuper ! Validate(setupInvalid, validateResponseProbe.ref)
    validateResponseProbe.expectMessageType[Invalid]
  }

  test("send a long-running command with query and queryFinal") {
    val testMocks = new MyFrameworkMocks()
    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    val longRunningSetup    = Setup(clientPrefix, longRunningCmd, None)
    val submitResponseProbe1 = testKit.createTestProbe[SubmitResponse]

    // Send a long running cmd and use query to test before completed
    testSuper ! Submit(longRunningSetup, submitResponseProbe1.ref)

    var response2 = submitResponseProbe1.expectMessageType[SubmitResponse]
    assert(response2.isInstanceOf[Started])

    testSuper ! Query(response2.runId, submitResponseProbe1.ref)
    submitResponseProbe1.expectMessage(Started(response2.runId))

    // Wait for Complete
    response2 = submitResponseProbe1.expectMessageType[SubmitResponse](10.seconds)
    assert(response2.isInstanceOf[Completed])

    // Send the command again and use queryFinal
    testSuper ! Submit(longRunningSetup, submitResponseProbe1.ref)

    val response3 = submitResponseProbe1.expectMessageType[SubmitResponse]
    assert(response3.isInstanceOf[Started])

    testSuper ! QueryFinal(response3.runId, submitResponseProbe1.ref)
    submitResponseProbe1.expectMessage(10.seconds, Completed(response3.runId))

    // Try query after to see if it's still there?
    testSuper ! Query(response3.runId, submitResponseProbe1.ref)
    submitResponseProbe1.expectMessage(Completed(response3.runId))
  }

  test("send a oneway and get response") {
    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    val onewayResponseProbe = testKit.createTestProbe[OnewayResponse]()

    val setup    = Setup(clientPrefix, immediateCmd, None)

    testSuper ! Oneway(setup, onewayResponseProbe.ref)
    val onewayResponse = onewayResponseProbe.expectMessageType[OnewayResponse]
    assert(onewayResponse.isInstanceOf[Accepted])
  }

  test("send a command to a locked component and test commands") {
    val longDuration = 5.seconds
    val lockingResponseProbe = testKit.createTestProbe[LockingResponse]

    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    // Will run after component is in running
    testSuper ! Lock(clientPrefix, lockingResponseProbe.ref, longDuration)
    lockingResponseProbe.expectMessage(LockAcquired)

    // Now test that we can send commands with correct prefix
    val setup = Setup(clientPrefix, immediateCmd, None)

    val onewayResponseProbe = testKit.createTestProbe[OnewayResponse]()
    testSuper ! Oneway(setup, onewayResponseProbe.ref)
    var onewayResponse = onewayResponseProbe.expectMessageType[OnewayResponse]
    assert(onewayResponse.isInstanceOf[Accepted])

    // Now try a bad prefix
    val badSetup = Setup(invalidPrefix, immediateCmd, None)
    testSuper ! Oneway(badSetup, onewayResponseProbe.ref)

    var badOnewayResponse = onewayResponseProbe.expectMessageType[OnewayResponse]
    assert(badOnewayResponse.isInstanceOf[Locked])

    // Now unlock and send both again
    testSuper ! Unlock(clientPrefix, lockingResponseProbe.ref)
    lockingResponseProbe.expectMessage(LockReleased)

    testSuper ! Oneway(setup, onewayResponseProbe.ref)
    onewayResponse = onewayResponseProbe.expectMessageType[OnewayResponse]
    assert(onewayResponse.isInstanceOf[Accepted])

    testSuper ! Oneway(badSetup, onewayResponseProbe.ref)
    badOnewayResponse = onewayResponseProbe.expectMessageType[OnewayResponse]
    assert(badOnewayResponse.isInstanceOf[Accepted])
  }

  test("create one and then restart") {
    import SupervisorLifecycleState._

    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    val stateChangeProbe = TestProbe[LifecycleStateChanged]

    testSuper ! LifecycleStateSubscription2(stateChangeProbe.ref)

    val states = waitForState(stateChangeProbe, Running)
    assert(states.size == 4)
    assert(states.contains(Running))

    testSuper ! SupervisorContainerCommonMessages.Restart
    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Unregistering))
    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Restart))
  }

  test("should create and go online offline") {
    import SupervisorLifecycleState._

    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    val stateChangeProbe = TestProbe[LifecycleStateChanged]

    testSuper ! LifecycleStateSubscription2(stateChangeProbe.ref)

    val states = waitForState(stateChangeProbe, Running)
    assert(states.size == 4)
    assert(states.contains(Running))

    testSuper ! Lifecycle(GoOffline)
    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.RunningOffline))

    testSuper ! Lifecycle(GoOnline)
    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Running))
  }

  test("should create and go to diagnosticsMode and OperationsMode") {

    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    val stateChangeProbe = TestProbe[LifecycleStateChanged]


    testSuper ! DiagnosticDataMessage.DiagnosticMode(UTCTime.now(), "hint")


    testSuper ! DiagnosticDataMessage.OperationsMode

  }

  test("create one and then shutdown") {
    import SupervisorLifecycleState._

    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    val stateChangeProbe = TestProbe[LifecycleStateChanged]

    testSuper ! LifecycleStateSubscription2(stateChangeProbe.ref)

    var states = waitForState(stateChangeProbe, Running, 10.seconds)
    assert(states.size == 4)  // 4

    testSuper ! SupervisorContainerCommonMessages.Shutdown

    states = waitForState(stateChangeProbe, Shutdown, 10.seconds)
    assert(states.size == 2) // Unregistering, Shutdown
  }

  test("send a validate and get response only for handlers") {
    val testMocks = new MyFrameworkMocks()
    val cswContext = testMocks.createContext(assemblyInfo2)
    val testSuper =
      spawn(SupervisorBehavior2(testMocks.frameworkTestMocks().registrationFactory, cswContext))

    val validateResponseProbe = testKit.createTestProbe[ValidateResponse]

    val setupAccepted = Setup(clientPrefix, immediateCmd, None)
    testSuper ! Validate(setupAccepted, validateResponseProbe.ref)
    validateResponseProbe.expectMessageType[Accepted]

    val setupInvalid = Setup(clientPrefix, invalidCmd, None)
    testSuper ! Validate(setupInvalid, validateResponseProbe.ref)
    validateResponseProbe.expectMessageType[Invalid]
  }

  test("send a oneway and get response to an abstractbehavior") {
    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo2)
    val testSuper =
      spawn(SupervisorBehavior2(TestComponent3(cswContext).narrow, testMocks.frameworkTestMocks().registrationFactory, cswContext))

    val onewayResponseProbe = testKit.createTestProbe[OnewayResponse]()

    val setup    = Setup(clientPrefix, immediateCmd, None)

    testSuper ! Oneway(setup, onewayResponseProbe.ref)
    val onewayResponse = onewayResponseProbe.expectMessageType[OnewayResponse]
    assert(onewayResponse.isInstanceOf[Accepted])
  }
}
