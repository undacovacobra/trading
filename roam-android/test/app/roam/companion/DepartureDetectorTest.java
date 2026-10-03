package app.roam.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DepartureDetectorTest {
    static final double LAT = 40.0150;
    static final double LNG = -105.2705;
    static final long MIN = 60_000L;

    /** Shifts latitude by a number of metres north. */
    static double north(double meters) {
        return LAT + meters / 111_195.0;
    }

    private static DepartureDetector settled() {
        DepartureDetector d = new DepartureDetector();
        for (long t = 0; t <= 11 * MIN; t += MIN) assertFalse(d.update(LAT, LNG, 15, t));
        assertTrue(d.isSettled());
        return d;
    }

    @Test
    public void detectsLeavingAfterStayingTenMinutes() {
        DepartureDetector d = settled();
        long t = 12 * MIN;
        assertFalse("first fix outside only starts confirming", d.update(north(400), LNG, 15, t));
        assertTrue(d.isConfirming());
        assertTrue("confirmed 30 s later", d.update(north(450), LNG, 15, t + 31_000));
        assertEquals(LAT, d.departedLat(), 1e-9);
        assertFalse(d.isSettled());
    }

    @Test
    public void ignoresBriefVisits() {
        DepartureDetector d = new DepartureDetector();
        for (long t = 0; t <= 5 * MIN; t += MIN) d.update(LAT, LNG, 15, t);
        assertFalse(d.update(north(500), LNG, 15, 6 * MIN));
        assertFalse(d.update(north(550), LNG, 15, 7 * MIN));
    }

    @Test
    public void ignoresInaccurateFixes() {
        DepartureDetector d = settled();
        assertFalse(d.update(north(800), LNG, 300, 12 * MIN));
        assertFalse(d.update(north(800), LNG, 300, 13 * MIN));
        assertTrue(d.isSettled());
    }

    @Test
    public void wanderingInsideTheBufferIsNotLeaving() {
        DepartureDetector d = settled();
        assertFalse(d.update(north(150), LNG, 15, 12 * MIN));
        assertFalse(d.update(north(180), LNG, 15, 13 * MIN));
        assertTrue(d.isSettled());
    }

    @Test
    public void quietGapWhileRestingKeepsThePlace() {
        // RESTING mode can go an hour without a fix while you stay put.
        DepartureDetector d = settled();
        assertFalse(d.update(LAT, LNG, 30, 71 * MIN));
        assertTrue("an hour of silence still counts as being here", d.isSettled());
        assertFalse(d.update(north(500), LNG, 20, 72 * MIN));
        assertTrue(d.update(north(520), LNG, 20, 72 * MIN + 31_000));
    }

    @Test
    public void veryLongGapStartsFresh() {
        DepartureDetector d = settled();
        assertFalse(d.update(north(5000), LNG, 20, 4 * 60 * MIN));
        assertFalse(d.update(north(5000), LNG, 20, 4 * 60 * MIN + 31_000));
        assertFalse(d.isSettled());
    }

    @Test
    public void metersIsAboutRight() {
        assertEquals(1000, DepartureDetector.meters(LAT, LNG, north(1000), LNG), 2);
    }
}
