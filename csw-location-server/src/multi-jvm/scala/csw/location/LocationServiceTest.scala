package csw.location

import csw.location.api.models.Connection.{HttpConnection, TcpConnection}
import csw.location.api.models._
import csw.location.server.commons.TestFutureExtension.RichFuture
import csw.location.helpers.{LSNodeSpec, OneMemberAndSeed}
import org.scalatest.BeforeAndAfterEach

import scala.collection.immutable.Set
import scala.concurrent.duration._

class LocationServiceTestMultiJvmNode1 extends LocationServiceTest(0, "cluster")
class LocationServiceTestMultiJvmNode2 extends LocationServiceTest(0, "cluster")

class LocationServiceTest(ignore: Int, mode: String)
    extends LSNodeSpec(config = new OneMemberAndSeed, mode)
    with BeforeAndAfterEach {

  import config._

  test("ensure that a component registered by one node is resolved and listed on all the nodes") {
    val tcpPort         = 446
    val tcpConnection   = TcpConnection(ComponentId("redis", ComponentType.Service))
    val tcpRegistration = TcpRegistration(tcpConnection, tcpPort)

    val httpPort         = 81
    val httpPath         = "/test/hcd"
    val httpConnection   = HttpConnection(ComponentId("tromboneHcd", ComponentType.HCD))
    val httpRegistration = HttpRegistration(httpConnection, httpPort, httpPath)

    runOn(seed) {
      locationService.register(tcpRegistration).await
      enterBarrier("Registration")

      val resolvedHttpLocation = locationService.resolve(httpConnection, 5.seconds).await.get
      resolvedHttpLocation.connection shouldBe httpConnection

      val locations   = locationService.list.await
      val connections = locations.map(_.connection)
      connections.toSet shouldBe Set(tcpConnection, httpConnection)
    }

    runOn(member) {
      locationService.register(httpRegistration).await
      enterBarrier("Registration")

      val resolvedTcpLocation = locationService.resolve(tcpConnection, 5.seconds).await.get
      resolvedTcpLocation.connection shouldBe tcpConnection

      val locations   = locationService.list.await
      val connections = locations.map(_.connection)
      connections.toSet shouldBe Set(tcpConnection, httpConnection)
    }

    enterBarrier("after-2")
  }

}
