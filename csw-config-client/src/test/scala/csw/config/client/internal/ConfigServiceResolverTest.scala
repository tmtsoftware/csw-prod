package csw.config.client.internal

import java.net.URI

import csw.config.client.commons.ConfigServiceConnection
import csw.config.client.scaladsl.ConfigClientFactory
import csw.location.api.models.HttpLocation
import csw.location.api.scaladsl.LocationService
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.location.server.http.HTTPLocationService
import org.mockito.Mockito._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class ConfigServiceResolverTest extends HTTPLocationService {
  private val actorRuntime = new ActorRuntime()
  import actorRuntime._

  test("should throw exception if not able to resolve config service http server") {
    val locationService = HttpLocationServiceFactory.makeLocalClient
    val configService   = ConfigClientFactory.adminApi(actorSystem, locationService)

    val exception = intercept[RuntimeException] {
      Await.result(configService.list(), 7.seconds)
    }

    exception.getMessage shouldEqual s"config service connection=${ConfigServiceConnection.value.name} can not be resolved"
  }

  test("should give URI for resolved config service") {

    val mockedLocationService  = mock[LocationService]
    val uri                    = new URI(s"http://config-host:4000")
    val resolvedConfigLocation = Future(Some(HttpLocation(ConfigServiceConnection.value, uri)))
    when(mockedLocationService.resolve(ConfigServiceConnection.value, 5.seconds)).thenReturn(resolvedConfigLocation)

    val configServiceResolver = new ConfigServiceResolver(mockedLocationService, actorRuntime)
    val actualUri             = Await.result(configServiceResolver.uri, 2.seconds)

    actualUri.toString() shouldEqual uri.toString
  }
}
