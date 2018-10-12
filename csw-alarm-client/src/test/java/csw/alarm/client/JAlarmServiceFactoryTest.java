package csw.alarm.client;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import csw.alarm.api.javadsl.IAlarmService;
import csw.alarm.api.models.FullAlarmSeverity;
import csw.alarm.api.scaladsl.AlarmAdminService;
import csw.alarm.client.internal.commons.AlarmServiceConnection;
import csw.alarm.client.internal.helpers.AlarmServiceTestSetup;
import csw.location.api.javadsl.ILocationService;
import csw.location.api.models.TcpRegistration;
import csw.location.client.ActorSystemFactory;
import csw.location.client.javadsl.JHttpLocationServiceFactory;
import csw.location.server.http.JHTTPLocationService;
import org.junit.*;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static csw.alarm.api.javadsl.JAlarmSeverity.Indeterminate;

// DEOPSCSW-481: Component Developer API available to all CSW components
public class JAlarmServiceFactoryTest {
    // start location http server
    private static JHTTPLocationService jhttpLocationService = new JHTTPLocationService();

    private static AlarmServiceTestSetup testSetup = new AlarmServiceTestSetup();
    private AlarmServiceFactory alarmServiceFactory = testSetup.alarmServiceFactory();
    private static ActorSystem seedSystem = ActorSystemFactory.remote();
    private static ActorMaterializer mat = ActorMaterializer.create(seedSystem);
    private static ILocationService locationService = JHttpLocationServiceFactory.makeLocalClient(seedSystem, mat);
    private AlarmAdminService alarmService = testSetup.alarmService();

    @BeforeClass
    public static void setup() throws ExecutionException, InterruptedException {
        locationService.register(new TcpRegistration(AlarmServiceConnection.value(), testSetup.sentinelPort())).get();
    }

    @Before
    public void beforeTest() throws Exception {
        Config validAlarmsConfig = ConfigFactory.parseResources("test-alarms/valid-alarms.conf");
        Await.result(alarmService.initAlarms(validAlarmsConfig, true), new FiniteDuration(5, TimeUnit.SECONDS));
    }

    @AfterClass
    public static void teardown() throws Exception {
        Await.result(seedSystem.terminate(), FiniteDuration.create(5, TimeUnit.SECONDS));
        jhttpLocationService.afterAll();
        testSetup.afterAll();
    }

    @Test
    public void shouldCreateClientAlarmServiceUsingLocationService() throws Exception {
        IAlarmService alarmServiceUsingLS = alarmServiceFactory.jMakeClientApi(locationService, seedSystem);
        alarmServiceUsingLS.setSeverity(testSetup.tromboneAxisHighLimitAlarmKey(), Indeterminate).get();

        FullAlarmSeverity alarmSeverity = Await.result(alarmService.getCurrentSeverity(testSetup.tromboneAxisHighLimitAlarmKey()), Duration.create(5, TimeUnit.SECONDS));
        Assert.assertEquals(alarmSeverity, Indeterminate);
    }

    @Test
    public void shouldCreateClientAlarmServiceUsingHostAndPort() throws Exception {
        IAlarmService alarmServiceUsingHostPort = alarmServiceFactory.jMakeClientApi(testSetup.hostname(), testSetup.sentinelPort(), seedSystem);
        alarmServiceUsingHostPort.setSeverity(testSetup.tromboneAxisHighLimitAlarmKey(), Indeterminate).get();

        FullAlarmSeverity alarmSeverity = Await.result(alarmService.getCurrentSeverity(testSetup.tromboneAxisHighLimitAlarmKey()), Duration.create(5, TimeUnit.SECONDS));
        Assert.assertEquals(alarmSeverity, Indeterminate);
    }
}
