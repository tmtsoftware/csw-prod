package csw.location

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.stream.scaladsl.{Keep, Sink}
import csw.location.api.models.Connection.{AkkaConnection, HttpConnection}
import csw.location.api.models._
import csw.location.api.models.HttpRegistration
import csw.location.client.ActorSystemFactory
import csw.location.helpers.{LSNodeSpec, TwoMembersAndSeed}
import csw.params.core.models.Prefix

import scala.concurrent.Await
import scala.concurrent.duration._

class DetectAkkaComponentCrashTestMultiJvmNode1 extends DetectAkkaComponentCrashTest(0, "cluster")
class DetectAkkaComponentCrashTestMultiJvmNode2 extends DetectAkkaComponentCrashTest(0, "cluster")
class DetectAkkaComponentCrashTestMultiJvmNode3 extends DetectAkkaComponentCrashTest(0, "cluster")

/**
 * This test is running as a part of jenkins master-slave setup forming three nodes cluster. (seed: running on jenkins master, member1: running on jenkins slave, member2: running on jenkins slave)
 * This test exercises below steps :
 * 1. Registering akka connection on member1 node
 * 2. seed(master) and member2 is tracking a akka connection which is registered on slave (member1)
 * 3. Exiting member1 using testConductor.exit(member1, 1) (tell the remote node to shut itself down using System.exit with the given
 * exitValue 1. The node will also be removed from cluster)
 * 4. Once remote member1 is exited, we are asserting that master (seed) and member2 should receive LocationRemoved event within 5 seconds
 * => probe.requestNext(5.seconds) shouldBe a[LocationRemoved]
 *
**/
class DetectAkkaComponentCrashTest(ignore: Int, mode: String) extends LSNodeSpec(config = new TwoMembersAndSeed, mode) {

  import config._
  import cswCluster.mat

  test("akka component running on one node should detect if other component running on another node crashes") {

    val akkaConnection = AkkaConnection(ComponentId("Container1", ComponentType.Container))

    runOn(seed) {

      val probe = TestProbe[TrackingEvent]("test-probe")

      val switch = locationService.track(akkaConnection).toMat(Sink.foreach(probe.ref.tell(_)))(Keep.left).run()
      enterBarrier("Registration")

      probe.expectMessageType[LocationUpdated]
      Thread.sleep(2000)

      Await.result(testConductor.exit(member1, 0), 5.seconds)
      enterBarrier("after-crash")

      // Story CSW-15 requires crash detection within 10 seconds with a goal of 5 seconds.
      // This 5.seconds demonstrates that if the test passes, the performance goal is met. Could be relaxed to 10 seconds
      // if needed.
      within(5.seconds) {
        awaitAssert {
          probe.expectMessageType[LocationRemoved](5.seconds)
        }
      }

      locationService.list.await.size shouldBe 1

      // clean up
      switch.shutdown()
    }

    runOn(member1) {
      val actorRef = ActorSystemFactory
        .remote()
        .spawn(Behavior.empty, "trombone-hcd-1")

      locationService.register(AkkaRegistration(akkaConnection, Prefix("nfiraos.ncc.trombone"), actorRef)).await
      enterBarrier("Registration")

      Await.ready(system.whenTerminated, 5.seconds)
    }

    runOn(member2) {
      val port   = 9595
      val prefix = "/trombone/hcd"

      val httpConnection   = HttpConnection(ComponentId("Assembly1", ComponentType.Assembly))
      val httpRegistration = HttpRegistration(httpConnection, port, prefix)
      val probe            = TestProbe[TrackingEvent]("test-probe")

      locationService.register(httpRegistration).await

      enterBarrier("Registration")
      val switch = locationService.track(akkaConnection).toMat(Sink.foreach(probe.ref.tell(_)))(Keep.left).run()
      Thread.sleep(2000)
      enterBarrier("after-crash")

      within(5.seconds) {
        awaitAssert {
          probe.expectMessageType[LocationRemoved](5.seconds)
        }
      }

      // clean up
      switch.shutdown()
    }
    enterBarrier("end")
  }
}
