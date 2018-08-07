package csw.services.alarm.client;

import akka.actor.ActorSystem;
import csw.messages.commons.CoordinatedShutdownReasons;
import csw.services.alarm.api.javadsl.IAlarmService;
import csw.services.alarm.api.models.AlarmSeverity;
import csw.services.alarm.api.scaladsl.AlarmAdminService;
import csw.services.alarm.client.internal.commons.AlarmServiceConnection;
import csw.services.alarm.client.internal.helpers.AlarmServiceTestSetup;
import csw.services.location.commons.ClusterAwareSettings;
import csw.services.location.javadsl.ILocationService;
import csw.services.location.javadsl.JLocationServiceFactory;
import csw.services.location.models.TcpRegistration;
import csw.services.logging.commons.LogAdminActorFactory;
import org.junit.*;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static csw.services.alarm.api.javadsl.JAlarmSeverity.Indeterminate;

// DEOPSCSW-481: Component Developer API available to all CSW components
public class JAlarmServiceFactoryTest  {

    private static AlarmServiceTestSetup testSetup = new AlarmServiceTestSetup();
    private AlarmServiceFactory alarmServiceFactory = testSetup.alarmServiceFactory();
    private static ActorSystem seedSystem = ClusterAwareSettings.onPort(3558).system();
    private static ILocationService locationService =  JLocationServiceFactory.withSystem(seedSystem);
    private  AlarmAdminService alarmService = testSetup.alarmService();

    @BeforeClass
    public static void setup() throws ExecutionException, InterruptedException {
        locationService.register(new TcpRegistration(AlarmServiceConnection.value(), testSetup.sentinelPort(), LogAdminActorFactory.make(seedSystem))).get();
    }

    @Before
    public void beforeTest() throws Exception {
         String path = this.getClass().getResource("/test-alarms/valid-alarms.conf").getPath();
         File file = new File(path);
         Await.result(alarmService.initAlarms(file, true), new FiniteDuration(5, TimeUnit.SECONDS));
     }

     @AfterClass
     public static void teardown() throws ExecutionException, InterruptedException {
        locationService.shutdown(CoordinatedShutdownReasons.testFinishedReason()).get();
        testSetup.afterAll();
     }

     @Test
    public void shouldCreateClientAlarmServiceUsingLocationService() throws Exception {
        IAlarmService alarmServiceUsingLS =   alarmServiceFactory.jClientApi(locationService, seedSystem).get();
         alarmServiceUsingLS.setSeverity(testSetup.tromboneAxisHighLimitAlarmKey(), Indeterminate).get();

         AlarmSeverity alarmSeverity = Await.result(alarmService.getCurrentSeverity(testSetup.tromboneAxisHighLimitAlarmKey()), Duration.create(5, TimeUnit.SECONDS));
         Assert.assertEquals(alarmSeverity, Indeterminate);
     }

    @Test
    public void shouldCreateClientAlarmServiceUsingHostAndPort() throws Exception {
        IAlarmService alarmServiceUsingHostPort =   alarmServiceFactory.jClientApi(testSetup.hostname(), testSetup.sentinelPort(), seedSystem).get();
        alarmServiceUsingHostPort.setSeverity(testSetup.tromboneAxisHighLimitAlarmKey(), Indeterminate).get();

        AlarmSeverity alarmSeverity = Await.result(alarmService.getCurrentSeverity(testSetup.tromboneAxisHighLimitAlarmKey()), Duration.create(5, TimeUnit.SECONDS));
        Assert.assertEquals(alarmSeverity, Indeterminate);
    }
}