package csw.benchmark.command

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.actor.{typed, ActorSystem}
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import csw.command.extensions.AkkaLocationExt.RichAkkaLocation
import csw.command.messages.ComponentCommonMessage.GetSupervisorLifecycleState
import csw.command.messages.ContainerCommonMessage.GetContainerLifecycleState
import csw.command.messages.{ComponentMessage, ContainerMessage}
import csw.command.models.framework.{ContainerLifecycleState, SupervisorLifecycleState}
import csw.command.scaladsl.CommandService
import csw.framework.internal.wiring.{FrameworkWiring, Standalone}
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaLocation, ComponentId, ComponentType}
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.location.server.commons.BlockingUtils

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationDouble}

object BenchmarkHelpers {

  def spawnStandaloneComponent(actorSystem: ActorSystem, config: Config): CommandService = {
    val mat                                              = ActorMaterializer()(actorSystem)
    val locationService                                  = HttpLocationServiceFactory.makeLocalClient(actorSystem, mat)
    val wiring: FrameworkWiring                          = FrameworkWiring.make(actorSystem, locationService)
    implicit val typedSystem: typed.ActorSystem[Nothing] = actorSystem.toTyped

    val probe = TestProbe[SupervisorLifecycleState]

    Standalone.spawn(config, wiring)
    val akkaLocation: AkkaLocation =
      Await.result(locationService.resolve(AkkaConnection(ComponentId("Perf", ComponentType.HCD)), 5.seconds), 5.seconds).get

    assertThatSupervisorIsRunning(akkaLocation.componentRef, probe, 5.seconds)

    new CommandService(akkaLocation)
  }

  def assertThatContainerIsRunning(
      containerRef: ActorRef[ContainerMessage],
      probe: TestProbe[ContainerLifecycleState],
      duration: Duration
  ): Unit = {
    def getContainerLifecycleState: ContainerLifecycleState = {
      containerRef ! GetContainerLifecycleState(probe.ref)
      probe.expectMessageType[ContainerLifecycleState]
    }

    assert(
      BlockingUtils.poll(getContainerLifecycleState == ContainerLifecycleState.Running, duration),
      s"expected :${ContainerLifecycleState.Running}, found :$getContainerLifecycleState"
    )
  }

  def assertThatSupervisorIsRunning(
      actorRef: ActorRef[ComponentMessage],
      probe: TestProbe[SupervisorLifecycleState],
      duration: Duration
  ): Unit = {
    def getSupervisorLifecycleState: SupervisorLifecycleState = {
      actorRef ! GetSupervisorLifecycleState(probe.ref)
      probe.expectMessageType[SupervisorLifecycleState]
    }

    assert(
      BlockingUtils.poll(getSupervisorLifecycleState == SupervisorLifecycleState.Running, duration),
      s"expected :${SupervisorLifecycleState.Running}, found :$getSupervisorLifecycleState"
    )
  }
}
