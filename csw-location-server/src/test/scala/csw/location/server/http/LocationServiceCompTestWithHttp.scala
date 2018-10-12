package csw.location.server.http

import akka.actor.CoordinatedShutdown.UnknownReason
import csw.location.server.commons.TestFutureExtension.RichFuture
import csw.location.server.internal.AdminWiring
import csw.location.server.scaladsl.LocationServiceCompTest

// DEOPSCSW-429: [SPIKE] Provide HTTP server and client for location service
class LocationServiceCompTestWithHttp extends LocationServiceCompTest("http") {

  private val wiring = new AdminWiring
  wiring.locationHttpService.start().await

  override protected def afterAll(): Unit = {
    wiring.actorRuntime.shutdown(UnknownReason).await
    super.afterAll()
  }
}
