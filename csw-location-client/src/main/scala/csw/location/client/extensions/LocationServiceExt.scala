package csw.location.client.extensions

import csw.location.api.javadsl.ILocationService
import csw.location.api.scaladsl.LocationService
import csw.location.client.internal.JLocationServiceImpl

import scala.concurrent.ExecutionContext

object LocationServiceExt {

  implicit class RichLocationService(val locationService: LocationService) {

    /**
     * Returns the Java API for this instance of location service
     */
    def asJava(implicit ec: ExecutionContext): ILocationService = new JLocationServiceImpl(locationService)
  }

}
