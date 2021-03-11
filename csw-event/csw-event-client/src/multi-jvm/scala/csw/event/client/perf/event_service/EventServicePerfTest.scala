package csw.event.client.perf.event_service

import akka.Done
import akka.actor.testkit.typed.scaladsl
import akka.actor.typed.scaladsl.adapter._
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import com.typesafe.config.ConfigFactory
import csw.event.client.perf.BasePerfSuite
import csw.event.client.perf.commons.{EventsSetting, PerfPublisher, PerfSubscriber}
import csw.event.client.perf.reporter._
import csw.prefix.models.Prefix

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object EventServiceMultiNodeConfig extends MultiNodeConfig {

  val totalNumberOfNodes: Int =
    System.getProperty("csw.event.client.perf.nodes") match {
      case null  => 2
      case value => value.toInt
    }

  for (n <- 1 to totalNumberOfNodes) role("node-" + n)

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.load()))
}
// ip1
// ip2
// ip1
// ip2
class EventServicePerfTestMultiJvmNode1 extends EventServicePerfTest
class EventServicePerfTestMultiJvmNode2 extends EventServicePerfTest
//class EventServicePerfTestMultiJvmNode3 extends EventServicePerfTest
//class EventServicePerfTestMultiJvmNode4 extends EventServicePerfTest
//class EventServicePerfTestMultiJvmNode5 extends EventServicePerfTest
//class EventServicePerfTestMultiJvmNode6 extends EventServicePerfTest
//class EventServicePerfTestMultiJvmNode7  extends EventServicePerfTest
//class EventServicePerfTestMultiJvmNode8  extends EventServicePerfTest
//class EventServicePerfTestMultiJvmNode9  extends EventServicePerfTest
//class EventServicePerfTestMultiJvmNode10 extends EventServicePerfTest

class EventServicePerfTest extends BasePerfSuite(EventServiceMultiNodeConfig) {

  import testConfigs._
  import testWiring._

  var publisherNodes: immutable.Seq[RoleName]  = roles.take(roles.size / 2)
  var subscriberNodes: immutable.Seq[RoleName] = roles.takeRight(roles.size / 2)

  def adjustedTotalMessages(n: Long): Long = (n * totalMessagesFactor).toLong

  override def afterAll(): Unit = {
    enterBarrier("start-printing")
    runOn(subscriberNodes.head) {
      throughputPlots.printTable()
      initialLatencyPlots.printTable()
      latencyPlots.printTable()
    }
    enterBarrier("results-printed")
    topProcess.foreach(_ =>
      scenarios.foreach(s =>
        plotLatencyHistogram(
          s"${BenchmarkFileReporter.targetDirectory.getAbsolutePath}/${s.name}/Aggregated-*",
          s"[${testConfigs.frequency}Hz]"
        )
      )
    )
    super.afterAll()
  }

  def testScenario(scenarioName: String, testSettings: TestSettings): Unit = {
    import testSettings._

    updatePubSubNodes(singlePublisher)

    val subscriberName                                   = testName + "-subscriber"
    val nodeId                                           = myself.name.split("-").last.toInt
    val pubSubAllocationPerNode                          = getPubSubAllocationPerNode(publisherSubscriberPairs)
    val (activeSubscriberNodes, inactiveSubscriberNodes) = activeInactiveSubNodes(pubSubAllocationPerNode)
    val (activePublisherNodes, inactivePublisherNodes)   = activeInactivePubNodes(pubSubAllocationPerNode)
    val inactiveNodes                                    = inactivePublisherNodes ++ inactiveSubscriberNodes

    runOn(activeSubscriberNodes: _*) {
      val subIds          = pubSubAllocationPerNode(nodeId - publisherNodes.size - 1)
      val rep             = reporter(testName)
      val completionProbe = scaladsl.TestProbe[AggregatedResult]()(system.toTyped)

      implicit val typedSystem = actorSystem
      runOn(activeSubscriberNodes.head) {
        val resultAggregator =
          new ResultAggregator(scenarioName, testName, subscriber, activeSubscriberNodes.size, completionProbe.ref)
        Await.result(resultAggregator.startSubscription().ready(), defaultTimeout)
      }

      val subscribers: immutable.Seq[(Future[Done], PerfSubscriber)] = subIds.map { subId =>
        val pubId = if (singlePublisher) 1 else subId
        val subscriber =
          new PerfSubscriber(
            Prefix(testName),
            pubId,
            subId,
            EventsSetting(totalTestMsgs, payloadSize, warmupMsgs, frequency),
            rep,
            sharedSubscriber,
            testConfigs,
            testWiring
          )
        (subscriber.startSubscription(), subscriber)
      }
      enterBarrier(subscriberName + "-started")

      waitForResultsFromAllSubscribers(subscribers)
      rep.halt()

      subscribers.foreach { case (_, subscriber) => subscriber.printResult() }

      runOn(activeSubscriberNodes.head) {
        val aggregatedResult = completionProbe.expectMessageType[AggregatedResult](5.minute)
        aggregateResult(testName, aggregatedResult)
      }

      enterBarrier(testName + "-done")
    }

    runOn(activePublisherNodes: _*) {
      println(
        "========================================================================================================================================="
      )
      println(
        s"[$testName]: Starting benchmark with ${if (singlePublisher) 1 else publisherSubscriberPairs} publishers & $publisherSubscriberPairs subscribers $totalTestMsgs messages with " +
          s"throttling of $frequency msgs/s " +
          s"and payload size $payloadSize bytes"
      )
      println(
        "========================================================================================================================================="
      )

      enterBarrier(subscriberName + "-started")

      val pubIds = if (singlePublisher) List(1) else pubSubAllocationPerNode(nodeId - 1)
      pubIds.foreach(id =>
        new PerfPublisher(
          Prefix(testName),
          id,
          EventsSetting(totalTestMsgs, payloadSize, warmupMsgs, frequency),
          testConfigs,
          testWiring,
          sharedPublisher
        ).startPublishingWithEventGenerator()
      )

      enterBarrier(testName + "-done")
    }

    runOn(inactiveNodes: _*) {
      enterBarrier(subscriberName + "-started")
      enterBarrier(testName + "-done")
    }

    enterBarrier("after-" + testName)
  }

  private def activeInactivePubNodes(pubSubAllocationPerNode: List[immutable.IndexedSeq[Int]]) = {
    if (publisherNodes.size > pubSubAllocationPerNode.size) publisherNodes.splitAt(pubSubAllocationPerNode.size)
    else (publisherNodes, Seq.empty)

  }
  private def activeInactiveSubNodes(pubSubAllocationPerNode: List[immutable.IndexedSeq[Int]]) = {
    if (subscriberNodes.size > pubSubAllocationPerNode.size) subscriberNodes.splitAt(pubSubAllocationPerNode.size)
    else (subscriberNodes, Seq.empty)
  }

  private def updatePubSubNodes(singlePublisher: Boolean): Unit = {
    if (singlePublisher) {
      publisherNodes = List(roles.head)
      subscriberNodes = roles.tail
    }
  }

  private def getPubSubAllocationPerNode(publisherSubscriberPairs: Int): List[immutable.IndexedSeq[Int]] = {
    (1 to publisherSubscriberPairs)
      .grouped((publisherSubscriberPairs.toFloat / subscriberNodes.size.toFloat).ceil.toInt)
      .toList
  }

  private val scens     = new Scenarios(testConfigs)
  private val scenarios = List(scens.payloadOneToOne)

  for (scenario <- scenarios) {
    val scenarioName = scenario.name
    for (currentTest <- scenario.testSettings)
      test(s"Perf results must be great for ${currentTest.testName} with payloadSize = ${currentTest.payloadSize}") {
        testScenario(scenarioName, currentTest)
      }
  }

}
