package example.time

import java.time.Duration

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.{ActorRef, Scheduler, typed}
import csw.time.core.models.UTCTime
import csw.time.scheduler.TimeServiceSchedulerFactory
import csw.time.scheduler.api.TimeServiceScheduler

import scala.concurrent.ExecutionContext

class SchedulerExamples(ctx: ActorContext[UTCTime]) {

  //#create-scheduler
  // create time service scheduler using the factory method
  implicit val actorSystem: typed.ActorSystem[_]         = ctx.system
  implicit val scheduler: Scheduler                      = actorSystem.scheduler
  implicit val executionContext: ExecutionContext        = actorSystem.executionContext
  private val timeServiceScheduler: TimeServiceScheduler = new TimeServiceSchedulerFactory().make()
  //#create-scheduler

  private val utcTime = UTCTime.now()

  // #schedule-once
  timeServiceScheduler.scheduleOnce(utcTime) {
    // do something
  }
  // #schedule-once

  // #schedule-once-with-actorRef
  object SchedulingHandler {
    def behavior: Behavior[UTCTime] = Behaviors.setup { ctx =>
      //setup required for the actor

      Behaviors.receiveMessage {
        case _ => // handle the message to execute the task on scheduled time and return new behavior
          Behaviors.same
      }
    }
  }

  private val actorRef: ActorRef = ctx.spawnAnonymous(SchedulingHandler.behavior).toUntyped

  timeServiceScheduler.scheduleOnce(utcTime, actorRef, UTCTime.now())

  // #schedule-once-with-actorRef

  // #schedule-periodically
  timeServiceScheduler.schedulePeriodically(Duration.ofMillis(50)) { /* do something*/ }
  // #schedule-periodically

  // #schedule-periodically-with-startTime
  timeServiceScheduler.schedulePeriodically(utcTime, Duration.ofMillis(50)) { /* do something*/ }
  // #schedule-periodically-with-startTime

}
