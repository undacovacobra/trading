package app.roam.companion;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Picks three varied ideas for the weekly notification from the candidates the web app ranked
 * the last time it was open (your tastes are already in their scores).
 */
final class WeeklyPicks {
    static final long HOUR = 3_600_000L;

    private WeeklyPicks() {}

    static List<JSONObject> choose(JSONArray candidates, long now, int count) {
        List<JSONObject> list = new ArrayList<JSONObject>();
        if (candidates == null) return list;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject c = candidates.optJSONObject(i);
            if (c == null || c.optString("id").isEmpty()) continue;
            if ("event".equals(c.optString("type"))) {
                long start = c.optLong("start");
                // Events from the next week only, and not ones starting within two hours.
                if (start < now + 2 * HOUR || start > now + 8 * 24 * HOUR) continue;
            }
            list.add(c);
        }
        Collections.sort(list, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Double.compare(b.optDouble("score", 0), a.optDouble("score", 0));
            }
        });
        List<JSONObject> picks = new ArrayList<JSONObject>();
        Set<String> kinds = new HashSet<String>();
        int events = 0;
        for (JSONObject c : list) {
            if (picks.size() >= count) break;
            JSONArray tags = c.optJSONArray("tags");
            String kind = tags != null && tags.length() > 0 ? tags.optString(0) : c.optString("type");
            boolean event = "event".equals(c.optString("type"));
            if (kinds.contains(kind) || (event && events >= 2)) continue;
            kinds.add(kind);
            if (event) events++;
            picks.add(c);
        }
        return picks;
    }

    /** "A slow coffee at Boxcar, a walk at Chautauqua, and Big Thief on Tue." */
    static String message(List<JSONObject> picks, ZonedDateTime now) {
        List<String> parts = new ArrayList<String>();
        for (JSONObject p : picks) {
            String line = p.optString("line", p.optString("name"));
            if ("event".equals(p.optString("type")) && p.optLong("start") > 0) {
                ZonedDateTime at = java.time.Instant.ofEpochMilli(p.optLong("start")).atZone(now.getZone());
                line += " on " + at.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault());
            }
            parts.add(line);
        }
        if (parts.isEmpty()) return "";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) s.append(i == parts.size() - 1 ? (parts.size() > 2 ? ", and " : " and ") : ", ");
            s.append(parts.get(i));
        }
        String text = s.toString();
        return Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".";
    }

    static String title(ZonedDateTime now) {
        DayOfWeek d = now.getDayOfWeek();
        return d == DayOfWeek.THURSDAY || d == DayOfWeek.FRIDAY || d == DayOfWeek.SATURDAY
                ? "Your weekend picks" : "Your picks for this week";
    }

    /** Next time it's `day` (1 = Monday … 7 = Sunday) at `time` ("17:00"), strictly after now. */
    static ZonedDateTime next(ZonedDateTime now, int day, String time) {
        LocalTime t;
        try {
            t = LocalTime.parse(time);
        } catch (Exception e) {
            t = LocalTime.of(17, 0);
        }
        DayOfWeek dow = DayOfWeek.of(Math.max(1, Math.min(7, day)));
        ZonedDateTime at = now.with(TemporalAdjusters.nextOrSame(dow)).with(t).withSecond(0).withNano(0);
        if (!at.isAfter(now.plusMinutes(1))) at = at.plusWeeks(1);
        return at;
    }
}
