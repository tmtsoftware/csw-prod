package csw.admin.server.commons

import akka.actor.CoordinatedShutdown

object CoordinatedShutdownReasons {
  case class FailureReason(ex: Throwable) extends CoordinatedShutdown.Reason
}
