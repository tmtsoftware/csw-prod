package example.framework.components.assembly;

import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Adapter;
import akka.util.Timeout;
import csw.command.api.javadsl.ICommandService;
import csw.command.client.CommandResponseManager;
import csw.command.client.CommandServiceFactory;
import csw.command.client.messages.TopLevelActorMessage;
import csw.command.client.models.framework.ComponentInfo;
import csw.config.api.javadsl.IConfigClientService;
import csw.config.api.models.ConfigData;
import csw.config.client.javadsl.JConfigClientFactory;
import csw.event.api.javadsl.IEventService;
import csw.framework.CurrentStatePublisher;
import csw.framework.exceptions.FailureRestart;
import csw.framework.exceptions.FailureStop;
import csw.framework.javadsl.JComponentHandlers;
import csw.framework.models.JCswContext;
import csw.location.api.javadsl.ILocationService;
import csw.location.api.javadsl.JComponentType;
import csw.location.api.models.*;
import csw.logging.api.javadsl.ILogger;
import csw.params.commands.*;
import csw.params.core.generics.Key;
import csw.params.core.models.Id;
import csw.params.core.models.Prefix;
import csw.params.core.states.CurrentState;
import csw.params.core.states.StateName;
import csw.params.javadsl.JKeyType;
import scala.concurrent.duration.FiniteDuration;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;


//#jcomponent-handlers-class
public class JAssemblyComponentHandlers extends JComponentHandlers {

    private final ActorContext<TopLevelActorMessage> ctx;
    private final ComponentInfo componentInfo;
    private final CommandResponseManager commandResponseManager;
    private final CurrentStatePublisher currentStatePublisher;
    private final ILocationService locationService;
    private final IEventService eventService;
    private ILogger log;
    private IConfigClientService configClient;
    private Map<Connection, Optional<ICommandService>> runningHcds;
    private ActorRef<DiagnosticPublisherMessages> diagnosticPublisher;
    private ActorRef<CommandResponse.SubmitResponse> commandResponseAdapter;

    public JAssemblyComponentHandlers(akka.actor.typed.javadsl.ActorContext<TopLevelActorMessage> ctx, JCswContext cswCtx) {
        super(ctx, cswCtx);
        this.ctx = ctx;
        this.componentInfo = cswCtx.componentInfo();
        this.commandResponseManager = cswCtx.commandResponseManager();
        this.currentStatePublisher = cswCtx.currentStatePublisher();
        this.locationService = cswCtx.locationService();
        this.eventService = cswCtx.eventService();
        log = cswCtx.loggerFactory().getLogger(this.getClass());
        configClient = JConfigClientFactory.clientApi(Adapter.toUntyped(ctx.getSystem()), locationService);

        runningHcds = new HashMap<>();
        commandResponseAdapter = TestProbe.<CommandResponse.SubmitResponse>create(ctx.getSystem()).ref();
        commandResponseAdapter = TestProbe.<CommandResponse.SubmitResponse>create(ctx.getSystem()).ref();
    }
    //#jcomponent-handlers-class

    //#jInitialize-handler
    @Override
    public CompletableFuture<Void> jInitialize() {

        // fetch config (preferably from configuration service)
        CompletableFuture<ConfigData> configDataCompletableFuture = getAssemblyConfig();

        // create a worker actor which is used by this assembly
        CompletableFuture<ActorRef<WorkerActorMsg>> worker =
                configDataCompletableFuture.thenApply(config -> ctx.spawnAnonymous(WorkerActor.behavior(config)));

        // find a Hcd connection from the connections provided in componentInfo
        Optional<Connection> mayBeConnection = componentInfo.getConnections().stream()
                .filter(connection -> connection.componentId().componentType() == JComponentType.HCD)
                .findFirst();

        // If an Hcd is found as a connection, resolve its location from location service and create other
        // required worker actors required by this assembly, also subscribe to HCD's filter wheel event stream
        return mayBeConnection.map(connection ->
                worker.thenAcceptBoth(resolveHcd(), (workerActor, hcdLocation) -> {
                    if (!hcdLocation.isPresent())
                        throw new HcdNotFoundException();
                    else {
                        runningHcds.put(connection, Optional.of(CommandServiceFactory.jMake(hcdLocation.orElseThrow(), ctx.getSystem())));
                        //#event-subscriber
                    }
                    diagnosticPublisher = ctx.spawnAnonymous(JDiagnosticsPublisher.behavior(CommandServiceFactory.jMake(hcdLocation.orElseThrow(), ctx.getSystem()), workerActor));
                })).orElseThrow();

    }
    //#jInitialize-handler

    //#validateCommand-handler
    @Override
    public CommandResponse.ValidateCommandResponse validateCommand(Id runId, ControlCommand controlCommand) {
        if (controlCommand instanceof Setup) {
            // validation for setup goes here
            return new CommandResponse.Accepted(runId);
        } else if (controlCommand instanceof Observe) {
            // validation for observe goes here
            return new CommandResponse.Accepted(runId);
        } else {
            return new CommandResponse.Invalid(runId, new CommandIssue.AssemblyBusyIssue("Command not supported"));
        }
    }
    //#validateCommand-handler

    //#onSubmit-handler
    @Override
    public CommandResponse.SubmitResponse onSubmit(Id runId, ControlCommand controlCommand) {
        if (controlCommand instanceof Setup)
            return submitSetup(runId, (Setup) controlCommand); // includes logic to handle Submit with Setup config command
        else if (controlCommand instanceof Observe)
            return submitObserve(runId, (Observe) controlCommand); // includes logic to handle Submit with Observe config command
        else
            return new CommandResponse.Error(runId, "Submitted command not supported: " + controlCommand.commandName().name());
    }
    //#onSubmit-handler

    //#onOneway-handler
    @Override
    public void onOneway(Id runId, ControlCommand controlCommand) {
        if (controlCommand instanceof Setup)
            onewaySetup(runId, (Setup) controlCommand); // includes logic to handle Oneway with Setup config command
        else if (controlCommand instanceof Observe)
            onewayObserve(runId, (Observe) controlCommand); // includes logic to handle Oneway with Observe config command
    }
    //#onOneway-handler

    //#onGoOffline-handler
    @Override
    public void onGoOffline() {
        // do something when going offline
    }
    //#onGoOffline-handler

    //#onGoOnline-handler
    @Override
    public void onGoOnline() {
        // do something when going online
    }
    //#onGoOnline-handler

    //#onShutdown-handler
    @Override
    public CompletableFuture<Void> jOnShutdown() {
        // clean up resources
        return new CompletableFuture<>();
    }
    //#onShutdown-handler

    //#onLocationTrackingEvent-handler
    @Override
    public void onLocationTrackingEvent(TrackingEvent trackingEvent) {
        if (trackingEvent instanceof LocationUpdated) {
            // do something for the tracked location when it is updated
        } else if (trackingEvent instanceof LocationRemoved) {
            // do something for the tracked location when it is no longer available
        }
    }
    //#onLocationTrackingEvent-handler

    private CommandResponse.SubmitResponse processSetup(Id parentId, Setup sc) {
        switch (sc.commandName().name()) {
            case "forwardToWorker":
                //#addSubCommand
                /** FIXME --- NOT SURE WHAST TO DO HERE

                 Prefix prefix1 = new Prefix("wfos.red.detector");
                Setup subCommand1 = new Setup(prefix1, new CommandName("sub-command-1"), sc.jMaybeObsId());
                commandResponseManager.addSubCommand(parentId, subCommand1.runId());

                Prefix prefix2 = new Prefix("wfos.blue.detector");
                Setup subCommand2 = new Setup(prefix2, new CommandName("sub-command-2"), sc.jMaybeObsId());
                commandResponseManager.addSubCommand(parentId, subCommand2.runId());
                //#addSubCommand
                //#subscribe-to-command-response-manager
                // subscribe to the status of original command received and publish the state when its status changes to
                // Completed
                CommandResponse.SubmitResponse submitResponse = commandResponseManager
                        .jQueryFinal(subCommand1.runId(), Timeout.create(Duration.ofSeconds(10)))
                        .join();

                if (submitResponse instanceof CommandResponse.Completed) {
                    Key<String> stringKey = JKeyType.StringKey().make("sub-command-status");
                    CurrentState currentState = new CurrentState(sc.source(), new StateName("testStateName"));
                    currentStatePublisher.publish(currentState.madd(stringKey.set("complete")));
                } else {
                    // do something
                }
                //#subscribe-to-command-response-manager

                //#updateSubCommand
                // An original command is split into sub-commands and sent to a component.
                // The result from submitting the sub-commands is used to update the CRM
                ICommandService componentCommandService = runningHcds.get(componentInfo.getConnections().get(0)).orElseThrow();
                componentCommandService.submit(subCommand2, Timeout.durationToTimeout(FiniteDuration.apply(5, TimeUnit.SECONDS)))
                        .thenAccept(commandResponse -> {
                            if (commandResponse instanceof CommandResponse.Completed) {
                                // As the commands get completed, the results are updated in the commandResponseManager
                                commandResponseManager.updateSubCommand(commandResponse);
                            } else {
                                // do something
                            }
                        });
                //#updateSubCommand

                //#query-command-response-manager
                // query CommandResponseManager to get the current status of Command, for example: Accepted/Completed/Invalid etc.
                commandResponseManager
                        .jQuery(subCommand1.runId(), Timeout.durationToTimeout(FiniteDuration.apply(5, "seconds")))
                        .thenAccept(commandResponse -> {
                            // may choose to publish current state to subscribers or do other operations
                        });

                //#query-command-response-manage
                 **/
                return new CommandResponse.Completed(parentId);

            default:
                log.error("Invalid command [" + sc + "] received.");
                return new CommandResponse.Invalid(parentId, new CommandIssue.UnsupportedCommandIssue(sc.commandName().toString()));
        }
    }

    private CommandResponse.SubmitResponse processObserve(Id runId, Observe oc) {
        switch (oc.commandName().name()) {
            case "point":
            case "acquire":
            default:
                log.error("Invalid command [" + oc + "] received.");
        }
        return new CommandResponse.Completed(runId);
    }

    /**
     * in case of submit command, component writer is required to update commandResponseManager with the result
     */
    private CommandResponse.SubmitResponse submitSetup(Id runId, Setup setup) {
        processSetup(runId, setup);
        return new CommandResponse.Started(runId);
    }

    private CommandResponse.SubmitResponse submitObserve(Id runId, Observe observe) {
        processObserve(runId, observe);
        return new CommandResponse.Completed(runId);
    }

    private void onewaySetup(Id runId, Setup setup) {
        processSetup(runId, setup);
    }

    private void onewayObserve(Id runId, Observe observe) {
        processObserve(runId, observe);
    }

    /**
     * Below methods are just here to show how exceptions can be used to either restart or stop supervisor
     * This are snipped in paradox documentation
     */

    // #failureRestart-Exception
    class HcdNotFoundException extends FailureRestart {
        HcdNotFoundException() {
            super("Could not resolve hcd location. Initialization failure.");
        }
    }

    private CompletableFuture<Optional<AkkaLocation>> resolveHcd() {
        // find a Hcd connection from the connections provided in componentInfo
        Optional<Connection> mayBeConnection = componentInfo.getConnections().stream()
                .filter(connection -> connection.componentId().componentType() == JComponentType.HCD)
                .findFirst();

        // If an Hcd is found as a connection, resolve its location from location service and create other
        // required worker actors required by this assembly
        if (mayBeConnection.isPresent()) {
            CompletableFuture<Optional<AkkaLocation>> resolve = locationService.resolve(mayBeConnection.orElseThrow().<AkkaLocation>of(), Duration.ofSeconds(5));
            return resolve.thenCompose((Optional<AkkaLocation> resolvedHcd) -> {
                if (resolvedHcd.isPresent())
                    return CompletableFuture.completedFuture(resolvedHcd);
                else
                    throw new ConfigNotAvailableException();
            });
        } else
            return CompletableFuture.completedFuture(Optional.empty());
    }
    // #failureRestart-Exception

    // #failureStop-Exception
    class ConfigNotAvailableException extends FailureStop {
        ConfigNotAvailableException() {
            super("Configuration not available. Initialization failure.");
        }
    }

    private CompletableFuture<ConfigData> getAssemblyConfig() throws ConfigNotAvailableException {
        // required configuration could not be found in the configuration service. Component can choose to stop until the configuration is made available in the
        // configuration service and started again
        return configClient.getActive(Paths.get("tromboneAssemblyContext.conf"))
                .thenApply((Optional<ConfigData> maybeConfigData) -> {
                    return maybeConfigData.<ConfigNotAvailableException>orElseThrow(() -> new ConfigNotAvailableException());
                });
    }
    // #failureStop-Exception

    private ICommandService hcd = null;

    private void resolveHcdAndCreateCommandService() throws ExecutionException, InterruptedException {

        TypedConnection<AkkaLocation> hcdConnection = componentInfo.getConnections().stream()
                .filter(connection -> connection.componentId().componentType() == JComponentType.HCD)
                .findFirst().orElseThrow().<AkkaLocation>of();

        // #resolve-hcd-and-create-commandservice
        CompletableFuture<Optional<AkkaLocation>> resolvedHcdLocation = locationService.resolve(hcdConnection, Duration.ofSeconds(5));

        CompletableFuture<ICommandService> eventualCommandService = resolvedHcdLocation.thenApply((Optional<AkkaLocation> hcdLocation) -> {
            if (hcdLocation.isPresent())
                return CommandServiceFactory.jMake(hcdLocation.orElseThrow(), ctx.getSystem());
            else
                throw new HcdNotFoundException();
        });

        eventualCommandService.thenAccept((jcommandService) -> hcd = jcommandService);
        // #resolve-hcd-and-create-commandservice
    }
}
