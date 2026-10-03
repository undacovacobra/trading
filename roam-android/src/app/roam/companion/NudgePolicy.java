package app.roam.companion;

import java.time.LocalTime;

/** Rules every background suggestion must pass: daily limit, quiet hours, calendar, spacing. */
public final class NudgePolicy {
    static final long MIN_SPACING_MS = 90 * 60_000L;

    private NudgePolicy() {}

    public static boolean allowed(int max, int sentToday, boolean quiet, boolean busy, long lastNudge, long now) {
        if (max <= sentToday || quiet || busy) return false;
        return lastNudge == 0 || now - lastNudge >= MIN_SPACING_MS;
    }

    /** Quiet hours like 22:00-08:00 may wrap past midnight. Equal times mean quiet hours are off. */
    public static boolean quiet(String start, String end, LocalTime now) {
        LocalTime from;
        LocalTime until;
        try {
            from = LocalTime.parse(start);
            until = LocalTime.parse(end);
        } catch (Exception e) {
            return false;
        }
        if (from.equals(until)) return false;
        if (from.isBefore(until)) return !now.isBefore(from) && now.isBefore(until);
        return !now.isBefore(from) || now.isBefore(until);
    }
}
