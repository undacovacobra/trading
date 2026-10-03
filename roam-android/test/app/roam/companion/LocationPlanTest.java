package app.roam.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocationPlanTest {
    static final long MIN = 60_000L;

    private static DepartureDetector settled() {
        DepartureDetector d = new DepartureDetector();
        for (long t = 0; t <= 11 * MIN; t += MIN) d.update(DepartureDetectorTest.LAT, DepartureDetectorTest.LNG, 15, t);
        return d;
    }

    @Test
    public void activeUntilSettledThenRests() {
        LocationPlan plan = new LocationPlan();
        DepartureDetector d = new DepartureDetector();
        assertEquals(LocationPlan.Mode.ACTIVE, plan.mode(d, 0));
        d = settled();
        assertEquals(LocationPlan.Mode.RESTING, plan.mode(d, 11 * MIN));
    }

    @Test
    public void lowPowerFixFarAwayWakesPreciseTracking() {
        LocationPlan plan = new LocationPlan();
        DepartureDetector d = settled();
        long now = 20 * MIN;
        assertTrue(plan.wakeFor(d, DepartureDetectorTest.north(400), DepartureDetectorTest.LNG, 80, now));
        assertEquals(LocationPlan.Mode.ACTIVE, plan.mode(d, now + MIN));
        assertEquals("falls back to resting if nothing comes of it",
                LocationPlan.Mode.RESTING, plan.mode(d, now + 4 * MIN));
    }

    @Test
    public void jitterDoesNotWakeTheGps() {
        LocationPlan plan = new LocationPlan();
        DepartureDetector d = settled();
        assertFalse(plan.wakeFor(d, DepartureDetectorTest.north(150), DepartureDetectorTest.LNG, 200, 20 * MIN));
    }

    @Test
    public void wakesAtMostEveryTenMinutes() {
        LocationPlan plan = new LocationPlan();
        DepartureDetector d = settled();
        assertTrue(plan.wakeFor(d, DepartureDetectorTest.north(400), DepartureDetectorTest.LNG, 50, 20 * MIN));
        assertFalse(plan.wakeFor(d, DepartureDetectorTest.north(400), DepartureDetectorTest.LNG, 50, 25 * MIN));
        assertTrue(plan.wakeFor(d, DepartureDetectorTest.north(400), DepartureDetectorTest.LNG, 50, 31 * MIN));
    }
}
