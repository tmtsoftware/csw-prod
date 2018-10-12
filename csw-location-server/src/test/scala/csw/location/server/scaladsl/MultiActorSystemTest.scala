package csw.location.server.scaladsl

import csw.location.api.commons.ClusterSettings
import csw.location.api.models.Connection.TcpConnection
import csw.location.api.models.{ComponentId, ComponentType, TcpRegistration}
import csw.location.server.commons.TestFutureExtension.RichFuture
import csw.location.server.commons._
import csw.location.server.internal.LocationServiceFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FunSuite, Matchers}

import scala.concurrent.duration.DurationInt

class MultiActorSystemTest extends FunSuite with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  val connection: TcpConnection = TcpConnection(ComponentId("exampleTCPService", ComponentType.Service))

  private val system1 = ClusterSettings().onPort(3552).system
  private val system2 = ClusterSettings().joinLocal(3552).system

  private val locationService  = LocationServiceFactory.withCluster(CswCluster.withSystem(system1))
  private val locationService2 = LocationServiceFactory.withCluster(CswCluster.withSystem(system2))

  val tcpRegistration: TcpRegistration = TcpRegistration(connection, 1234)

  override protected def afterAll(): Unit = system2.terminate().await

  test("ensure that location service works across two actorSystems within the same JVM") {
    locationService.register(tcpRegistration).await
    locationService2.resolve(connection, 5.seconds).await.get.connection shouldBe tcpRegistration.connection

    system1.terminate().await
    locationService2.resolve(connection, 5.seconds).await.get.connection shouldBe tcpRegistration.connection
  }
}
