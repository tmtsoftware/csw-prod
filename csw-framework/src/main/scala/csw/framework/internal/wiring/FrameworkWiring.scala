package csw.framework.internal.wiring

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import csw.alarm.client.AlarmServiceFactory
import csw.command.internal.CommandResponseManagerFactory
import csw.config.api.scaladsl.ConfigClientService
import csw.config.client.scaladsl.ConfigClientFactory
import csw.event.client.EventServiceFactory
import csw.event.client.models.EventStores.RedisStore
import csw.framework.deploy.ConfigUtils
import csw.framework.scaladsl.RegistrationFactory
import csw.location.api.scaladsl.LocationService
import csw.location.client.ActorSystemFactory
import csw.location.client.scaladsl.HttpLocationServiceFactory
import io.lettuce.core.RedisClient

import scala.concurrent.{ExecutionContext, Future}

/**
 * Represents a class that lazily initializes necessary instances to run a component(s)
 */
class FrameworkWiring {
  lazy val actorSystem: ActorSystem                 = ActorSystemFactory.remote()
  lazy val actorRuntime: ActorRuntime               = new ActorRuntime(actorSystem)
  lazy val locationService: LocationService         = HttpLocationServiceFactory.makeLocalClient(actorSystem, actorRuntime.mat)
  lazy val registrationFactory                      = new RegistrationFactory
  lazy val commandResponseManagerFactory            = new CommandResponseManagerFactory
  lazy val configClientService: ConfigClientService = ConfigClientFactory.clientApi(actorSystem, locationService)
  lazy val configUtils: ConfigUtils                 = new ConfigUtils(configClientService, actorRuntime)
  lazy val eventServiceFactory: EventServiceFactory = new EventServiceFactory(RedisStore(redisClient))
  lazy val alarmServiceFactory: AlarmServiceFactory = new AlarmServiceFactory(redisClient)

  lazy val redisClient: RedisClient = {
    val client = RedisClient.create()
    shutdownRedisOnTermination(client)
    client
  }

  private def shutdownRedisOnTermination(client: RedisClient): Unit = {
    implicit val ec: ExecutionContext = actorSystem.dispatcher

    actorRuntime.coordinatedShutdown.addTask(
      CoordinatedShutdown.PhaseBeforeServiceUnbind,
      "redis-client-shutdown"
    )(() => Future { client.shutdown(); Done })
  }
}

/**
 * Represents the factory to make framework wiring
 */
object FrameworkWiring {

  /**
   * Makes a FrameworkWiring with the given actor system
   *
   * @param _actorSystem used to initialize other necessary instances like locationService
   * @return a FrameworkWiring containing instances to run a component(s)
   */
  def make(_actorSystem: ActorSystem): FrameworkWiring = new FrameworkWiring {
    override lazy val actorSystem: ActorSystem = _actorSystem
  }

  def make(_actorSystem: ActorSystem, _redisClient: RedisClient): FrameworkWiring = new FrameworkWiring {
    override lazy val actorSystem: ActorSystem = _actorSystem
    override lazy val redisClient: RedisClient = _redisClient
  }

  /**
   * Makes a FrameworkWiring with the given actor system
   *
   * @param _actorSystem used to initialize other necessary instances like locationService
   * @param _locationService used to initialize other necessary instances like configuration service client
   * @return a FrameworkWiring containing instances to run a component(s)
   */
  def make(_actorSystem: ActorSystem, _locationService: LocationService): FrameworkWiring = new FrameworkWiring {
    override lazy val actorSystem: ActorSystem         = _actorSystem
    override lazy val locationService: LocationService = _locationService
  }

  def make(_actorSystem: ActorSystem, _locationService: LocationService, _redisClient: RedisClient): FrameworkWiring =
    new FrameworkWiring {
      override lazy val actorSystem: ActorSystem         = _actorSystem
      override lazy val locationService: LocationService = _locationService
      override lazy val redisClient: RedisClient         = _redisClient
    }
}
