package csw.admin.server.log.http

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Directive, Directives, ExceptionHandler}
import csw.admin.server.commons.AdminLogger
import csw.admin.server.log.exceptions.{UnresolvedAkkaLocationException, UnsupportedConnectionException}
import csw.commons.http.{JsonRejectionHandler, JsonSupport}
import csw.logging.scaladsl.Logger
import play.api.libs.json.{Json, OFormat}

import scala.util.control.NonFatal

// Two classes are used just to wrap status code and error message inside "error" key in json representation
case class ErrorResponse(error: ErrorMessage)
case object ErrorResponse {
  implicit val errorResponseFormat: OFormat[ErrorResponse] = Json.format[ErrorResponse]
}

case class ErrorMessage(code: Int, message: String)
case object ErrorMessage {
  implicit val errorMessageFormat: OFormat[ErrorMessage] = Json.format[ErrorMessage]
}

/**
 * Maps server side exceptions to Http Status codes
 */
class AdminExceptionHandlers extends Directives with JsonRejectionHandler {
  private val log: Logger = AdminLogger.getLogger

  def route: Directive[Unit] = handleExceptions(jsonExceptionHandler) & handleRejections(jsonRejectionHandler)

  private val jsonExceptionHandler: ExceptionHandler = ExceptionHandler {
    case ex: UnresolvedAkkaLocationException ⇒
      log.error(ex.getMessage, ex = ex)
      complete(JsonSupport.asJsonResponse(StatusCodes.NotFound, ex.getMessage))
    case ex: UnsupportedConnectionException ⇒
      log.error(ex.getMessage, ex = ex)
      complete(JsonSupport.asJsonResponse(StatusCodes.BadRequest, ex.getMessage))
    case NonFatal(ex) ⇒
      log.error(ex.getMessage, ex = ex)
      complete(JsonSupport.asJsonResponse(StatusCodes.InternalServerError, ex.getMessage))
  }

}
