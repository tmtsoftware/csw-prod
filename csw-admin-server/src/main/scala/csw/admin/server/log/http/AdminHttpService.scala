package csw.admin.server.log.http

import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import csw.admin.server.commons.AdminLogger
import csw.admin.server.commons.CoordinatedShutdownReasons.FailureReason
import csw.admin.server.internal.{ActorRuntime, Settings}
import csw.location.api.commons.ClusterAwareSettings
import csw.logging.scaladsl.Logger

import scala.async.Async._
import scala.concurrent.Future
import scala.util.control.NonFatal

/**
 * Start AdminServer on a given port which is responsible for handling log level change/get requests
 */
class AdminHttpService(adminRoutes: AdminRoutes, actorRuntime: ActorRuntime, settings: Settings) {
  private val log: Logger = AdminLogger.getLogger

  import actorRuntime._

  lazy val registeredLazyBinding: Future[ServerBinding] = async {
    val binding = await(bind())
    log.info(s"Server online at http://${binding.localAddress.getHostName}:${binding.localAddress.getPort}/")
    binding
  } recoverWith {
    case NonFatal(ex) ⇒
      log.error("can not start admin http server", ex = ex)
      shutdown(FailureReason(ex)).map(_ ⇒ throw ex)
  }

  private def bind() = Http().bindAndHandle(
    handler = adminRoutes.route,
    interface = ClusterAwareSettings.hostname,
    port = settings.adminPort
  )
}
