package csw.time.core.models;

import csw.time.clock.natives.models.TimeConstants$;
import csw.time.core.models.utils.JTestProperties;
import csw.time.core.models.utils.TestProperties;
import csw.time.core.models.utils.TestUtil;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;
import scala.concurrent.duration.FiniteDuration;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class JTMTTimeTest extends JUnitSuite {

    private static int TaiOffset = TimeConstants$.MODULE$.taiOffset();
    private int jitter = 100;
    private TestProperties testProperties = JTestProperties.instance();

    //------------------------------UTC-------------------------------

    //DEOPSCSW-532: Synchronize activities with other comp. using UTC
    //DEOPSCSW-549: Time service api
    // This test is sufficient to show code works in both Scala and Java
    // since UTCTime.now is used in both languages.
    @Test
    public void should_get_utc_time() {
        UTCTime utcTime = UTCTime.now();
        Instant fixedInstant = Instant.now();

        long expectedMillis = fixedInstant.toEpochMilli();

        assertEquals((double) expectedMillis, (double) utcTime.value().toEpochMilli(), jitter);
    }

    //DEOPSCSW-537: Scala and Java API for conversion between TAI and UTC
    //DEOPSCSW-530: SPIKE: Get TAI offset and convert to UTC and Vice Versa
    //DEOPSCSW-549: Time service api
    @Test
    public void should_convert_utc_to_tai() {
        UTCTime utcTime = UTCTime.now();
        TAITime taiTime = utcTime.toTAI();

        assertEquals(TaiOffset, Duration.between(utcTime.value(), taiTime.value()).getSeconds());
    }

    //DEOPSCSW-534: PTP accuracy and precision while reading UTC
    //DEOPSCSW-549: Time service api
    @Test
    public void should_get_maximum_precision_supported_by_system_in_utc() {
        assertFalse(TestUtil.formatWithPrecision(UTCTime.now().value(), testProperties.precision()).endsWith("000"));
    }

    //------------------------------TAI-------------------------------

    //DEOPSCSW-535: Synchronize activities with other comp, using TAI
    //DEOPSCSW-530: SPIKE: Get TAI offset and convert to UTC and Vice Versa
    //DEOPSCSW-549: Time service api
    @Test
    public void should_get_tai_time() {
        TAITime taiTime = TAITime.now();
        Instant expectedTaiInstant = Instant.now().plusSeconds(TaiOffset);

        long expectedMillis = expectedTaiInstant.toEpochMilli();

        assertEquals(expectedMillis, taiTime.value().toEpochMilli(), jitter);
    }

    //DEOPSESW-122: Time Dsl for script writer to schedule tasks after specified time
    @Test
    public void should_get_tai_time_after_specified_duration() {
        FiniteDuration duration = new FiniteDuration(1, TimeUnit.SECONDS);
        TAITime futureTime = TAITime.after(duration);

        assertEquals(futureTime.durationFromNow().toMillis(), duration.toMillis(), jitter);
    }

    //DEOPSESW-122: Time Dsl for script writer to schedule tasks after specified time
    @Test
    public void should_get_utc_time_after_specified_duration() {
        FiniteDuration duration = new FiniteDuration(1, TimeUnit.SECONDS);
        UTCTime futureTime = UTCTime.after(duration);

        assertEquals(futureTime.durationFromNow().toMillis(), duration.toMillis(), jitter);
    }


    @Test
    public void should_get_tai_offset() {
        assertEquals(TaiOffset, TAITime.offset());
    }

    //DEOPSCSW-537: Scala and Java API for conversion between TAI and UTC
    //DEOPSCSW-530: SPIKE: Get TAI offset and convert to UTC and Vice Versa
    //DEOPSCSW-549: Time service api
    @Test
    public void should_convert_tai_to_utc() {
        TAITime taiTime = TAITime.now();
        UTCTime utcTime = taiTime.toUTC();

        assertEquals(TaiOffset, Duration.between(utcTime.value(), taiTime.value()).getSeconds());
    }

    //DEOPSCSW-538: PTP accuracy and precision while reading TAI
    //DEOPSCSW-549: Time service api
    @Test
    public void should_get_maximum_precision_supported_by_system_in_tai() {
        assertFalse(TestUtil.formatWithPrecision(TAITime.now().value(), testProperties.precision()).endsWith("000"));
    }
}
