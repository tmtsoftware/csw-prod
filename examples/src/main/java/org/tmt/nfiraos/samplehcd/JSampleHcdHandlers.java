package org.tmt.nfiraos.samplehcd;

import akka.actor.Cancellable;
import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import csw.command.client.messages.TopLevelActorMessage;
import csw.framework.javadsl.JComponentHandlers;
import csw.framework.models.JCswContext;
import csw.location.api.models.TrackingEvent;
import csw.logging.api.javadsl.ILogger;
import csw.params.commands.*;
import csw.params.core.generics.Key;
import csw.params.core.generics.Parameter;
import csw.params.core.models.Id;
import csw.params.events.Event;
import csw.params.events.EventName;
import csw.params.events.SystemEvent;
import csw.params.javadsl.JKeyType;
import csw.serializable.TMTSerializable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Domain specific logic should be written in below handlers.
 * These handlers get invoked when component receives messages/commands from other component/entity.
 * For example, if one component sends Submit(Setup(args)) command to SampleHcd, these will be first validated in the
 * supervisor and then forwarded to Component TLA which first invokes validateCommand hook
 * and if validation is successful, then onSubmit hook gets invoked.
 * You can find more information on this here : https://tmtsoftware.github.io/csw/framework.html
 */
public class JSampleHcdHandlers extends JComponentHandlers {

    private JCswContext cswCtx;
    private ILogger log;
    private ActorContext<TopLevelActorMessage> actorContext;
    private ActorRef<WorkerCommand> workerActor;


    JSampleHcdHandlers(ActorContext<TopLevelActorMessage> ctx, JCswContext cswCtx) {
        super(ctx, cswCtx);
        this.cswCtx = cswCtx;
        this.log = cswCtx.loggerFactory().getLogger(getClass());
        this.actorContext = ctx;
        workerActor = createWorkerActor();
    }

    //#worker-actor
    private interface WorkerCommand extends TMTSerializable {
    }

    private static final class Sleep implements WorkerCommand {
        private final Id runId;
        private final long timeInMillis;

        private Sleep(Id runId, long timeInMillis) {
            this.runId = runId;
            this.timeInMillis = timeInMillis;
        }
    }

    private ActorRef<WorkerCommand> createWorkerActor() {
        return actorContext.spawn(
                Behaviors.receiveMessage(msg -> {
                    if (msg instanceof Sleep) {
                        Sleep sleep = (Sleep) msg;
                        log.trace(() -> "WorkerActor received sleep command with time of " + sleep.timeInMillis + " ms");
                        // simulate long running command
                        Thread.sleep(sleep.timeInMillis);
                        cswCtx.commandResponseManager().addOrUpdateCommand(new CommandResponse.Completed(sleep.runId));
                    } else {
                        log.error("Unsupported message type");
                    }
                    return Behaviors.same();
                }),
                "WorkerActor"
        );
    }
    //#worker-actor


    //#initialize
    private Optional<Cancellable> maybePublishingGenerator = Optional.empty();
    @Override
    public CompletableFuture<Void> jInitialize() {
        return CompletableFuture.runAsync(() -> {
            log.info("In HCD initialize");
            maybePublishingGenerator = Optional.of(publishCounter());
        });
    }

    @Override
    public void onLocationTrackingEvent(TrackingEvent trackingEvent) {
        log.debug(() -> "TrackingEvent received: " + trackingEvent.connection().name());
    }

    @Override
    public CompletableFuture<Void> jOnShutdown() {
        return CompletableFuture.runAsync(() -> log.info("HCD is shutting down"));
    }
    //#initialize

    //#publish
    private int counter = 0;
    private Optional<Event> incrementCounterEvent() {
        counter += 1;
        Parameter<Integer> param = JKeyType.IntKey().make("counter").set(counter);
        return Optional.of(new SystemEvent(cswCtx.componentInfo().prefix(), new EventName("HcdCounter")).add(param));
    }

    private Cancellable publishCounter() {
        log.info("Starting publish stream.");
        return cswCtx.eventService().defaultPublisher().publish(this::incrementCounterEvent, java.time.Duration.ofSeconds(2));
    }

    private void stopPublishingGenerator() {
        log.info("Stopping publish stream");
        maybePublishingGenerator.ifPresent(Cancellable::cancel);
    }
    //#publish

    //#validate
    @Override
    public CommandResponse.ValidateCommandResponse validateCommand(Id runId, ControlCommand controlCommand) {
        String commandName = controlCommand.commandName().name();
        log.info(() -> "Validating command: " + commandName);
        if (commandName.equals("sleep")) {
            return new CommandResponse.Accepted(runId);
        }
        return new CommandResponse.Invalid(runId, new CommandIssue.UnsupportedCommandIssue("Command " + commandName + ". not supported."));
    }
    //#validate


    //#onSetup
    @Override
    public CommandResponse.SubmitResponse onSubmit(Id runId, ControlCommand controlCommand) {
        log.info(() -> "Handling command: " + controlCommand.commandName());

        if (controlCommand instanceof Setup) {
            onSetup(runId, (Setup) controlCommand);
            return new CommandResponse.Started(runId);
        } else if (controlCommand instanceof Observe) {
            // implement (or not)
        }
        return new CommandResponse.Error(runId, "Observe command not supported");
    }

    private void onSetup(Id runId, Setup setup) {
        Key<Long> sleepTimeKey = JKeyType.LongKey().make("SleepTime");

        // get param from the Parameter Set in the Setup
        Optional<Parameter<Long>> sleepTimeParamOption = setup.jGet(sleepTimeKey);

        // values of parameters are arrays.  Get the first one (the only one in our case) using `head` method available as a convenience method on `Parameter`.
        if (sleepTimeParamOption.isPresent()) {
            Parameter<Long> sleepTimeParam = sleepTimeParamOption.orElseThrow();
            long sleepTimeInMillis = sleepTimeParam.head();

            log.info(() -> "command payload: " + sleepTimeParam.keyName() + " = " + sleepTimeInMillis);

            workerActor.tell(new Sleep(runId, sleepTimeInMillis));
        }
    }
    //#onSetup


    @Override
    public void onOneway(Id runId, ControlCommand controlCommand) {
    }

    @Override
    public void onGoOffline() {
    }

    @Override
    public void onGoOnline() {
    }
}
