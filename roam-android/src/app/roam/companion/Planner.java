package app.roam.companion;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Chooses the next "near home" or "before your trip" idea, mirroring evaluate() in planning.js.
 * Native extras: live OpenStreetMap places near the plan, and a calendar check for timed events.
 */
final class Planner {
    interface Sources {
        /** Live candidates near a plan's origin. */
        JSONArray liveNear(double lat, double lng, long now);
        /** True if the phone calendar has something between start and end. */
        boolean busyBetween(long start, long end);
    }

    static final class Pick {
        final JSONObject task;
        final JSONObject place;
        Pick(JSONObject task, JSONObject place) {
            this.task = task;
            this.place = place;
        }
    }

    private Planner() {}

    /** Combines the web app's ledger with the native one, keeping the most recent entry per plan. */
    static JSONObject mergeLedger(JSONObject nativeLedger, JSONObject webLedger) throws JSONException {
        JSONObject out = new JSONObject(nativeLedger.toString());
        if (webLedger == null) return out;
        Iterator<String> keys = webLedger.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject web = webLedger.optJSONObject(key);
            JSONObject mine = out.optJSONObject(key);
            if (web != null && (mine == null || web.optLong("last") > mine.optLong("last"))) out.put(key, web);
        }
        return out;
    }

    static ZoneId zone(JSONObject task) {
        try {
            String z = task.optString("zone", "");
            if (!z.isEmpty()) return ZoneId.of(z);
        } catch (Exception ignored) {
            // fall through to the phone's zone
        }
        return ZoneId.systemDefault();
    }

    static Pick choose(JSONObject planning, JSONObject ledger, JSONArray replies, boolean homeAway,
                       long now, Sources sources) throws JSONException {
        JSONArray tasks = planning.optJSONArray("tasks");
        if (tasks == null) return null;
        JSONObject planned = planning.optJSONObject("planned");
        for (int t = 0; t < tasks.length(); t++) {
            if (Thread.currentThread().isInterrupted()) return null;
            JSONObject task = tasks.optJSONObject(t);
            if (task == null) continue;
            String key = task.optString("key");
            boolean advance = "advance".equals(task.optString("kind"));
            if (!advance && homeAway) continue;
            JSONObject entry = ledger.optJSONObject(key);
            if (entry == null) entry = new JSONObject();
            boolean finalSent = entry.optBoolean("final") && entry.optLong("finalFor") == task.optLong("start");
            if (!PlanningPolicy.due(advance, task.optLong("created"), task.optLong("start"), task.optLong("end"),
                    task.optLong("every"), task.optLong("jitter"), entry.optLong("last"), finalSent, now)) {
                continue;
            }
            JSONArray sent = entry.optJSONArray("ids");
            ZoneId zone = zone(task);
            ZonedDateTime local = Instant.ofEpochMilli(now).atZone(zone);
            int month = Instant.ofEpochMilli(advance ? task.optLong("start") : now).atZone(zone).getMonthValue();

            for (JSONObject c : ranked(task, advance, now, sources)) {
                String id = c.optString("id");
                if (c.optLong("snoozeUntil") > now) continue;
                if (planned != null && planned.optBoolean(key + "|" + id)) continue;
                if (Json.contains(sent, id)) continue;
                if (Suggestions.declined(replies, id, now)) continue;
                if (!Suggestions.inSeason(c.optJSONArray("months"), month)) continue;
                if (!advance && c.optBoolean("lateNight") && local.getHour() < 17) continue;
                long start = c.optLong("start");
                if (start > 0) {
                    if (start <= now || (!advance && start > now + 7 * Suggestions.DAY)) continue;
                    if (sources.busyBetween(start, start + 3 * 3_600_000L)) continue;
                }
                return new Pick(task, c);
            }
        }
        return null;
    }

    /** The plan's own candidates plus live places near it, best first. */
    private static List<JSONObject> ranked(JSONObject task, boolean advance, long now, Sources sources) throws JSONException {
        List<JSONObject> list = new ArrayList<JSONObject>();
        HashSet<String> ids = new HashSet<String>();
        JSONArray given = task.optJSONArray("candidates");
        if (given != null) {
            for (int i = 0; i < given.length(); i++) {
                JSONObject c = given.optJSONObject(i);
                // Live places are re-read from the native cache below so their details are fresh.
                if (c == null || c.optString("id").startsWith("osm-")) continue;
                list.add(c);
                ids.add(c.optString("id"));
            }
        }
        JSONObject origin = task.optJSONObject("origin");
        if (origin != null) {
            JSONArray live = sources.liveNear(origin.optDouble("lat"), origin.optDouble("lng"), now);
            for (int i = 0; i < live.length(); i++) {
                JSONObject c = live.optJSONObject(i);
                if (c == null || !ids.add(c.optString("id"))) continue;
                c.put("body", advance
                        ? "Before " + task.optString("name") + ": check venue booking, hours and access. Fits your saved interests."
                        : "Near home: a place that fits your saved interests. Check current hours before going.");
                list.add(c);
            }
        }
        Collections.sort(list, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Double.compare(b.optDouble("rank", 0), a.optDouble("rank", 0));
            }
        });
        return list;
    }

    /** Records a delivered idea so the same plan doesn't repeat it. */
    static void record(JSONObject ledger, JSONObject task, String placeId, long now) throws JSONException {
        String key = task.optString("key");
        JSONObject entry = ledger.optJSONObject(key);
        if (entry == null) entry = new JSONObject();
        JSONArray ids = entry.optJSONArray("ids");
        if (ids == null) ids = new JSONArray();
        ids.put(placeId);
        entry.put("ids", ids);
        entry.put("last", now);
        if ("advance".equals(task.optString("kind")) && now >= task.optLong("start") - Suggestions.DAY) {
            entry.put("final", true);
            entry.put("finalFor", task.optLong("start"));
        }
        ledger.put(key, entry);
    }

    /** Away from home: during a known trip, or a recent fix more than 75 miles from home. */
    static boolean homeAway(JSONObject planning, JSONObject fix, long now) {
        JSONArray away = planning.optJSONArray("awayIntervals");
        if (away != null) {
            for (int i = 0; i < away.length(); i++) {
                JSONObject a = away.optJSONObject(i);
                if (a != null && a.optLong("start") <= now && a.optLong("end") > now) return true;
            }
        }
        JSONObject home = planning.optJSONObject("home");
        if (home == null || fix == null || !fix.has("lat")) return false;
        if (fix.optDouble("accuracy", 999) > 200 || now - fix.optLong("at") >= Suggestions.DAY) return false;
        return DepartureDetector.meters(home.optDouble("lat"), home.optDouble("lng"),
                fix.optDouble("lat"), fix.optDouble("lng")) > 120_700;
    }
}
