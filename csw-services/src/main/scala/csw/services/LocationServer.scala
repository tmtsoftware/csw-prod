package csw.services

import akka.http.scaladsl.Http
import csw.location.server.internal.ServerWiring
import csw.location.server.{Main => LocationMain}

object LocationServer {
  def start(clusterPort: String): Option[(Http.ServerBinding, ServerWiring)] =
    Service.start("Location Service", LocationMain.start(Array("--clusterPort", clusterPort)))
}
