package example.time;

import akka.actor.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Adapter;
import akka.actor.typed.scaladsl.ActorContext;
import csw.time.core.models.UTCTime;
import csw.time.scheduler.TimeServiceSchedulerFactory;
import csw.time.scheduler.api.Cancellable;
import csw.time.scheduler.api.TimeServiceScheduler;

import java.time.Duration;

public class JSchedulerExamples {

    private ActorContext<UTCTime> ctx;
    private UTCTime utcTime = UTCTime.now();
    TimeServiceScheduler scheduler;

    public JSchedulerExamples(ActorSystem<Object> actorSystem) {
        //#create-scheduler
        // create time service scheduler using the factory method
        TimeServiceScheduler scheduler = new TimeServiceSchedulerFactory(actorSystem.scheduler()).make( actorSystem.executionContext());
        //#create-scheduler

        this.scheduler = scheduler;
    }


    void scheduleOnce() {
        UTCTime utcTime = UTCTime.now();

        // #schedule-once
        Runnable task = () -> {/* do something*/};
        scheduler.scheduleOnce(utcTime, task);
        // #schedule-once
    }

    // #schedule-once-with-actorRef
    static class SchedulingHandler {

        public static Behavior<UTCTime> behavior() {
            // handle the message to execute the task on scheduled time
            return null;
        }
    }

    Cancellable schedule() {
        ActorRef actorRef = Adapter.toUntyped(ctx.asJava().spawnAnonymous(SchedulingHandler.behavior()));

        return scheduler.scheduleOnce(utcTime, actorRef, UTCTime.now());
    }
    // #schedule-once-with-actorRef

    void schedulePeriodically() {
        // #schedule-periodically
        // #schedule-periodically-with-startTime
        Runnable task = () -> {/* do something*/};
        // #schedule-periodically-with-startTime
        scheduler.schedulePeriodically(Duration.ofMillis(50), task);
        // #schedule-periodically

        Runnable runnable = () -> {/* do something*/};
        // #schedule-periodically-with-startTime
        scheduler.schedulePeriodically(utcTime, Duration.ofMillis(50), task);
        // #schedule-periodically-with-startTime
    }

}
