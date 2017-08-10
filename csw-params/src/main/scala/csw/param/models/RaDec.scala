package csw.param.models

import csw.param.ParamSerializable
import spray.json.RootJsonFormat

case class RaDec(ra: Double, dec: Double) extends ParamSerializable

case object RaDec {
  import spray.json.DefaultJsonProtocol._

  implicit val raDecFormat: RootJsonFormat[RaDec] = jsonFormat2(RaDec.apply)
}
