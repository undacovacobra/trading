package app.roam.companion;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Google Places API (New): nearby places by kind, text search, and the shape Roam's web app uses.
 * Every request counts against {@link Keys}' monthly budget.
 */
final class GooglePlaces {
    static final String NEARBY = "https://places.googleapis.com/v1/places:searchNearby";
    static final String TEXT = "https://places.googleapis.com/v1/places:searchText";
    static final String FIELDS = "places.id,places.displayName,places.location,places.types,places.primaryType,"
            + "places.primaryTypeDisplayName,places.rating,places.userRatingCount,places.priceLevel,"
            + "places.regularOpeningHours,places.photos,places.shortFormattedAddress,places.googleMapsUri,"
            + "places.websiteUri,places.businessStatus";

    /** Thrown when Google refuses the key itself, so the app can say so plainly. */
    static final class KeyProblem extends IOException {
        KeyProblem(String message) {
            super(message);
        }
    }

    /** One nearby request per group keeps each kind of place from crowding out the others. */
    static final class Group {
        final String[] types;
        final String[] fallback;
        final int radius;

        Group(int radius, String[] types, String[] fallback) {
            this.radius = radius;
            this.types = types;
            this.fallback = fallback;
        }
    }

    static final Group[] GROUPS = {
        new Group(6000, new String[] {"cafe", "coffee_shop", "tea_house", "bakery", "ice_cream_shop"},
                new String[] {"cafe", "bakery"}),
        new Group(6000, new String[] {"restaurant"}, new String[] {"restaurant"}),
        new Group(8000, new String[] {"bar", "pub", "wine_bar", "night_club"}, new String[] {"bar", "night_club"}),
        new Group(25000, new String[] {"park", "hiking_area", "national_park", "botanical_garden", "garden", "observation_deck"},
                new String[] {"park", "tourist_attraction"}),
        new Group(15000, new String[] {"museum", "art_gallery", "performing_arts_theater", "historical_landmark",
                "book_store", "library", "zoo", "aquarium", "bowling_alley", "amusement_park", "spa"},
                new String[] {"museum", "art_gallery", "book_store", "library", "zoo", "aquarium", "bowling_alley", "spa"}),
    };

    private GooglePlaces() {}

    /** Places around a point, or null when this month's budget is already spent. */
    static JSONArray nearby(Context c, double lat, double lng) throws IOException, JSONException {
        String key = Keys.get(c, Keys.GOOGLE);
        Map<String, JSONObject> byId = new LinkedHashMap<String, JSONObject>();
        boolean any = false;
        for (Group g : GROUPS) {
            if (!Keys.spend(c, "search")) break;
            any = true;
            Http.Response r = call(NEARBY, key, nearbyBody(g.types, g.radius, lat, lng));
            if (r.code == 400 && Keys.spend(c, "search")) {
                // A place type Google doesn't know (they rename them now and then): retry with old staples.
                r = call(NEARBY, key, nearbyBody(g.fallback, g.radius, lat, lng));
            }
            collect(check(r), byId);
        }
        if (!any) return null;
        return new JSONArray(byId.values());
    }

    static JSONArray search(Context c, String query, double lat, double lng) throws IOException, JSONException {
        if (!Keys.spend(c, "search")) return null;
        JSONObject body = new JSONObject()
                .put("textQuery", query)
                .put("pageSize", 20)
                .put("locationBias", circle(lat, lng, 15000));
        Map<String, JSONObject> byId = new LinkedHashMap<String, JSONObject>();
        collect(check(call(TEXT, Keys.get(c, Keys.GOOGLE), body.toString())), byId);
        return new JSONArray(byId.values());
    }

    static String nearbyBody(String[] types, int radius, double lat, double lng) throws JSONException {
        JSONArray t = new JSONArray();
        for (String s : types) t.put(s);
        return new JSONObject()
                .put("includedTypes", t)
                .put("maxResultCount", 20)
                .put("rankPreference", "POPULARITY")
                .put("locationRestriction", circle(lat, lng, radius))
                .toString();
    }

    private static JSONObject circle(double lat, double lng, int radius) throws JSONException {
        return new JSONObject().put("circle", new JSONObject()
                .put("center", new JSONObject().put("latitude", lat).put("longitude", lng))
                .put("radius", (double) radius));
    }

    private static Http.Response call(String url, String key, String body) throws IOException {
        Map<String, String> h = new HashMap<String, String>();
        h.put("X-Goog-Api-Key", key);
        h.put("X-Goog-FieldMask", FIELDS);
        h.put("Accept", "application/json");
        return Http.exchange("POST", url, h, body, "application/json; charset=utf-8", 3_000_000);
    }

    private static JSONObject check(Http.Response r) throws IOException, JSONException {
        if (r.ok()) return new JSONObject(r.text());
        if (r.code == 401 || r.code == 403 || (r.code == 400 && r.text().contains("API_KEY_INVALID"))) {
            String message = "Google didn't accept the API key.";
            try {
                message = "Google: " + new JSONObject(r.text()).getJSONObject("error").getString("message");
            } catch (JSONException ignored) {
                // keep the plain message
            }
            throw new KeyProblem(message);
        }
        throw new IOException("Google Places HTTP " + r.code);
    }

    private static void collect(JSONObject response, Map<String, JSONObject> into) throws JSONException {
        JSONArray places = response.optJSONArray("places");
        if (places == null) return;
        for (int i = 0; i < places.length(); i++) {
            JSONObject p = normalize(places.optJSONObject(i));
            if (p != null && !into.containsKey(p.getString("id"))) into.put(p.getString("id"), p);
        }
    }

    /** Google's place → Roam's place. Returns null for closed or unusable places. */
    static JSONObject normalize(JSONObject g) throws JSONException {
        if (g == null) return null;
        String status = g.optString("businessStatus", "OPERATIONAL");
        if (!"OPERATIONAL".equals(status)) return null;
        JSONObject loc = g.optJSONObject("location");
        JSONObject name = g.optJSONObject("displayName");
        if (loc == null || name == null || g.optString("id").isEmpty()) return null;
        Set<String> tags = PlaceTags.fromGoogle(g.optJSONArray("types"));
        if (tags.isEmpty()) return null;

        JSONObject p = new JSONObject();
        p.put("id", "g:" + g.getString("id"));
        p.put("source", "google");
        p.put("name", Json.clip(name.optString("text"), 160));
        p.put("lat", loc.optDouble("latitude"));
        p.put("lng", loc.optDouble("longitude"));
        p.put("tags", new JSONArray(tags));
        if (g.optJSONArray("types") != null) p.put("types", g.getJSONArray("types"));
        JSONObject kind = g.optJSONObject("primaryTypeDisplayName");
        p.put("kind", kind != null ? kind.optString("text") : "");
        if (g.has("rating")) p.put("rating", g.optDouble("rating"));
        if (g.has("userRatingCount")) p.put("ratings", g.optInt("userRatingCount"));
        int price = priceLevel(g.optString("priceLevel"));
        if (price >= 0) p.put("price", price);
        JSONObject hours = g.optJSONObject("regularOpeningHours");
        if (hours != null) {
            JSONObject h = new JSONObject();
            if (hours.has("periods")) h.put("periods", hours.getJSONArray("periods"));
            if (hours.has("weekdayDescriptions")) h.put("text", hours.getJSONArray("weekdayDescriptions"));
            p.put("hours", h);
        }
        p.put("address", g.optString("shortFormattedAddress"));
        p.put("mapsUrl", g.optString("googleMapsUri"));
        String site = g.optString("websiteUri");
        if (site.startsWith("https://") || site.startsWith("http://")) p.put("website", site);
        JSONArray photos = g.optJSONArray("photos");
        JSONArray out = new JSONArray();
        if (photos != null) {
            for (int i = 0; i < photos.length() && out.length() < 4; i++) {
                JSONObject ph = photos.optJSONObject(i);
                if (ph == null || !ph.optString("name").startsWith("places/")) continue;
                JSONObject o = new JSONObject().put("ref", "g:" + ph.getString("name"));
                JSONArray by = ph.optJSONArray("authorAttributions");
                if (by != null && by.length() > 0) o.put("by", by.optJSONObject(0).optString("displayName"));
                out.put(o);
            }
        }
        p.put("photos", out);
        return p;
    }

    static int priceLevel(String level) {
        if ("PRICE_LEVEL_FREE".equals(level)) return 0;
        if ("PRICE_LEVEL_INEXPENSIVE".equals(level)) return 1;
        if ("PRICE_LEVEL_MODERATE".equals(level)) return 2;
        if ("PRICE_LEVEL_EXPENSIVE".equals(level)) return 3;
        if ("PRICE_LEVEL_VERY_EXPENSIVE".equals(level)) return 4;
        return -1;
    }

    /**
     * Google's short editorial description and up to five reviews for one place, for the daily
     * pick's "why". Cached for two weeks; budgeted separately because these fields cost more.
     */
    static JSONObject details(Context c, String googleId) throws IOException, JSONException {
        if (!googleId.matches("[A-Za-z0-9_\\-]{10,300}")) throw new IOException("Bad place id");
        FileCache cache = new FileCache(c, "details", 5_000_000);
        String key = FileCache.key("d:" + googleId);
        String hit = cache.getText(key, 14L * 24 * 3_600_000L);
        if (hit != null) return new JSONObject(hit);
        if (!Keys.googleReady(c) || !Keys.spend(c, "details")) return null;
        Map<String, String> h = new HashMap<String, String>();
        h.put("X-Goog-Api-Key", Keys.get(c, Keys.GOOGLE));
        h.put("X-Goog-FieldMask", "id,editorialSummary,reviews");
        h.put("Accept", "application/json");
        JSONObject r;
        try {
            r = check(Http.exchange("GET", "https://places.googleapis.com/v1/places/" + googleId + "?languageCode=en",
                    h, null, null, 400_000));
        } catch (KeyProblem e) {
            Keys.block(c, e.getMessage());
            throw e;
        }
        JSONObject out = new JSONObject();
        JSONObject summary = r.optJSONObject("editorialSummary");
        if (summary != null && !summary.optString("text").isEmpty()) out.put("summary", Json.clip(summary.optString("text"), 300));
        JSONArray reviews = new JSONArray();
        JSONArray given = r.optJSONArray("reviews");
        if (given != null) {
            for (int i = 0; i < given.length(); i++) {
                JSONObject rv = given.optJSONObject(i);
                if (rv == null) continue;
                JSONObject text = rv.optJSONObject("text");
                if (text == null || text.optString("text").isEmpty()) continue;
                JSONObject by = rv.optJSONObject("authorAttribution");
                reviews.put(new JSONObject().put("rating", rv.optInt("rating"))
                        .put("text", Json.clip(text.optString("text"), 1500))
                        .put("when", rv.optString("relativePublishTimeDescription"))
                        .put("by", by == null ? "" : by.optString("displayName")));
            }
        }
        out.put("reviews", reviews);
        cache.putText(key, out.toString());
        return out;
    }

    /** Resolves a photo name to a short-lived image URL (one billable call). */
    static String photoUrl(Context c, String photoName, int width) throws IOException, JSONException {
        if (!photoName.matches("places/[A-Za-z0-9_\\-]+/photos/[A-Za-z0-9_\\-]+")) throw new IOException("Bad photo name");
        if (!Keys.spend(c, "photo")) throw new IOException("Photo budget used");
        Http.Response r = Http.exchange("GET", "https://places.googleapis.com/v1/" + photoName
                        + "/media?maxWidthPx=" + width + "&skipHttpRedirect=true&key=" + Keys.get(c, Keys.GOOGLE),
                java.util.Collections.singletonMap("Accept", "application/json"), null, null, 100_000);
        String uri = new JSONObject(check(r).toString()).optString("photoUri");
        if (!uri.startsWith("https://")) throw new IOException("No photo");
        return uri;
    }
}
