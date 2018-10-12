package csw.location

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.stream.scaladsl.{Keep, Sink}
import csw.location.api.models.Connection.{HttpConnection, TcpConnection}
import csw.location.api.models.{HttpRegistration, TcpRegistration, _}
import csw.location.helpers.{LSNodeSpec, TwoMembersAndSeed}

import scala.concurrent.Await
import scala.concurrent.duration._

class DetectComponentCrashTestMultiJvmNode1 extends DetectComponentCrashTest(0, "cluster")
class DetectComponentCrashTestMultiJvmNode2 extends DetectComponentCrashTest(0, "cluster")
class DetectComponentCrashTestMultiJvmNode3 extends DetectComponentCrashTest(0, "cluster")

// DEOPSCSW-298: DeathWatch Http components
// DEOPSCSW-300: DeathWatch tcp components
class DetectComponentCrashTest(ignore: Int, mode: String) extends LSNodeSpec(config = new TwoMembersAndSeed, mode) {

  import config._
  import cswCluster.mat

  //FixMe: Delete this test as HTTP and TCP location's deathwatch is no longer supported.
  ignore("A component running on one node should detect if a http/tcp component running on another node crashes") {

    val httpConnection = HttpConnection(ComponentId("Assembly1", ComponentType.Assembly))
    val tcpConnection1 = TcpConnection(ComponentId("Assembly2", ComponentType.Assembly))
    val tcpConnection2 = TcpConnection(ComponentId("Assembly3", ComponentType.Assembly))

    runOn(seed) {
      val httpProbe = TestProbe[TrackingEvent]("test-probe1")
      val tcpProbe1 = TestProbe[TrackingEvent]("test-probe2")
      val tcpProbe2 = TestProbe[TrackingEvent]("test-probe3")

      val switch1 = locationService.track(httpConnection).toMat(Sink.foreach(httpProbe.ref.tell(_)))(Keep.left).run()
      val switch2 = locationService.track(tcpConnection1).toMat(Sink.foreach(tcpProbe1.ref.tell(_)))(Keep.left).run()
      val switch3 = locationService.track(tcpConnection2).toMat(Sink.foreach(tcpProbe2.ref.tell(_)))(Keep.left).run()
      enterBarrier("Registration")

      httpProbe.expectMessageType[LocationUpdated]
      tcpProbe1.expectMessageType[LocationUpdated]
      tcpProbe2.expectMessageType[LocationUpdated]
      Thread.sleep(2000)

      Await.result(testConductor.exit(member1, 0), 5.seconds)
      within(20.seconds) {
        awaitAssert {
          httpProbe.expectMessageType[LocationRemoved](20.seconds)
        }
      }

      enterBarrier("after-crash")
      Await.result(testConductor.exit(member2, 0), 5.seconds)

      within(20.seconds) {
        awaitAssert {
          tcpProbe1.expectMessageType[LocationRemoved](20.seconds)
          tcpProbe2.expectMessageType[LocationRemoved](20.seconds)
        }
      }

      //clean up
      switch1.shutdown()
      switch2.shutdown()
      switch3.shutdown()
    }

    runOn(member1) {
      val port   = 9595
      val prefix = "/trombone/hcd"

      val httpRegistration =
        HttpRegistration(httpConnection, port, prefix)

      locationService.register(httpRegistration).await
      enterBarrier("Registration")

      Await.ready(system.whenTerminated, 15.seconds)
    }

    runOn(member2) {
      val port             = 9595
      val tcpRegistration1 = TcpRegistration(tcpConnection1, port)
      val tcpRegistration2 = TcpRegistration(tcpConnection2, port)

      locationService.register(tcpRegistration1).await
      locationService.register(tcpRegistration2).await
      enterBarrier("Registration")

      enterBarrier("after-crash")
      Await.ready(system.whenTerminated, 15.seconds)
    }
  }
}
