package csw.event.client.perf.commons

import java.time.Instant
import java.util.concurrent.TimeUnit.SECONDS

import akka.Done
import akka.actor.typed
import akka.stream.UniqueKillSwitch
import akka.stream.scaladsl.{Keep, Source}
import csw.event.api.scaladsl.{EventSubscriber, EventSubscription}
import csw.event.client.perf.ocs.gateway.client.GatewayClient
import csw.event.client.perf.reporter.{ResultReporter, TestRateReporter}
import csw.event.client.perf.utils.EventUtils._
import csw.event.client.perf.wiring.{TestConfigs, TestWiring}
import csw.params.events.{Event, EventKey, EventName, SystemEvent}
import csw.prefix.models.Prefix
import org.HdrHistogram.Histogram

import scala.concurrent.Future

class PerfSubscriber(
    prefix: Prefix,
    pubId: Int,
    subId: Int,
    eventsSetting: EventsSetting,
    reporter: TestRateReporter,
    sharedSubscriber: EventSubscriber,
    testConfigs: TestConfigs,
    testWiring: TestWiring
) {

  import eventsSetting._
  import testConfigs._
  import testWiring._

  implicit val system= actorSystem

  private val subscriber: EventSubscriber =
    if (testConfigs.shareConnection) sharedSubscriber else testWiring.subscriber

  private lazy val ocsGatewayClient = new GatewayClient("172.31.57.227", 8090, subId)

  val histogram: Histogram   = new Histogram(SECONDS.toNanos(10), 3)
  private val resultReporter = new ResultReporter(prefix.toString, actorSystem)

  var startTime           = 0L
  var totalTime           = 0L
  var aggregatedLatency   = 0L
  var eventsReceived      = 0L
  var timeBeforeSubscribe = 0L
  var initialLatency      = 0L
  var lastId              = 0
  var outOfOrderCount     = 0
  var lastCurrentId       = 0

  private val testEventName = EventName(s"$testEventS-$pubId")
  private val endEventName  = EventName(s"$endEventS-$pubId")

  private var eventKeys = Set.empty[EventKey]

  def subscription: Source[Event, EventSubscription] =
    if (isPatternSubscriber) {
      val pattern = if (redisEnabled) redisPattern else kafkaPattern
      subscriber.pSubscribe(prefix.subsystem, pattern)
    }
    else {
      eventKeys = Set(EventKey(prefix, testEventName), EventKey(prefix, endEventName))
      timeBeforeSubscribe = getNanos(Instant.now()).toLong
      subscriber.subscribe(eventKeys)
    }

  def subscribeToGateways: Source[Event, UniqueKillSwitch] = {
    eventKeys = Set(EventKey(prefix, testEventName), EventKey(prefix, endEventName))
    timeBeforeSubscribe = getNanos(Instant.now()).toLong
    ocsGatewayClient.subscribe(eventKeys)
  }

  def startSubscription(): Future[Done] =
    subscribeToGateways
      .prefixAndTail(eventKeys.size)
      .flatMapConcat {
        case (events, remainingSource) =>
          events.foreach {
            case SystemEvent(_, _, `testEventName`, _, _) =>
              initialLatency = getNanos(Instant.now()).toLong - timeBeforeSubscribe
            case _ => // Do nothing
          }
          remainingSource
      }
      .drop(warmup)
      .takeWhile {
        case SystemEvent(_, _, `endEventName`, _, _) => false
        case e @ _ =>
          if (isPatternSubscriber & e.eventKey.key.contains(endEventS)) false else true
      }
      .watchTermination()(Keep.right)
      .runForeach(report)

  private def report(event: Event): Unit = {
    val currentTime          = getNanos(Instant.now()).toLong
    val eventOriginationTime = getNanos(event.eventTime.value).toLong
    val latency              = currentTime - eventOriginationTime
    aggregatedLatency += latency

    if (eventsReceived == 0) startTime = currentTime.toLong

    eventsReceived += 1
    totalTime = currentTime - startTime

    reporter.onMessage(1, payloadSize)

    try {
      histogram.recordValue(latency)
    }
    catch {
      case _: ArrayIndexOutOfBoundsException =>
    }

    val currentId = event.eventId.id.toInt
    val inOrder   = currentId >= lastId
    lastId = currentId

    if (!inOrder) outOfOrderCount += 1
  }

  def totalDropped(): Long = totalTestMsgs - eventsReceived
  def avgLatency(): Long   = aggregatedLatency / eventsReceived

  def printResult(): Unit =
    resultReporter.printResult(
      subId,
      totalDropped(),
      payloadSize,
      histogram,
      eventsReceived,
      totalTime.toDouble,
      outOfOrderCount,
      avgLatency()
    )

  def isPatternSubscriber: Boolean = patternBasedSubscription & prefix.toString.contains("pattern")

}
