package csw.event.client.internal.kafka

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.kafka.ProducerSettings
import csw.commons.utils.SocketUtils.getFreePort
import csw.event.api.javadsl.{IEventPublisher, IEventService, IEventSubscriber}
import csw.event.api.scaladsl.{EventPublisher, EventService, EventSubscriber}
import csw.event.client.EventServiceFactory
import csw.event.client.helpers.TestFutureExt.RichFuture
import csw.event.client.internal.wiring.BaseProperties
import csw.event.client.internal.wiring.BaseProperties.createInfra
import csw.event.client.models.EventStores.KafkaStore
import csw.location.api.commons.ClusterAwareSettings
import csw.location.api.scaladsl.LocationService
import csw.location.client.extensions.LocationServiceExt.RichLocationService
import csw.location.server.http.HTTPLocationServiceOnPorts
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.concurrent.Future

class KafkaTestProps(
    kafkaPort: Int,
    locationService: LocationService,
    locationServer: HTTPLocationServiceOnPorts,
    additionalBrokerProps: Map[String, String]
)(implicit val actorSystem: ActorSystem)
    extends BaseProperties {
  private val brokers          = s"PLAINTEXT://${ClusterAwareSettings.hostname}:$kafkaPort"
  private val brokerProperties = Map("listeners" → brokers, "advertised.listeners" → brokers) ++ additionalBrokerProps
  val config                   = EmbeddedKafkaConfig(customBrokerProperties = brokerProperties, zooKeeperPort = getFreePort)

  private val eventServiceFactory = new EventServiceFactory(KafkaStore)
  private lazy val producerSettings: ProducerSettings[String, String] =
    ProducerSettings(actorSystem, new StringSerializer, new StringSerializer)
      .withBootstrapServers(s"${ClusterAwareSettings.hostname}:$kafkaPort")

  private lazy val kafkaProducer = producerSettings.createKafkaProducer()

  val eventService: EventService   = eventServiceFactory.make(locationService)
  val jEventService: IEventService = eventServiceFactory.jMake(locationService.asJava, actorSystem)

  override val publisher: EventPublisher     = eventService.defaultPublisher
  override val subscriber: EventSubscriber   = eventService.defaultSubscriber
  override val jPublisher: IEventPublisher   = jEventService.defaultPublisher
  override val jSubscriber: IEventSubscriber = jEventService.defaultSubscriber

  override def toString: String = "Kafka"

  override val eventPattern: String = ".*sys.*"

  override def publishGarbage(channel: String, message: String): Future[Done] =
    Future { kafkaProducer.send(new ProducerRecord(channel, message)).get() }.map(_ ⇒ Done)

  override def start(): Unit = EmbeddedKafka.start()(config)

  override def shutdown(): Unit = {
    EmbeddedKafka.stop()
    Http(actorSystem).shutdownAllConnectionPools().await
    actorSystem.terminate().await
    locationServer.afterAll()
  }
}

object KafkaTestProps {

  def createKafkaProperties(
      clusterPort: Int = getFreePort,
      httpLocationServicePort: Int = getFreePort,
      serverPort: Int = getFreePort,
      additionalBrokerProps: Map[String, String] = Map.empty
  ): KafkaTestProps = {

    val locationServer = new HTTPLocationServiceOnPorts(clusterPort, httpLocationServicePort)

    val (locationService, system) = createInfra(serverPort, httpLocationServicePort)
    new KafkaTestProps(serverPort, locationService, locationServer, additionalBrokerProps)(system)
  }

  def jCreateKafkaProperties(additionalBrokerProps: java.util.Map[String, String]): KafkaTestProps =
    createKafkaProperties(additionalBrokerProps = additionalBrokerProps.asScala.toMap)

  def jCreateKafkaProperties(): KafkaTestProps = createKafkaProperties()
}
