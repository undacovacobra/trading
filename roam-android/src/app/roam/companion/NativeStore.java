package app.roam.companion;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.UUID;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Native-side state shared by the activity, the companion service and the planning job. */
final class NativeStore {
    static final int MAX_LIVE_PLACES = 900;
    static final int MAX_REPLIES = 100;
    static final int MAX_DELIVERIES = 90;

    private NativeStore() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("roam-native", Context.MODE_PRIVATE);
    }

    static JSONObject snapshot(Context c) { return Json.object(prefs(c).getString("snapshot", "{}")); }
    static JSONArray livePlaces(Context c) { return Json.array(prefs(c).getString("livePlaces", "[]")); }
    static JSONArray replies(Context c) { return Json.array(prefs(c).getString("replies", "[]")); }
    static JSONArray deliveries(Context c) { return Json.array(prefs(c).getString("deliveries", "[]")); }
    static JSONObject ledger(Context c) { return Json.object(prefs(c).getString("planningLedger", "{}")); }
    static JSONObject location(Context c) { return Json.object(prefs(c).getString("location", "{}")); }

    static boolean trackingWanted(Context c) {
        SharedPreferences p = prefs(c);
        return p.getBoolean("trackingWanted", p.getBoolean("tracking", false));
    }

    static int sentToday(Context c) {
        return Suggestions.sentOn(deliveries(c), LocalDate.now(), ZoneId.systemDefault());
    }

    static void saveLocation(Context c, double lat, double lng, float accuracy) {
        try {
            JSONObject fix = new JSONObject();
            fix.put("lat", lat);
            fix.put("lng", lng);
            fix.put("accuracy", (double) accuracy);
            fix.put("at", System.currentTimeMillis());
            prefs(c).edit().putString("location", fix.toString()).apply();
        } catch (JSONException ignored) {
            // coordinates are always finite here
        }
    }

    /** Remembers that a suggestion went out, for the daily limit and the 15-minute spacing. */
    static synchronized void recordDelivery(Context c, long now, JSONObject ledger) {
        JSONArray list = deliveries(c);
        list.put(now);
        while (list.length() > MAX_DELIVERIES) list.remove(0);
        SharedPreferences.Editor e = prefs(c).edit()
                .putLong("lastNudge", now)
                .putString("deliveries", list.toString());
        if (ledger != null) e.putString("planningLedger", ledger.toString());
        e.apply();
    }

    static synchronized void reply(Context c, String placeId, String feeling, String note) {
        try {
            JSONArray list = replies(c);
            JSONObject r = new JSONObject();
            r.put("id", UUID.randomUUID().toString());
            r.put("placeId", placeId);
            r.put("feeling", feeling);
            r.put("note", note);
            r.put("at", System.currentTimeMillis());
            JSONArray live = livePlaces(c);
            for (int i = 0; i < live.length(); i++) {
                JSONObject p = live.optJSONObject(i);
                if (p != null && placeId.equals(p.optString("id"))) {
                    r.put("place", p);
                    break;
                }
            }
            list.put(r);
            while (list.length() > MAX_REPLIES) list.remove(0);
            prefs(c).edit().putString("replies", list.toString()).apply();
        } catch (JSONException ignored) {
            // nothing sensible to store
        }
    }

    /** The web app confirms it has taken a reply. */
    static synchronized void ack(Context c, String id) {
        JSONArray list = replies(c);
        for (int i = list.length() - 1; i >= 0; i--) {
            JSONObject r = list.optJSONObject(i);
            if (r != null && id.equals(r.optString("id"))) list.remove(i);
        }
        prefs(c).edit().putString("replies", list.toString()).apply();
    }

    /** Keeps the newest live places, most recently checked last. */
    static synchronized void cachePlaces(Context c, JSONArray fresh) {
        LinkedHashMap<String, JSONObject> byId = new LinkedHashMap<String, JSONObject>();
        JSONArray old = livePlaces(c);
        for (int i = 0; i < old.length(); i++) {
            JSONObject p = old.optJSONObject(i);
            if (p != null) byId.put(p.optString("id"), p);
        }
        for (int i = 0; i < fresh.length(); i++) {
            JSONObject p = fresh.optJSONObject(i);
            if (p == null) continue;
            byId.remove(p.optString("id"));
            byId.put(p.optString("id"), p);
        }
        Iterator<String> it = byId.keySet().iterator();
        while (byId.size() > MAX_LIVE_PLACES && it.hasNext()) {
            it.next();
            it.remove();
        }
        prefs(c).edit().putString("livePlaces", new JSONArray(byId.values()).toString()).apply();
    }
}
