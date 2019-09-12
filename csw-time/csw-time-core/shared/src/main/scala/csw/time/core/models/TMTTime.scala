package csw.time.core.models

import java.time.{Duration, Instant}
import java.util.concurrent.TimeUnit.NANOSECONDS

import csw.time.clock.natives.models.TMTClock.clock
import julienrf.json.derived
import play.api.libs.json._

import scala.concurrent.duration.FiniteDuration

/**
 * Represents an instantaneous point in time. Its a wrapper around `java.time.Instant`and provides nanosecond precision.
 * Supports 2 timescales:
 * - [[UTCTime]] for Coordinated Universal Time (UTC) and
 * - [[TAITime]] for International Atomic Time (TAI)
 */
sealed trait TMTTime extends Product with Serializable {
  def value: Instant

  def durationFromNow: FiniteDuration = {
    val duration = Duration.between(currentInstant, this.value)
    FiniteDuration(duration.toNanos, NANOSECONDS)
  }

  private def currentInstant: Instant = this match {
    case _: UTCTime => UTCTime.now().value
    case _: TAITime => TAITime.now().value
  }
}

object TMTTime {

  implicit val instantFormat: Format[Instant] = new Format[Instant] {
    override def reads(json: JsValue): JsResult[Instant] = JsSuccess(Instant.parse(json.as[String]))
    override def writes(instant: Instant): JsValue       = JsString(instant.toString)
  }

  implicit val tmtTimeFormat: OFormat[TMTTime]      = derived.flat.oformat((__ \ "type").format[String])
  implicit def utcTimeReads[T <: TMTTime]: Reads[T] = tmtTimeFormat.map(_.asInstanceOf[T])

  // Allows UTCTime and TAITime to be sorted
  implicit def orderByInstant[A <: TMTTime]: Ordering[A] = Ordering.by(e => e.value)
}

/**
 * Represents an instantaneous point in time in the UTC scale.
 * Does not contain zone information. To represent this instance in various zones, use [[csw.time.core.TMTTimeHelper]].
 *
 * @param value the underlying `java.time.Instant`
 */
final case class UTCTime(value: Instant) extends TMTTime {

  /**
   * Converts the [[UTCTime]] to [[TAITime]] by adding the UTC-TAI offset.
   * UTC-TAI offset is fetched by doing a native call to `ntp_gettimex`. It ensures to get the latest offset as updated by the PTP Grandmaster.
   *
   * @return TAI time at the given UTC time
   */
  def toTAI: TAITime = TAITime(value.plusSeconds(clock.offset))
}

object UTCTime {

  implicit def utcTimeFormat(implicit instantFormat: Format[Instant]): Format[UTCTime] = new Format[UTCTime] {
    override def writes(time: UTCTime): JsValue          = instantFormat.writes(time.value)
    override def reads(json: JsValue): JsResult[UTCTime] = instantFormat.reads(json).map(UTCTime(_))
  }

  /**
   * Obtains the PTP (Precision Time Protocol) synchronized current UTC time.
   * In case of a Linux machine, this will make a native call `clock_gettime` inorder to get time from the system clock with nanosecond precision.
   * In case of all the other operating systems, nanosecond precision is not supported, hence no native call is made.
   *
   * @return current time in UTC scale
   */
  def now(): UTCTime = UTCTime(clock.utcInstant)

  def after(duration: FiniteDuration): UTCTime = UTCTime(now().value.plusNanos(duration.toNanos))
}

/**
 * Represents an instantaneous point in International Atomic Time (TAI).
 *
 * @param value the underlying `java.time.Instant`
 */
final case class TAITime(value: Instant) extends TMTTime {

  /**
   * Converts the [[TAITime]] to [[UTCTime]] by subtracting the UTC-TAI offset.
   * UTC-TAI offset is fetched by doing a native call to `ntp_gettimex`. It ensures to get the latest offset as updated by the PTP Grandmaster.
   *
   * @return UTC time at the given TAI time
   */
  def toUTC: UTCTime = UTCTime(value.minusSeconds(clock.offset))
}

object TAITime {

  implicit def taiTimeFormat(implicit instantFormat: Format[Instant]): Format[TAITime] = new Format[TAITime] {
    override def writes(time: TAITime): JsValue          = instantFormat.writes(time.value)
    override def reads(json: JsValue): JsResult[TAITime] = instantFormat.reads(json).map(TAITime(_))
  }

  /**
   * Obtains the PTP (Precision Time Protocol) synchronized current time in TAI timescale.
   * In case of a Linux machine, this will make a native call `clock_gettime` inorder to get time from the system clock with nanosecond precision
   * In case of all the other operating systems, nanosecond precision is not supported, hence no native call is made.
   *
   * @return current time in TAI scale
   */
  def now(): TAITime = TAITime(clock.taiInstant)

  def after(duration: FiniteDuration): TAITime = TAITime(now().value.plusNanos(duration.toNanos))

  /**
   * Fetches UTC to TAI offset by doing a native call to `ntp_gettimex` in case of a Linux machine.
   * It ensures to get the latest offset as updated by the PTP Grandmaster.
   *
   * @return offset of UTC to TAI in seconds
   */
  def offset: Int = clock.offset
}
