package csw.framework.internal.supervisor

import akka.actor.testkit.typed.Effect.Spawned
import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, TestInbox, TestProbe}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Scheduler, Terminated}
import csw.command.client.messages.ComponentCommonMessage.{GetSupervisorLifecycleState, LifecycleStateSubscription}
import csw.command.client.messages.FromComponentLifecycleMessage.Running
import csw.command.client.messages.RunningMessage.Lifecycle
import csw.command.client.messages.SupervisorContainerCommonMessages.Restart
import csw.command.client.messages.SupervisorIdleMessage.InitializeTimeout
import csw.command.client.messages.SupervisorInternalRunningMessage.{RegistrationNotRequired, RegistrationSuccess}
import csw.command.client.messages.{ContainerIdleMessage, SupervisorMessage, TopLevelActorMessage}
import csw.command.client.models.framework.LocationServiceUsage.{DoNotRegister, RegisterOnly}
import csw.command.client.models.framework.PubSub.{Publish, Subscribe, Unsubscribe}
import csw.command.client.models.framework.ToComponentLifecycleMessage._
import csw.command.client.models.framework.{ComponentInfo, LifecycleStateChanged, PubSub, SupervisorLifecycleState}
import csw.common.components.command
import csw.common.components.framework.SampleComponentBehaviorFactory
import csw.common.extensions.CswContextExtensions.RichCswContext
import csw.framework.ComponentInfos._
import csw.framework.exceptions.InitializationFailed
import csw.framework.scaladsl.ComponentHandlers
import csw.framework.{FrameworkTestMocks, FrameworkTestSuite}
import csw.location.api.models.ComponentType.Assembly
import csw.location.api.models.ConnectionType.AkkaType
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem.ESW
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, FiniteDuration}

// DEOPSCSW-163: Provide admin facilities in the framework through Supervisor role
// DEOPSCSW-177: Hooks for lifecycle management
// DEOPSCSW-181: Multiple Examples for Lifecycle Support
class SupervisorBehavior2LifecycleTest extends FrameworkTestSuite with BeforeAndAfterEach {

  private val clientPrefix:Prefix = Prefix(ESW, "engUI")
  private val invalidPrefix = Prefix("WFOS.invalid.engUI")

  val assemblyInfo: ComponentInfo = ComponentInfo(
    Prefix("WFOS.SampleAssembly"),
    Assembly,
    "csw.common.components.framework.SampleComponentBehaviorFactory",
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

  class TestData(compInfo: ComponentInfo) {
    val testMocks: FrameworkTestMocks = frameworkTestMocks()
    import testMocks._
    private implicit val scheduler: Scheduler = typedSystem.scheduler
    private implicit val ec: ExecutionContext = typedSystem.executionContext

    val sampleHcdHandler: ComponentHandlers                        = mock[ComponentHandlers]
    val timerScheduler: TimerScheduler[SupervisorMessage]          = mock[TimerScheduler[SupervisorMessage]]
    val containerIdleMessageProbe: TestProbe[ContainerIdleMessage] = TestProbe[ContainerIdleMessage]()
    val componentActorName                                         = s"${compInfo.prefix}-${SupervisorBehavior.ComponentActorNameSuffix}"
    val supervisorBehaviorKit = BehaviorTestKit(
      Behaviors
        .setup[SupervisorMessage](ctx =>
           SupervisorBehavior2(
           // ctx,
            //timerScheduler,
            //None,
            //new SampleComponentBehaviorFactory,
             command.TestComponent(cswCtx),
            testMocks.registrationFactory,
            cswCtx.copy(compInfo)
          )
        )
    )

    verify(timerScheduler).startSingleTimer(SupervisorBehavior.InitializeTimerKey, InitializeTimeout, hcdInfo.initializeTimeout)
    when(timerScheduler.isTimerActive(SupervisorBehavior.InitializeTimerKey)).thenReturn(true)
  }

  test("supervisor should start in Idle lifecycle state and spawn two actors | DEOPSCSW-163, DEOPSCSW-177, DEOPSCSW-181") {
    val testData = new TestData(hcdInfo)
    import testData._

    val supervisorLifecycleStateProbe = TestProbe[SupervisorLifecycleState]()

    supervisorBehaviorKit.run(GetSupervisorLifecycleState(supervisorLifecycleStateProbe.ref))
    supervisorLifecycleStateProbe.expectMessage(SupervisorLifecycleState.Idle)

    val spawnedEffects = supervisorBehaviorKit.retrieveAllEffects().map {
      case s: Spawned[_] => s.childName
      case _             => ""
    }

    (spawnedEffects should contain).allOf(SupervisorBehavior.PubSubLifecycleActor, componentActorName)

    verify(timerScheduler).startSingleTimer(SupervisorBehavior.InitializeTimerKey, InitializeTimeout, hcdInfo.initializeTimeout)
  }
/*
  // *************** Begin testing of onIdleMessages ***************
  test(
    "supervisor should accept Running message and register with Location service when LocationServiceUsage is RegisterOnly | DEOPSCSW-163, DEOPSCSW-177, DEOPSCSW-181"
  ) {
    val testData = new TestData(hcdInfo)
    import testData._
    import testData.testMocks._
    val supervisorLifecycleStateProbe            = TestProbe[SupervisorLifecycleState]()
    val childRef: ActorRef[TopLevelActorMessage] = supervisorBehaviorKit.childInbox(componentActorName).ref

    supervisorBehaviorKit.run(Running(childRef))

    verify(timerScheduler).isTimerActive(SupervisorBehavior.InitializeTimerKey)
    verify(timerScheduler).cancel(SupervisorBehavior.InitializeTimerKey)
    verify(locationService).register(akkaRegistration)

    supervisorBehaviorKit.run(GetSupervisorLifecycleState(supervisorLifecycleStateProbe.ref))
    supervisorLifecycleStateProbe.expectMessage(SupervisorLifecycleState.Running)
  }

  test(
    "supervisor should accept Running message and should not register when LocationServiceUsage is DoNotRegister | DEOPSCSW-163, DEOPSCSW-177, DEOPSCSW-181"
  ) {
    val testData = new TestData(hcdInfo.copy(locationServiceUsage = DoNotRegister))
    import testData._
    import testData.testMocks._
    val supervisorLifecycleStateProbe            = TestProbe[SupervisorLifecycleState]()
    val childRef: ActorRef[TopLevelActorMessage] = supervisorBehaviorKit.childInbox(componentActorName).ref

    supervisorBehaviorKit.run(Running(childRef))

    verify(locationService, never).register(akkaRegistration)
    verify(timerScheduler).cancel(SupervisorBehavior.InitializeTimerKey)
    supervisorBehaviorKit.run(RegistrationNotRequired(childRef))

    supervisorBehaviorKit.run(GetSupervisorLifecycleState(supervisorLifecycleStateProbe.ref))
    supervisorLifecycleStateProbe.expectMessage(SupervisorLifecycleState.Running)
  }
  // *************** End of testing onIdleMessages ***************

  // *************** Begin testing of onInternalMessage ***************
  test(
    "supervisor should publish state change after successful registration with location service | DEOPSCSW-163, DEOPSCSW-177, DEOPSCSW-181"
  ) {
    val testData = new TestData(hcdInfo)
    import testData._

    val supervisorLifecycleStateProbe            = TestProbe[SupervisorLifecycleState]()
    val childRef: ActorRef[TopLevelActorMessage] = supervisorBehaviorKit.childInbox(componentActorName).ref

    supervisorBehaviorKit.run(Running(childRef))
    supervisorBehaviorKit.run(RegistrationSuccess(childRef))

    supervisorBehaviorKit.run(GetSupervisorLifecycleState(supervisorLifecycleStateProbe.ref))
    supervisorLifecycleStateProbe.expectMessage(SupervisorLifecycleState.Running)

    val childPubSubLifecycleInbox: TestInbox[PubSub[LifecycleStateChanged]] =
      supervisorBehaviorKit.childInbox(SupervisorBehavior.PubSubLifecycleActor)

    childPubSubLifecycleInbox.receiveMessage() shouldBe Publish(
      LifecycleStateChanged(supervisorBehaviorKit.selfInbox().ref, SupervisorLifecycleState.Running)
    )
  }

  test(
    "supervisor should publish state change if locationServiceUsage is DoNotRegister | DEOPSCSW-163, DEOPSCSW-177, DEOPSCSW-181"
  ) {
    val testData = new TestData(hcdInfo)
    import testData._
    val supervisorLifecycleStateProbe            = TestProbe[SupervisorLifecycleState]()
    val childRef: ActorRef[TopLevelActorMessage] = supervisorBehaviorKit.childInbox(componentActorName).ref

    supervisorBehaviorKit.run(Running(childRef))
    supervisorBehaviorKit.run(RegistrationNotRequired(childRef))

    supervisorBehaviorKit.run(GetSupervisorLifecycleState(supervisorLifecycleStateProbe.ref))
    supervisorLifecycleStateProbe.expectMessage(SupervisorLifecycleState.Running)

    val childPubSubLifecycleInbox: TestInbox[PubSub[LifecycleStateChanged]] =
      supervisorBehaviorKit.childInbox(SupervisorBehavior.PubSubLifecycleActor)

    childPubSubLifecycleInbox.receiveMessage() shouldBe Publish(
      LifecycleStateChanged(supervisorBehaviorKit.selfInbox().ref, SupervisorLifecycleState.Running)
    )
  }
  // *************** End of testing onInternalMessage ***************

  // *************** Begin testing of onCommonMessages ***************
  test(
    "supervisor should handle LifecycleStateSubscription message by coordinating with pub sub actor | DEOPSCSW-163, DEOPSCSW-177, DEOPSCSW-181"
  ) {
    val testData = new TestData(hcdInfo)
    import testData._
    val supervisorLifecycleStateProbe = TestProbe[SupervisorLifecycleState]()
    val subscriberProbe               = TestProbe[LifecycleStateChanged]()

    supervisorBehaviorKit.run(GetSupervisorLifecycleState(supervisorLifecycleStateProbe.ref))
    val previousSupervisorLifecycleState = supervisorLifecycleStateProbe.expectMessageType[SupervisorLifecycleState]

    // Subscribe
    supervisorBehaviorKit.run(LifecycleStateSubscription(Subscribe(subscriberProbe.ref)))

    supervisorBehaviorKit.run(GetSupervisorLifecycleState(supervisorLifecycleStateProbe.ref))
    supervisorLifecycleStateProbe.expectMessage(previousSupervisorLifecycleState)

    val childPubSubLifecycleInbox: TestInbox[PubSub[LifecycleStateChanged]] =
      supervisorBehaviorKit.childInbox(SupervisorBehavior.PubSubLifecycleActor)

    val subscribeMessage = childPubSubLifecycleInbox.receiveMessage()
    subscribeMessage shouldBe Subscribe[LifecycleStateChanged](subscriberProbe.ref)

    // Unsubscribe
    supervisorBehaviorKit.run(LifecycleStateSubscription(Unsubscribe[LifecycleStateChanged](subscriberProbe.ref)))

    supervisorBehaviorKit.run(GetSupervisorLifecycleState(supervisorLifecycleStateProbe.ref))
    supervisorLifecycleStateProbe.expectMessage(previousSupervisorLifecycleState)

    val unsubscribeMessage = childPubSubLifecycleInbox.receiveMessage()
    unsubscribeMessage shouldBe Unsubscribe[LifecycleStateChanged](subscriberProbe.ref)
  }

  // *************** End of testing onCommonMessages ***************

  /**
   * Below Tests show that all external messages for the TLA are received by the Supervisor
   * which passes them to TLA (depending on lifecycle)
   */
  // *************** Begin testing of onRunning Messages ***************

  test("supervisor should handle lifecycle Restart message | DEOPSCSW-163, DEOPSCSW-177, DEOPSCSW-181") {
    val testData = new TestData(hcdInfo)
    import testData._
    val supervisorLifecycleStateProbe = TestProbe[SupervisorLifecycleState]()

    supervisorBehaviorKit.run(Restart)
    supervisorBehaviorKit.run(GetSupervisorLifecycleState(supervisorLifecycleStateProbe.ref))
    supervisorLifecycleStateProbe.expectMessage(SupervisorLifecycleState.Restart)
  }

  test("supervisor should handle lifecycle GoOffline message | DEOPSCSW-163, DEOPSCSW-177, DEOPSCSW-181") {
    val testData = new TestData(hcdInfo)
    import testData._

    val supervisorLifecycleStateProbe = TestProbe[SupervisorLifecycleState]()

    val childComponentInbox: TestInbox[TopLevelActorMessage] = supervisorBehaviorKit.childInbox(componentActorName)

    supervisorBehaviorKit.run(Running(childComponentInbox.ref))
    supervisorBehaviorKit.run(RegistrationSuccess(childComponentInbox.ref))
    supervisorBehaviorKit.run(Lifecycle(GoOffline))

    supervisorBehaviorKit.run(GetSupervisorLifecycleState(supervisorLifecycleStateProbe.ref))
    supervisorLifecycleStateProbe.expectMessage(SupervisorLifecycleState.RunningOffline)

    childComponentInbox.receiveAll() should contain(Lifecycle(GoOffline))
  }

  test("supervisor should handle lifecycle GoOnline message | DEOPSCSW-163, DEOPSCSW-177, DEOPSCSW-181") {
    val testData = new TestData(hcdInfo)
    import testData._
    val supervisorLifecycleStateProbe                        = TestProbe[SupervisorLifecycleState]()
    val childComponentInbox: TestInbox[TopLevelActorMessage] = supervisorBehaviorKit.childInbox(componentActorName)

    supervisorBehaviorKit.run(Running(childComponentInbox.ref))
    supervisorBehaviorKit.run(RegistrationSuccess(childComponentInbox.ref))
    supervisorBehaviorKit.run(Lifecycle(GoOffline))
    supervisorBehaviorKit.run(Lifecycle(GoOnline))

    supervisorBehaviorKit.run(GetSupervisorLifecycleState(supervisorLifecycleStateProbe.ref))
    supervisorLifecycleStateProbe.expectMessage(SupervisorLifecycleState.Running)
    childComponentInbox.receiveAll() should contain(Lifecycle(GoOnline))
  }

  // *************** End of testing onRunning Messages ***************

  test("supervisor should handle Terminated signal for Idle lifecycle state | DEOPSCSW-163, DEOPSCSW-177, DEOPSCSW-181") {
    val testData = new TestData(hcdInfo)
    import testData._
    val supervisorLifecycleStateProbe = TestProbe[SupervisorLifecycleState]()

    val childComponentInbox: TestInbox[TopLevelActorMessage] = supervisorBehaviorKit.childInbox(componentActorName)

    supervisorBehaviorKit.run(GetSupervisorLifecycleState(supervisorLifecycleStateProbe.ref))
    supervisorLifecycleStateProbe.expectMessage(SupervisorLifecycleState.Idle)

    intercept[InitializationFailed.type] {
      supervisorBehaviorKit.signal(Terminated(childComponentInbox.ref))
    }
  }

 */
  /*
  test("supervisor should handle Command Response Manager messages by forwarding it to Command Response Manager | DEOPSCSW-163, DEOPSCSW-177, DEOPSCSW-181") {
    val testData = new TestData(hcdInfo)
    import testData._

    val childRef: ActorRef[TopLevelActorMessage] = supervisorBehaviorKit.childInbox(componentActorName).ref
    val queryResponseProbe                       = TestProbe[QueryResponse]()
    val testCmdId                                = Id()

    supervisorBehaviorKit.run(Running(childRef))

    supervisorBehaviorKit.run(Query(testCmdId, queryResponseProbe.ref))
    testMocks.commandResponseManagerActor.expectMessage(Query(testCmdId, queryResponseProbe.ref))

    supervisorBehaviorKit.run(CommandResponseManagerMessage.Subscribe(testCmdId, queryResponseProbe.ref))
    testMocks.commandResponseManagerActor.expectMessage(
      CommandResponseManagerMessage.Subscribe(testCmdId, queryResponseProbe.ref)
    )

    supervisorBehaviorKit.run(CommandResponseManagerMessage.Unsubscribe(testCmdId, queryResponseProbe.ref))
    testMocks.commandResponseManagerActor.expectMessage(
      CommandResponseManagerMessage.Unsubscribe(testCmdId, queryResponseProbe.ref)
    )
  }
   */
}
