package csw.event.client.perf.wiring

import akka.actor.typed.{ActorSystem, SpawnProtocol}
import csw.event.api.scaladsl.{EventPublisher, EventSubscriber}
import csw.event.client.EventServiceFactory
import csw.event.client.models.EventStores.{KafkaStore, RedisStore}
import org.mockito.MockitoSugar

import scala.concurrent.ExecutionContext

class TestWiring(val actorSystem: ActorSystem[SpawnProtocol.Command]) extends MockitoSugar {
  lazy val testConfigs: TestConfigs = new TestConfigs(actorSystem.settings.config)
  import testConfigs._

  implicit lazy val ec: ExecutionContext = actorSystem.executionContext

  private lazy val redisEventService = new EventServiceFactory(RedisStore()).make(redisHost, redisPort)(actorSystem)
  private lazy val kafkaEventService = new EventServiceFactory(KafkaStore).make(kafkaHost, kafkaPort)(actorSystem)

  def publisher: EventPublisher =
    if (redisEnabled) redisEventService.makeNewPublisher() else kafkaEventService.makeNewPublisher()

  def subscriber: EventSubscriber =
    if (redisEnabled) redisEventService.defaultSubscriber else kafkaEventService.defaultSubscriber

}
