package csw.framework.javadsl.components;

import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Adapter;
import akka.stream.ActorMaterializer;
import akka.stream.ThrottleMode;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import csw.common.components.command.ComponentStateForCommand;
import csw.common.components.framework.SampleComponentState;
import csw.framework.CurrentStatePublisher;
import csw.framework.javadsl.JComponentHandlers;
import csw.framework.models.JCswContext;
import csw.command.client.messages.TopLevelActorMessage;
import csw.params.commands.*;
import csw.location.api.models.TrackingEvent;
import csw.params.core.generics.Key;
import csw.params.core.models.Id;
import csw.params.javadsl.JKeyType;
import csw.params.core.generics.Parameter;
import csw.params.core.states.CurrentState;
import csw.params.core.states.StateName;
import csw.command.client.CommandResponseManager;
import csw.logging.api.javadsl.ILogger;

import java.time.Duration;
import java.util.concurrent.*;

import static csw.common.components.command.ComponentStateForCommand.*;

public class JSampleComponentHandlers extends JComponentHandlers {

    // Demonstrating logger accessibility in Java Component handlers
    private ILogger log;
    private CommandResponseManager commandResponseManager;
    private CurrentStatePublisher currentStatePublisher;
    private CurrentState currentState = new CurrentState(SampleComponentState.prefix(), new StateName("testStateName"));
    private ActorContext<TopLevelActorMessage> actorContext;

    JSampleComponentHandlers(ActorContext<TopLevelActorMessage> ctx, JCswContext cswCtx) {
        super(ctx, cswCtx);
        this.currentStatePublisher = cswCtx.currentStatePublisher();
        this.log = cswCtx.loggerFactory().getLogger(getClass());
        this.commandResponseManager = cswCtx.commandResponseManager();
        this.actorContext = ctx;
    }

    @Override
    public CompletableFuture<Void> jInitialize() {
        log.debug("Initializing Sample component");
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }
        return CompletableFuture.runAsync(() -> {
            //#currentStatePublisher
            CurrentState initState = currentState.add(SampleComponentState.choiceKey().set(SampleComponentState.initChoice()));
            currentStatePublisher.publish(initState);
            //#currentStatePublisher
        });
    }

    @Override
    public void onLocationTrackingEvent(TrackingEvent trackingEvent) {

    }

    @Override
    public CommandResponse.ValidateCommandResponse validateCommand(Id runId, ControlCommand controlCommand) {
        if (controlCommand.commandName().equals(hcdCurrentStateCmd())) {
            // This is special because test doesn't want these other CurrentState values published
            return new CommandResponse.Accepted(runId);
        } else if (controlCommand.commandName().equals(crmAddOrUpdateCmd())) {
            return new CommandResponse.Accepted(runId);
        } else {
            // All other tests
            CurrentState submitState = currentState.add(SampleComponentState.choiceKey().set(SampleComponentState.commandValidationChoice()));
            currentStatePublisher.publish(submitState);

            // Special case to accept failure after validation
            if (controlCommand.commandName().equals(failureAfterValidationCmd())) {
                return new CommandResponse.Accepted(runId);
            } else if (controlCommand.commandName().name().contains("failure")) {
                return new CommandResponse.Invalid(runId, new CommandIssue.OtherIssue("Testing: Received failure, will return Invalid."));
            } else {
                return new CommandResponse.Accepted(runId);
            }
        }
    }

    @Override
    public CommandResponse.SubmitResponse onSubmit(Id runId, ControlCommand controlCommand) {
        // Adding item from CommandMessage paramset to ensure things are working
        if (controlCommand.commandName().equals(crmAddOrUpdateCmd())) {
            return crmAddOrUpdate(runId, (Setup)controlCommand);
        } else {
            CurrentState submitState = currentState.add(SampleComponentState.choiceKey().set(SampleComponentState.submitCommandChoice()));
            currentStatePublisher.publish(submitState);
            return processSubmitCommand(runId, controlCommand);
        }
    }

    @Override
    public void onOneway(Id runId, ControlCommand controlCommand) {
        if (controlCommand.commandName().equals(hcdCurrentStateCmd())) {
            // Special handling for oneway to test current state
            processCurrentStateOnewayCommand((Setup)controlCommand);
        } else {
            // Adding item from CommandMessage paramset to ensure things are working
            CurrentState onewayState = currentState.add(SampleComponentState.choiceKey().set(SampleComponentState.oneWayCommandChoice()));
            currentStatePublisher.publish(onewayState);
            processOnewayCommand(controlCommand);
        }
    }

    private CommandResponse.SubmitResponse processSubmitCommand(Id runId, ControlCommand controlCommand) {
        publishCurrentState(controlCommand);
        if (controlCommand.commandName().equals(immediateCmd())) {
            return new CommandResponse.Completed(runId);
        } else if (controlCommand.commandName().equals(immediateResCmd())) {
            Parameter<Integer> param = JKeyType.IntKey().make("encoder").set(22);
            Result result = new Result(controlCommand.source().prefix()).add(param);
            return new CommandResponse.CompletedWithResult(runId, result);
        } else if (controlCommand.commandName().equals(ComponentStateForCommand.matcherCmd())) {
            processCommandWithMatcher(controlCommand);
            return new CommandResponse.Started(runId);
        } else if (controlCommand.commandName().equals(failureAfterValidationCmd())) {
            return processCommandWithoutMatcher(runId, controlCommand);
        } else if (controlCommand.commandName().equals(ComponentStateForCommand.longRunningCmd())) {
            return processCommandWithoutMatcher(runId, controlCommand);
        }

        return new CommandResponse.Completed(runId);
    }

    //#addOrUpdateCommand
    private CommandResponse.SubmitResponse crmAddOrUpdate(Id runId, Setup setup) {
        // This simulates some worker task doing something that finishes after onSubmit returns
        Runnable task = new Runnable() {
            @Override
            public void run() {
                commandResponseManager.addOrUpdateCommand(new CommandResponse.Completed(runId));
            }
        };

        // Wait a bit and then set CRM to Completed
        ExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        ((ScheduledExecutorService) executor).schedule(task, 1, TimeUnit.SECONDS);

        // Return Started from onSubmit
        return new CommandResponse.Started(runId);
    }
    //#addOrUpdateCommand

    private void processCurrentStateOnewayCommand(Setup setup) {
        //#subscribeCurrentState
        Key<Integer> encoder = JKeyType.IntKey().make("encoder");
        int expectedEncoderValue = setup.jGet(encoder).orElseThrow().head();

        CurrentState currentState = new CurrentState(prefix(), new StateName("HCDState")).add(encoder().set(expectedEncoderValue));
        currentStatePublisher.publish(currentState);
        //#subscribeCurrentState
    }

    private void processOnewayCommand(ControlCommand controlCommand) {
        publishCurrentState(controlCommand);
        if (controlCommand.commandName().equals(ComponentStateForCommand.matcherCmd())) {
            processCommandWithMatcher(controlCommand);
        }
        // Nothing else done in oneway
    }


    private void processCommandWithMatcher(ControlCommand controlCommand) {
        Source.range(1, 10)
                .map(i -> {
                    currentStatePublisher.publish(new CurrentState(controlCommand.source(), new StateName("testStateName")).add(JKeyType.IntKey().make("encoder").set(i * 10)));
                    return i;
                })
                .throttle(1, Duration.ofMillis(100), 1, ThrottleMode.shaping())
                .runWith(Sink.ignore(), ActorMaterializer.create(Adapter.toUntyped(actorContext.getSystem())));
    }


    private CommandResponse.SubmitResponse processCommandWithoutMatcher(Id runId, ControlCommand controlCommand) {
        if (controlCommand.commandName().equals(failureAfterValidationCmd())) {
            // Set CRM to Error after 1 second
            sendCRM(runId, new CommandResponse.Error(runId, "Unknown Error occurred"));
            return new CommandResponse.Started(runId);
        } else {
            Parameter<Integer> parameter = JKeyType.IntKey().make("encoder").set(20);
            Result result = new Result(controlCommand.source().prefix()).add(parameter);

            // Set CRM to Completed after 1 second
            sendCRM(runId, new CommandResponse.CompletedWithResult(runId, result));
            return new CommandResponse.Started(runId);
        }

    }

    private void sendCRM(Id runId, CommandResponse.SubmitResponse response) {
        Runnable task = new Runnable() {
            @Override
            public void run() {
                commandResponseManager.addOrUpdateCommand(response);
            }
        };
        // Wait a bit and then set CRM to Completed
        ExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        ((ScheduledExecutorService) executor).schedule(task, 1, TimeUnit.SECONDS);
    }

    private void publishCurrentState(ControlCommand controlCommand) {
        CurrentState commandState;

        if (controlCommand instanceof Setup) {
            commandState = new CurrentState(SampleComponentState.prefix(), new StateName("testStateSetup"))
                    .add(SampleComponentState.choiceKey().set(SampleComponentState.setupConfigChoice())).add(controlCommand.paramSet().head());
        } else
            commandState = currentState.add(SampleComponentState.choiceKey().set(SampleComponentState.observeConfigChoice())).add(controlCommand.paramSet().head());

        // DEOPSCSW-372: Provide an API for PubSubActor that hides actor based interaction
        currentStatePublisher.publish(commandState);
    }

    @Override
    public CompletableFuture<Void> jOnShutdown() {
        return CompletableFuture.runAsync(() -> {
            CurrentState shutdownState = currentState.add(SampleComponentState.choiceKey().set(SampleComponentState.shutdownChoice()));
            currentStatePublisher.publish(shutdownState);
        });
    }

    @Override
    public void onGoOffline() {
        CurrentState offlineState = currentState.add(SampleComponentState.choiceKey().set(SampleComponentState.offlineChoice()));
        currentStatePublisher.publish(offlineState);
    }

    @Override
    public void onGoOnline() {
        CurrentState onlineState = currentState.add(SampleComponentState.choiceKey().set(SampleComponentState.onlineChoice()));
        currentStatePublisher.publish(onlineState);
    }
}
