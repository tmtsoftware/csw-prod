package csw.alarm.cli

import akka.actor.ActorSystem
import akka.actor.CoordinatedShutdown.UnknownReason
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import csw.alarm.cli.args.ArgsParser
import csw.alarm.cli.utils.TestFutureExt.RichFuture
import csw.alarm.cli.wiring.Wiring
import csw.alarm.client.internal.commons.AlarmServiceConnection
import csw.commons.redis.EmbeddedRedis
import csw.location.api.models.TcpRegistration
import csw.location.api.scaladsl.LocationService
import csw.location.client.ActorSystemFactory
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.location.server.http.HTTPLocationService
import csw.services.BuildInfo
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.SpanSugar.convertFloatToGrainOfTime
import org.scalatest.{BeforeAndAfterEach, Matchers}
import redis.embedded.{RedisSentinel, RedisServer}

import scala.collection.mutable

trait AlarmCliTestSetup
    extends HTTPLocationService
    with Matchers
    with BeforeAndAfterEach
    with EmbeddedRedis
    with ScalaFutures
    with Eventually {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 10.millis)

  implicit val system: ActorSystem    = ActorSystemFactory.remote()
  implicit val mat: ActorMaterializer = ActorMaterializer()

  val argsParser                        = new ArgsParser(BuildInfo.name)
  val logBuffer: mutable.Buffer[String] = mutable.Buffer.empty[String]

  val (localHttpClient: LocationService, redisSentinel: RedisSentinel, redisServer: RedisServer) =
    withSentinel(masterId = ConfigFactory.load().getString("csw-alarm.redis.masterId")) { (sentinelPort, _) ⇒
      val localHttpClient: LocationService = HttpLocationServiceFactory.makeLocalClient
      localHttpClient
        .register(TcpRegistration(AlarmServiceConnection.value, sentinelPort))
        .await
      localHttpClient
    }

  private def printLine(msg: Any): Unit = logBuffer += msg.toString

  val cliWiring: Wiring = Wiring.make(system, localHttpClient, printLine)

  override protected def afterEach(): Unit = {
    super.afterEach()
    logBuffer.clear()
  }

  override def afterAll(): Unit = {
    stopSentinel(redisSentinel, redisServer)
    cliWiring.actorRuntime.shutdown(UnknownReason).await
    super.afterAll()
  }
}
