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

    private Places() {}

    static String cell(double lat, double lng) {
        return String.format(Locale.US, "%d,%d", Math.round(lat / CELL), Math.round(lng / CELL));
    }

    static JSONObject nearby(Context c, double lat, double lng, boolean force) {
        if (!valid(lat, lng)) return HomeSearch.error("Invalid location");
        boolean google = Keys.has(c, Keys.GOOGLE);
        FileCache cache = new FileCache(c, "places", 30_000_000);
        String key = FileCache.key((google ? "g:" : "o:") + cell(lat, lng));
        String fresh = force ? null : cache.getText(key, FRESH_MS);
        if (fresh != null) return Json.object(fresh);
        String notice = null;
        try {
            JSONArray places = null;
            String source = "google";
            if (google) {
                try {
                    places = GooglePlaces.nearby(c, lat, lng);
                    if (places == null) notice = "This month's Google budget is used up, so you're seeing free map data until the 1st.";
                } catch (GooglePlaces.KeyProblem e) {
                    notice = e.getMessage();
                }
            }
            String saveAs = key;
            if (places == null) {
                String stale = google ? cache.getText(key, STALE_MS) : null;
                if (stale != null) return Json.object(stale).put("notice", notice);
                // Free map data, cached under its own name so a later Google key isn't masked by it.
                saveAs = FileCache.key("o:" + cell(lat, lng));
                String osm = force ? null : cache.getText(saveAs, FRESH_MS);
                if (osm != null) return Json.object(osm).put("notice", notice);
                source = "osm";
                places = OsmPlaces.nearby(lat, lng, 8000);
            }
            JSONObject out = new JSONObject()
                    .put("places", places)
                    .put("source", source)
                    .put("fetchedAt", System.currentTimeMillis())
                    .put("origin", new JSONObject().put("lat", lat).put("lng", lng));
            cache.putText(saveAs, out.toString());
            if (notice != null) out.put("notice", notice);
            return out;
        } catch (Exception e) {
            String stale = cache.getText(key, STALE_MS);
            if (stale != null) {
                try {
                    return Json.object(stale).put("notice", "Couldn't refresh places, showing what Roam found earlier.");
                } catch (JSONException ignored) {
                    // fall through
                }
            }
            return HomeSearch.error(notice != null ? notice : "Places are unavailable right now. Check your connection.");
        }
    }

    static JSONObject search(Context c, String q, double lat, double lng) {
        if (q == null || q.trim().length() < 2 || q.length() > 120 || !valid(lat, lng)) return HomeSearch.error("Type a little more.");
        String query = q.trim();
        boolean google = Keys.has(c, Keys.GOOGLE);
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
            return HomeSearch.error(e.getMessage());
        } catch (Exception e) {
            return HomeSearch.error("Search is unavailable right now.");
        }
    }

    static boolean valid(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
    }
}
