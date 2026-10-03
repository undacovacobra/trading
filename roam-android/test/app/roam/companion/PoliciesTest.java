package app.roam.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalTime;
import java.util.Locale;

import org.junit.Test;

public class PoliciesTest {
    static final long DAY = 86_400_000L;

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
    public void planningCadence() {
        long created = 0;
        long every = 3 * DAY;
        assertFalse("waits a day after a plan is created", PlanningPolicy.due(false, created, 0, Long.MAX_VALUE, every, 0, 0, false, DAY - 1));
        assertTrue(PlanningPolicy.due(false, created, 0, Long.MAX_VALUE, every, 0, 0, false, DAY));
        assertFalse(PlanningPolicy.due(false, created, 0, Long.MAX_VALUE, every, 0, DAY, false, 3 * DAY));
        assertTrue(PlanningPolicy.due(false, created, 0, Long.MAX_VALUE, every, 0, DAY, false, 4 * DAY));
        assertFalse("never when off", PlanningPolicy.due(false, created, 0, Long.MAX_VALUE, 0, 0, 0, false, 9 * DAY));
    }

    @Test
    public void advanceTripGetsOneFinalIdeaTheDayBefore() {
        long start = 10 * DAY;
        long every = 7 * DAY;
        assertTrue(PlanningPolicy.due(true, 0, start, start + 3 * DAY, every, 0, 5 * DAY, false, start - DAY / 2));
        assertFalse("only once", PlanningPolicy.due(true, 0, start, start + 3 * DAY, every, 0, start - DAY / 2, true, start - DAY / 4));
        assertFalse("stops at departure", PlanningPolicy.due(true, 0, start, start + 3 * DAY, every, 0, 0, false, start));
    }

    @Test
    public void unitsFollowTheRegion() {
        assertEquals("1.0 miles", Units.distance(1609.344, Locale.US));
        assertEquals("1.6 km", Units.distance(1609.344, Locale.GERMANY));
        assertEquals("450 m", Units.distance(447, Locale.FRANCE));
    }
}
