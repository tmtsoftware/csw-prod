package csw.framework.command;

import akka.actor.ActorSystem;
import akka.actor.testkit.typed.javadsl.TestInbox;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.internal.adapter.ActorSystemAdapter;
import akka.stream.ActorMaterializer;
import akka.util.Timeout;
import com.typesafe.config.ConfigFactory;
import csw.command.extensions.AkkaLocationExt;
import csw.command.javadsl.JCommandService;
import csw.command.messages.SupervisorLockMessage;
import csw.command.models.framework.LockingResponse;
import csw.command.models.framework.LockingResponses;
import csw.command.models.matchers.*;
import csw.command.scaladsl.CurrentStateSubscription;
import csw.location.server.http.JHTTPLocationService;
import csw.common.components.framework.SampleComponentState;
import csw.framework.internal.wiring.FrameworkWiring;
import csw.framework.internal.wiring.Standalone;
import csw.location.api.javadsl.ILocationService;
import csw.location.api.models.AkkaLocation;
import csw.location.api.models.ComponentId;
import csw.location.client.ActorSystemFactory;
import csw.location.client.javadsl.JHttpLocationServiceFactory;
import csw.logging.javadsl.JLoggingSystemFactory;
import csw.params.commands.CommandResponse;
import csw.params.commands.ControlCommand;
import csw.params.commands.Setup;
import csw.params.core.generics.Key;
import csw.params.core.generics.Parameter;
import csw.params.core.states.CurrentState;
import csw.params.core.states.DemandState;
import csw.params.core.states.StateName;
import csw.params.javadsl.JKeyType;
import io.lettuce.core.RedisClient;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.ExecutionContext;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static csw.common.components.command.ComponentStateForCommand.*;
import static csw.location.api.javadsl.JComponentType.HCD;
import static csw.location.api.models.Connection.AkkaConnection;

// DEOPSCSW-217: Execute RPC like commands
// DEOPSCSW-224: Inter component command sending
// DEOPSCSW-225: Allow components to receive commands
// DEOPSCSW-227: Distribute commands to multiple destinations
// DEOPSCSW-228: Assist Components with command completion
// DEOPSCSW-317: Use state values of HCD to determine command completion
// DEOPSCSW-321: AkkaLocation provides wrapper for ActorRef[ComponentMessage]
public class JCommandIntegrationTest {
    private static ActorSystem hcdActorSystem = ActorSystemFactory.remote();
    private ExecutionContext ec = hcdActorSystem.dispatcher();
    private static ActorMaterializer mat = ActorMaterializer.create(hcdActorSystem);

    private static JHTTPLocationService jHttpLocationService;
    private static ILocationService locationService;
    private static JCommandService hcdCmdService;
    private static AkkaLocation hcdLocation;
    private Timeout timeout = new Timeout(5, TimeUnit.SECONDS);

    @BeforeClass
    public static void setup() throws Exception {
        jHttpLocationService = new JHTTPLocationService();

        locationService = JHttpLocationServiceFactory.makeLocalClient(hcdActorSystem, mat);
        hcdLocation = getLocation();
        hcdCmdService = new JCommandService(hcdLocation, akka.actor.typed.ActorSystem.wrap(hcdActorSystem));
        JLoggingSystemFactory.start("","", "", hcdActorSystem);
    }

    @AfterClass
    public static void teardown() throws Exception {
        Await.result(hcdActorSystem.terminate(), Duration.create(20, "seconds"));
        jHttpLocationService.afterAll();
    }

    private static AkkaLocation getLocation() throws Exception {
        RedisClient redisClient = null;
        FrameworkWiring wiring = FrameworkWiring.make(hcdActorSystem, redisClient);
        Await.result(Standalone.spawn(ConfigFactory.load("mcs_hcd_java.conf"), wiring), new FiniteDuration(5, TimeUnit.SECONDS));

        AkkaConnection akkaConnection = new AkkaConnection(new ComponentId("Test_Component_Running_Long_Command_Java", HCD));
        CompletableFuture<Optional<AkkaLocation>> eventualLocation = locationService.resolve(akkaConnection, java.time.Duration.ofSeconds(5));
        Optional<AkkaLocation> maybeLocation = eventualLocation.get();
        Assert.assertTrue(maybeLocation.isPresent());

        return maybeLocation.get();
    }

    @Test
    public void testCommandExecutionBetweenComponents() throws Exception {

        // immediate response - CompletedWithResult
        Key<Integer> intKey1 = JKeyType.IntKey().make("encoder");
        Parameter<Integer> intParameter1 = intKey1.set(22, 23);
        Setup imdResCommand = new Setup(prefix(), immediateResCmd(), Optional.empty()).add(intParameter1);

        CompletableFuture<CommandResponse.SubmitResponse> imdResCmdResponseCompletableFuture = hcdCmdService.submit(imdResCommand, timeout);
        CommandResponse.SubmitResponse actualImdCmdResponse = imdResCmdResponseCompletableFuture.get();
        Assert.assertTrue(actualImdCmdResponse instanceof CommandResponse.CompletedWithResult);

        // immediate response - Invalid
        Key<Integer> intKey2 = JKeyType.IntKey().make("encoder");
        Parameter<Integer> intParameter2 = intKey2.set(22, 23);
        Setup imdInvalidCommand = new Setup(prefix(), invalidCmd(), Optional.empty()).add(intParameter2);

        //#immediate-response
        CompletableFuture<CommandResponse.SubmitResponse> eventualCommandResponse =
                hcdCmdService
                        .submit(imdInvalidCommand, timeout)
                        .thenApply(
                                response -> {
                                    if (response instanceof CommandResponse.Completed) {
                                        //do something with completed result
                                    }
                                    return response;
                                }
                        );
        //#immediate-response

        CompletableFuture<CommandResponse.SubmitResponse> imdInvalidCmdResponseCompletableFuture = hcdCmdService.submit(imdInvalidCommand, timeout);
        CommandResponse.SubmitResponse actualImdInvalidCmdResponse = imdInvalidCmdResponseCompletableFuture.get();
        Assert.assertTrue(actualImdInvalidCmdResponse instanceof CommandResponse.Invalid);

        // long running command which does not use matcher
        Key<Integer> encoder = JKeyType.IntKey().make("encoder");
        Parameter<Integer> parameter = encoder.set(22, 23);
        Setup controlCommand = new Setup(prefix(), withoutMatcherCmd(), Optional.empty()).add(parameter);

        //#query-response
        hcdCmdService.submit(controlCommand, timeout);

        // do some work before querying for the result of above command as needed
        CompletableFuture<CommandResponse.QueryResponse> queryResponseFuture = hcdCmdService.query(controlCommand.runId(), timeout);

        //#query-response

        //#subscribe-for-result
        CompletableFuture<CommandResponse.SubmitResponse> testCommandResponse =
                hcdCmdService
                        .submit(controlCommand, timeout)
                        .thenCompose(commandResponse -> {
                            if (commandResponse instanceof CommandResponse.Started) {
                                return hcdCmdService.subscribe(commandResponse.runId(), timeout);
                            } else {
                                return CompletableFuture.completedFuture(new CommandResponse.Error(commandResponse.runId(), "tests error"));
                            }
                        });
        //#subscribe-for-result

        CommandResponse.Completed expectedCmdResponse = new CommandResponse.Completed(controlCommand.runId());
        CommandResponse.SubmitResponse actualCmdResponse = testCommandResponse.get();

        Assert.assertEquals(expectedCmdResponse, actualCmdResponse);

        // DEOPSCSW-229: Provide matchers infrastructure for comparison
        // long running command which uses matcher
        Parameter<Integer> param = JKeyType.IntKey().make("encoder").set(100);
        Setup setup = new Setup(prefix(), matcherCmd(), Optional.empty()).add(parameter);

        //#matcher

        // create a DemandMatcher which specifies the desired state to be matched.
        DemandMatcher demandMatcher = new DemandMatcher(new DemandState(prefix().prefix(), new StateName("testStateName")).add(param), false, timeout);

        // create matcher instance
        Matcher matcher = new Matcher(AkkaLocationExt.RichAkkaLocation(hcdLocation).componentRef().narrow(), demandMatcher, ec, mat);

        // start the matcher so that it is ready to receive state published by the source
        CompletableFuture<MatcherResponse> matcherResponseFuture = matcher.jStart();

        // submit command and if the command is successfully validated, check for matching of demand state against current state
        CompletableFuture<CommandResponse.MatchingResponse> commandResponseToBeMatched = hcdCmdService
                .oneway(setup, timeout)
                .thenCompose(initialCommandResponse -> {
                    if (initialCommandResponse instanceof CommandResponse.Accepted) {

                        return matcherResponseFuture.thenApply(matcherResponse -> {
                            if (matcherResponse.getClass().isAssignableFrom(MatcherResponses.jMatchCompleted().getClass()))
                                return new CommandResponse.Completed(initialCommandResponse.runId());
                            else
                                return new CommandResponse.Error(initialCommandResponse.runId(), "Match not completed");
                        });

                    } else {
                        matcher.stop();
                        // Need a better error message
                        return CompletableFuture.completedFuture(new CommandResponse.Error(initialCommandResponse.runId(), "Matcher failed"));
                    }

                });

        CommandResponse.MatchingResponse actualResponse = commandResponseToBeMatched.get();
        //#matcher

        //#oneway
        CompletableFuture onewayCommandResponseF = hcdCmdService
                .oneway(setup, timeout)
                .thenAccept(initialCommandResponse -> {
                    if (initialCommandResponse instanceof CommandResponse.Accepted) {
                        //do something
                    } else if (initialCommandResponse instanceof CommandResponse.Invalid) {
                        //do something
                    } else {
                        //do something
                    }
                });

        //#oneway

        //#submit
        CompletableFuture submitCommandResponseF = hcdCmdService
                .submit(setup, timeout)
                .thenAccept(initialCommandResponse -> {
                    if (initialCommandResponse instanceof CommandResponse.Started) {
                        //do something
                    } else if (initialCommandResponse instanceof CommandResponse.Invalid) {
                        //do something
                    } else {
                        //do something
                    }
                });

        //#submit

        //#onewayAndMatch

        // create a DemandMatcher which specifies the desired state to be matched.
        StateMatcher stateMatcher = new DemandMatcher(new DemandState(prefix().prefix(), new StateName("testStateName")).add(param), false, timeout);

        // create matcher instance
        Matcher matcher1 = new Matcher(AkkaLocationExt.RichAkkaLocation(hcdLocation).componentRef().narrow(), demandMatcher, ec, mat);

        // start the matcher so that it is ready to receive state published by the source
        CompletableFuture<MatcherResponse> matcherResponse = matcher1.jStart();


        CompletableFuture<CommandResponse.MatchingResponse> matchedCommandResponse =
                hcdCmdService.onewayAndMatch(setup, stateMatcher, timeout);

        //#onewayAndMatch

        CommandResponse.Completed expectedResponse = new CommandResponse.Completed(setup.runId());
        Assert.assertEquals(expectedResponse, actualResponse);
    }

    @Test
    public void testSupervisorLock() throws ExecutionException, InterruptedException {
        // Lock scenarios
        akka.actor.typed.ActorSystem<?> typedSystem = ActorSystemAdapter.apply(hcdActorSystem);
        TestProbe<LockingResponse> probe = TestProbe.create(typedSystem);
        FiniteDuration duration = new FiniteDuration(5, TimeUnit.SECONDS);

        // Lock component
        AkkaLocationExt.RichAkkaLocation(hcdLocation).componentRef().tell(new SupervisorLockMessage.Lock(prefix(), probe.ref(), duration));
        probe.expectMessage(LockingResponses.lockAcquired());

        Key<Integer> intKey2 = JKeyType.IntKey().make("encoder");
        Parameter<Integer> intParameter2 = intKey2.set(22, 23);

        // Send command to locked component and verify that it is not allowed
        Setup imdSetupCommand = new Setup(invalidPrefix(), immediateCmd(), Optional.empty()).add(intParameter2);
        CompletableFuture<CommandResponse.SubmitResponse> lockedCmdResCompletableFuture = hcdCmdService.submit(imdSetupCommand, timeout);
        CommandResponse.SubmitResponse actualLockedCmdResponse = lockedCmdResCompletableFuture.get();

        String reason = "This component is locked by component " + prefix();
        CommandResponse.Locked expectedLockedCmdResponse = new CommandResponse.Locked(imdSetupCommand.runId());
        Assert.assertEquals(expectedLockedCmdResponse, actualLockedCmdResponse);

        // Unlock component
        AkkaLocationExt.RichAkkaLocation(hcdLocation).componentRef().tell(new SupervisorLockMessage.Unlock(prefix(), probe.ref()));
        probe.expectMessage(LockingResponses.lockReleased());

        CompletableFuture<CommandResponse.SubmitResponse> cmdAfterUnlockResCompletableFuture = hcdCmdService.submit(imdSetupCommand, timeout);
        CommandResponse.SubmitResponse actualCmdResponseAfterUnlock = cmdAfterUnlockResCompletableFuture.get();
        Assert.assertTrue(actualCmdResponseAfterUnlock instanceof CommandResponse.Completed);
    }

    @Test
    public void testCommandDistributor() throws ExecutionException, InterruptedException {
        Parameter<Integer> encoderParam = JKeyType.IntKey().make("encoder").set(22, 23);

        Setup setupHcd1 = new Setup(prefix(), shortRunning(), Optional.empty()).add(encoderParam);
        Setup setupHcd2 = new Setup(prefix(), mediumRunning(), Optional.empty()).add(encoderParam);

        HashMap<JCommandService, Set<ControlCommand>> componentsToCommands = new HashMap<JCommandService, Set<ControlCommand>>() {
            {
                put(hcdCmdService, new HashSet<ControlCommand>(Arrays.asList(setupHcd1, setupHcd2)));
            }
        };
/*
        //#aggregated-validation
        CompletableFuture<CommandResponse.SubmitResponse> cmdValidationResponseF =
                new JCommandDistributor(componentsToCommands).
                        aggregatedValidationResponse(timeout, ec, mat);
        //#aggregated-validation

        Assert.assertTrue(cmdValidationResponseF.get() instanceof CommandResponse.Started);

        //#aggregated-completion
        CompletableFuture<CommandResponse.SubmitResponse> cmdCompletionResponseF =
                new JCommandDistributor(componentsToCommands).
                        aggregatedCompletionResponse(timeout, ec, mat);
        //#aggregated-completion

        Assert.assertTrue(cmdCompletionResponseF.get() instanceof CommandResponse.Completed);
        */
    }

    // DEOPSCSW-208: Report failure on Configuration Completion command
    @Test
    public void testCommandFailure() throws ExecutionException, InterruptedException {
        // using single submitAndSubscribe API
        Key<Integer> intKey1 = JKeyType.IntKey().make("encoder");
        Parameter<Integer> intParameter1 = intKey1.set(22, 23);
        Setup failureResCommand1 = new Setup(prefix(), failureAfterValidationCmd(), Optional.empty()).add(intParameter1);
        akka.actor.typed.ActorSystem<?> typedSystem = ActorSystemAdapter.apply(hcdActorSystem);

        //#submitAndSubscribe
        CompletableFuture<CommandResponse.SubmitResponse> finalResponseCompletableFuture = hcdCmdService.submitAndSubscribe(failureResCommand1, timeout);
        CommandResponse.SubmitResponse actualValidationResponse = finalResponseCompletableFuture.get();
        //#submitAndSubscribe
        System.out.println("Final: " + actualValidationResponse);

        Assert.assertTrue(actualValidationResponse instanceof CommandResponse.Error);

        // using separate submit and subscribe API
        Setup failureResCommand2 = new Setup(prefix(), failureAfterValidationCmd(), Optional.empty()).add(intParameter1);
        CompletableFuture<CommandResponse.SubmitResponse> validationResponse = hcdCmdService.submit(failureResCommand2, timeout);
        Assert.assertTrue(validationResponse.get() instanceof CommandResponse.Started);  // This should fail but I guess not typed by compiler

        CompletableFuture<CommandResponse.SubmitResponse> finalResponse = hcdCmdService.subscribe(failureResCommand1.runId(), timeout);
        Assert.assertTrue(finalResponse.get() instanceof CommandResponse.Error);
    }
/*
    @Test
    public void testSubmitAllAndGetResponse() throws ExecutionException, InterruptedException {
        Parameter<Integer> encoderParam = JKeyType.IntKey().make("encoder").set(22, 23);

        //#submitAllAndGetResponse
        Setup setupHcd1 = new Setup(prefix(), shortRunning(), Optional.empty()).add(encoderParam);
        Setup setupHcd2 = new Setup(prefix(), mediumRunning(), Optional.empty()).add(encoderParam);

        HashMap<JCommandService, Set<ControlCommand>> componentsToCommands = new HashMap<JCommandService, Set<ControlCommand>>() {
            {
                put(hcdCmdService, new HashSet<ControlCommand>(Arrays.asList(setupHcd1, setupHcd2)));
            }
        };

        CompletableFuture<CommandResponse.SubmitResponse> commandResponse = hcdCmdService
                .submitAllAndGetResponse(
                        new HashSet<ControlCommand>(Arrays.asList(setupHcd1, setupHcd2)),
                        timeout
                );
        //#submitAllAndGetResponse

        Assert.assertTrue(commandResponse.get() instanceof CommandResponse.Accepted);  // Should fail
    }
    */
/*  TODO -- Remove
    @Test
    public void testSubmitAllAndGetFinalResponse() throws ExecutionException, InterruptedException {

        Parameter<Integer> encoderParam = JKeyType.IntKey().make("encoder").set(22, 23);

        //#submitAllAndGetFinalResponse
        Setup setupHcd1 = new Setup(prefix(), shortRunning(), Optional.empty()).add(encoderParam);
        Setup setupHcd2 = new Setup(prefix(), mediumRunning(), Optional.empty()).add(encoderParam);

        HashMap<JCommandService, Set<ControlCommand>> componentsToCommands = new HashMap<JCommandService, Set<ControlCommand>>() {
            {
                put(hcdCmdService, new HashSet<ControlCommand>(Arrays.asList(setupHcd1, setupHcd2)));
            }
        };

        CompletableFuture<CommandResponse.SubmitResponse> finalCommandResponse = hcdCmdService
                .submitAllAndGetFinalResponse(
                        new HashSet<ControlCommand>(Arrays.asList(setupHcd1, setupHcd2)),
                        timeout
                );
        //#submitAllAndGetFinalResponse

        Assert.assertTrue(finalCommandResponse.get() instanceof CommandResponse.Completed);
    }
*/
    @Test
    public void testSubscribeCurrentState() throws InterruptedException {
        Key<Integer> intKey1 = JKeyType.IntKey().make("encoder");
        Parameter<Integer> intParameter1 = intKey1.set(22, 23);
        Setup setup = new Setup(prefix(), acceptedCmd(), Optional.empty()).add(intParameter1);

        akka.actor.typed.ActorSystem<?> typedSystem = ActorSystemAdapter.apply(hcdActorSystem);
        TestProbe<CurrentState> probe = TestProbe.create(typedSystem);

        // DEOPSCSW-372: Provide an API for PubSubActor that hides actor based interaction
        //#subscribeCurrentState
        // subscribe to the current state of an assembly component and use a callback which forwards each received
        // element to a test probe actor
        CurrentStateSubscription subscription = hcdCmdService.subscribeCurrentState(currentState -> probe.ref().tell(currentState));
        //#subscribeCurrentState

        hcdCmdService.submit(setup, timeout);

        CurrentState currentState = new CurrentState(SampleComponentState.prefix().prefix(), new StateName("testStateName"));
        CurrentState expectedValidationCurrentState = currentState.add(SampleComponentState.choiceKey().set(SampleComponentState.commandValidationChoice()));
        CurrentState expectedSubmitCurrentState = currentState.add(SampleComponentState.choiceKey().set(SampleComponentState.submitCommandChoice()));
        CurrentState expectedSetupCurrentState = new CurrentState(SampleComponentState.prefix().prefix(), new StateName("testStateSetup")).madd(SampleComponentState.choiceKey().set(SampleComponentState.setupConfigChoice()), intParameter1);

        probe.expectMessage(expectedValidationCurrentState);
        probe.expectMessage(expectedSubmitCurrentState);
        probe.expectMessage(expectedSetupCurrentState);

        // unsubscribe and verify no messages received by probe
        subscription.unsubscribe();
        Thread.sleep(1000);

        hcdCmdService.submit(setup, timeout);
        probe.expectNoMessage(java.time.Duration.ofMillis(20));
    }

    @Test
    public void testSubscribeOnlyCurrentState() throws InterruptedException {
        Key<Integer> intKey1 = JKeyType.IntKey().make("encoder");
        Parameter<Integer> intParameter1 = intKey1.set(22, 23);
        Setup setup = new Setup(prefix(), acceptedCmd(), Optional.empty()).add(intParameter1);

        TestInbox<CurrentState> inbox = TestInbox.create();
        Thread.sleep(1000);

        // DEOPSCSW-434: Provide an API for PubSubActor that hides actor based interaction
        //#subscribeOnlyCurrentState
        // subscribe to the current state of an assembly component and use a callback which forwards each received
        // element to a test probe actor
        CurrentStateSubscription subscription = hcdCmdService.subscribeOnlyCurrentState(Collections.singleton(StateName.apply("testStateSetup")), currentState -> inbox.getRef().tell(currentState));
        //#subscribeOnlyCurrentState

        hcdCmdService.submit(setup, timeout);

        CurrentState currentState = new CurrentState(SampleComponentState.prefix().prefix(), new StateName("testStateName"));
        CurrentState expectedValidationCurrentState = currentState.add(SampleComponentState.choiceKey().set(SampleComponentState.commandValidationChoice()));
        CurrentState expectedSubmitCurrentState = currentState.add(SampleComponentState.choiceKey().set(SampleComponentState.submitCommandChoice()));
        CurrentState expectedSetupCurrentState = new CurrentState(SampleComponentState.prefix().prefix(), new StateName("testStateSetup")).madd(SampleComponentState.choiceKey().set(SampleComponentState.setupConfigChoice()), intParameter1);

        Thread.sleep(1000);
        List<CurrentState> receivedStates = inbox.getAllReceived();

        Assert.assertFalse(receivedStates.contains(expectedValidationCurrentState));
        Assert.assertFalse(receivedStates.contains(expectedSubmitCurrentState));
        Assert.assertTrue(receivedStates.contains(expectedSetupCurrentState));

        subscription.unsubscribe();
    }
}
