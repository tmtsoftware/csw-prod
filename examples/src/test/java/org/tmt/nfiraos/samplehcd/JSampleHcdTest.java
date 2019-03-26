package org.tmt.nfiraos.samplehcd;

import akka.actor.typed.ActorSystem;
import akka.util.Timeout;
import csw.command.api.javadsl.ICommandService;
import csw.command.client.CommandServiceFactory;
import csw.event.api.javadsl.IEventService;
import csw.event.api.javadsl.IEventSubscriber;
import csw.location.api.javadsl.ILocationService;
import csw.location.api.javadsl.JComponentType;
import csw.location.api.models.AkkaLocation;
import csw.location.api.models.ComponentId;
import csw.location.api.models.Connection;
import csw.params.commands.CommandName;
import csw.params.commands.CommandResponse;
import csw.params.commands.Setup;
import csw.params.core.generics.Key;
import csw.params.core.generics.Parameter;
import csw.params.core.models.ObsId;
import csw.params.core.models.Prefix;
import csw.params.events.Event;
import csw.params.events.EventKey;
import csw.params.events.EventName;
import csw.params.events.SystemEvent;
import csw.params.javadsl.JKeyType;
import csw.params.javadsl.JUnits;
import csw.testkit.javadsl.FrameworkTestKitJunitResource;
import csw.testkit.javadsl.JCSWService;
import org.junit.*;
import org.junit.rules.ExpectedException;
import org.scalatestplus.junit.JUnitSuite;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

//#setup
public class JSampleHcdTest extends JUnitSuite {

    @ClassRule
    public static final FrameworkTestKitJunitResource testKit =
            new FrameworkTestKitJunitResource(Arrays.asList(JCSWService.AlarmServer, JCSWService.EventServer));


    @BeforeClass
    public static void setup() {
        testKit.spawnStandalone(com.typesafe.config.ConfigFactory.load("JSampleHcdStandalone.conf"));
    }

    @Test
    public void testHCDShouldBeLocatableUsingLocationService() throws ExecutionException, InterruptedException {
        Connection.AkkaConnection connection = new Connection.AkkaConnection(new ComponentId("JSampleHcd", JComponentType.HCD));
        ILocationService locationService = testKit.jLocationService();
        AkkaLocation location = locationService.resolve(connection, Duration.ofSeconds(10)).get().orElseThrow();

        Assert.assertEquals(connection, location.connection());
    }
//#setup

    //#subscribe
    @Test
    public void testShouldBeAbleToSubscribeToHCDEvents() throws InterruptedException {
        EventKey counterEventKey = new EventKey(new Prefix("nfiraos.samplehcd"), new EventName("HcdCounter"));
        Key<Integer> hcdCounterKey = JKeyType.IntKey().make("counter");

        IEventService eventService = testKit.jEventService();
        IEventSubscriber subscriber = eventService.defaultSubscriber();

        // wait for a bit to ensure HCD has started and published an event
        Thread.sleep(2500);


        ArrayList<Event> subscriptionEventList = new ArrayList<>();
        subscriber.subscribeCallback(Set.of(counterEventKey), subscriptionEventList::add);

        // Sleep for 5 seconds, to allow HCD to publish events
        Thread.sleep(5000);

        // Event publishing period is 2 seconds.
        // Expecting 3 events: first event on subscription
        // and two more events 2 and 4 seconds later.
        Assert.assertEquals(3, subscriptionEventList.size());

        // extract counter values to a List for comparison
        List<Integer> counterList = subscriptionEventList.stream()
                .map(ev -> {
                    SystemEvent sysEv = ((SystemEvent) ev);
                    if (sysEv.contains(hcdCounterKey)) {
                        return sysEv.parameter(hcdCounterKey).head();
                    } else {
                        return -1;
                    }
                })
                .collect(Collectors.toList());

        // we don't know exactly how long HCD has been running when this test runs,
        // so we don't know what the first value will be,
        // but we know we should get three consecutive numbers
        int counter0 = counterList.get(0);
        List<Integer> expectedCounterList = Arrays.asList(counter0, counter0+1, counter0+2);

        Assert.assertEquals(expectedCounterList, counterList);
    }
    //#subscribe

    //#submit
    private ActorSystem<Void> typedActorSystem = akka.actor.typed.javadsl.Adapter.toTyped(testKit.actorSystem());
    @Test
    public void testShouldBeAbleToSendSleepCommandToHCD() throws ExecutionException, InterruptedException {

        // Construct Setup command
        Key<Long> sleepTimeKey = JKeyType.LongKey().make("SleepTime");
        Parameter<Long> sleepTimeParam = sleepTimeKey.set(5000L).withUnits(JUnits.millisecond);

        Setup setupCommand = new Setup(new Prefix("test"), new CommandName("sleep"), Optional.of(new ObsId("2018A-001"))).add(sleepTimeParam);

        Timeout commandResponseTimeout = new Timeout(10, TimeUnit.SECONDS);

        Connection.AkkaConnection connection = new Connection.AkkaConnection(new ComponentId("JSampleHcd", JComponentType.HCD));
        ILocationService locationService = testKit.jLocationService();
        AkkaLocation location = locationService.resolve(connection, Duration.ofSeconds(10)).get().orElseThrow();

        ICommandService hcd = CommandServiceFactory.jMake(location, typedActorSystem);
        CommandResponse.SubmitResponse sr = hcd.submit(setupCommand, commandResponseTimeout).get();
        // Check type
        Assert.assertTrue(sr instanceof CommandResponse.Completed);
    }
    //#submit

    //#exception
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testShouldGetExecutionExceptionIfSubmitTimeoutIsTooSmall() throws ExecutionException, InterruptedException {

        // Construct Setup command
        Key<Long> sleepTimeKey = JKeyType.LongKey().make("SleepTime");
        Parameter<Long> sleepTimeParam = sleepTimeKey.set(5000L).withUnits(JUnits.millisecond);

        Setup setupCommand = new Setup(new Prefix("test"), new CommandName("sleep"), Optional.of(new ObsId("2018A-001"))).add(sleepTimeParam);

        Timeout commandResponseTimeout = new Timeout(1, TimeUnit.SECONDS);

        Connection.AkkaConnection connection = new Connection.AkkaConnection(new ComponentId("JSampleHcd", JComponentType.HCD));
        ILocationService locationService = testKit.jLocationService();
        AkkaLocation location = locationService.resolve(connection, Duration.ofSeconds(10)).get().orElseThrow();

        ICommandService hcd = CommandServiceFactory.jMake(location, typedActorSystem);

        thrown.expect(ExecutionException.class);
        hcd.submit(setupCommand, commandResponseTimeout).get();
    }
    //#exception
}
