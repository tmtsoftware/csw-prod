package csw.admin.server.log

import akka.actor
import akka.actor.CoordinatedShutdown.UnknownReason
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, StatusCodes, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.typesafe.config.ConfigFactory
import csw.admin.server.internal.AdminWiring
import csw.admin.server.log.http.HttpSupport
import csw.command.messages.CommandMessage.Oneway
import csw.command.messages.ContainerCommonMessage.GetComponents
import csw.command.messages.ContainerMessage
import csw.command.models.framework.{Component, Components, ContainerLifecycleState}
import csw.common.FrameworkAssertions.assertThatContainerIsRunning
import csw.commons.tags.LoggingSystemSensitive
import csw.framework.internal.wiring.{Container, FrameworkWiring}
import csw.location.api.commons.ClusterAwareSettings
import csw.location.api.models.ComponentId
import csw.location.api.models.ComponentType.{Assembly, HCD}
import csw.location.api.models.Connection.AkkaConnection
import csw.location.client.ActorSystemFactory
import csw.location.server.http.HTTPLocationService
import csw.logging.internal.LoggingLevels.{ERROR, Level, WARN}
import csw.logging.internal._
import csw.logging.models.LogMetadata
import csw.logging.scaladsl.LoggingSystemFactory
import csw.params.commands.CommandResponse.OnewayResponse
import csw.params.commands.{CommandName, Setup}
import csw.params.core.models.Prefix
import io.lettuce.core.RedisClient

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.concurrent.Await
import scala.concurrent.duration.DurationDouble

@LoggingSystemSensitive
class AkkaLogAdminTest extends AdminLogTestSuite with HTTPLocationService with HttpSupport {

  private val adminWiring: AdminWiring = AdminWiring.make(Some(7879))
  import adminWiring.actorRuntime._

  implicit val typedSystem: ActorSystem[Nothing] = actorSystem.toTyped
  implicit val testKitSettings: TestKitSettings  = TestKitSettings(typedSystem)

  private val laserConnection            = AkkaConnection(ComponentId("Laser", Assembly))
  private val motionControllerConnection = AkkaConnection(ComponentId("Motion_Controller", HCD))
  private val galilConnection            = AkkaConnection(ComponentId("Galil", Assembly))

  private var containerActorSystem: actor.ActorSystem = _

  private var laserComponent: Component = _
  private var galilComponent: Component = _
  private val probe                     = TestProbe[OnewayResponse]
  private val startLoggingCmd           = CommandName("StartLogging")
  private val prefix                    = Prefix("iris.command")

  private var loggingSystem: LoggingSystem = _

  override protected def beforeAll(): Unit = {
    loggingSystem = LoggingSystemFactory.start("logging", "version", hostName, adminWiring.actorSystem)
    loggingSystem.setAppenders(List(testAppender))

    logBuffer.clear()
    Await.result(adminWiring.adminHttpService.registeredLazyBinding, 5.seconds)

    // this will start seed on port 3652 and log admin server on 7879
    adminWiring.locationService

    containerActorSystem = ActorSystemFactory.remote()

    // this will start container on random port and join seed and form a cluster
    val containerRef = startContainerAndWaitForRunning()
    extractComponentsFromContainer(containerRef)
  }

  override protected def afterEach(): Unit = logBuffer.clear()

  override def afterAll(): Unit = {
    Await.result(adminWiring.actorRuntime.shutdown(UnknownReason), 10.seconds)
    Await.result(containerActorSystem.terminate(), 5.seconds)
    super.afterAll()
  }

  def startContainerAndWaitForRunning(): ActorRef[ContainerMessage] = {
    val frameworkWiring = FrameworkWiring.make(containerActorSystem, mock[RedisClient])
    val config          = ConfigFactory.load("laser_container.conf")
    val containerRef    = Await.result(Container.spawn(config, frameworkWiring), 5.seconds)

    val containerStateProbe = TestProbe[ContainerLifecycleState]
    assertThatContainerIsRunning(containerRef, containerStateProbe, 5.seconds)
    containerRef
  }

  def extractComponentsFromContainer(containerRef: ActorRef[ContainerMessage]): Unit = {
    val probe = TestProbe[Components]
    containerRef ! GetComponents(probe.ref)
    val components = probe.expectMessageType[Components].components

    laserComponent = components.find(x ⇒ x.info.name.equals("Laser")).get
    galilComponent = components.find(x ⇒ x.info.name.equals("Galil")).get
  }

  // DEOPSCSW-127: Runtime update for logging characteristics
  // DEOPSCSW-168: Actors can receive and handle runtime update for logging characteristics
  test("should able to get the current component log meta data") {

    // send http get metadata request and verify the response has correct log levels
    val getLogMetadataUri = Uri.from(
      scheme = "http",
      host = ClusterAwareSettings.hostname,
      port = 7879,
      path = s"/admin/logging/${motionControllerConnection.name}/level"
    )

    val getLogMetadataRequest   = HttpRequest(HttpMethods.GET, uri = getLogMetadataUri)
    val getLogMetadataResponse1 = Await.result(Http().singleRequest(getLogMetadataRequest), 5.seconds)
    val logMetadata1            = Await.result(Unmarshal(getLogMetadataResponse1).to[LogMetadata], 5.seconds)

    getLogMetadataResponse1.status shouldBe StatusCodes.OK

    val config     = ConfigFactory.load().getConfig("csw-logging")
    val logLevel   = Level(config.getString("logLevel"))
    val akkaLevel  = Level(config.getString("akkaLogLevel"))
    val slf4jLevel = Level(config.getString("slf4jLogLevel"))
    val componentLogLevel = Level(
      config.getObject("component-log-levels").unwrapped().asScala(motionControllerConnection.componentId.name).toString
    )

    logMetadata1 shouldBe LogMetadata(logLevel, akkaLevel, slf4jLevel, componentLogLevel)

    // updating default and akka log level
    loggingSystem.setDefaultLogLevel(LoggingLevels.ERROR)
    loggingSystem.setAkkaLevel(LoggingLevels.WARN)

    // verify getLogMetadata http request gives updated log levels in response
    val getLogMetadataResponse2 = Await.result(Http().singleRequest(getLogMetadataRequest), 5.seconds)
    val logMetadata2            = Await.result(Unmarshal(getLogMetadataResponse2).to[LogMetadata], 5.seconds)

    logMetadata2 shouldBe LogMetadata(ERROR, WARN, slf4jLevel, componentLogLevel)

    // reset log levels to default
    loggingSystem.setDefaultLogLevel(logLevel)
    loggingSystem.setAkkaLevel(akkaLevel)
  }

  // DEOPSCSW-127: Runtime update for logging characteristics
  // DEOPSCSW-168: Actors can receive and handle runtime update for logging characteristics
  test("should able to set log level of the component dynamically through http end point") {
    laserComponent.supervisor ! Oneway(Setup(prefix, startLoggingCmd, None), probe.ref)
    Thread.sleep(100)

    // default logging level for Laser component is info
    val groupByComponentNamesLog = logBuffer.groupBy { json ⇒
      if (json.contains("@componentName")) json("@componentName").toString
    }
    val laserComponentLogs = groupByComponentNamesLog(laserComponent.info.name)

    laserComponentLogs.exists(log ⇒ log("@severity").toString.toLowerCase.equalsIgnoreCase("info")) shouldBe true
    laserComponentLogs.foreach { log ⇒
      val currentLogLevel = log("@severity").toString.toLowerCase
      Level(currentLogLevel) >= LoggingLevels.INFO shouldBe true
    }

    // set log level of laser component to error through http endpoint
    val uri = Uri.from(
      scheme = "http",
      host = ClusterAwareSettings.hostname,
      port = 7879,
      path = s"/admin/logging/${laserConnection.name}/level",
      queryString = Some("value=error")
    )

    val request  = HttpRequest(HttpMethods.POST, uri = uri)
    val response = Await.result(Http().singleRequest(request), 5.seconds)

    response.status shouldBe StatusCodes.OK

    Thread.sleep(100)
    logBuffer.clear()

    // laser and galil components, start logging messages at all log levels
    // and expected is that, laser component logs messages at and above Error level
    // and galil component  still logs messages at and above Info level

    laserComponent.supervisor ! Oneway(Setup(prefix, startLoggingCmd, None), probe.ref)
    galilComponent.supervisor ! Oneway(Setup(prefix, startLoggingCmd, None), probe.ref)
    Thread.sleep(100)

    val groupByAfterFilter       = logBuffer.groupBy(json ⇒ json("@componentName").toString)
    val laserCompLogsAfterFilter = groupByAfterFilter(laserConnection.componentId.name)
    val galilCompLogsAfterFilter = groupByAfterFilter(galilConnection.componentId.name)

    laserCompLogsAfterFilter.exists(log ⇒ log("@severity").toString.toLowerCase.equalsIgnoreCase("error")) shouldBe true
    laserCompLogsAfterFilter.foreach { log ⇒
      val currentLogLevel = log("@severity").toString.toLowerCase
      Level(currentLogLevel) >= LoggingLevels.ERROR shouldBe true
    }

    // this makes sure that, changing log level of one component (laser component) from container does not affect other components (galil component) log level
    galilCompLogsAfterFilter.exists(log ⇒ log("@severity").toString.toLowerCase.equalsIgnoreCase("info")) shouldBe true

    galilCompLogsAfterFilter.foreach { log ⇒
      val currentLogLevel = log("@severity").toString.toLowerCase
      Level(currentLogLevel) >= LoggingLevels.INFO shouldBe true
    }
  }

  test("should give appropriate exception when component name is incorrect") {
    // send http get metadata request for invalid component
    val getLogMetadataUri = Uri.from(
      scheme = "http",
      host = ClusterAwareSettings.hostname,
      port = 7879,
      path = s"/admin/logging/abcd-hcd-akka/level"
    )

    val getLogMetadataRequest   = HttpRequest(HttpMethods.GET, uri = getLogMetadataUri)
    val getLogMetadataResponse1 = Await.result(Http().singleRequest(getLogMetadataRequest), 5.seconds)

    getLogMetadataResponse1.status shouldBe StatusCodes.NotFound
  }

  test("should give appropriate exception when logging level is incorrect") {
    // send http set request for invalid log level
    val uri = Uri.from(
      scheme = "http",
      host = ClusterAwareSettings.hostname,
      port = 7879,
      path = s"/admin/logging/${laserConnection.name}/level",
      queryString = Some("value=error1")
    )

    val request  = HttpRequest(HttpMethods.POST, uri = uri)
    val response = Await.result(Http().singleRequest(request), 5.seconds)

    response.status shouldBe StatusCodes.BadRequest

  }
}
