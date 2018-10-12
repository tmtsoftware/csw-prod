package csw.location.models

import java.net.URI

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter.{TypedActorRefOps, UntypedActorSystemOps}
import akka.actor.{ActorPath, ActorSystem}
import akka.serialization.Serialization
import com.typesafe.config.{Config, ConfigFactory}
import csw.location.api.exceptions.LocalAkkaActorRegistrationNotAllowed
import csw.location.api.internal.Networks
import csw.location.api.models.Connection.{AkkaConnection, HttpConnection, TcpConnection}
import csw.location.api.models._
import csw.location.client.ActorSystemFactory
import csw.params.core.models.Prefix
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FunSuite, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration.DurationDouble

class RegistrationTest extends FunSuite with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  implicit val actorSystem: ActorSystem = ActorSystemFactory.remote()

  test("should able to create the AkkaRegistration which should internally create AkkaLocation") {
    val hostname = new Networks().hostname()

    val akkaConnection = AkkaConnection(ComponentId("hcd1", ComponentType.HCD))
    val actorRef       = actorSystem.spawn(Behavior.empty, "my-actor-1")
    val actorPath      = ActorPath.fromString(Serialization.serializedActorPath(actorRef.toUntyped))
    val akkaUri        = new URI(actorPath.toString)
    val prefix         = Prefix("nfiraos.ncc.trombone")

    val akkaRegistration = AkkaRegistration(akkaConnection, prefix, actorRef)

    val expectedAkkaLocation = AkkaLocation(akkaConnection, prefix, akkaUri, actorRef)

    akkaRegistration.location(hostname) shouldBe expectedAkkaLocation
  }

  test("should able to create the HttpRegistration which should internally create HttpLocation") {
    val hostname = new Networks().hostname()
    val port     = 9595
    val prefix   = "/trombone/hcd"

    val httpConnection   = HttpConnection(ComponentId("trombone", ComponentType.HCD))
    val httpRegistration = HttpRegistration(httpConnection, port, prefix)

    val expectedhttpLocation = HttpLocation(httpConnection, new URI(s"http://$hostname:$port/$prefix"))

    httpRegistration.location(hostname) shouldBe expectedhttpLocation
  }

  test("should able to create the TcpRegistration which should internally create TcpLocation") {
    val hostname = new Networks().hostname()
    val port     = 9596

    val tcpConnection   = TcpConnection(ComponentId("lgsTrombone", ComponentType.HCD))
    val tcpRegistration = TcpRegistration(tcpConnection, port)

    val expectedTcpLocation = TcpLocation(tcpConnection, new URI(s"tcp://$hostname:$port"))

    tcpRegistration.location(hostname) shouldBe expectedTcpLocation
  }

  test("should not allow AkkaRegistration using local ActorRef") {
    val config: Config = ConfigFactory.parseString("""
        akka.actor.provider = local
      """)

    implicit val actorSystem: ActorSystem = ActorSystem("local-actor-system", config)
    val actorRef                          = actorSystem.spawn(Behavior.empty, "my-actor-2")
    val akkaConnection                    = AkkaConnection(ComponentId("hcd1", ComponentType.HCD))
    val prefix                            = Prefix("nfiraos.ncc.trombone")

    intercept[LocalAkkaActorRegistrationNotAllowed] {
      AkkaRegistration(akkaConnection, prefix, actorRef)
    }
    Await.result(actorSystem.terminate, 10.seconds)
  }

  override protected def afterAll(): Unit = {
    Await.result(actorSystem.terminate, 10.seconds)
    super.afterAll()
  }
}
