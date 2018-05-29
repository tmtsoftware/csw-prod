package csw.services.event.perf.commons

import java.time.Instant
import java.util.concurrent.TimeUnit.SECONDS

import akka.Done
import akka.stream.scaladsl.{Keep, Source}
import csw.messages.events.{Event, EventKey, EventName, SystemEvent}
import csw.messages.params.models.Subsystem
import csw.services.event.perf.reporter.{ResultReporter, TestRateReporter}
import csw.services.event.perf.utils.EventUtils
import csw.services.event.perf.utils.EventUtils._
import csw.services.event.perf.wiring.{TestConfigs, TestWiring}
import csw.services.event.scaladsl.{EventSubscriber, EventSubscription}
import org.HdrHistogram.Histogram

import scala.concurrent.Future

class PerfSubscriber(
    name: String,
    subscriberId: Int,
    subscribeKey: String,
    eventsSetting: EventsSetting,
    reporter: TestRateReporter,
    sharedSubscriber: EventSubscriber,
    testConfigs: TestConfigs,
    testWiring: TestWiring
) {

  import eventsSetting._
  import testConfigs._
  import testWiring.wiring._

  private val subscriber: EventSubscriber =
    if (testConfigs.shareConnection) sharedSubscriber else testWiring.subscriber

  val histogram: Histogram   = new Histogram(SECONDS.toNanos(10), 3)
  private val resultReporter = new ResultReporter(name, actorSystem)

  var startTime       = 0L
  var totalTime       = 0L
  var avgLatency      = 0L
  var eventsReceived  = 0L
  var lastId          = 0
  var outOfOrderCount = 0
  var lastCurrentId   = 0

  private val eventKeys     = Set(EventKey(s"$testEventKey-$subscribeKey"), EventKey(s"${prefix.prefix}.$endEventS-$subscribeKey"))
  private val eventPatterns = Subsystem.values.map(subsystem ⇒ s"*${subsystem.entryName}*")

  private val eventsToDrop = warmup + eventKeys.size //inclusive of latest events from subscription

  val endEventName = EventName(s"${EventUtils.endEventS}-$subscribeKey")

  def subscription: Source[Event, EventSubscription] =
    if (isPatternSubscriber) {
      subscriber.pSubscribe(eventPatterns.toSet)
    } else subscriber.subscribe(eventKeys)

  def startSubscription(): Future[Done] =
    subscription
      .drop(eventsToDrop)
      .takeWhile {
        case SystemEvent(_, _, `endEventName`, _, _) ⇒ false
        case e @ _ ⇒
          if (isPatternSubscriber & e.eventKey.key.contains("end")) false else true
      }
      .watchTermination()(Keep.right)
      .runForeach(if (isPatternSubscriber) _ ⇒ () else report)

  private def report(event: Event): Unit = {
    val currentTime          = getNanos(Instant.now()).toLong
    val eventOriginationTime = getNanos(event.eventTime.time).toLong
    val latency              = currentTime - eventOriginationTime

    if (eventsReceived == 0) {
      startTime = currentTime.toLong
      avgLatency = latency
    }

    eventsReceived += 1
    totalTime = currentTime - startTime

    reporter.onMessage(1, payloadSize)

    try {
      histogram.recordValue(latency)
    } catch {
      case _: ArrayIndexOutOfBoundsException ⇒
    }

    val currentId = event.eventId.id.toInt
    val inOrder   = currentId >= lastId
    lastId = currentId

    if (!inOrder) outOfOrderCount += 1

    avgLatency = (avgLatency + latency) / 2
  }

  def totalDropped(): Long = totalTestMsgs - eventsReceived

  def printResult(): Unit =
    resultReporter.printResult(
      subscriberId,
      totalDropped(),
      payloadSize,
      histogram,
      eventsReceived,
      totalTime,
      outOfOrderCount,
      avgLatency
    )

  def isPatternSubscriber: Boolean = patternBasedSubscription & name.contains("pattern")

}
