package csw.admin.server.log.app

import akka.actor.typed.scaladsl.adapter._
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.{ActorRef, ActorSystem}
import com.typesafe.config.ConfigFactory
import csw.admin.server.internal.AdminWiring
import csw.common.FrameworkAssertions.assertThatContainerIsRunning
import csw.framework.internal.wiring.{Container, FrameworkWiring}
import csw.params.commands.{CommandName, Setup}
import csw.command.models.framework.{Component, Components, ContainerLifecycleState}
import csw.params.core.models.Prefix
import csw.command.messages.CommandMessage.Oneway
import csw.command.messages.ContainerMessage
import csw.command.messages.ContainerCommonMessage.GetComponents
import csw.location.api.commons.ClusterAwareSettings
import csw.logging.scaladsl.{LoggerFactory, LoggingSystemFactory}
import csw.params.commands.CommandResponse.OnewayResponse

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object AppLogger extends LoggerFactory("app")

/*
  This app is for testing runtime changes of component log level

  DemoApp does four things :
    1. Start seed node on port 3552
    2. Start AdminHttpServer on port 7878
    3. Creates a Laser Container with 3 components within it
    4. Laser Assembly start logging messages at all levels in infinite loop

  How to test :
    1. Start the app
    2. Import postman collection present under tools/postman which has two routes inside log admin folder (1. get log metadata 2. set log level)
    3. get log metadata :=> this will give current configuration for specified component
    4. set log level :=> update component name and value and verify on the console that logs are printed as per the updated log level.

  Important :
    Make sure you stop the app once you finish testing as it will not terminate automatically.

 */

object DemoApp extends App {

  val adminWiring: AdminWiring = AdminWiring.make(ClusterAwareSettings.onPort(3552), None)

  val frameworkSystem = ClusterAwareSettings.joinLocal(3552).system
  val frameworkWiring = FrameworkWiring.make(frameworkSystem)

  implicit val typedSystem: ActorSystem[Nothing] = frameworkWiring.actorSystem.toTyped
  implicit val testKitSettings: TestKitSettings  = TestKitSettings(typedSystem)

  private val cmdResponseProbe = TestProbe[OnewayResponse]
  private val startLoggingCmd  = CommandName("StartLogging")
  private val prefix           = Prefix("iris.command")

  private def startSeed() = {
    LoggingSystemFactory.start("logging", "version", ClusterAwareSettings.hostname, adminWiring.actorSystem)
    adminWiring.locationService
    Await.result(adminWiring.adminHttpService.registeredLazyBinding, 5.seconds)
  }

  private def spawnContainer(): ActorRef[ContainerMessage] = {

    val config       = ConfigFactory.load("laser_container.conf")
    val containerRef = Await.result(Container.spawn(config, frameworkWiring), 5.seconds)

    val containerStateProbe = TestProbe[ContainerLifecycleState]
    assertThatContainerIsRunning(containerRef, containerStateProbe, 5.seconds)
    containerRef
  }

  startSeed()

  private val containerRef: ActorRef[ContainerMessage] = spawnContainer()

  val probe = TestProbe[Components]
  containerRef ! GetComponents(probe.ref)
  val components = probe.expectMessageType[Components].components

  private val laserComponent: Component = components.find(x ⇒ x.info.name.equals("Laser")).get

  while (true) {
    println("------------------------------------")
    laserComponent.supervisor ! Oneway(Setup(prefix, startLoggingCmd, None), cmdResponseProbe.ref)
    println("------------------------------------")
    Thread.sleep(1000)
  }
}
