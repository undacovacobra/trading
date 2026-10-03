package app.roam.companion;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.json.JSONArray;
import org.json.JSONObject;

/** Small pure rules shared by background suggestions: replies, delivery counts, calendar. */
final class Suggestions {
    static final long DAY = 86_400_000L;

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
}
