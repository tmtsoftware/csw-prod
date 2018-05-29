package csw.services.event.perf.model_obs

import akka.actor.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.remote.testkit.MultiNodeConfig
import akka.testkit.typed.scaladsl
import com.typesafe.config.ConfigFactory
import csw.services.event.perf.BasePerfSuite
import csw.services.event.perf.commons.{EventsSetting, PerfPublisher, PerfSubscriber}
import csw.services.event.perf.reporter._

import scala.concurrent.Await

object ModelObsMultiNodeConfig extends MultiNodeConfig {

  val totalNumberOfNodes: Int =
    System.getProperty("csw.event.perf.model-obs.nodes") match {
      case null  ⇒ 2
      case value ⇒ value.toInt
    }

  for (n ← 1 to totalNumberOfNodes) role("node-" + n)

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.load()))

}

class ModelObsPerfTestMultiJvmNode1 extends ModelObsPerfTest
class ModelObsPerfTestMultiJvmNode2 extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode3 extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode4 extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode5 extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode6  extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode7  extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode8  extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode9  extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode10 extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode11 extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode12 extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode13 extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode14 extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode15 extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode16 extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode17 extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode18 extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode19 extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode20 extends ModelObsPerfTest
//class ModelObsPerfTestMultiJvmNode21 extends ModelObsPerfTest

class ModelObsPerfTest extends BasePerfSuite {

  override def afterAll(): Unit = {
    runOn(roles.last) {
      throughputPlots.printTable()
      latencyPlots.printTable()
    }
    topProcess.foreach(
      _ ⇒ plotLatencyHistogram(s"${BenchmarkFileReporter.targetDirectory.toPath}/$scenarioName/Aggregated-*", "")
    )
    super.afterAll()
  }

  def runScenario(testSettings: ModelObservatoryTestSettings): Unit = {
    val nodeId = myself.name.split("-").tail.head.toInt

    runOn(roles: _*) {
      val jvmSetting = testSettings.jvmSettings(nodeId - 1)
      import jvmSetting._

      val rep = reporter(s"ModelObsTest-$nodeId")

      val subscribers = subSettings.flatMap { subSetting ⇒
        import subSetting._
        (1 to noOfSubs).map { subId ⇒
          val subscriber = new PerfSubscriber(
            subsystem,
            subId,
            s"${subSetting.key}-$subId",
            EventsSetting(totalTestMsgs, payloadSize, warmup, rate),
            rep,
            sharedSubscriber,
            testConfigs,
            testWiring
          )
          val doneF = subscriber.startSubscription()
          (doneF, subscriber)
        }
      }

      val completionProbe = scaladsl.TestProbe[AggregatedResult]()(system.toTyped)

      runOn(roles.last) {
        val resultAggregator =
          new ResultAggregator(scenarioName, "ModelObsPerfTest", testWiring.subscriber, roles.size, completionProbe.ref)
        Await.result(resultAggregator.startSubscription().ready(), defaultTimeout)
      }

      enterBarrier("subscribers-started")

      pubSettings.foreach { pubSetting ⇒
        import pubSetting._
        (1 to noOfPubs).foreach { pubId ⇒
          new PerfPublisher(
            s"${pubSetting.key}-$pubId",
            EventsSetting(totalTestMsgs, payloadSize, warmup, rate),
            testConfigs,
            testWiring,
            sharedPublisher
          ).startPublishingWithEventGenerator()
        }
      }

      enterBarrier("publishers-started")

      waitForResultsFromAllSubscribers(subscribers)

      runOn(roles.last) {
        val aggregatedResult = completionProbe.expectMessageType[AggregatedResult](maxTimeout)
        aggregateResult("ModelObsPerfTest", aggregatedResult)
      }

      enterBarrier("done")
      rep.halt()
    }
  }

  private val scenarios = new ModelObsScenarios(testConfigs)
  val scenarioName      = "Model-Obs"

  test("Perf results must be great for model observatory use case") {
    runScenario(scenarios.modelObsScenarioWithTwoProcesses)
  }

}
