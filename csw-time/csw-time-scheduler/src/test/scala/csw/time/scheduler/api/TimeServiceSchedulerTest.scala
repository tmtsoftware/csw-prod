package csw.time.scheduler.api

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.scaladsl
import akka.actor.testkit.typed.scaladsl.{ManualTime, ScalaTestWithActorTestKit}
import akka.actor.typed
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.testkit.TestProbe
import csw.time.core.models.{TAITime, UTCTime}
import csw.time.scheduler.TimeServiceSchedulerFactory
import org.scalatest.FunSuiteLike

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

class TimeServiceSchedulerTest extends ScalaTestWithActorTestKit(ManualTime.config) with FunSuiteLike {

  private val manualTime                    = ManualTime()(system)
  private val jitter                        = 10
  private implicit val ec: ExecutionContext = system.executionContext

//  private implicit val system1: typed.ActorSystem[_] = typed.ActorSystem(Behavior.empty, "test")
  private val timeService = new TimeServiceSchedulerFactory().make()
  // DEOPSCSW-542: Schedule a task to execute in future
  List(
    ("TAITime", () => TAITime(TAITime.now().value.plusSeconds(1))), // lazily evaluate time when tests are executed
    ("UTCTime", () => UTCTime(UTCTime.now().value.plusSeconds(1)))
  ).foreach {
    case (name, idealScheduleTime) =>
      test(s"[$name] should schedule task at start time") {
        val testProbe = TestProbe()(system.toUntyped)
        val probeMsg  = "echo"

        val cancellable = timeService.scheduleOnce(idealScheduleTime())(testProbe.ref ! probeMsg)
        manualTime.timePasses(500.millis)
        // check immediately after 5 millis, there should be no message
        testProbe.expectNoMessage(0.millis)
        manualTime.timePasses(500.millis)
        testProbe.expectMsg(probeMsg)
        cancellable.cancel()
      }
  }

  // DEOPSCSW-544: Schedule a task to be executed repeatedly
  // DEOPSCSW-547: Cancel scheduled timers for periodic tasks
  // DEOPSCSW-549: Time service api
  test("[TAITime] should schedule a task periodically at given interval") {
    val buffer: ArrayBuffer[Int] = ArrayBuffer.empty
    val atomicInt                = new AtomicInteger(0)
    val cancellable: Cancellable = timeService.schedulePeriodically(Duration.ofMillis(100)) {
      buffer += atomicInt.getAndIncrement()
    }
    manualTime.timePasses(500.millis)
    cancellable.cancel()
    buffer shouldBe ArrayBuffer(0, 1, 2, 3, 4, 5)
  }

  // DEOPSCSW-545: Start a repeating task with initial offset
  // DEOPSCSW-547: Cancel scheduled timers for periodic tasks
  // DEOPSCSW-549: Time service api
  test("[TAITime] should schedule a task periodically at given interval after start time") {
    val buffer: ArrayBuffer[Int] = ArrayBuffer.empty
    val atomicInt                = new AtomicInteger(0)
    val startTime: TAITime       = new TAITime(TAITime.now().value.plusSeconds(1))

    val cancellable: Cancellable = timeService.schedulePeriodically(startTime, Duration.ofMillis(100)) {
      buffer += atomicInt.getAndIncrement()
    }
    manualTime.timePasses(1.seconds)
    buffer shouldBe ArrayBuffer(0)
    manualTime.timePasses(500.millis)
    cancellable.cancel()
    buffer shouldBe ArrayBuffer(0, 1, 2, 3, 4, 5)
  }

  // DEOPSCSW-542: Schedule a task to execute in future
  // DEOPSCSW-549: Time service api
  test("should schedule multiple tasks at same start time") {
    // we do not want manual config in this test to compare start time with task execution time
    // hence separate instance of actor typedSystem is created here which does not use ManualConfig
    val system                        = typed.ActorSystem(Behavior.empty, "test1")
    implicit val ec: ExecutionContext = system.executionContext

    val timeService = new TimeServiceSchedulerFactory()(system.scheduler).make()
    val testProbe   = scaladsl.TestProbe[UTCTime]("blah")(system)

    val startTime    = UTCTime(UTCTime.now().value.plusSeconds(1))
    val cancellable  = timeService.scheduleOnce(startTime)(testProbe.ref ! UTCTime.now())
    val cancellable2 = timeService.scheduleOnce(startTime)(testProbe.ref ! UTCTime.now())

    val utcTime1 = testProbe.expectMessageType[UTCTime]
    val utcTime2 = testProbe.expectMessageType[UTCTime]

    val expectedTimeSpread = startTime.value.toEpochMilli +- jitter
    utcTime1.value.toEpochMilli shouldBe expectedTimeSpread
    utcTime2.value.toEpochMilli shouldBe expectedTimeSpread

    cancellable.cancel()
    cancellable2.cancel()

    system.terminate()
    Await.result(system.whenTerminated, 5.seconds)
  }

  // DEOPSCSW-544: Schedule a task to execute in future with actual times
  // DEOPSCSW-549: Time service api
  test("repeating task that also saves time") {
    // we do not want manual config in this test to compare start time with task execution time
    // hence separate instance of actor typedSystem is created here which does not use ManualConfig
    val system                        = typed.ActorSystem(Behavior.empty, "test1")
    implicit val ec: ExecutionContext = system.executionContext

    val timeService = new TimeServiceSchedulerFactory()(system.scheduler).make()

    val testProbe = scaladsl.TestProbe[UTCTime]()(system)

    val buffer: ArrayBuffer[Int] = ArrayBuffer.empty

    val atomicInt    = new AtomicInteger(0)
    val startTime    = UTCTime.now()
    val offset: Long = 100L // milliseconds
    val cancellable: Cancellable = timeService.schedulePeriodically(Duration.ofMillis(offset)) {
      buffer += atomicInt.getAndIncrement()
      testProbe.ref ! UTCTime.now()
    }

    val times = testProbe.receiveMessages(6, 590.milli).map { t: UTCTime =>
      t
    }

    cancellable.cancel()

    // Verify correct number and values
    buffer shouldBe ArrayBuffer(0, 1, 2, 3, 4, 5)

    times.size shouldBe 6
    buffer.foreach { i =>
      times(i).value.toEpochMilli shouldBe (startTime.value.toEpochMilli + offset * i) +- jitter
    }

    system.terminate()
    Await.result(system.whenTerminated, 5.seconds)
  }

  // DEOPSCSW-544: Schedule a task to execute in future with actual times
  // DEOPSCSW-545: Start a repeating task with an initial offset
  // DEOPSCSW-549: Time service api
  test("repeating task that also saves time but with an offset") {
    // we do not want manual config in this test to compare start time with task execution time
    // hence separate instance of actor typedSystem is created here which does not use ManualConfig
    val system                        = typed.ActorSystem(Behavior.empty, "test1")
    implicit val ec: ExecutionContext = system.executionContext
    val timeService                   = new TimeServiceSchedulerFactory()(system.scheduler).make()
    val testProbe                     = scaladsl.TestProbe[UTCTime]()(system)

    val buffer: ArrayBuffer[Int] = ArrayBuffer.empty

    val atomicInt    = new AtomicInteger(0)
    val startTime    = UTCTime(UTCTime.now().value.plusSeconds(1L))
    val offset: Long = 100L // milliseconds
    val cancellable: Cancellable = timeService.schedulePeriodically(startTime, Duration.ofMillis(offset)) {
      buffer += atomicInt.getAndIncrement()
      testProbe.ref ! UTCTime.now()
    }

    val times = testProbe.receiveMessages(6, 1590.milli).map { case t: UTCTime => t }

    cancellable.cancel()

    // Verify correct number and values
    buffer shouldBe ArrayBuffer(0, 1, 2, 3, 4, 5)

    times.size shouldBe 6
    buffer.foreach { i =>
      times(i).value.toEpochMilli shouldBe (startTime.value.toEpochMilli + offset * i) +- jitter
    }

    system.terminate()
    Await.result(system.whenTerminated, 5.seconds)
  }
}
