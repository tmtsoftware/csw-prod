package csw.event.client;

import akka.actor.Cancellable;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import csw.location.server.http.JHTTPLocationService;
import csw.params.events.Event;
import csw.params.events.Event$;
import csw.params.events.EventKey;
import csw.event.api.javadsl.IEventPublisher;
import csw.event.api.javadsl.IEventSubscription;
import csw.event.client.helpers.Utils;
import csw.event.client.internal.kafka.KafkaTestProps;
import csw.event.client.internal.redis.RedisTestProps;
import csw.event.client.internal.wiring.BaseProperties;
import org.scalatest.testng.TestNGSuite;
import org.testng.Assert;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

//DEOPSCSW-334: Publish an event
//DEOPSCSW-335: Model for EventName that encapsulates the topic(or channel ) name
//DEOPSCSW-337: Subscribe to an event based on prefix
//DEOPSCSW-349: Event Service API creation
//DEOPSCSW-395: Provide EventService handle to component developers
public class JEventPublisherTest extends TestNGSuite {

    private RedisTestProps redisTestProps;
    private KafkaTestProps kafkaTestProps;
    private JHTTPLocationService jHttpLocationService;

    private int counter = -1;
    private Cancellable cancellable;

    @BeforeSuite
    public void beforeAll() {
        redisTestProps = RedisTestProps.jCreateRedisProperties();
        kafkaTestProps = KafkaTestProps.jCreateKafkaProperties();
        redisTestProps.start();
        kafkaTestProps.start();
        jHttpLocationService = new JHTTPLocationService();
    }

    @AfterSuite
    public void afterAll() {
        redisTestProps.shutdown();
        kafkaTestProps.shutdown();
        jHttpLocationService.afterAll();
    }

    @DataProvider(name = "event-service-provider")
    public Object[] pubsubProvider() {
        return new Object[]{redisTestProps, kafkaTestProps};
    }

    //DEOPSCSW-345: Publish events irrespective of subscriber existence
    @Test(dataProvider = "event-service-provider")
    public void should_be_able_to_publish_and_subscribe_an_event(BaseProperties baseProperties) throws InterruptedException, ExecutionException, TimeoutException {
        Event event1 = Utils.makeDistinctJavaEvent(new Random().nextInt());
        EventKey eventKey = event1.eventKey();

        TestProbe probe = TestProbe.create(baseProperties.typedActorSystem());

        Set<EventKey> set = new HashSet<>();
        set.add(eventKey);

        baseProperties.jPublisher().publish(event1).get(10, TimeUnit.SECONDS);
        Thread.sleep(500); // Needed for redis set which is fire and forget operation

        IEventSubscription subscription = baseProperties.jSubscriber().subscribe(set).take(2).toMat(Sink.foreach(event -> probe.ref().tell(event)), Keep.left()).run(baseProperties.resumingMat());
        subscription.ready().get(10, TimeUnit.SECONDS);

        probe.expectMessage(event1);

        subscription.unsubscribe().get(10, TimeUnit.SECONDS);

        baseProperties.jPublisher().publish(event1).get(10, TimeUnit.SECONDS);
        probe.expectNoMessage(Duration.ofMillis(200));
    }

    //DEOPSCSW-345: Publish events irrespective of subscriber existence
    @Test(dataProvider = "event-service-provider")
    public void should_be_able_to_publish_an_event_with_duration(BaseProperties baseProperties) throws InterruptedException, TimeoutException, ExecutionException {
        List<Event> events = new ArrayList<>();
        for (int i = 11; i < 21; i++) {
            events.add(Utils.makeEvent(i));
        }

        EventKey eventKey = Utils.makeEvent(0).eventKey();

        List<Event> queue = new ArrayList<>();
        IEventSubscription subscription = baseProperties.jSubscriber().subscribe(Collections.singleton(eventKey)).toMat(Sink.foreach(queue::add), Keep.left()).run(baseProperties.resumingMat());
        subscription.ready().get(10, TimeUnit.SECONDS);
        Thread.sleep(500); // Needed for getting the latest event

        IEventPublisher publisher = baseProperties.jPublisher();
        counter = -1;
        cancellable = publisher.publish(() -> {
            counter += 1;
            return events.get(counter);
        }, Duration.ofMillis(10));

        Thread.sleep(1000);
        cancellable.cancel();

        // subscriber will receive an invalid event first as subscription happened before publishing started.
        // The 10 published events will follow
        Assert.assertEquals(11, queue.size());

        events.add(0, Event$.MODULE$.invalidEvent(eventKey));
        Assert.assertEquals(events, queue);
    }

    //DEOPSCSW-341: Allow to reuse single connection for subscribing to multiple EventKeys
    @Test(dataProvider = "event-service-provider")
    public void should_be_able_to_publish_concurrently_to_the_different_channel(BaseProperties baseProperties) throws InterruptedException, TimeoutException, ExecutionException {
        List<Event> events = new ArrayList<>();
        for (int i = 101; i < 111; i++) {
            events.add(Utils.makeDistinctJavaEvent(i));
        }

        Set<Event> queue = new HashSet<>();
        IEventSubscription subscription = baseProperties.jSubscriber().subscribe(events.stream().map(Event::eventKey).collect(Collectors.toSet())).toMat(Sink.foreach(queue::add), Keep.left()).run(baseProperties.resumingMat());
        subscription.ready().get(10, TimeUnit.SECONDS);

        baseProperties.jPublisher().publish(Source.fromIterator(events::iterator));
        Thread.sleep(1000);

        // subscriber will receive an invalid event first as subscription happened before publishing started.
        // The 10 published events will follow
        Assert.assertEquals(20, queue.size());

        List<Event> expectedEvents = events.stream().map(event -> Event$.MODULE$.invalidEvent(event.eventKey())).collect(Collectors.toList());
        expectedEvents.addAll(events);
        Assert.assertEquals(new HashSet<>(expectedEvents), queue);
    }
}
