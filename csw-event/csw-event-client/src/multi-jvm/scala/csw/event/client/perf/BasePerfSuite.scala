package csw.event.client.perf

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.{ExecutorService, Executors}

import akka.Done
import akka.actor.typed.{ActorSystem, SpawnProtocol}
import akka.actor.typed.scaladsl.adapter._
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec, MultiNodeSpecCallbacks}
import akka.testkit.ImplicitSender
import csw.event.api.scaladsl.{EventPublisher, EventSubscriber}
import csw.event.client.perf.commons.PerfSubscriber
import csw.event.client.perf.reporter._
import csw.event.client.perf.utils.EventUtils.nanosToSeconds
import csw.event.client.perf.utils.{EventUtils, SystemMonitoringSupport}
import csw.event.client.perf.wiring.{TestConfigs, TestWiring}
import org.HdrHistogram.Histogram
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable
import scala.concurrent.duration.{Duration, DurationDouble, FiniteDuration}
import scala.concurrent.{Await, Future}

class BasePerfSuite(config: MultiNodeConfig)
    extends MultiNodeSpec(config)
    with MultiNodeSpecCallbacks
    with AnyFunSuiteLike
    with Matchers
    with ImplicitSender
    with SystemMonitoringSupport
    with BeforeAndAfterAll {

  private val typedSystem = ActorSystem(SpawnProtocol(),"typed-actor-system")
  val testConfigs = new TestConfigs(system.settings.config)
  val testWiring  = new TestWiring(typedSystem)

  import testWiring._

  lazy val sharedPublisher: EventPublisher   = publisher
  lazy val sharedSubscriber: EventSubscriber = subscriber
  lazy val reporterExecutor: ExecutorService = Executors.newFixedThreadPool(1)

  var topProcess: Option[Process] = None

  var throughputPlots: ThroughputPlots         = ThroughputPlots()
  var latencyPlots: LatencyPlots               = LatencyPlots()
  var initialLatencyPlots: InitialLatencyPlots = InitialLatencyPlots()

  val defaultTimeout: Duration   = 1.minute
  val maxTimeout: FiniteDuration = 1.hour

  override def initialParticipants: Int = roles.size

  def reporter(name: String): TestRateReporter = {
    val r = new TestRateReporter(name)
    reporterExecutor.execute(r)
    r
  }

  override def beforeAll(): Unit = {
    multiNodeSpecBeforeAll()
    if (testConfigs.systemMonitoring) startSystemMonitoring()
  }

  override def afterAll(): Unit = {
    reporterExecutor.shutdown()
    if (testConfigs.systemMonitoring) {
      topProcess.foreach { top =>
        top.destroyForcibly().waitFor()
        plotCpuUsageGraph()
        plotMemoryUsageGraph()
      }
      plotJstat().foreach(_.waitFor())
    }
    multiNodeSpecAfterAll()
  }

  def startSystemMonitoring(): Unit = {
    runJstat()
    runPerfFlames(roles: _*)(delay = 15.seconds, time = 60.seconds)
    topProcess = runTop()
  }

  def waitForResultsFromAllSubscribers(subscribers: immutable.Seq[(Future[Done], PerfSubscriber)]): Unit = {
    val histogramPerNode               = new Histogram(SECONDS.toNanos(10), 3)
    val initialLatencyHistogramPerNode = new Histogram(SECONDS.toNanos(10), 3)
    var totalTimePerNode               = 0L
    var eventsReceivedPerNode          = 0L
    var totalDroppedPerNode            = 0L
    var outOfOrderCountPerNode         = 0L
    var aggregatedLatencyPerNode       = 0L

    subscribers.foreach {
      case (doneF, subscriber) =>
        Await.result(doneF, Duration.Inf)
        if (!subscriber.isPatternSubscriber) {
          outOfOrderCountPerNode += subscriber.outOfOrderCount
          totalDroppedPerNode += subscriber.totalDropped()
          aggregatedLatencyPerNode += subscriber.avgLatency()
          histogramPerNode.add(subscriber.histogram)
          initialLatencyHistogramPerNode.recordValue(subscriber.initialLatency)
          eventsReceivedPerNode += subscriber.eventsReceived
          totalTimePerNode = Math.max(totalTimePerNode, subscriber.totalTime)
        }
    }

    val byteBuffer: ByteBuffer = ByteBuffer.allocate(326942)
    histogramPerNode.encodeIntoByteBuffer(byteBuffer)

    val initialLatencyByteBuffer: ByteBuffer = ByteBuffer.allocate(326942)
    initialLatencyHistogramPerNode.encodeIntoByteBuffer(initialLatencyByteBuffer)

    Await.result(
      publisher.publish(
        EventUtils.perfResultEvent(
          byteBuffer.array(),
          initialLatencyByteBuffer.array(),
          eventsReceivedPerNode / nanosToSeconds(totalTimePerNode.toDouble),
          totalDroppedPerNode,
          outOfOrderCountPerNode,
          aggregatedLatencyPerNode / subscribers.size
        )
      ),
      defaultTimeout
    )
  }

  def aggregateResult(testName: String, aggregatedResult: AggregatedResult): Unit = {
    latencyPlots = latencyPlots.copy(
      plot50 = latencyPlots.plot50.addAll(aggregatedResult.latencyPlots.plot50),
      plot90 = latencyPlots.plot90.addAll(aggregatedResult.latencyPlots.plot90),
      plot99 = latencyPlots.plot99.addAll(aggregatedResult.latencyPlots.plot99),
      avg = latencyPlots.avg.addAll(aggregatedResult.latencyPlots.avg)
    )

    throughputPlots = throughputPlots.copy(
      throughput = throughputPlots.throughput.addAll(aggregatedResult.throughputPlots.throughput),
      dropped = throughputPlots.dropped.addAll(aggregatedResult.throughputPlots.dropped),
      outOfOrder = throughputPlots.outOfOrder.addAll(aggregatedResult.throughputPlots.outOfOrder)
    )

    initialLatencyPlots = initialLatencyPlots.copy(
      plot50 = initialLatencyPlots.plot50.addAll(aggregatedResult.initialLatencyPlots.plot50),
      plot90 = initialLatencyPlots.plot90.addAll(aggregatedResult.initialLatencyPlots.plot90),
      plot99 = initialLatencyPlots.plot99.addAll(aggregatedResult.initialLatencyPlots.plot99)
    )
  }
}
