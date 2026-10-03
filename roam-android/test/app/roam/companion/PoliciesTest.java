package app.roam.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalTime;
import java.util.Locale;

import org.junit.Test;

public class PoliciesTest {
    @Test
    public void quietHoursWrapPastMidnight() {
        assertTrue(NudgePolicy.quiet("22:00", "08:00", LocalTime.of(23, 30)));
        assertTrue(NudgePolicy.quiet("22:00", "08:00", LocalTime.of(7, 59)));
        assertFalse(NudgePolicy.quiet("22:00", "08:00", LocalTime.of(8, 0)));
        assertTrue(NudgePolicy.quiet("13:00", "14:00", LocalTime.of(13, 15)));
        assertFalse("equal times turn quiet hours off", NudgePolicy.quiet("09:00", "09:00", LocalTime.of(9, 0)));
        assertFalse(NudgePolicy.quiet("", "nonsense", LocalTime.NOON));
    }

    @Test
    public void nudgeLimits() {
        long now = 1_000_000_000L;
        assertTrue(NudgePolicy.allowed(3, 2, false, false, 0, now));
        assertFalse("daily limit", NudgePolicy.allowed(3, 3, false, false, 0, now));
        assertFalse("quiet", NudgePolicy.allowed(3, 0, true, false, 0, now));
        assertFalse("busy", NudgePolicy.allowed(3, 0, false, true, 0, now));
        assertFalse("15 minute spacing", NudgePolicy.allowed(3, 0, false, false, now - 10 * 60_000L, now));
        assertTrue(NudgePolicy.allowed(3, 0, false, false, now - 16 * 60_000L, now));
    }

    @Test
    public void unitsFollowTheRegion() {
        assertEquals("1.0 miles", Units.distance(1609.344, Locale.US));
        assertEquals("1.6 km", Units.distance(1609.344, Locale.GERMANY));
        assertEquals("450 m", Units.distance(447, Locale.FRANCE));
    }
}
