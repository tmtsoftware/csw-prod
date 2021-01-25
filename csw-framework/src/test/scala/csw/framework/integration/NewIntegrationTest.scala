package csw.framework.integration

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SpawnProtocol}
import csw.command.client.messages.ComponentCommonMessage.LifecycleStateSubscription2
import csw.command.client.messages.SupervisorMessage
import csw.command.client.models.framework.LocationServiceUsage.RegisterOnly
import csw.command.client.models.framework.{ComponentInfo, LifecycleStateChanged, SupervisorLifecycleState}
import csw.common.components.command
import csw.framework.internal.supervisor.SupervisorBehavior2
import csw.framework.internal.supervisor.SupervisorBehavior2.Supervisor2Message
import csw.framework.internal.wiring.{CswFrameworkSystem, FrameworkWiring}
import csw.framework.models.CswContext
import csw.framework.scaladsl.RegistrationFactory
import csw.location.api.models.ComponentType.Assembly
import csw.location.api.models.ConnectionType.{AkkaType, HttpType}
import csw.logging.client.scaladsl.LoggingSystemFactory
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem.ESW
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.async.Async.{async, await}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt

object TestApp {
  implicit val typedSystem: ActorSystem[SpawnProtocol.Command] = ActorSystem(SpawnProtocol(), "Test")
}

class NewIntegrationTest extends ScalaTestWithActorTestKit(TestApp.typedSystem) with AnyFunSuiteLike with BeforeAndAfterEach {

  private val clientPrefix: Prefix = Prefix(ESW, "engUI")
  private val invalidPrefix        = Prefix("wfos.invalid.engUI")

  val assemblyInfo: ComponentInfo = ComponentInfo(
    Prefix("WFOS.SampleAssembly"),
    Assembly,
    "csw.common.components.framework.SampleComponentBehaviorFactory",
    RegisterOnly,
    Set(AkkaType, HttpType)
  )

  import TestApp._
  import typedSystem.executionContext
/*
  def create(
      componentInfo: ComponentInfo
  )(implicit actorSystem: ActorSystem[SpawnProtocol.Command]): Future[(CswContext, RegistrationFactory)] = {
    async {
      val wiring = FrameworkWiring.make(actorSystem)
      import wiring._
      val richSystem = new CswFrameworkSystem(actorRuntime.actorSystem)
      //val componentInfo = ConfigParser.parseStandalone(componentConf)
      //LoggingSystemFactory.start("logging", "1", "localhost", typedSystem)
      (
        await(CswContext.make(locationService, eventServiceFactory, alarmServiceFactory, componentInfo)(richSystem)),
        registrationFactory
      )
    }
  }
*/
  def create(
              componentInfo: ComponentInfo
            )(implicit actorSystem: ActorSystem[SpawnProtocol.Command]): Future[ActorRef[SupervisorMessage]] = {
    async {
      val wiring = FrameworkWiring.make(actorSystem)
      import wiring._
      val richSystem = new CswFrameworkSystem(actorRuntime.actorSystem)
      //val componentInfo = ConfigParser.parseStandalone(componentConf)
      //LoggingSystemFactory.start("logging", "1", "localhost", typedSystem)
      val cswContext = await(CswContext.make(locationService, eventServiceFactory, alarmServiceFactory, componentInfo)(richSystem))
      await(richSystem.spawnTyped(SupervisorBehavior2(command.TestComponent(cswContext), registrationFactory, cswContext), "svr"))
    }
  }

  override def afterAll(): Unit = {
    testKit.internalSystem.terminate()
    super.afterAll()
  }

  test("should create one") {

    // val testSuper = testActorSystem.spawn(SupervisorBehavior2(command.TestComponent(cswContext), registrationFactory, cswContext))
    //    val result = Await.result(create(assemblyInfo), 5.seconds)

    //  val cswContext          = result._1
    //    val registrationFactory = result._2
    println("Creating super now")

    val testSuper = Await.result(create(assemblyInfo), 10.seconds)

  //val testSuper = spawn(SupervisorBehavior2(command.TestComponent(cswContext), registrationFactory, cswContext))

    println(s"testSuper: $testSuper")

    val testProbe        = TestProbe[SupervisorMessage]
    val testState        = TestProbe[SupervisorLifecycleState]
    val stateChangeProbe = TestProbe[LifecycleStateChanged]

    testSuper ! LifecycleStateSubscription2(stateChangeProbe.ref)

    // println("Sending state request")
    //  testSuper ! GetSupervisorLifecycleState(testState.ref)

    stateChangeProbe.expectMessage(8.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Idle))
    println("Got the damn Idle")
    stateChangeProbe.expectMessage(
      4.seconds,
      LifecycleStateChanged(testSuper, SupervisorLifecycleState.Registering(assemblyInfo.prefix))
    )

    println("Waiting 15 seconds")
    stateChangeProbe.expectMessage(15.seconds, LifecycleStateChanged(testSuper, SupervisorLifecycleState.Running))
    println("Got the damn Running")

    println("Waiting")
    Thread.sleep(15000)
    println("DONE")
  }
}

/*
    val probe = testKit.createTestProbe[HelloWorld.Greeted]()
    val actor = testKit.spawn(HelloWorld.apply())

    actor ! HelloWorld.Greet("Kim", probe.ref)

    val m = probe.expectMessageType[HelloWorld.Greeted]
    println(s"Received message: $m")
  }


}
 */
