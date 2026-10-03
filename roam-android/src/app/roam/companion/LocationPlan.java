package app.roam.companion;

/**
 * Decides how hard the companion should look for location, so the GPS only runs when it matters.
 *
 * ACTIVE  - precise fixes every 15 s: while moving, just after arriving, or confirming a departure.
 * RESTING - low-power fixes at most every 2 min, only after moving 50 m: while you stay put.
 *
 * In RESTING mode a low-power fix that lands well away from where you settled wakes ACTIVE mode for
 * a few minutes so the detector can confirm (or dismiss) the departure with precise fixes.
 */
public final class LocationPlan {
    public enum Mode { ACTIVE, RESTING }

    static final long ACTIVE_INTERVAL_MS = 15_000L;
    static final float ACTIVE_DISTANCE_M = 10f;
    static final long RESTING_INTERVAL_MS = 120_000L;
    static final float RESTING_DISTANCE_M = 50f;
    static final long WAKE_MS = 3 * 60_000L;
    static final long WAKE_COOLDOWN_MS = 10 * 60_000L;
    static final double WAKE_DISTANCE_M = 120;

    private long wakeUntil;
    private long lastWake = Long.MIN_VALUE / 2;

    public Mode mode(DepartureDetector detector, long now) {
        if (!detector.isSettled() || detector.isConfirming() || now < wakeUntil) return Mode.ACTIVE;
        return Mode.RESTING;
    }

    /**
     * Called for every fix while RESTING. Returns true when the fix suggests you may be leaving
     * and precise tracking should resume.
     */
    public boolean wakeFor(DepartureDetector detector, double lat, double lng, float accuracy, long now) {
        if (!detector.isSettled() || now - lastWake < WAKE_COOLDOWN_MS) return false;
        double d = detector.distanceFromAnchor(lat, lng);
        if (!Double.isFinite(d)) return false;
        // Discount half the reported accuracy so wifi/cell jitter doesn't keep waking the GPS.
        if (d - Math.min(accuracy, 1000f) / 2 < WAKE_DISTANCE_M) return false;
        lastWake = now;
        wakeUntil = now + WAKE_MS;
        return true;
    }
}
