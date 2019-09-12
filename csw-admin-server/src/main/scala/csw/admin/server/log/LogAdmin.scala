package csw.admin.server.log

import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import csw.admin.server.commons.AdminLogger
import csw.admin.server.log.exceptions._
import csw.admin.server.wiring.ActorRuntime
import csw.command.client.extensions.AkkaLocationExt.RichAkkaLocation
import csw.command.client.messages.{GetComponentLogMetadata, SetComponentLogLevel}
import csw.location.api.scaladsl.LocationService
import csw.location.models.ComponentType.Sequencer
import csw.location.models.Connection.{AkkaConnection, HttpConnection, TcpConnection}
import csw.location.models.{AkkaLocation, Connection, Location}
import csw.logging.api.scaladsl.Logger
import csw.logging.models.{Level, LogMetadata}

import scala.async.Async._
import scala.concurrent.Future
import scala.concurrent.duration.DurationDouble

/**
 * Utility to resolve and get the reference of supervisor actor for the component using location service
 */
class LogAdmin(locationService: LocationService, actorRuntime: ActorRuntime) {
  private val log: Logger = AdminLogger.getLogger
  import actorRuntime._

  def getLogMetadata(componentFullName: String): Future[LogMetadata] = async {
    implicit val timeout: Timeout = Timeout(5.seconds)
    await(getLocation(componentFullName)) match {
      case Some(location: AkkaLocation) =>
        val componentName = location.connection.componentId.name
        log.info(
          "Getting log information from logging system",
          Map(
            "componentName" -> componentFullName,
            "location"      -> location.toString
          )
        )

        val metadataF: Future[LogMetadata] = location.connection.componentId.componentType match {
          case Sequencer => location.sequencerRef ? (GetComponentLogMetadata(componentName, _))
          case _         => location.componentRef ? (GetComponentLogMetadata(componentName, _))
        }

        await(metadataF)
      case _ => throw UnresolvedAkkaLocationException(componentFullName)
    }
  }

  def setLogLevel(componentFullName: String, logLevel: Level): Future[Unit] =
    async {
      await(getLocation(componentFullName)) match {
        case Some(location: AkkaLocation) =>
          val componentName = location.connection.componentId.name
          log.info(
            s"Setting log level to $logLevel",
            Map(
              "componentName" -> componentFullName,
              "location"      -> location.toString
            )
          )
          location.connection.componentId.componentType match {
            case Sequencer => location.sequencerRef ! SetComponentLogLevel(componentName, logLevel)
            case _         => location.componentRef ! SetComponentLogLevel(componentName, logLevel)
          }
        case _ => throw UnresolvedAkkaLocationException(componentFullName)
      }
    }

  private def getLocation(componentFullName: String): Future[Option[Location]] =
    async {
      Connection.from(componentFullName) match {
        case connection: AkkaConnection => await(locationService.find(connection))
        case connection: HttpConnection => throw UnsupportedConnectionException(connection)
        case connection: TcpConnection  => throw UnsupportedConnectionException(connection)
        case _                          => throw InvalidComponentNameException(componentFullName)
      }
    }
}
