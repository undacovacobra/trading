package app.roam.companion;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Address search through OpenStreetMap Nominatim. Follows its usage policy: an identifying
 * User-Agent, at most one request per second, and repeated searches answered from memory.
 */
final class HomeSearch {
    private static final long MIN_GAP_MS = 1_100L;
    private static final Map<String, JSONObject> CACHE = new LinkedHashMap<String, JSONObject>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JSONObject> eldest) {
            return size() > 50;
        }
    };
    private static long lastRequest;

    private HomeSearch() {}

    static JSONObject fetch(String q) {
        JSONObject out = new JSONObject();
        try {
            if (q == null || q.trim().length() < 3 || q.length() > 240) throw new IOException("Invalid query");
            String key = q.trim().toLowerCase(Locale.ROOT);
            synchronized (CACHE) {
                JSONObject hit = CACHE.get(key);
                if (hit != null) return hit;
            }
            String body;
            synchronized (HomeSearch.class) {
                long wait = lastRequest + MIN_GAP_MS - System.currentTimeMillis();
                if (wait > 0) Thread.sleep(wait);
                lastRequest = System.currentTimeMillis();
                body = Http.get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q="
                        + URLEncoder.encode(q.trim(), "UTF-8"), "application/json", 100_000);
            }
            JSONArray found = new JSONArray(body);
            JSONArray results = new JSONArray();
            for (int i = 0; i < Math.min(5, found.length()); i++) {
                JSONObject r = found.optJSONObject(i);
                if (r == null) continue;
                double lat = r.optDouble("lat", 999);
                double lng = r.optDouble("lon", 999);
                String name = r.optString("display_name");
                if (name.isEmpty() || Math.abs(lat) > 90 || Math.abs(lng) > 180) continue;
                results.put(new JSONObject().put("name", Json.clip(name, 500)).put("lat", lat).put("lng", lng));
            }
            out.put("results", results);
            
            synchronized (CACHE) {
                CACHE.put(key, out);
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // reported below
        }
        try {
            out = new JSONObject().put("error", "Address search is unavailable. Try coordinates or your current location.");
        } catch (JSONException ignored) {
            // constant string
        }
        return out;
    }
}
