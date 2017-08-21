package csw.common.framework.models

import csw.services.location.models.{ComponentType, Connection}
import spray.json.JsonFormat

import scala.collection.JavaConverters._

/**
 * The information needed to create a component
 */
final case class ComponentInfo(name: String,
                               componentType: ComponentType,
                               prefix: String,
                               className: String,
                               connections: Set[Connection] = Set.empty) {

  /**
   * Java API to get the list of connections for the assembly
   */
  def getConnections: java.util.List[Connection] = connections.toList.asJava

}

case object ComponentInfo {
  import csw.services.location.internal.JsonSupport._
  implicit val componentInfoFormat: JsonFormat[ComponentInfo] = jsonFormat5(ComponentInfo.apply)
}

final case class ContainerInfo(name: String, components: Set[ComponentInfo])

case object ContainerInfo {
  import spray.json.DefaultJsonProtocol._
  implicit val format: JsonFormat[ContainerInfo] = jsonFormat2(ContainerInfo.apply)
}

trait Component {
  def info: ComponentInfo
}

trait Assembly extends Component

trait Hcd extends Component

trait Container extends Component
