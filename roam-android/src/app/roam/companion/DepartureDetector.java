package app.roam.companion;

/**
 * Recognises "you just left a place you were staying at".
 *
 * A place qualifies after ten minutes of accurate fixes inside a 100 m (+ accuracy) circle.
 * Leaving is confirmed by accurate fixes outside a 200 m (+ accuracy) buffer for 30 seconds.
 *
 * Unlike the original detector, a long silence between fixes does not throw the qualified place
 * away: the companion deliberately asks for few updates while you sit still, so a gap means
 * "nothing moved". Only gaps over two hours (location off, phone in a drawer) start fresh.
 */
public final class DepartureDetector {
    static final float MAX_ACCURACY_M = 60f;
    static final long QUALIFY_MS = 10 * 60_000L;
    static final long CONFIRM_MS = 30_000L;
    static final long STALE_GAP_MS = 2 * 3_600_000L;
    static final double STAY_RADIUS_M = 100;
    static final double LEAVE_RADIUS_M = 200;

    private boolean anchored;
    private boolean qualified;
    private double anchorLat;
    private double anchorLng;
    private long arrived;
    private long last = -1;
    private long outside = -1;
    private double leftLat = Double.NaN;
    private double leftLng = Double.NaN;

    /** Great-circle distance in metres. */
    public static double meters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 12_742_000.0 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
    }

    public boolean isAnchored() { return anchored; }
    /** True once you've stayed in one place long enough for leaving it to count. */
    public boolean isSettled() { return anchored && qualified && outside < 0; }
    /** True while a possible departure is being confirmed. */
    public boolean isConfirming() { return outside >= 0; }
    public double anchorLat() { return anchorLat; }
    public double anchorLng() { return anchorLng; }
    public double departedLat() { return leftLat; }
    public double departedLng() { return leftLng; }

    /** Distance from the current anchor, or NaN when there is none. */
    public double distanceFromAnchor(double lat, double lng) {
        return anchored ? meters(anchorLat, anchorLng, lat, lng) : Double.NaN;
    }

    /**
     * Feeds one fix. Returns true exactly once per confirmed departure.
     *
     * @param at monotonic time in ms (elapsed realtime)
     */
    public boolean update(double lat, double lng, float accuracy, long at) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || !(accuracy > 0) || accuracy > MAX_ACCURACY_M) {
            return false;
        }
        if (!anchored || at < last) {
            reset(lat, lng, at);
            return false;
        }
        long gap = at - last;
        last = at;
        double d = meters(anchorLat, anchorLng, lat, lng);
        if (gap > STALE_GAP_MS) {
            reset(lat, lng, at);
            return false;
        }
        if (d <= STAY_RADIUS_M + accuracy) {
            outside = -1;
            if (at - arrived >= QUALIFY_MS) qualified = true;
            return false;
        }
        if (!qualified) {
            // Still moving around: follow along without announcing anything.
            reset(lat, lng, at);
            return false;
        }
        if (d <= LEAVE_RADIUS_M + accuracy) {
            outside = -1;
            return false;
        }
        if (outside < 0) {
            outside = at;
            return false;
        }
        if (at - outside < CONFIRM_MS) return false;
        leftLat = anchorLat;
        leftLng = anchorLng;
        reset(lat, lng, at);
        return true;
    }

    private void reset(double lat, double lng, long at) {
        anchorLat = lat;
        anchorLng = lng;
        last = at;
        arrived = at;
        outside = -1;
        anchored = true;
        qualified = false;
    }
}
