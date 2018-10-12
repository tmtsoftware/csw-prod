package csw.admin.server.log

import csw.admin.server.commons.AdminLogger
import csw.logging.internal.LoggingLevels.Level
import csw.logging.models.LogMetadata
import csw.logging.scaladsl.Logger
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import play.api.libs.json._

trait JsonSupport extends PlayJsonSupport {
  protected val log: Logger = AdminLogger.getLogger

  implicit val levelFormat: Format[Level] = new Format[Level] {
    override def writes(obj: Level): JsValue = JsString(obj.name)

    override def reads(json: JsValue): JsResult[Level] = json match {
      case JsString(value) ⇒ JsSuccess(Level(value))
      case _ ⇒
        val runtimeException = new RuntimeException(s"can not parse $json")
        log.error(runtimeException.getMessage, ex = runtimeException)
        throw runtimeException
    }
  }

  implicit val logMetadataFormat: Format[LogMetadata] = Json.format[LogMetadata]
}
