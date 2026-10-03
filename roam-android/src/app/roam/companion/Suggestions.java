package app.roam.companion;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pure selection rules shared by the departure companion and the planning job. */
final class Suggestions {
    static final long DAY = 86_400_000L;
    static final double DEPARTURE_MIN_M = 250;
    static final double DEPARTURE_MAX_M = 3218;      // about 2 miles
    static final double AWAY_FROM_DEPARTED_M = 160;  // don't suggest the place you just left
    static final double METERS_PER_MINUTE = 670;     // ~40 km/h, used to judge detours

    private Suggestions() {}

    /** A "no", or a "not now" in the last day, rules a place out. */
    static boolean declined(JSONArray replies, String placeId, long now) {
        for (int i = 0; i < replies.length(); i++) {
            JSONObject r = replies.optJSONObject(i);
            if (r == null || !placeId.equals(r.optString("placeId"))) continue;
            String feeling = r.optString("feeling");
            if ("no".equals(feeling)) return true;
            if ("later".equals(feeling) && now - r.optLong("at") < DAY) return true;
        }
        return false;
    }

    static int sentOn(JSONArray deliveries, LocalDate day, ZoneId zone) {
        int n = 0;
        for (int i = 0; i < deliveries.length(); i++) {
            if (Instant.ofEpochMilli(deliveries.optLong(i)).atZone(zone).toLocalDate().equals(day)) n++;
        }
        return n;
    }

    static boolean busyIn(JSONArray busy, long now) {
        if (busy == null) return false;
        for (int i = 0; i < busy.length(); i++) {
            JSONObject b = busy.optJSONObject(i);
            if (b != null && b.optLong("start") <= now && b.optLong("end") > now) return true;
        }
        return false;
    }

    static boolean inSeason(JSONArray months, int month) {
        if (months == null) return true;
        for (int i = 0; i < months.length(); i++) {
            if (months.optInt(i) == month) return true;
        }
        return false;
    }

    /** Places the web app ranked for you near your usual spots. */
    static JSONArray departureCandidates(JSONObject snapshot) {
        JSONArray places = snapshot.optJSONArray("places");
        return places == null ? new JSONArray() : places;
    }

    /**
     * Picks the best place to suggest right after leaving somewhere, or null.
     * Ranks by the web app's score, minus distance and any detour from the remembered route.
     */
    static JSONObject bestAfterDeparture(JSONObject snapshot, JSONArray candidates, JSONArray replies,
                                         double lat, double lng, double leftLat, double leftLng,
                                         long now, ZonedDateTime local) {
        JSONObject route = snapshot.optJSONObject("route");
        int detourBudget = snapshot.optInt("detour", 15);
        JSONObject best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject p = candidates.optJSONObject(i);
            if (p == null) continue;
            double plat = p.optDouble("lat");
            double plng = p.optDouble("lng");
            double d = DepartureDetector.meters(lat, lng, plat, plng);
            if (!Double.isFinite(d) || d < DEPARTURE_MIN_M || d > DEPARTURE_MAX_M) continue;
            if (Double.isFinite(leftLat) && DepartureDetector.meters(leftLat, leftLng, plat, plng) < AWAY_FROM_DEPARTED_M) continue;
            if (p.optBoolean("rejected") || p.optLong("snoozeUntil") > now) continue;
            if (declined(replies, p.optString("id"), now)) continue;
            if (!inSeason(p.optJSONArray("months"), local.getMonthValue())) continue;
            if (p.optBoolean("lateNight") && local.getHour() < 17) continue;
            long start = p.optLong("start");
            if (start > 0 && (start < now || start > now + 3 * 3_600_000L)) continue;

            double score = p.optDouble("rank", 0) - d / 500;
            if (route != null && route.has("lat")) {
                double rlat = route.optDouble("lat");
                double rlng = route.optDouble("lng");
                double detour = d + DepartureDetector.meters(plat, plng, rlat, rlng)
                        - DepartureDetector.meters(lat, lng, rlat, rlng);
                // Within your detour budget a stop costs a little; beyond it, it costs a lot.
                score -= detour / (detour / METERS_PER_MINUTE <= detourBudget ? 500 : 250);
            }
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }
}
