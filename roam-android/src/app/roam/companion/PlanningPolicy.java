package app.roam.companion;

/** Mirrors RoamPlanPolicy.due in planning-core.js. */
public final class PlanningPolicy {
    static final long DAY = 86_400_000L;

    private PlanningPolicy() {}

    public static boolean due(boolean advance, long created, long start, long end, long every, long jitter,
                              long last, boolean finalSent, long now) {
        if (every <= 0 || now >= end || (advance && now >= start)) return false;
        boolean finalIdea = advance && now >= start - DAY && !finalSent;
        if (finalIdea && (last == 0 || now - last >= DAY)) return true;
        long next = last > 0 ? last + every + jitter : created + DAY + jitter;
        return now >= next;
    }
}
