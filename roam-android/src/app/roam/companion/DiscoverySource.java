package app.roam.companion;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Finds real places nearby from OpenStreetMap through the Overpass API.
 *
 * Results are cached on the phone for 30 minutes per ~1 km cell (the web app keeps its own 6 hour
 * cache on top), and a second public Overpass server is tried when the first is busy. The old
 * fallback to the OpenStreetMap editing API is gone: that API is reserved for map editors.
 */
final class DiscoverySource {
    static final String[] OVERPASS = {
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    };
    static final long CACHE_MS = 30 * 60_000L;
    static final long BACKGROUND_REFRESH_MS = 6 * 3_600_000L;
    static final int MAX_PLACES = 180;

    private DiscoverySource() {}

    static JSONObject fetch(Context c, double lat, double lng, int radius) {
        JSONObject out = new JSONObject();
        try {
            if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180
                    || radius < 500 || radius > 30_000) {
                throw new IOException("Invalid origin");
            }
            File cache = new File(new File(c.getCacheDir(), "discovery"),
                    String.format(Locale.US, "%.2f_%.2f_%d.json", lat, lng, radius));
            JSONObject cached = readCache(cache);
            if (cached != null) return cached;

            JSONArray rules = rules(c);
            JSONObject response = null;
            IOException last = null;
            String query = query(rules, lat, lng, radius);
            for (String server : OVERPASS) {
                try {
                    response = new JSONObject(Http.post(server, "application/json",
                            "data=" + URLEncoder.encode(query, "UTF-8"), 5_000_000));
                    break;
                } catch (IOException e) {
                    last = e;
                }
            }
            if (response == null) throw last != null ? last : new IOException("Unavailable");

            long now = System.currentTimeMillis();
            out.put("places", normalize(response.getJSONArray("elements"), rules, now));
            out.put("fetchedAt", now);
            out.put("source", "OpenStreetMap");
            out.put("origin", new JSONObject().put("lat", lat).put("lng", lng));
            out.put("radius", radius);
            out.put("limited", false);
            NativeStore.cachePlaces(c, out.getJSONArray("places"));
            writeCache(cache, out);
            return out;
        } catch (Exception e) {
            try {
                out = new JSONObject();
                out.put("error", "Live places are unavailable right now. Your saved places are still available.");
            } catch (JSONException ignored) {
                // constant string
            }
            return out;
        }
    }

    /** Background refresh used by the companion and planner, at most every 6 hours per area. */
    static void refreshInBackground(Context c, double lat, double lng) {
        long now = System.currentTimeMillis();
        String key = "discovery:" + String.format(Locale.US, "%.2f,%.2f", lat, lng);
        if (now - NativeStore.prefs(c).getLong(key, 0) < BACKGROUND_REFRESH_MS) return;
        NativeStore.prefs(c).edit().putLong(key, now).apply();
        if (fetch(c, lat, lng, 6000).has("error")) {
            // Try again in about an hour instead of six.
            NativeStore.prefs(c).edit().putLong(key, now - 5 * 3_600_000L).apply();
        }
    }

    static String query(JSONArray rules, double lat, double lng, int radius) throws JSONException {
        Map<String, Set<String>> byKey = new LinkedHashMap<String, Set<String>>();
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            String key = rule.getString("key");
            Set<String> values = byKey.get(key);
            if (values == null) {
                values = new LinkedHashSet<String>();
                byKey.put(key, values);
            }
            JSONArray v = rule.getJSONArray("values");
            for (int j = 0; j < v.length(); j++) values.add(v.getString(j));
        }
        StringBuilder q = new StringBuilder("[out:json][timeout:20];(");
        for (Map.Entry<String, Set<String>> e : byKey.entrySet()) {
            StringJoiner alt = new StringJoiner("|");
            for (String v : e.getValue()) alt.add(v);
            q.append("nwr(around:").append(radius).append(',').append(lat).append(',').append(lng)
                    .append(")[\"").append(e.getKey()).append("\"~\"^(").append(alt).append(")$\"][\"name\"]; ");
        }
        return q.append(");out center ").append(MAX_PLACES).append(';').toString();
    }

    static JSONArray normalize(JSONArray elements, JSONArray rules, long now) throws JSONException {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < elements.length() && out.length() < MAX_PLACES; i++) {
            JSONObject el = elements.optJSONObject(i);
            if (el == null) continue;
            JSONObject tags = el.optJSONObject("tags");
            if (tags == null) continue;
            JSONObject at = el.optJSONObject("center");
            if (at == null) at = el;
            double lat = at.optDouble("lat", 999);
            double lng = at.optDouble("lon", 999);
            String type = el.optString("type");
            String osmId = el.optString("id");
            String id = "osm-" + type + "-" + osmId;
            String name = tags.optString("name").trim();
            if (!Arrays.asList("node", "way", "relation").contains(type) || !osmId.matches("[0-9]+")
                    || seen.contains(id) || name.isEmpty() || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
                continue;
            }

            JSONObject rule = null;
            Set<String> placeTags = new LinkedHashSet<String>();
            Set<String> kinds = new LinkedHashSet<String>();
            for (int r = 0; r < rules.length(); r++) {
                JSONObject candidate = rules.getJSONObject(r);
                if (!Json.contains(candidate.optJSONArray("values"), tags.optString(candidate.optString("key")))) continue;
                if (rule == null || "sport".equals(candidate.optString("key"))) rule = candidate;
                addAll(placeTags, candidate.optJSONArray("tags"));
                addAll(kinds, candidate.optJSONArray("kinds"));
            }
            if (rule == null) continue;

            String category = rule.optString("category");
            String label = rule.optString("label");
            String website = safeWebsite(tags.optString("website", tags.optString("contact:website")));
            String hours = Json.clip(tags.optString("opening_hours"), 300);
            StringJoiner address = new StringJoiner(" ");
            for (String k : new String[] {"addr:housenumber", "addr:street", "addr:city", "addr:postcode"}) {
                if (!tags.optString(k).isEmpty()) address.add(tags.optString(k));
            }
            String addr = Json.clip(address.toString(), 300);

            JSONObject p = new JSONObject();
            p.put("id", id);
            p.put("name", Json.clip(name, 180));
            p.put("lat", lat);
            p.put("lng", lng);
            p.put("city", Json.clip(tags.optString("addr:city", "Nearby"), 100));
            p.put("address", addr);
            p.put("website", website.isEmpty() ? JSONObject.NULL : website);
            p.put("category", category);
            p.put("tags", new JSONArray(placeTags));
            p.put("kinds", new JSONArray(kinds));
            p.put("pace", rule.optString("pace"));
            p.put("price", "Check venue");
            String mood = "Outdoors".equals(category) ? "Fresh air"
                    : "Coffee & food".equals(category) ? "A little hungry"
                    : "Nightlife".equals(category) ? "Something social" : "Feeling adventurous";
            p.put("moods", new JSONArray(Arrays.asList("Open to anything", mood, "Slow & cozy")));
            p.put("description", "Mapped as " + label + ".");
            p.put("details", "OpenStreetMap lists this as " + label + (addr.isEmpty() ? "" : " at " + addr) + ". "
                    + (hours.isEmpty() ? "" : "Mapped opening hours: " + hours + ". ")
                    + "Check current hours, booking, entry and activity requirements with the venue. "
                    + "Map information can be incomplete or out of date.");
            p.put("reason", "A real place near your destination.");
            p.put("badge", "LIVE PLACE");
            p.put("runtimePhoto", true);
            p.put("lateNight", "nightclub".equals(tags.optString("amenity")));
            p.put("live", true);
            p.put("source", "OpenStreetMap");
            p.put("sourceURL", "https://www.openstreetmap.org/" + type + "/" + osmId);
            p.put("checkedAt", now);
            p.put("openingHours", hours);
            p.put("booking", "Check venue details & availability");
            String commons = tags.optString("wikimedia_commons");
            if (commons.matches("File:[^\\n]{1,300}")) p.put("commonsFile", commons);
            out.put(p);
            seen.add(id);
        }
        return out;
    }

    private static void addAll(Set<String> into, JSONArray from) {
        if (from == null) return;
        for (int i = 0; i < from.length(); i++) into.add(from.optString(i));
    }

    static JSONArray rules(Context c) throws IOException, JSONException {
        InputStream in = c.getAssets().open("discovery-types.json");
        try {
            return new JSONArray(new String(Http.read(in, 50_000), StandardCharsets.UTF_8));
        } finally {
            in.close();
        }
    }

    /** Only plain public https sites; never local network names or raw IP addresses. */
    static String safeWebsite(String value) {
        try {
            if (value == null || value.isEmpty() || value.length() > 1500) return "";
            if (value.startsWith("www.")) value = "https://" + value;
            URL u = new URL(value);
            String host = u.getHost().toLowerCase(Locale.ROOT);
            if (!Arrays.asList("https", "http").contains(u.getProtocol()) || u.getUserInfo() != null
                    || u.getPort() != -1 || !host.contains(".") || host.matches("[0-9.]+") || host.contains(":")
                    || host.matches(".*\\.(local|internal|localhost|invalid|test)$")) {
                return "";
            }
            return new URL("https", host, u.getFile()).toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static JSONObject readCache(File f) {
        if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > CACHE_MS) return null;
        try {
            InputStream in = new FileInputStream(f);
            try {
                return new JSONObject(new String(Http.read(in, 6_000_000), StandardCharsets.UTF_8));
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(File f, JSONObject data) {
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(data.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
            // the cache is optional
        }
    }
}
