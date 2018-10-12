package csw.framework.integration

import akka.actor.testkit.typed.scaladsl.{TestInbox, TestProbe}
import akka.http.scaladsl.Http
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.command.models.framework.ContainerLifecycleState
import csw.command.scaladsl.CommandService
import csw.common.FrameworkAssertions.assertThatContainerIsRunning
import csw.common.components.framework.SampleComponentState._
import csw.commons.redis.EmbeddedRedis
import csw.event.client.helpers.TestFutureExt.RichFuture
import csw.event.client.internal.commons.EventServiceConnection
import csw.framework.FrameworkTestWiring
import csw.framework.internal.wiring.{Container, FrameworkWiring}
import csw.location.api.models.ComponentId
import csw.location.api.models.ComponentType.{Assembly, HCD}
import csw.location.api.models.Connection.AkkaConnection
import csw.location.server.http.HTTPLocationService
import csw.params.commands
import csw.params.commands.CommandName
import csw.params.core.states.{CurrentState, StateName}
import org.scalatest.Matchers

import scala.concurrent.duration.DurationLong

//DEOPSCSW-395: Provide EventService handle to component developers
class EventServiceIntegrationTest extends HTTPLocationService with EmbeddedRedis with Matchers {
  private val testWiring = new FrameworkTestWiring()
  import testWiring._

  private val masterId: String      = ConfigFactory.load().getString("csw-event.redis.masterId")
  private val (_, sentinel, server) = startSentinelAndRegisterService(EventServiceConnection.value, masterId)

  private val filterAssemblyConnection = AkkaConnection(ComponentId("Filter", Assembly))
  private val disperserHcdConnection   = AkkaConnection(ComponentId("Disperser", HCD))
  private val wiring                   = FrameworkWiring.make(testActorSystem)

  override def afterAll(): Unit = {
    Http(testActorSystem).shutdownAllConnectionPools().await
    shutdown()
    stopSentinel(sentinel, server)
    super.afterAll()
  }

  test("should be able to publish and subscribe to events") {
    val containerRef = Container.spawn(ConfigFactory.load("container_tracking_connections.conf"), wiring).await

    val assemblyProbe                = TestInbox[CurrentState]()
    val containerLifecycleStateProbe = TestProbe[ContainerLifecycleState]("container-lifecycle-state-probe")
    assertThatContainerIsRunning(containerRef, containerLifecycleStateProbe, 5.seconds)

    val filterAssemblyLocation = wiring.locationService.find(filterAssemblyConnection).await
    val disperserHcdLocation   = wiring.locationService.find(disperserHcdConnection).await

    val assemblyCommandService  = new CommandService(filterAssemblyLocation.get)
    val disperserCommandService = new CommandService(disperserHcdLocation.get)

    assemblyCommandService.subscribeCurrentState(assemblyProbe.ref ! _)

    implicit val timeout: Timeout = Timeout(100.millis)
    assemblyCommandService.submit(commands.Setup(prefix, CommandName("subscribe.event.success"), None))
    Thread.sleep(1000)
    disperserCommandService.submit(commands.Setup(prefix, CommandName("publish.event.success"), None))
    Thread.sleep(1000)

    val states = assemblyProbe.receiveAll().filter(_.paramSet.contains(choiceKey.set(eventReceivedChoice)))

    states.size shouldBe 2 //inclusive of latest event
    states should contain(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(eventReceivedChoice))))
  }
}
