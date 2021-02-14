package csw.framework.internal.supervisor

import akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Route
import csw.command.client.messages.SupervisorMessage
import csw.command.client.models.framework.{ComponentInfo, LocationServiceUsage}
import csw.framework.models.CswContext
import csw.framework.scaladsl.RegistrationFactory
import csw.location.api.models.{ComponentId, ConnectionType, HttpRegistration}
import csw.location.api.models.Connection.{AkkaConnection, HttpConnection}
import csw.location.api.models.ConnectionType.{AkkaType, HttpType}
import csw.network.utils.Networks

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

private[framework] object SupervisorLocationHelper {

  sealed trait SupervisorLocationMessage extends akka.actor.NoSerializationVerificationNeeded

  case class Register(
      componentInfo: ComponentInfo,
      svr: ActorRef[SupervisorMessage],
      replyTo: ActorRef[SupervisorRegisterResponse]
  ) extends SupervisorLocationMessage

  case class AkkaRegister(
      componentInfo: ComponentInfo,
      svr: ActorRef[SupervisorMessage],
      replyTo: ActorRef[SupervisorRegisterResponse]
  ) extends SupervisorLocationMessage

  case class HttpRegister(componentInfo: ComponentInfo, binding: ServerBinding, replyTo: ActorRef[SupervisorRegisterResponse])
      extends SupervisorLocationMessage
      with akka.actor.NoSerializationVerificationNeeded

  case class HttpBindRegister(
      componentInfo: ComponentInfo,
      svr: ActorRef[SupervisorMessage],
      replyTo: ActorRef[SupervisorRegisterResponse]
  ) extends SupervisorLocationMessage

  private case class DoNotRegister(
      componentInfo: ComponentInfo,
      connectionType: ConnectionType,
      replyTo: ActorRef[SupervisorRegisterResponse]
  ) extends SupervisorLocationMessage

  private case class BindFailed(componentInfo: ComponentInfo, replyTo: ActorRef[SupervisorRegisterResponse])
      extends SupervisorLocationMessage

  sealed trait SupervisorRegisterResponse
  case class AkkaRegisterSuccess(componentInfo: ComponentInfo)                                extends SupervisorRegisterResponse
  case class HttpRegisterSuccess(componentInfo: ComponentInfo, binding: ServerBinding)        extends SupervisorRegisterResponse
  case class RegistrationFailed(componentInfo: ComponentInfo, connectionType: ConnectionType) extends SupervisorRegisterResponse
  case class RegistrationNotRequired(componentInfo: ComponentInfo, connectionType: ConnectionType)
      extends SupervisorRegisterResponse

  case class Unregister(componentInfo: ComponentInfo, replyTo: ActorRef[SupervisorUnregisterResponse])
      extends SupervisorLocationMessage
  case class AkkaUnregister(componentInfo: ComponentInfo, replyTo: ActorRef[SupervisorUnregisterResponse])
      extends SupervisorLocationMessage
  case class HttpUnregister(componentInfo: ComponentInfo, replyTo: ActorRef[SupervisorUnregisterResponse])
      extends SupervisorLocationMessage

  sealed trait SupervisorUnregisterResponse
  case class AkkaUnregisterSuccess(componentId: ComponentId)                                extends SupervisorUnregisterResponse
  case class HttpUnregisterSuccess(componentId: ComponentId)                                extends SupervisorUnregisterResponse
  case class UnregisterFailed(componentInfo: ComponentInfo, connectionType: ConnectionType) extends SupervisorUnregisterResponse

  //noinspection ScalaStyle
  def apply(registrationFactory: RegistrationFactory, cswCtx: CswContext): Behavior[SupervisorLocationMessage] = {

    val locationService = cswCtx.locationService
    val log             = cswCtx.loggerFactory.getLogger

    Behaviors.setup { ctx =>
      import ctx.executionContext

      //noinspection ScalaStyle
      Behaviors
        .receiveMessage[SupervisorLocationMessage] {
          case Register(componentInfo, svr, replyTo) =>
            if (componentInfo.registerAs.contains(AkkaType)) {
              ctx.self ! AkkaRegister(componentInfo, svr, replyTo)
            }
            if (componentInfo.registerAs.contains(HttpType)) {
              ctx.self ! HttpBindRegister(componentInfo, svr, replyTo)
            }
            Behaviors.same

          case AkkaRegister(componentInfo, svr, replyTo) =>
            if (componentInfo.locationServiceUsage == LocationServiceUsage.DoNotRegister)
              ctx.self ! DoNotRegister(componentInfo, AkkaType, replyTo)
            else {
              val akkaConnection: AkkaConnection =
                AkkaConnection(ComponentId(componentInfo.prefix, componentInfo.componentType))
              val akkaRegistration = registrationFactory.akkaTyped(akkaConnection, svr)
              locationService.register(akkaRegistration).onComplete {
                case Success(_) =>
                  log.info(s"Component: ${componentInfo.prefix} successfully registered for Akka")
                  replyTo ! AkkaRegisterSuccess(componentInfo)
                case Failure(exception) =>
                  log.error(s"Akka register failed for component: ${componentInfo.prefix} with exception: $exception")
                  replyTo ! RegistrationFailed(componentInfo, ConnectionType.AkkaType)
              }
            }
            Behaviors.same

          case HttpRegister(componentInfo, binding, replyTo) =>
            if (componentInfo.locationServiceUsage == LocationServiceUsage.DoNotRegister)
              ctx.self ! DoNotRegister(componentInfo, HttpType, replyTo)
            else {
              val httpConnection: HttpConnection = HttpConnection(ComponentId(componentInfo.prefix, componentInfo.componentType))
              val port                           = binding.localAddress.getPort
              val registration                   = HttpRegistration(httpConnection, port, path = "")
              //log.info(s"Registering HTTP component with Location Service using registration: [${registration.toString}]")
              locationService.register(registration).onComplete {
                case Success(_) =>
                  log.info(s"Server online at http://${binding.localAddress.getHostName}:$port")
                  replyTo ! HttpRegisterSuccess(componentInfo, binding)
                case Failure(exception) =>
                  log.error(s"HTTP register failed for component: ${componentInfo.prefix} with exception: $exception")
                  replyTo ! RegistrationFailed(componentInfo, ConnectionType.HttpType)
              }
            }
            Behaviors.same

          case HttpBindRegister(componentInfo, svr, replyTo) =>
            implicit val system: ActorSystem[Nothing] = ctx.system

            val route: Route = CommandServiceRoutesFactory.createRoutes(svr)(ctx.system)
            val binding = {
              Http()
                .newServerAt(Networks().hostname, 0)
                .bind(route)
                .map(_.addToCoordinatedShutdown(hardTerminationDeadline = 5.seconds))
            }
            ctx.pipeToSelf(binding) {
              // map the future value to a message handled by this actor
              case Success(binding) =>
                HttpRegister(componentInfo, binding, replyTo)
              case Failure(exception) =>
                log.error(s"Bind failed for component: ${componentInfo.prefix} with exception: $exception")
                BindFailed(componentInfo, replyTo)
            }
            Behaviors.same

          case BindFailed(componentInfo, replyTo) =>
            replyTo ! RegistrationFailed(componentInfo, ConnectionType.HttpType)
            Behaviors.same

          case DoNotRegister(componentInfo, connectionType, replyTo) =>
            println(s"Registration Not required for: $componentInfo and $connectionType")
            replyTo ! RegistrationNotRequired(componentInfo, connectionType)
            Behaviors.same

          case Unregister(componentInfo, replyTo) =>
            if (componentInfo.registerAs.contains(AkkaType)) {
              ctx.self ! AkkaUnregister(componentInfo, replyTo)
            }
            if (componentInfo.registerAs.contains(HttpType)) {
              ctx.self ! HttpUnregister(componentInfo, replyTo)
            }
            Behaviors.same

          case AkkaUnregister(componentInfo, replyTo) =>
            val akkaConnection: AkkaConnection = AkkaConnection(ComponentId(componentInfo.prefix, componentInfo.componentType))
            locationService.unregister(akkaConnection).onComplete {
              case Success(_) =>
                replyTo ! AkkaUnregisterSuccess(akkaConnection.componentId)
              case Failure(exception) =>
                log.error(s"Akka Unregister failed for component: ${componentInfo.prefix} with exception: $exception")
                replyTo ! UnregisterFailed(componentInfo, AkkaType)
            }
            Behaviors.same

          case HttpUnregister(componentInfo, replyTo) =>
            val httpConnection: HttpConnection = HttpConnection(ComponentId(componentInfo.prefix, componentInfo.componentType))
            locationService.unregister(httpConnection).onComplete {
              case Success(_) =>
                replyTo ! HttpUnregisterSuccess(httpConnection.componentId)
              case Failure(exception) =>
                log.error(s"HTTP Unregister failed for component: ${componentInfo.prefix} with exception: $exception")
                replyTo ! UnregisterFailed(componentInfo, HttpType)
            }
            Behaviors.same
        }
        .receiveSignal {
          case (_: ActorContext[SupervisorLocationMessage], PostStop) =>
            println(s"PostStop signal for location Helper received")
            Behaviors.same
        }

    }
  }
  /*
  def unregisterAndStopEmbeddedServer(locationService: LocationService, akkaConnection: AkkaConnection): Unit /*Future[Done]*/ = {
    locationService.unregister(akkaConnection)
  }
   */
}
