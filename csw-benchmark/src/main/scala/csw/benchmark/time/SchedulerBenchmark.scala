package csw.benchmark.time

import java.time.Duration
import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import csw.time.scheduler.TimeServiceSchedulerFactory
import csw.time.scheduler.api.TimeServiceScheduler
import org.openjdk.jmh.annotations.{OperationsPerInvocation, _}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.All))
class SchedulerBenchmark {
  final private val Offset                  = 20L
  final private val OperationsPerInvocation = 1

  private val actorSystem: ActorSystem[_]                = ActorSystem(Behaviors.empty, "test")
  private var timeServiceScheduler: TimeServiceScheduler = _

  @Setup
  def setup(): Unit = {
    timeServiceScheduler = new TimeServiceSchedulerFactory()(actorSystem.scheduler).make()(actorSystem.executionContext)
  }

  @TearDown
  def shutdown(): Unit = {
    actorSystem.terminate()
    Await.result(actorSystem.whenTerminated, 5.seconds)
  }

  // Refer :
  // 1. https://stackoverflow.com/questions/54132761/how-operationsperinvocation-number-is-used-for-calculating-percentile-latencies
  // 2. https://discuss.lightbend.com/t/what-is-the-accuracy-of-akka-scheduler/3134
  @Benchmark
  @OperationsPerInvocation(OperationsPerInvocation)
  def schedulePeriodicallyBenchmark(): Unit = {
    val latch = new CountDownLatch(OperationsPerInvocation)
    timeServiceScheduler.schedulePeriodically(Duration.ofMillis(Offset)) {
      latch.countDown()
    }
    awaitLatch(latch)
  }

  private def awaitLatch(latch: CountDownLatch): Unit =
    if (!latch.await(1, TimeUnit.MINUTES))
      throw new RuntimeException("Latch didn't complete in time")

}
