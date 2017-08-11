package csw.common.framework.scaladsl

import akka.typed.scaladsl.{Actor, ActorContext}
import akka.typed.testkit.TestKitSettings
import akka.typed.{ActorRef, ActorSystem}
import akka.util.Timeout
import csw.common.ccs.Validation
import csw.common.components.assembly.AssemblyDomainMsg
import csw.common.components.hcd.HcdDomainMsg
import csw.common.framework.models.Component.{AssemblyInfo, ComponentInfo, DoNotRegister, HcdInfo}
import csw.common.framework.models.{CommandMsg, ComponentMsg, PubSub}
import csw.common.framework.models.PubSub.PublisherMsg
import csw.param.states.CurrentState
import csw.services.location.models.ConnectionType.AkkaType
import org.scalatest.{BeforeAndAfterAll, FunSuite, Matchers}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{DurationLong, FiniteDuration}

class SampleHcdHandlers(ctx: ActorContext[ComponentMsg], componentInfo: ComponentInfo)
    extends ComponentHandlers[HcdDomainMsg](ctx, componentInfo) {
  override def onRestart(): Unit                                    = println(s"${componentInfo.componentName} restarting")
  override def onRun(): Unit                                        = println(s"${componentInfo.componentName} running")
  override def onGoOnline(): Unit                                   = println(s"${componentInfo.componentName} going online")
  override def onDomainMsg(msg: HcdDomainMsg): Unit                 = println(s"${componentInfo.componentName} going offline")
  override def onShutdown(): Unit                                   = println(s"${componentInfo.componentName} shutting down")
  override def onControlCommand(commandMsg: CommandMsg): Validation = Validation.Valid
  override def initialize(): Future[Unit]                           = Future.unit
  override def onGoOffline(): Unit                                  = println(s"${componentInfo.componentName} going offline")
}

class SampleHcdWiring extends ComponentWiring[HcdDomainMsg] {
  override def handlers(
      ctx: ActorContext[ComponentMsg],
      componentInfo: ComponentInfo,
      pubSubRef: ActorRef[PubSub.PublisherMsg[CurrentState]]
  ): ComponentHandlers[HcdDomainMsg] = new SampleHcdHandlers(ctx, componentInfo)
}

abstract class FrameworkComponentTestSuite extends FunSuite with Matchers with BeforeAndAfterAll {
  implicit val system   = ActorSystem(Actor.empty, "testHcd")
  implicit val settings = TestKitSettings(system)
  implicit val timeout  = Timeout(5.seconds)

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  val assemblyInfo = AssemblyInfo("trombone",
                                  "wfos",
                                  "csw.common.components.assembly.SampleAssembly",
                                  DoNotRegister,
                                  Set(AkkaType),
                                  Set.empty)

  val hcdInfo = HcdInfo("SampleHcd",
                        "wfos",
                        "csw.common.framework.scaladsl.SampleHcdWiring",
                        DoNotRegister,
                        Set(AkkaType),
                        FiniteDuration(5, "seconds"))

  def getSampleHcdFactory(componentHandlers: ComponentHandlers[HcdDomainMsg]): ComponentWiring[HcdDomainMsg] =
    new ComponentWiring[HcdDomainMsg] {

      override def handlers(ctx: ActorContext[ComponentMsg],
                            componentInfo: ComponentInfo,
                            pubSubRef: ActorRef[PublisherMsg[CurrentState]]): ComponentHandlers[HcdDomainMsg] =
        componentHandlers
    }

  def getSampleAssemblyFactory(
      assemblyHandlers: ComponentHandlers[AssemblyDomainMsg]
  ): ComponentWiring[AssemblyDomainMsg] =
    new ComponentWiring[AssemblyDomainMsg] {
      override def handlers(ctx: ActorContext[ComponentMsg],
                            componentInfo: ComponentInfo,
                            pubSubRef: ActorRef[PublisherMsg[CurrentState]]): ComponentHandlers[AssemblyDomainMsg] =
        assemblyHandlers
    }

}
