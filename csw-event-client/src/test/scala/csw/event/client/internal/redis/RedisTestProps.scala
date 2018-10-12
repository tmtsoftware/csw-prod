package csw.event.client.internal.redis

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import com.typesafe.config.ConfigFactory
import csw.commons.redis.EmbeddedRedis
import csw.commons.utils.SocketUtils.getFreePort
import csw.event.api.javadsl.{IEventPublisher, IEventService, IEventSubscriber}
import csw.event.api.scaladsl._
import csw.event.client.EventServiceFactory
import csw.event.client.helpers.TestFutureExt.RichFuture
import csw.event.client.internal.commons.javawrappers.JEventService
import csw.event.client.internal.wiring.BaseProperties
import csw.event.client.internal.wiring.BaseProperties.createInfra
import csw.event.client.models.EventStores.RedisStore
import csw.location.api.scaladsl.LocationService
import csw.location.server.http.HTTPLocationServiceOnPorts
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.{ClientOptions, RedisClient, RedisURI}
import redis.embedded.{RedisSentinel, RedisServer}

import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.Future

class RedisTestProps(
    name: String,
    sentinelPort: Int,
    serverPort: Int,
    val redisClient: RedisClient,
    locationService: LocationService,
    locationServer: HTTPLocationServiceOnPorts
)(implicit val actorSystem: ActorSystem)
    extends BaseProperties
    with EmbeddedRedis {

  private lazy val masterId = ConfigFactory.load().getString("csw-event.redis.masterId")
  private lazy val redisURI = RedisURI.Builder.sentinel("localhost", sentinelPort, masterId).build()
  private lazy val asyncConnection: Future[RedisAsyncCommands[String, String]] =
    redisClient.connectAsync(new StringCodec(), redisURI).toScala.map(_.async())

  var redisSentinel: RedisSentinel = _
  var redisServer: RedisServer     = _

  override val eventPattern: String = "*"

  private val eventServiceFactory = new EventServiceFactory(RedisStore(redisClient))

  val eventService: EventService       = eventServiceFactory.make(locationService)
  val jEventService: IEventService     = new JEventService(eventService)
  lazy val publisher: EventPublisher   = eventService.defaultPublisher
  lazy val subscriber: EventSubscriber = eventService.defaultSubscriber

  override def toString: String = name

  override lazy val jPublisher: IEventPublisher = jEventService.defaultPublisher

  override lazy val jSubscriber: IEventSubscriber = jEventService.defaultSubscriber

  override def publishGarbage(channel: String, message: String): Future[Done] =
    asyncConnection.flatMap(c ⇒ c.publish(channel, message).toScala.map(_ ⇒ Done))

  override def start(): Unit = {
    val redis = startSentinel(sentinelPort, serverPort, masterId)
    redisSentinel = redis._1
    redisServer = redis._2
  }

  override def shutdown(): Unit = {
    publisher.shutdown().await
    redisClient.shutdown()
    stopSentinel(redisSentinel, redisServer)
    Http(actorSystem).shutdownAllConnectionPools().await
    actorSystem.terminate().await
    locationServer.afterAll()
  }
}

object RedisTestProps extends EmbeddedRedis {
  def createRedisProperties(
      clusterPort: Int = getFreePort,
      httpLocationServicePort: Int = getFreePort,
      sentinelPort: Int = getFreePort,
      serverPort: Int = getFreePort,
      clientOptions: ClientOptions = ClientOptions.create()
  ): RedisTestProps = {

    val locationServer = new HTTPLocationServiceOnPorts(clusterPort, httpLocationServicePort)

    val (locationService, system) = createInfra(sentinelPort, httpLocationServicePort)
    val redisClient: RedisClient  = RedisClient.create()
    redisClient.setOptions(clientOptions)

    new RedisTestProps("Redis", sentinelPort, serverPort, redisClient, locationService, locationServer)(system)
  }

  def jCreateRedisProperties(clientOptions: ClientOptions): RedisTestProps = createRedisProperties(clientOptions = clientOptions)

  def jCreateRedisProperties(): RedisTestProps = createRedisProperties()
}
