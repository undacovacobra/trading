package app.roam.companion;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Free fallback when there's no Google key (or this month's Google budget is spent):
 * OpenStreetMap places through Overpass, and name search through Nominatim.
 */
final class OsmPlaces {
    static final String[] OVERPASS = {
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    };
    static final String[] KEYS = {"amenity", "shop", "tourism", "leisure", "sport", "highway", "historic", "natural"};

    private OsmPlaces() {}

    static JSONArray nearby(double lat, double lng, int radius) throws IOException, JSONException {
        String query = query(lat, lng, radius);
        IOException last = null;
        for (String server : OVERPASS) {
            try {
                JSONObject r = new JSONObject(Http.post(server, "application/json",
                        "data=" + URLEncoder.encode(query, "UTF-8"), 5_000_000));
                return normalizeAll(r.optJSONArray("elements"));
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    static String query(double lat, double lng, int radius) {
        Map<String, List<String>> byKey = new LinkedHashMap<String, List<String>>();
        List<String> keys = new ArrayList<String>(PlaceTags.osmKeys());
        Collections.sort(keys);
        for (String kv : keys) {
            String[] parts = kv.split("=", 2);
            List<String> values = byKey.get(parts[0]);
            if (values == null) {
                values = new ArrayList<String>();
                byKey.put(parts[0], values);
            }
            values.add(parts[1]);
        }
        StringBuilder q = new StringBuilder("[out:json][timeout:20];(");
        for (Map.Entry<String, List<String>> e : byKey.entrySet()) {
            StringBuilder alt = new StringBuilder();
            for (String v : e.getValue()) alt.append(alt.length() == 0 ? "" : "|").append(v);
            q.append("nwr(around:").append(radius).append(',').append(lat).append(',').append(lng)
                    .append(")[\"").append(e.getKey()).append("\"~\"^(").append(alt).append(")$\"][\"name\"];");
        }
        return q.append(");out center 250;").toString();
    }

    static JSONArray normalizeAll(JSONArray elements) throws JSONException {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<String>();
        if (elements == null) return out;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject p = normalize(elements.optJSONObject(i));
            if (p != null && seen.add(p.getString("id"))) out.put(p);
        }
        return out;
    }

    static JSONObject normalize(JSONObject el) throws JSONException {
        if (el == null) return null;
        JSONObject tags = el.optJSONObject("tags");
        if (tags == null) return null;
        String name = tags.optString("name").trim();
        String type = el.optString("type");
        String osmId = el.optString("id");
        JSONObject at = el.optJSONObject("center") != null ? el.optJSONObject("center") : el;
        double lat = at.optDouble("lat", 999);
        double lng = at.optDouble("lon", 999);
        if (name.isEmpty() || !osmId.matches("[0-9]+") || Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
        if (!type.equals("node") && !type.equals("way") && !type.equals("relation")) return null;

        Set<String> placeTags = new java.util.LinkedHashSet<String>();
        String kind = null;
        for (String key : KEYS) {
            String value = tags.optString(key);
            if (value.isEmpty()) continue;
            Set<String> mapped = PlaceTags.fromOsm(key, value);
            if (mapped.isEmpty()) continue;
            placeTags.addAll(mapped);
            if (kind == null) kind = PlaceTags.osmLabel(key, value);
        }
        if (placeTags.isEmpty()) return null;

        JSONObject p = new JSONObject();
        p.put("id", "osm-" + type + "-" + osmId);
        p.put("source", "osm");
        p.put("name", Json.clip(name, 160));
        p.put("lat", lat);
        p.put("lng", lng);
        p.put("tags", new JSONArray(placeTags));
        p.put("kind", kind);
        String hours = tags.optString("opening_hours");
        if (!hours.isEmpty()) p.put("hours", new JSONObject().put("text", new JSONArray().put(Json.clip(hours, 200))));
        StringBuilder addr = new StringBuilder();
        for (String k : new String[] {"addr:housenumber", "addr:street", "addr:city"}) {
            String v = tags.optString(k);
            if (!v.isEmpty()) addr.append(addr.length() == 0 ? "" : k.equals("addr:city") ? ", " : " ").append(v);
        }
        p.put("address", addr.toString());
        p.put("mapsUrl", "https://www.google.com/maps/search/?api=1&query=" + lat + "%2C" + lng);
        String site = tags.optString("website", tags.optString("contact:website"));
        if (site.startsWith("https://") || site.startsWith("http://")) p.put("website", site);
        JSONArray photos = new JSONArray();
        String commons = tags.optString("wikimedia_commons");
        if (commons.matches("File:[^\\n]{1,300}")) photos.put(new JSONObject().put("ref", "c:" + commons).put("by", "Wikimedia Commons"));
        p.put("photos", photos);
        // Places with a Wikipedia/Wikidata entry are notable enough to be a daily pick without ratings.
        if (!tags.optString("wikidata").isEmpty() || !tags.optString("wikipedia").isEmpty()) p.put("notable", true);
        return p;
    }

    /** Name search, e.g. "tacos" or "climbing gym", near a point. */
    static JSONArray search(String q, double lat, double lng) throws IOException, JSONException {
        double d = 0.25;
        String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=20&bounded=1&extratags=1&q="
                + URLEncoder.encode(q, "UTF-8") + "&viewbox=" + (lng - d) + "," + (lat + d) + "," + (lng + d) + "," + (lat - d);
        JSONArray found = new JSONArray(HomeSearch.throttledGet(url));
        JSONArray out = new JSONArray();
        for (int i = 0; i < found.length(); i++) {
            JSONObject r = found.optJSONObject(i);
            if (r == null) continue;
            JSONObject el = new JSONObject()
                    .put("type", r.optString("osm_type"))
                    .put("id", r.optString("osm_id"))
                    .put("lat", r.optDouble("lat", 999))
                    .put("lon", r.optDouble("lon", 999));
            JSONObject tags = r.optJSONObject("extratags") != null ? new JSONObject(r.optJSONObject("extratags").toString()) : new JSONObject();
            tags.put("name", r.optString("name"));
            tags.put(r.optString("category"), r.optString("type"));
            el.put("tags", tags);
            JSONObject p = normalize(el);
            if (p != null) out.put(p);
        }
        return out;
    }
}
