package csw.location.server.http

import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import csw.commons.http.{JsonRejectionHandler, JsonSupport}
import csw.location.api.exceptions.{OtherLocationIsRegistered, RegistrationFailed}

class LocationExceptionHandler extends Directives with JsonRejectionHandler {

  def route: Directive[Unit] =
    handleExceptions(jsonExceptionHandler) & handleRejections(jsonRejectionHandler) & rejectEmptyResponse

  private val jsonExceptionHandler: ExceptionHandler = ExceptionHandler {
    case ex: RegistrationFailed =>
      complete(JsonSupport.asJsonResponse(StatusCodes.InternalServerError, ex.getMessage))
    case ex: OtherLocationIsRegistered =>
      complete(JsonSupport.asJsonResponse(StatusCodes.BadRequest, ex.getMessage))
    case ex: QueryFilterException =>
      complete(JsonSupport.asJsonResponse(StatusCodes.BadRequest, ex.getMessage))
  }
}
