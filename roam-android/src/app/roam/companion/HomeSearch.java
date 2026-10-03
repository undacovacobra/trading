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
 * Address search and "what's this area called" through OpenStreetMap Nominatim. Follows its usage
 * policy: an identifying User-Agent, at most one request per second, repeated lookups from memory.
 */
final class HomeSearch {
    private static final long MIN_GAP_MS = 1_100L;
    private static final Map<String, String> CACHE = new LinkedHashMap<String, String>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 60;
        }
    };
    private static long lastRequest;

    private HomeSearch() {}

    /** GET a Nominatim URL politely, answering repeats from memory. */
    static String throttledGet(String url) throws IOException {
        synchronized (CACHE) {
            String hit = CACHE.get(url);
            if (hit != null) return hit;
        }
        String body;
        synchronized (HomeSearch.class) {
            long wait = lastRequest + MIN_GAP_MS - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted");
                }
            }
            lastRequest = System.currentTimeMillis();
            body = Http.get(url, "application/json", 300_000);
        }
        synchronized (CACHE) {
            CACHE.put(url, body);
        }
        return body;
    }

    static JSONObject fetch(String q) {
        try {
            if (q == null || q.trim().length() < 3 || q.length() > 240) throw new IOException("Invalid query");
            JSONArray found = new JSONArray(throttledGet("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&addressdetails=1&q="
                    + URLEncoder.encode(q.trim().toLowerCase(Locale.ROOT), "UTF-8")));
            JSONArray results = new JSONArray();
            for (int i = 0; i < Math.min(5, found.length()); i++) {
                JSONObject r = found.optJSONObject(i);
                if (r == null) continue;
                double lat = r.optDouble("lat", 999);
                double lng = r.optDouble("lon", 999);
                String name = label(r.optJSONObject("address"), r.optString("display_name"));
                if (name.isEmpty() || Math.abs(lat) > 90 || Math.abs(lng) > 180) continue;
                JSONObject a = r.optJSONObject("address");
                results.put(new JSONObject().put("name", Json.clip(name, 300)).put("town", a == null ? "" : town(a))
                        .put("lat", lat).put("lng", lng));
            }
            return new JSONObject().put("results", results);
        } catch (Exception e) {
            return error("Address search is unavailable right now.");
        }
    }

    /**
     * Where a point is, in words: the town for headers ("Boulder") and a street address for
     * "you're here" ("1770 13th Street, Boulder"). Never coordinates.
     */
    static JSONObject reverse(double lat, double lng) {
        try {
            String url = String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&lat=%.4f&lon=%.4f", lat, lng);
            JSONObject a = new JSONObject(throttledGet(url)).optJSONObject("address");
            if (a == null) throw new IOException("No address");
            String town = town(a);
            String street = street(a);
            String area = first(a, "neighbourhood", "suburb", "quarter");
            return new JSONObject()
                    .put("name", town.isEmpty() ? area : town)
                    .put("address", street.isEmpty() ? (area.isEmpty() || area.equals(town) ? town : area + ", " + town) : street + (town.isEmpty() ? "" : ", " + town))
                    .put("area", area);
        } catch (Exception e) {
            return error("Unknown area");
        }
    }

    static String town(JSONObject a) {
        return first(a, "city", "town", "village", "hamlet", "municipality", "suburb", "county");
    }

    /** "1770 13th Street", or just the road or place name when there's no house number. */
    static String street(JSONObject a) {
        String road = first(a, "road", "pedestrian", "footway", "path", "square", "place");
        if (road.isEmpty()) return first(a, "amenity", "building", "leisure", "tourism");
        String number = a.optString("house_number");
        return number.isEmpty() ? road : number + " " + road;
    }

    /** A readable search result: "1770 13th Street, Boulder, Colorado" or "Golden, Colorado". */
    static String label(JSONObject a, String fallback) {
        if (a != null) {
            String street = street(a);
            String town = town(a);
            String region = a.optString("state", a.optString("country"));
            StringBuilder s = new StringBuilder();
            for (String part : new String[] {street, town, region}) {
                if (part.isEmpty() || s.indexOf(part) >= 0) continue;
                if (s.length() > 0) s.append(", ");
                s.append(part);
            }
            if (s.length() > 0) return s.toString();
        }
        String[] parts = fallback.split(",\\s*");
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(3, parts.length); i++) s.append(i == 0 ? "" : ", ").append(parts[i]);
        return s.toString();
    }

    private static String first(JSONObject a, String... keys) {
        for (String k : keys) {
            String v = a.optString(k);
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    static JSONObject error(String message) {
        try {
            return new JSONObject().put("error", message);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
