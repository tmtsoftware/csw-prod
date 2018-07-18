package csw.services.alarm.client.internal.shelve

import csw.services.alarm.api.models.AlarmKey

sealed trait ShelveTimeoutMessage

object ShelveTimeoutMessage {
  case class ScheduleShelveTimeout(key: AlarmKey) extends ShelveTimeoutMessage
  case class CancelShelveTimeout(key: AlarmKey)   extends ShelveTimeoutMessage
  case class ShelveHasTimedOut(key: AlarmKey)     extends ShelveTimeoutMessage
}
