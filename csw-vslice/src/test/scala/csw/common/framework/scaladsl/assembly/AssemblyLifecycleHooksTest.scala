package csw.common.framework.scaladsl.assembly

import akka.typed.ActorSystem
import akka.typed.scaladsl.{Actor, ActorContext}
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl.TestProbe
import akka.util.Timeout
import csw.common.components.assembly.AssemblyDomainMessages
import csw.common.framework.models.AssemblyResponseMode.{Initialized, Running}
import csw.common.framework.models.Component.{AssemblyInfo, DoNotRegister}
import csw.common.framework.models.FromComponentLifecycleMessage.ShutdownComplete
import csw.common.framework.models.InitialAssemblyMsg.Run
import csw.common.framework.models.RunningAssemblyMsg.Lifecycle
import csw.common.framework.models.{AssemblyMsg, AssemblyResponseMode, ToComponentLifecycleMessage}
import csw.services.location.models.ConnectionType.AkkaType
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FunSuite, Matchers}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class AssemblyLifecycleHooksTest
    extends FunSuite
    with Matchers
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with MockitoSugar {

  implicit val system   = ActorSystem("testAssembly", Actor.empty)
  implicit val settings = TestKitSettings(system)
  implicit val timeout  = Timeout(5.seconds)

  class SampleAssemblyHandlersFactory(sampleAssemblyHandler: AssemblyHandlers[AssemblyDomainMessages])
      extends AssemblyHandlersFactory[AssemblyDomainMessages] {
    override def make(ctx: ActorContext[AssemblyMsg],
                      assemblyInfo: AssemblyInfo): AssemblyHandlers[AssemblyDomainMessages] = sampleAssemblyHandler
  }

  def run(assemblyHandlersFactory: AssemblyHandlersFactory[AssemblyDomainMessages],
          supervisorProbe: TestProbe[AssemblyResponseMode]): Running = {
    val assemblyInfo = AssemblyInfo("trombone",
                                    "wfos",
                                    "csw.common.components.assembly.SampleAssembly",
                                    DoNotRegister,
                                    Set(AkkaType),
                                    Set.empty)

    Await.result(
      system.systemActorOf[Nothing](assemblyHandlersFactory.behaviour(assemblyInfo, supervisorProbe.ref), "Assembly"),
      5.seconds
    )

    val initialized = supervisorProbe.expectMsgType[Initialized]
    initialized.assemblyRef ! Run
    supervisorProbe.expectMsgType[Running]
  }

  override protected def afterAll(): Unit = {
    system.terminate()
  }

  test("A running Assembly component should accept Shutdown lifecycle message") {
    val sampleAssemblyHandler = mock[AssemblyHandlers[AssemblyDomainMessages]]
    when(sampleAssemblyHandler.initialize()).thenReturn(Future.unit)

    val supervisorProbe = TestProbe[AssemblyResponseMode]
    val running         = run(new SampleAssemblyHandlersFactory(sampleAssemblyHandler), supervisorProbe)

    doNothing().when(sampleAssemblyHandler).onShutdown()

    running.assemblyRef ! Lifecycle(ToComponentLifecycleMessage.Shutdown)
    supervisorProbe.expectMsg(ShutdownComplete)
    verify(sampleAssemblyHandler).onShutdown()
  }

  test("A running Assembly component should accept Restart lifecycle message") {
    val sampleAssemblyHandler = mock[AssemblyHandlers[AssemblyDomainMessages]]
    when(sampleAssemblyHandler.initialize()).thenReturn(Future.unit)

    val supervisorProbe = TestProbe[AssemblyResponseMode]
    val running         = run(new SampleAssemblyHandlersFactory(sampleAssemblyHandler), supervisorProbe)

    running.assemblyRef ! Lifecycle(ToComponentLifecycleMessage.Restart)
    Thread.sleep(1000)

    verify(sampleAssemblyHandler).onRestart()
  }

  test("A running Assembly component should accept RunOffline lifecycle message") {
    val sampleAssemblyHandler = mock[AssemblyHandlers[AssemblyDomainMessages]]
    when(sampleAssemblyHandler.initialize()).thenReturn(Future.unit)
    when(sampleAssemblyHandler.isOnline).thenReturn(true)

    val supervisorProbe = TestProbe[AssemblyResponseMode]
    val running         = run(new SampleAssemblyHandlersFactory(sampleAssemblyHandler), supervisorProbe)

    running.assemblyRef ! Lifecycle(ToComponentLifecycleMessage.GoOffline)
    Thread.sleep(1000)

    verify(sampleAssemblyHandler).onGoOffline()
  }

  test("A running Assembly component should not accept RunOffline lifecycle message when it is already offline") {
    val sampleAssemblyHandler = mock[AssemblyHandlers[AssemblyDomainMessages]]
    when(sampleAssemblyHandler.isOnline).thenReturn(false)
    when(sampleAssemblyHandler.initialize()).thenReturn(Future.unit)

    val supervisorProbe = TestProbe[AssemblyResponseMode]
    val running         = run(new SampleAssemblyHandlersFactory(sampleAssemblyHandler), supervisorProbe)

    running.assemblyRef ! Lifecycle(ToComponentLifecycleMessage.GoOffline)
    Thread.sleep(1000)

    verify(sampleAssemblyHandler, never).onGoOffline()
  }

  test("A running Assembly component should accept RunOnline lifecycle message when it is Offline") {
    val sampleAssemblyHandler = mock[AssemblyHandlers[AssemblyDomainMessages]]
    when(sampleAssemblyHandler.isOnline).thenReturn(false)
    when(sampleAssemblyHandler.initialize()).thenReturn(Future.unit)

    val supervisorProbe = TestProbe[AssemblyResponseMode]
    val running         = run(new SampleAssemblyHandlersFactory(sampleAssemblyHandler), supervisorProbe)

    running.assemblyRef ! Lifecycle(ToComponentLifecycleMessage.GoOnline)
    Thread.sleep(1000)

    verify(sampleAssemblyHandler).onGoOnline()
  }

  test("A running Assembly component should not accept RunOnline lifecycle message when it is already Online") {
    val sampleAssemblyHandler = mock[AssemblyHandlers[AssemblyDomainMessages]]
    when(sampleAssemblyHandler.isOnline).thenReturn(true)
    when(sampleAssemblyHandler.initialize()).thenReturn(Future.unit)

    val supervisorProbe = TestProbe[AssemblyResponseMode]
    val running         = run(new SampleAssemblyHandlersFactory(sampleAssemblyHandler), supervisorProbe)

    running.assemblyRef ! Lifecycle(ToComponentLifecycleMessage.GoOnline)
    Thread.sleep(1000)

    verify(sampleAssemblyHandler, never).onGoOnline()
  }
}
