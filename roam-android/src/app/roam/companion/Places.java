package app.roam.companion;

import java.util.Locale;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Where the web app's places come from: Google when you've added a key and this month's budget
 * allows, otherwise OpenStreetMap. Each ~4 km area is fetched at most once a day; older results
 * are still served (with a note) if a refresh fails.
 */
final class Places {
    static final long FRESH_MS = 24 * 3_600_000L;
    static final long STALE_MS = 30L * 24 * 3_600_000L;
    static final double CELL = 0.04;
    static final double NEAR_M = 2500;
    static final long FORCE_MIN_MS = 6 * 3_600_000L;

    private Places() {}

    static String cell(double lat, double lng) {
        return String.format(Locale.US, "%d,%d", Math.round(lat / CELL), Math.round(lng / CELL));
    }

    static JSONObject nearby(Context c, double lat, double lng, boolean force) {
        if (!valid(lat, lng)) return HomeSearch.error("Invalid location");
        FileCache cache = new FileCache(c, "places", 30_000_000);
        boolean google = Keys.has(c, Keys.GOOGLE);

        // Anything Google gave us for a spot within ~2.5 km today is good enough; a "force"
        // refresh only goes back to Google once those results are over 6 hours old.
        JSONObject near = nearestFetch(c, lat, lng);
        if (google && near != null) {
            long age = System.currentTimeMillis() - near.optLong("at");
            if (age < (force ? FORCE_MIN_MS : FRESH_MS)) {
                String hit = cache.getText(near.optString("key"), FRESH_MS);
                if (hit != null) return Json.object(hit);
            }
        }

        String notice = null;
        try {
            if (google && Keys.googleReady(c)) {
                try {
                    JSONArray places = GooglePlaces.nearby(c, lat, lng);
                    if (places != null) {
                        JSONObject out = result(places, "google", lat, lng);
                        String key = FileCache.key("g:" + cell(lat, lng));
                        cache.putText(key, out.toString());
                        remember(c, lat, lng, key);
                        return out;
                    }
                    notice = "Roam has used today's Google lookups, so you're seeing saved and free map results until tomorrow.";
                } catch (GooglePlaces.KeyProblem e) {
                    Keys.block(c, e.getMessage());
                    notice = "Google refused your key. Details are in You › Connections.";
                }
            } else if (google && Keys.blocked(c)) {
                notice = "Google refused your key. Details are in You › Connections.";
            }
            // Older Google results nearby beat free map data.
            if (google && near != null) {
                String stale = cache.getText(near.optString("key"), STALE_MS);
                if (stale != null) return withNotice(Json.object(stale), notice);
            }
            String osmKey = FileCache.key("o:" + cell(lat, lng));
            String osm = cache.getText(osmKey, FRESH_MS);
            if (osm != null) return withNotice(Json.object(osm), notice);
            JSONObject out = result(OsmPlaces.nearby(lat, lng, 8000), "osm", lat, lng);
            cache.putText(osmKey, out.toString());
            return withNotice(out, notice);
        } catch (Exception e) {
            return HomeSearch.error(notice != null ? notice : "Places are unavailable right now. Check your connection.");
        }
    }

    private static JSONObject result(JSONArray places, String source, double lat, double lng) throws JSONException {
        return new JSONObject()
                .put("places", places)
                .put("source", source)
                .put("fetchedAt", System.currentTimeMillis())
                .put("origin", new JSONObject().put("lat", lat).put("lng", lng));
    }

    private static JSONObject withNotice(JSONObject o, String notice) {
        try {
            return notice == null ? o : o.put("notice", notice);
        } catch (JSONException e) {
            return o;
        }
    }

    /** The most recent Google fetch within NEAR_M of here, from a small index of past fetches. */
    static JSONObject nearestFetch(Context c, double lat, double lng) {
        JSONArray index = Json.array(NativeStore.prefs(c).getString("placesIndex", "[]"));
        JSONObject best = null;
        for (int i = 0; i < index.length(); i++) {
            JSONObject e = index.optJSONObject(i);
            if (e == null) continue;
            if (DepartureDetector.meters(lat, lng, e.optDouble("lat"), e.optDouble("lng")) > NEAR_M) continue;
            if (best == null || e.optLong("at") > best.optLong("at")) best = e;
        }
        return best;
    }

    private static synchronized void remember(Context c, double lat, double lng, String key) throws JSONException {
        JSONArray index = Json.array(NativeStore.prefs(c).getString("placesIndex", "[]"));
        index.put(new JSONObject().put("lat", lat).put("lng", lng).put("at", System.currentTimeMillis()).put("key", key));
        while (index.length() > 40) index.remove(0);
        NativeStore.prefs(c).edit().putString("placesIndex", index.toString()).apply();
    }

    static JSONObject search(Context c, String q, double lat, double lng) {
        if (q == null || q.trim().length() < 2 || q.length() > 120 || !valid(lat, lng)) return HomeSearch.error("Type a little more.");
        String query = q.trim();
        boolean google = Keys.googleReady(c);
        FileCache cache = new FileCache(c, "search", 5_000_000);
        String key = FileCache.key((google ? "g:" : "o:") + query.toLowerCase(Locale.ROOT) + "@" + cell(lat, lng));
        String cached = cache.getText(key, FRESH_MS);
        if (cached != null) return Json.object(cached);
        try {
            JSONArray places = google ? GooglePlaces.search(c, query, lat, lng) : null;
            if (places == null) places = OsmPlaces.search(query, lat, lng);
            JSONObject out = new JSONObject().put("places", places).put("query", query);
            cache.putText(key, out.toString());
            return out;
        } catch (GooglePlaces.KeyProblem e) {
            Keys.block(c, e.getMessage());
            return HomeSearch.error("Google refused your key. Details are in You › Connections.");
        } catch (Exception e) {
            return HomeSearch.error("Search is unavailable right now.");
        }
    }

    static boolean valid(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
    }
}
