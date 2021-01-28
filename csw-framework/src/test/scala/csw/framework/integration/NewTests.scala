package csw.framework.integration

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.{ActorSystem, Scheduler, SpawnProtocol}
import csw.command.client.CommandResponseManager
import csw.command.client.messages.CommandMessage.{Oneway, Submit, Validate}
import csw.command.client.messages.ComponentCommonMessage.{GetSupervisorLifecycleState, LifecycleStateSubscription2}
import csw.command.client.messages.RunningMessage.Lifecycle
import csw.command.client.messages.SupervisorLockMessage.{Lock, Unlock}
import csw.command.client.messages.{Query, QueryFinal, SupervisorContainerCommonMessages, SupervisorMessage}
import csw.command.client.models.framework.LocationServiceUsage.RegisterOnly
import csw.command.client.models.framework.LockingResponse.{LockAcquired, LockReleased}
import csw.command.client.models.framework.ToComponentLifecycleMessage.GoOffline
import csw.command.client.models.framework.{ComponentInfo, LifecycleStateChanged, LockingResponse, SupervisorLifecycleState}
import csw.common.components.command
import csw.common.components.command.CommandComponentState.{immediateCmd, longRunningCmd}
import csw.framework.FrameworkTestMocks
import csw.framework.internal.supervisor.SupervisorBehavior2
import csw.framework.internal.supervisor.SupervisorBehavior2.PrintCRM
import csw.framework.models.CswContext
import csw.location.api.models.ComponentType.Assembly
import csw.location.api.models.ConnectionType.{AkkaType, HttpType}
import csw.location.client.ActorSystemFactory
import csw.logging.client.scaladsl.{LoggerFactory, LoggingSystemFactory}
import csw.params.commands.CommandResponse._
import csw.params.commands.{CommandName, Setup}
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem.ESW
import csw.time.scheduler.TimeServiceSchedulerFactory
import org.mockito.MockitoSugar.mock
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class MyFrameworkMocks {
  implicit val typedSystem: ActorSystem[SpawnProtocol.Command] = ActorSystemFactory.remote(SpawnProtocol(), "testSystem")
  def frameworkTestMocks(): FrameworkTestMocks                 = new FrameworkTestMocks()

  //LoggingSystemFactory.start("logging", "1", "localhost", typedSystem)

  //private val manualTime                    = ManualTime()(system)
  private val jitter                        = 10
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
  private val invalidPrefix = Prefix("wfos.invalid.engUI")

  val assemblyInfo: ComponentInfo = ComponentInfo(
    Prefix("WFOS.SampleAssembly"),
    Assembly,
    "csw.common.components.framework.SampleComponentBehaviorFactory",
    RegisterOnly,
    Set(AkkaType)
  )

  test("should create one") {
    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    val testProbe = TestProbe[SupervisorMessage]
    val testState = TestProbe[SupervisorLifecycleState]
    val stateChangeProbe = TestProbe[LifecycleStateChanged]

    testSuper ! LifecycleStateSubscription2(stateChangeProbe.ref)

   // println("Sending state request")
  //  testSuper ! GetSupervisorLifecycleState(testState.ref)

    stateChangeProbe.expectMessage(8.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Idle))
    println("Got the damn Idle")
    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Registering))

    println("Waiting 15 seconds")
    stateChangeProbe.expectMessage(15.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Running))
    println("Got the damn Running")

    println("DONE")
  }

  test("failure with restart") {
    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestCompInitFailureRestart(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

//    val testProbe = TestProbe[SupervisorMessage]
//    val testState = TestProbe[SupervisorLifecycleState]
    val stateChangeProbe = TestProbe[LifecycleStateChanged]

    testSuper ! LifecycleStateSubscription2(stateChangeProbe.ref)

    // println("Sending state request")
    //  testSuper ! GetSupervisorLifecycleState(testState.ref)

    // stateChangeProbe.expectMessage(8.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Idle))
    //println("Got the damn Idle")
    //stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Registering(cswContext.componentInfo.prefix)))

    println("Test waiting 15 seconds")
    Thread.sleep(15000)
    //stateChangeProbe.expectMessage(15.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Running))
    //println("Got the damn Running")

    println("DONE")
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
    stateChangeProbe.expectMessage(LifecycleStateChanged(testSuper, SupervisorLifecycleState.Idle))

    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Registering))

    stateChangeProbe.expectMessage(LifecycleStateChanged(testSuper, SupervisorLifecycleState.Running))
    testSuper ! GetSupervisorLifecycleState(lifecycleStateProbe.ref)
    lifecycleStateProbe.expectMessage(SupervisorLifecycleState.Running)

    //Thread.sleep(1000)
    println("Sending lock request")
    testSuper ! Lock(clientPrefix, lockingResponseProbe.ref, longDuration)
    lockingResponseProbe.expectMessage(LockAcquired)
    println("Got LockAcquired")

    stateChangeProbe.expectMessage(LifecycleStateChanged(testSuper, SupervisorLifecycleState.Lock))
    testSuper ! GetSupervisorLifecycleState(lifecycleStateProbe.ref)
    lifecycleStateProbe.expectMessage(SupervisorLifecycleState.Lock)

    testSuper ! Unlock(clientPrefix, lockingResponseProbe.ref)
    lockingResponseProbe.expectMessage(LockReleased)

    stateChangeProbe.expectMessage(LifecycleStateChanged(testSuper, SupervisorLifecycleState.Running))
    testSuper ! GetSupervisorLifecycleState(lifecycleStateProbe.ref)
    lifecycleStateProbe.expectMessage(SupervisorLifecycleState.Running)

    println("DONE")
  }

  test("send a long term with query") {

    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

//    val setup    = Setup(clientPrefix, CommandName("setup-test"), None)
    val longRunningSetup    = Setup(clientPrefix, longRunningCmd, None)
    val submitResponseProbe1 = testKit.createTestProbe[SubmitResponse]

    println("Now test long running submit")

    testSuper ! Submit(longRunningSetup, submitResponseProbe1.ref)

    var cresponse2 = submitResponseProbe1.expectMessageType[SubmitResponse]
    println(s"Command response2: $cresponse2")

    testSuper ! Query(cresponse2.runId, submitResponseProbe1.ref)
    submitResponseProbe1.expectMessage(Started(cresponse2.runId))
    println("Query Response got Started")

    cresponse2 = submitResponseProbe1.expectMessageType[SubmitResponse](10.seconds)
    println(s"Command response2: $cresponse2")

    println("Try QUery FInal")
    testSuper ! Submit(longRunningSetup, submitResponseProbe1.ref)

    val cresponse3 = submitResponseProbe1.expectMessageType[SubmitResponse]

    testSuper ! QueryFinal(cresponse3.runId, submitResponseProbe1.ref)

    submitResponseProbe1.expectMessage(10.seconds, Completed(cresponse3.runId))

    // Try query after
    testSuper ! Query(cresponse3.runId, submitResponseProbe1.ref)
    submitResponseProbe1.expectMessage(Completed(cresponse3.runId))

    //testSuper ! PrintCRM

    Thread.sleep(1000)

    println("Long running command Done")
  }

  test("send a validate and get response") {

    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    val setup    = Setup(clientPrefix, CommandName("setup-test"), None)

    val validateResponseProbe = testKit.createTestProbe[ValidateResponse]

    //Thread.sleep(3000)
    println("*************Sending cmd validate request")

    testSuper ! Validate(setup, validateResponseProbe.ref)

    val reponse = validateResponseProbe.expectMessageType[Accepted]
    println(s"Validate response: $reponse")

    println("DONE validate")

    val submitResponseProbe = testKit.createTestProbe[SubmitResponse]

    println("Now test submit")
    testSuper ! Submit(setup, submitResponseProbe.ref)

    val cresponse = submitResponseProbe.expectMessageType[SubmitResponse]
    println(s"Command response: $cresponse")

    println("Command Done")

    println("Now test oneway")
    val onewayResponseProbe = testKit.createTestProbe[OnewayResponse]()
    testSuper ! Oneway(setup, onewayResponseProbe.ref)

    val onewayResponse = onewayResponseProbe.expectMessageType[OnewayResponse]
    println(s"Oneway response in test: $onewayResponse")

    println("Oneway Done")

    println("Now test long running submit")
    val longRunningSetup    = Setup(clientPrefix, longRunningCmd, None)

    testSuper ! Submit(longRunningSetup, submitResponseProbe.ref)

    var cresponse2 = submitResponseProbe.expectMessageType[SubmitResponse]
    println(s"Command response2: $cresponse2")
    cresponse2 = submitResponseProbe.expectMessageType[SubmitResponse](10.seconds)
    println(s"Command response2: $cresponse2")

    println("Long running command Done")
  }

  test("send a command to a locked component and test commands") {
    val longDuration = 5.seconds
    val lockingResponseProbe = testKit.createTestProbe[LockingResponse]

    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    println("Sending lock request")
    testSuper ! Lock(clientPrefix, lockingResponseProbe.ref, longDuration)
    lockingResponseProbe.expectMessage(LockAcquired)

    println("Got Lock Acquired")

    val setup = Setup(clientPrefix, CommandName("setup-test"), None)

    println("Now test oneway")
    val onewayResponseProbe = testKit.createTestProbe[OnewayResponse]()
    testSuper ! Oneway(setup, onewayResponseProbe.ref)

    var onewayResponse = onewayResponseProbe.expectMessageType[OnewayResponse]
    println(s"Oneway response in test: $onewayResponse")

    println("Now try with bad prefix")
    val badSetup = Setup(invalidPrefix, immediateCmd, None)

    testSuper ! Oneway(badSetup, onewayResponseProbe.ref)

    var badOnewayResponse = onewayResponseProbe.expectMessageType[OnewayResponse]
    println(s"Bad Oneway response in test: $badOnewayResponse")

    // Now unlock and send both again
    testSuper ! Unlock(clientPrefix, lockingResponseProbe.ref)
    lockingResponseProbe.expectMessage(LockReleased)

    testSuper ! Oneway(setup, onewayResponseProbe.ref)

    onewayResponse = onewayResponseProbe.expectMessageType[OnewayResponse]
    println(s"Oneway response in test: $onewayResponse")

    testSuper ! Oneway(badSetup, onewayResponseProbe.ref)

    badOnewayResponse = onewayResponseProbe.expectMessageType[OnewayResponse]
    println(s"Bad Oneway response in test: $badOnewayResponse")

    println("Bad Oneway Done")
  }

  test("create one and then shutdown") {
    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    val testProbe = TestProbe[SupervisorMessage]
    val testState = TestProbe[SupervisorLifecycleState]
    val stateChangeProbe = TestProbe[LifecycleStateChanged]

    testSuper ! LifecycleStateSubscription2(stateChangeProbe.ref)

    // println("Sending state request")
    //  testSuper ! GetSupervisorLifecycleState(testState.ref)

    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Idle))
    println("Got the damn Idle")
    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Registering))
    println("Waiting 15 seconds")
    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Running))
    println("Got the damn Running")
    Thread.sleep(2000)
    println("Sending Shutdown")
    testSuper ! SupervisorContainerCommonMessages.Shutdown
    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Unregistering))
    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Shutdown))

    Thread.sleep(4000)
    println("DONE")
  }

  test("create one and then restart") {
    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    val testProbe = TestProbe[SupervisorMessage]
    val testState = TestProbe[SupervisorLifecycleState]
    val stateChangeProbe = TestProbe[LifecycleStateChanged]

    testSuper ! LifecycleStateSubscription2(stateChangeProbe.ref)

    // println("Sending state request")
    //  testSuper ! GetSupervisorLifecycleState(testState.ref)

    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Idle))
    println("Got the damn Idle")
    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Registering))
    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Running))
    println("Got the damn Running")
    Thread.sleep(2000)
    println("Sending Restart")
    testSuper ! SupervisorContainerCommonMessages.Restart
    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Unregistering))
    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Restart))


    Thread.sleep(8000)
    println("DONE")
  }

  test("should create and go online offline") {
    val testMocks = new MyFrameworkMocks()

    val cswContext = testMocks.createContext(assemblyInfo)
    val testSuper =
      spawn(SupervisorBehavior2(command.TestComponent(cswContext), testMocks.frameworkTestMocks().registrationFactory, cswContext))

    val testProbe = TestProbe[SupervisorMessage]
    val testState = TestProbe[SupervisorLifecycleState]
    val stateChangeProbe = TestProbe[LifecycleStateChanged]

    testSuper ! LifecycleStateSubscription2(stateChangeProbe.ref)

    // println("Sending state request")
    //  testSuper ! GetSupervisorLifecycleState(testState.ref)

    stateChangeProbe.expectMessage(8.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Idle))
    stateChangeProbe.expectMessage(4.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Registering))
    stateChangeProbe.expectMessage(15.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Running))
    println("Got the damn Running")

    testSuper ! Lifecycle(GoOffline)

    Thread.sleep(3000)

    println("DONE")
  }
}
