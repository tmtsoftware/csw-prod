package csw.location

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.stream.ActorMaterializer
import akka.testkit.TestProbe
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.AkkaRegistration
import csw.location.api.models.{ComponentId, ComponentType, LocationRemoved, LocationUpdated}
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.params.core.models.Prefix
import csw.location.server.commons.CswCluster
import csw.location.helpers.{LSNodeSpec, TwoMembersAndSeed}
import csw.location.server.internal.LocationServiceFactory
import org.jboss.netty.logging.{InternalLoggerFactory, Slf4JLoggerFactory}

import scala.concurrent.Await
import scala.concurrent.duration._

class DetectComponentRestartTestMultiJvmNode1 extends DetectComponentRestartTest(0, "cluster")
class DetectComponentRestartTestMultiJvmNode2 extends DetectComponentRestartTest(0, "cluster")
class DetectComponentRestartTestMultiJvmNode3 extends DetectComponentRestartTest(0, "cluster")

class DetectComponentRestartTest(ignore: Int, mode: String) extends LSNodeSpec(config = new TwoMembersAndSeed, mode) {

  import config._

  // Fix to avoid 'java.util.concurrent.RejectedExecutionException: Worker has already been shutdown'
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

  test("should detect re-registering of new location for a connection that has crashed/gone away") {

    val akkaConnection = AkkaConnection(ComponentId("TromboneHcd", ComponentType.HCD))

    runOn(member1) {
      locationService
        .register(
          AkkaRegistration(
            akkaConnection,
            Prefix("nfiraos.ncc.trombone"),
            system.spawnAnonymous(Behavior.empty)
          )
        )
        .await
      enterBarrier("location-registered")
      enterBarrier("location-updated")

      Await.ready(system.whenTerminated, 10.seconds)

      val newSystem = startNewSystem()

      val freshLocationService = mode match {
        case "http"    => HttpLocationServiceFactory.makeLocalClient(newSystem, ActorMaterializer()(newSystem))
        case "cluster" => LocationServiceFactory.withCluster(CswCluster.withSystem(newSystem))
      }

      Thread.sleep(2000)

      freshLocationService
        .register(
          AkkaRegistration(
            akkaConnection,
            Prefix("nfiraos.ncc.trombone"),
            newSystem.spawnAnonymous(Behavior.empty)
          )
        )
        .await
      enterBarrier("member-re-registered")
    }

    runOn(seed, member2) {
      enterBarrier("location-registered")
      val testProbe = TestProbe()
      locationService.subscribe(akkaConnection, testProbe.testActor ! _)

      testProbe.expectMsgType[LocationUpdated]
      enterBarrier("location-updated")

      runOn(seed) {
        Await.result(testConductor.shutdown(member1), 10.seconds)
      }

      testProbe.expectMsgType[LocationRemoved](5.seconds)
      Thread.sleep(2000)
      enterBarrier("member-re-registered")
      testProbe.expectMsgType[LocationUpdated](5.seconds)
    }

    enterBarrier("after-2")
  }

}
