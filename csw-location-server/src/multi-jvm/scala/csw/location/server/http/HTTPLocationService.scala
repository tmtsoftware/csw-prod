package csw.location.server.http

import akka.actor.CoordinatedShutdown.UnknownReason
import akka.http.scaladsl.Http
import csw.location.server.commons.TestFutureExtension.RichFuture
import csw.location.server.internal.AdminWiring
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FunSuiteLike, Matchers}

import scala.util.{Failure, Success, Try}

trait HTTPLocationService
    extends FunSuiteLike
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ScalaFutures
    with MockitoSugar {

  private var maybeWiring: Option[AdminWiring]         = None
  private var maybeBinding: Option[Http.ServerBinding] = None

  def start(clusterPort: Option[Int], httpPort: Option[Int] = None): Unit =
    Try {
      val adminWiring = AdminWiring.make(clusterPort, httpPort)
      (adminWiring, adminWiring.locationHttpService.start().futureValue)
    } match {
      case Success((adminWiring, serverBinding)) ⇒ maybeWiring = Some(adminWiring); maybeBinding = Some(serverBinding)
      case Failure(_)                            ⇒
    }

  override def afterAll(): Unit = maybeWiring.map(_.actorRuntime.shutdown(UnknownReason).await)

  start(Some(3553))
}

class JHTTPLocationService extends HTTPLocationService

class HTTPLocationServiceOnPorts(clusterPort: Int, httpPort: Int) extends HTTPLocationService {
  start(Some(clusterPort), Some(httpPort))
}
