package csw.services.alarm.api.models

import enumeratum.EnumEntry.Lowercase
import enumeratum.{Enum, EnumEntry}

import scala.collection.immutable.IndexedSeq

sealed abstract class FullAlarmSeverity private[alarm] (val level: Int) extends EnumEntry with Lowercase {

  /**
   * The name of SeverityLevels e.g. for Major severity level, the name will be represented as `major`
   */
  def name: String = entryName

  def >(otherSeverity: FullAlarmSeverity): Boolean = this.level > otherSeverity.level

  def max(otherSeverity: FullAlarmSeverity): FullAlarmSeverity = if (otherSeverity > this) otherSeverity else this

  def isHighRisk: Boolean = this.level > 0
}

object FullAlarmSeverity extends Enum[FullAlarmSeverity] {

  /**
   * Returns a sequence of all alarm severity
   */
  def values: IndexedSeq[FullAlarmSeverity] = findValues ++ AlarmSeverity.values

  case object Disconnected extends FullAlarmSeverity(4)
}

sealed abstract class AlarmSeverity private[alarm] (override val level: Int) extends FullAlarmSeverity(level)

object AlarmSeverity extends Enum[AlarmSeverity] {

  /**
   * Returns a sequence of all alarm severity
   */
  def values: IndexedSeq[AlarmSeverity] = findValues

  case object Okay          extends AlarmSeverity(0)
  case object Warning       extends AlarmSeverity(1)
  case object Major         extends AlarmSeverity(2)
  case object Indeterminate extends AlarmSeverity(3)
  case object Critical      extends AlarmSeverity(5)
}