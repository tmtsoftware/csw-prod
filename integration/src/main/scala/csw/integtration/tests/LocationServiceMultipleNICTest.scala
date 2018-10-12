package csw.integtration.tests

import akka.actor.CoordinatedShutdown.UnknownReason
import csw.integtration.common.TestFutureExtension.RichFuture
import csw.location.api.commons.ClusterAwareSettings
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaLocation, ComponentId, ComponentType}
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.location.server.internal.AdminWiring
import csw.logging.scaladsl.LoggingSystemFactory
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.Span
import org.scalatest.{BeforeAndAfterAll, FunSuite, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration.DurationDouble

class LocationServiceMultipleNICTest() extends FunSuite with Matchers with BeforeAndAfterAll with ScalaFutures with Eventually {

  implicit val patience: PatienceConfig =
    PatienceConfig(Span(5, org.scalatest.time.Seconds), Span(100, org.scalatest.time.Millis))

  val adminWiring: AdminWiring = AdminWiring.make(ClusterAwareSettings.onPort(3553).withInterface("eth1"))
  LoggingSystemFactory.start("Assembly", "1.0", ClusterAwareSettings.hostname, adminWiring.actorSystem)

  adminWiring.locationHttpService.start().futureValue

  import adminWiring.actorRuntime._
  private val locationService = HttpLocationServiceFactory.makeLocalClient

  override def afterAll(): Unit = Await.result(adminWiring.actorRuntime.shutdown(UnknownReason), 5.seconds)

  test("should list and resolve component having multiple-nic's") {

    val componentId = ComponentId("assembly", ComponentType.Assembly)
    val connection  = AkkaConnection(componentId)

    eventually(locationService.list.await should have size 1)

    locationService.find(connection).await.get shouldBe a[AkkaLocation]
  }

}
