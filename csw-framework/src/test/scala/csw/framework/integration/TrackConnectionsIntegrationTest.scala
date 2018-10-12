package csw.framework.integration

import akka.actor
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.http.scaladsl.Http
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.command.extensions.AkkaLocationExt.RichAkkaLocation
import csw.command.messages.SupervisorContainerCommonMessages.Shutdown
import csw.command.models.framework.{ContainerLifecycleState, SupervisorLifecycleState}
import csw.command.scaladsl.CommandService
import csw.common.FrameworkAssertions._
import csw.common.components.framework.SampleComponentState._
import csw.event.client.helpers.TestFutureExt.RichFuture
import csw.framework.FrameworkTestWiring
import csw.framework.internal.wiring.{Container, FrameworkWiring, Standalone}
import csw.location.api.models.ComponentType.{Assembly, HCD}
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{ComponentId, HttpRegistration, TcpRegistration}
import csw.location.client.ActorSystemFactory
import csw.location.server.http.HTTPLocationService
import csw.params.commands
import csw.params.commands.CommandName
import csw.params.core.states.{CurrentState, StateName}
import io.lettuce.core.RedisClient
import org.scalatest.OptionValues

import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationLong

class TrackConnectionsIntegrationTest extends HTTPLocationService with OptionValues {

  private val testWiring = new FrameworkTestWiring()
  import testWiring._

  private val filterAssemblyConnection = AkkaConnection(ComponentId("Filter", Assembly))
  private val disperserHcdConnection   = AkkaConnection(ComponentId("Disperser", HCD))

  override def afterAll(): Unit = {
    shutdown()
    super.afterAll()
  }

  // DEOPSCSW-218: Discover component connection information using Akka protocol
  // DEOPSCSW-220: Access and Monitor components for current values
  // DEOPSCSW-221: Avoid sending commands to non-executing components
  test("should track connections when locationServiceUsage is RegisterAndTrackServices") {
    val actorSystem: actor.ActorSystem = ActorSystemFactory.remote("test1")
    val wiring: FrameworkWiring        = FrameworkWiring.make(actorSystem, mock[RedisClient])

    // start a container and verify it moves to running lifecycle state
    val containerRef = Container.spawn(ConfigFactory.load("container_tracking_connections.conf"), wiring).await

    val containerLifecycleStateProbe = TestProbe[ContainerLifecycleState]("container-lifecycle-state-probe")
    val assemblyProbe                = TestProbe[CurrentState]("assembly-state-probe")

    // initially container is put in Idle lifecycle state and wait for all the components to move into Running lifecycle state
    // ********** Message: GetContainerLifecycleState **********
    assertThatContainerIsRunning(containerRef, containerLifecycleStateProbe, 5.seconds)

    // resolve all the components from container using location service
    val filterAssemblyLocation = wiring.locationService.find(filterAssemblyConnection).await
    val disperserHcdLocation   = wiring.locationService.find(disperserHcdConnection).await

    val assemblyCommandService = new CommandService(filterAssemblyLocation.get)

    val disperserComponentRef   = disperserHcdLocation.get.componentRef
    val disperserCommandService = new CommandService(disperserHcdLocation.get)

    // Subscribe to component's current state
    assemblyCommandService.subscribeCurrentState(assemblyProbe.ref ! _)

    // assembly is tracking two HCD's, hence assemblyProbe will receive LocationUpdated event from two HCD's
    assemblyProbe.expectMessage(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(akkaLocationUpdatedChoice))))
    assemblyProbe.expectMessage(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(akkaLocationUpdatedChoice))))

    // if one of the HCD shuts down, then assembly should know and receive LocationRemoved event
    disperserComponentRef ! Shutdown
    assemblyProbe.expectMessage(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(akkaLocationRemovedChoice))))

    implicit val timeout: Timeout = Timeout(100.millis)
    a[TimeoutException] shouldBe thrownBy(
      disperserCommandService.submit(commands.Setup(prefix, CommandName("isAlive"), None)).await(200.millis)
    )
    Http(actorSystem).shutdownAllConnectionPools().await
  }

  /**
   * If component writer wants to track additional connections which are not specified in configuration file,
   * then using trackConnection(connection: Connection) hook which is added in ComponentHandlers can be used
   * Uses of this are shown in [[csw.common.components.framework.SampleComponentHandlers]]
   * */
  //DEOPSCSW-219 Discover component connection using HTTP protocol
  test("component should be able to track http and tcp connections") {
    val actorSystem: actor.ActorSystem = ActorSystemFactory.remote("test2")
    val wiring: FrameworkWiring        = FrameworkWiring.make(actorSystem, mock[RedisClient])
    // start component in standalone mode
    val assemblySupervisor = Standalone.spawn(ConfigFactory.load("standalone.conf"), wiring).await

    val supervisorLifecycleStateProbe = TestProbe[SupervisorLifecycleState]("supervisor-lifecycle-state-probe")
    val akkaConnection                = AkkaConnection(ComponentId("IFS_Detector", HCD))

    assertThatSupervisorIsRunning(assemblySupervisor, supervisorLifecycleStateProbe, 5.seconds)

    val resolvedAkkaLocation = seedLocationService.resolve(akkaConnection, 5.seconds).await.value
    resolvedAkkaLocation.connection shouldBe akkaConnection

    val assemblyProbe          = TestProbe[CurrentState]("assembly-state-probe")
    val assemblyCommandService = new CommandService(resolvedAkkaLocation)
    // Subscribe to component's current state
    assemblyCommandService.subscribeCurrentState(assemblyProbe.ref ! _)

    // register http connection
    seedLocationService.register(HttpRegistration(httpConnection, 9090, "test/path")).await

    // assembly is tracking HttpConnection that we registered above, hence assemblyProbe will receive LocationUpdated event
    assemblyProbe.expectMessage(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(httpLocationUpdatedChoice))))

    // On unavailability of HttpConnection, the assembly should know and receive LocationRemoved event
    seedLocationService.unregister(httpConnection)
    assemblyProbe.expectMessage(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(httpLocationRemovedChoice))))

    // register tcp connection
    seedLocationService.register(TcpRegistration(tcpConnection, 9090)).await

    // assembly is tracking TcpConnection that we registered above, hence assemblyProbe will receive LocationUpdated event.
    assemblyProbe.expectMessage(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(tcpLocationUpdatedChoice))))

    // On unavailability of TcpConnection, the assembly should know and receive LocationRemoved event
    seedLocationService.unregister(tcpConnection)
    assemblyProbe.expectMessage(CurrentState(prefix, StateName("testStateName"), Set(choiceKey.set(tcpLocationRemovedChoice))))
    Http(actorSystem).shutdownAllConnectionPools().await
  }

}
