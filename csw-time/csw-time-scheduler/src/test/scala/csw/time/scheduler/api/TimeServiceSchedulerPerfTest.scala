package csw.time.scheduler.api

import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}

import akka.actor.testkit.typed.scaladsl
import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import csw.time.core.models.UTCTime
import csw.time.scheduler.TimeServiceSchedulerFactory
import org.HdrHistogram.Histogram
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class TimeServiceSchedulerPerfTest extends ScalaTestWithActorTestKit with FunSuiteLike with BeforeAndAfterAll {

  private val sys                                         = ActorSystem(Behaviors.empty, "test")
  private implicit val executionContext: ExecutionContext = sys.executionContext
  private val timeService                                 = new TimeServiceSchedulerFactory().make()

  for (scenario <- TestSettings.all) {
    import scenario._
    ignore(s"Offset:$offset Schedulers:$nSchedulers Warmup:$warmup Tasks:$nTasks") {
      val xs: List[(TestProbe[UTCTime], Cancellable)] = (1 to nSchedulers).map { _ =>
        val testProbe = scaladsl.TestProbe[UTCTime]()(sys)
        val startTime = UTCTime(UTCTime.now().value.plusSeconds(1L))
        val cancellable: Cancellable = timeService.schedulePeriodically(startTime, Duration.ofMillis(offset)) {
          testProbe.ref ! UTCTime.now()
        }
        (testProbe, cancellable)
      }.toList

      xs.foreach {
        case (probe, cancellable) =>
          probe.receiveMessages(warmup, 100.hours) //Do not record warmup tasks

          val times = probe.receiveMessages(nTasks, 1.hour).map { t: UTCTime =>
            t
          }
          val histogram               = new Histogram(3)
          val histogramForConsistency = new Histogram(3)

          times.zipWithIndex.toList.foreach {
            case (_, i) =>
              val currentTime  = times(i).value
              val t1_s: Double = currentTime.getEpochSecond
              val ns: Double   = currentTime.getNano
              val t1           = (t1_s * 1000 * 1000 * 1000) + ns

              val previousTime = if (i == 0) UTCTime(Instant.now) else times(i - 1)
              val t2_s: Double = previousTime.value.getEpochSecond
              val ns_2: Double = previousTime.value.getNano
              val t2           = (t2_s * 1000 * 1000 * 1000) + ns_2

              val diff = math.abs(((t1 - t2) - (offset * 1000 * 1000)) / 1000).toLong
              histogram.recordValue(diff)

              val jitterInMicros = math.abs(
                Duration.between(times.head.value.plus(i * offset, ChronoUnit.MILLIS), currentTime).toNanos
              ) / 1000

              histogramForConsistency.recordValue(jitterInMicros)
          }
          println(
            "===========================Jitter(us) in Percentile [WRT Previous-Time | WRT Start-Time] ========================"
          )
          println("50 %tile: " + histogram.getValueAtPercentile(50) + " | " + histogramForConsistency.getValueAtPercentile(50))
          println("65 %tile: " + histogram.getValueAtPercentile(65) + " | " + histogramForConsistency.getValueAtPercentile(65))
          println(
            "98.5 %tile: " + histogram.getValueAtPercentile(98.5) + " | " + histogramForConsistency.getValueAtPercentile(98.5)
          )
          cancellable.cancel()
      }

    }
  }
}
