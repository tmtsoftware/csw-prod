package csw.admin.server.log.http

import akka.Done
import akka.http.scaladsl.server.Route
import csw.admin.server.internal.ActorRuntime
import csw.admin.server.log.LogAdmin

/**
 * Routes supported by Admin server
 *
 * @param logAdmin instance of log admin to which the routes will delegate operations
 * @param actorRuntime actorRuntime provides runtime accessories related to ActorSystem like Materializer, ExecutionContext etc.
 * @param adminExceptionHandlers exception handler which maps server side exceptions to Http Status codes
 */
class AdminRoutes(logAdmin: LogAdmin, actorRuntime: ActorRuntime, adminExceptionHandlers: AdminExceptionHandlers)
    extends HttpSupport {

  import actorRuntime._
  val route: Route = routeLogger {
    adminExceptionHandlers.route {
      path("admin" / "logging" / Segment / "level") { componentName ⇒
        get {
          complete(logAdmin.getLogMetadata(componentName))
        } ~
        post {
          logLevelParam { logLevel ⇒
            complete(logAdmin.setLogLevel(componentName, logLevel).map(_ ⇒ Done))
          }
        }
      }
    }
  }
}
