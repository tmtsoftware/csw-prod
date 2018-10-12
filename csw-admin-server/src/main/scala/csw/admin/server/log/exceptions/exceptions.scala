package csw.admin.server.log.exceptions

import csw.location.api.models.Connection

case class InvalidComponentNameException(componentName: String)
    extends RuntimeException(s"$componentName is not a valid component name")

case class UnsupportedConnectionException(connection: Connection) extends RuntimeException(s"$connection is not supported")

case class UnresolvedAkkaLocationException(componentName: String)
    extends RuntimeException(s"Could not resolve $componentName to a valid Akka location")
