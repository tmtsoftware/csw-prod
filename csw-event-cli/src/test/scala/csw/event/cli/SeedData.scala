package csw.event.cli

import akka.actor.ActorSystem
import akka.actor.CoordinatedShutdown.UnknownReason
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import csw.commons.redis.EmbeddedRedis
import csw.event.api.scaladsl.EventPublisher
import csw.event.cli.args.ArgsParser
import csw.event.cli.wiring.Wiring
import csw.event.client.helpers.TestFutureExt.RichFuture
import csw.event.client.internal.commons.EventServiceConnection
import csw.location.api.models.TcpRegistration
import csw.location.api.scaladsl.LocationService
import csw.location.client.ActorSystemFactory
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.location.server.http.HTTPLocationService
import csw.params.core.formats.JsonSupport
import csw.params.events._
import org.scalatest.{BeforeAndAfterEach, Matchers}
import play.api.libs.json.Json
import redis.embedded.{RedisSentinel, RedisServer}

import scala.collection.mutable
import scala.io.Source

trait SeedData extends HTTPLocationService with Matchers with BeforeAndAfterEach with EmbeddedRedis {

  implicit val system: ActorSystem    = ActorSystemFactory.remote()
  implicit val mat: ActorMaterializer = ActorMaterializer()

  val argsParser                        = new ArgsParser("csw-event-cli")
  val logBuffer: mutable.Buffer[String] = mutable.Buffer.empty[String]

  val (localHttpClient: LocationService, redisSentinel: RedisSentinel, redisServer: RedisServer) =
    withSentinel(masterId = ConfigFactory.load().getString("csw-event.redis.masterId")) { (sentinelPort, _) ⇒
      val localHttpClient: LocationService = HttpLocationServiceFactory.makeLocalClient
      localHttpClient
        .register(TcpRegistration(EventServiceConnection.value, sentinelPort))
        .await
      localHttpClient
    }

  private def printLine(msg: Any): Unit = logBuffer += msg.toString

  val cliWiring: Wiring = Wiring.make(system, localHttpClient, printLine)

  val (event1: SystemEvent, event2: ObserveEvent) = seedEvents()

  override protected def afterEach(): Unit = {
    super.afterEach()
    logBuffer.clear()
  }

  override def afterAll(): Unit = {
    stopSentinel(redisSentinel, redisServer)
    cliWiring.actorRuntime.shutdown(UnknownReason).await
    super.afterAll()
  }

  def seedEvents(): (SystemEvent, ObserveEvent) = {
    val event1Str = Source.fromResource("seedData/event1.json").mkString
    val event2Str = Source.fromResource("seedData/event2.json").mkString

    val e1 = JsonSupport.readEvent[SystemEvent](Json.parse(event1Str))
    val e2 = JsonSupport.readEvent[ObserveEvent](Json.parse(event2Str))

    val publisher: EventPublisher = cliWiring.eventService.defaultPublisher
    publisher.publish(e1).await
    publisher.publish(e2).await

    (e1, e2)
  }
}
