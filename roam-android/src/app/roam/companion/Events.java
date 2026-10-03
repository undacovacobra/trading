package app.roam.companion;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Concerts, games and shows nearby from the Ticketmaster Discovery API, cached for 6 hours. */
final class Events {
    static final long CACHE_MS = 6 * 3_600_000L;
    static final int DAYS = 21;

    private Events() {}

    static JSONObject fetch(Context c, double lat, double lng) {
        try {
            if (!Keys.has(c, Keys.TICKETMASTER)) return new JSONObject().put("events", new JSONArray()).put("needsKey", true);
            FileCache cache = new FileCache(c, "events", 4_000_000);
            String cell = String.format(Locale.US, "%.1f,%.1f", lat, lng);
            String cached = cache.getText(FileCache.key("tm:" + cell), CACHE_MS);
            if (cached != null) return new JSONObject(cached);

            DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            String url = "https://app.ticketmaster.com/discovery/v2/events.json?size=100&sort=date,asc&radius=40&unit=miles"
                    + "&latlong=" + String.format(Locale.US, "%.4f,%.4f", lat, lng)
                    + "&startDateTime=" + f.format(now) + "&endDateTime=" + f.format(now.plus(DAYS, ChronoUnit.DAYS))
                    + "&apikey=" + Keys.get(c, Keys.TICKETMASTER);
            Http.Response r = Http.exchange("GET", url, java.util.Collections.singletonMap("Accept", "application/json"),
                    null, null, 4_000_000);
            if (r.code == 401 || r.code == 403) return HomeSearch.error("Ticketmaster didn't accept the API key.");
            if (!r.ok()) throw new java.io.IOException("HTTP " + r.code);
            JSONObject out = new JSONObject()
                    .put("events", normalizeAll(new JSONObject(r.text())))
                    .put("fetchedAt", System.currentTimeMillis());
            cache.putText(FileCache.key("tm:" + cell), out.toString());
            return out;
        } catch (Exception e) {
            return HomeSearch.error("Events are unavailable right now.");
        }
    }

    /** One entry per show; repeat dates of the same show at the same venue are folded together. */
    static JSONArray normalizeAll(JSONObject response) throws JSONException {
        Map<String, JSONObject> shows = new LinkedHashMap<String, JSONObject>();
        JSONObject embedded = response.optJSONObject("_embedded");
        JSONArray events = embedded == null ? null : embedded.optJSONArray("events");
        if (events == null) return new JSONArray();
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = normalize(events.optJSONObject(i));
            if (e == null) continue;
            String key = e.getString("name").toLowerCase(Locale.ROOT) + "|" + e.optString("venue");
            JSONObject first = shows.get(key);
            if (first == null) shows.put(key, e);
            else first.put("moreDates", first.optInt("moreDates") + 1);
        }
        return new JSONArray(shows.values());
    }

    static JSONObject normalize(JSONObject e) throws JSONException {
        if (e == null || e.optString("id").isEmpty() || e.optString("name").isEmpty()) return null;
        JSONObject dates = e.optJSONObject("dates");
        JSONObject start = dates == null ? null : dates.optJSONObject("start");
        if (start == null) return null;
        JSONObject status = dates.optJSONObject("status");
        if (status != null && ("cancelled".equals(status.optString("code")) || "postponed".equals(status.optString("code")))) return null;

        JSONObject venue = null;
        JSONObject emb = e.optJSONObject("_embedded");
        if (emb != null && emb.optJSONArray("venues") != null) venue = emb.getJSONArray("venues").optJSONObject(0);
        JSONObject vloc = venue == null ? null : venue.optJSONObject("location");
        if (vloc == null) return null;
        double lat = parse(vloc.optString("latitude"));
        double lng = parse(vloc.optString("longitude"));
        if (Double.isNaN(lat) || Double.isNaN(lng)) return null;

        long at;
        String localDate = start.optString("localDate");
        String localTime = start.optString("localTime");
        boolean timed = !localTime.isEmpty() && !start.optBoolean("timeTBA") && !start.optBoolean("noSpecificTime");
        if (!start.optString("dateTime").isEmpty()) {
            at = Instant.parse(start.getString("dateTime")).toEpochMilli();
        } else if (!localDate.isEmpty()) {
            ZoneId zone;
            try {
                zone = ZoneId.of(dates.optString("timezone", venue.optString("timezone", "UTC")));
            } catch (Exception x) {
                zone = ZoneOffset.UTC;
            }
            at = LocalDateTime.parse(localDate + "T" + (timed ? localTime : "12:00:00")).atZone(zone).toInstant().toEpochMilli();
        } else {
            return null;
        }

        JSONObject out = new JSONObject();
        out.put("id", "tm:" + e.getString("id"));
        out.put("type", "event");
        out.put("source", "ticketmaster");
        out.put("name", Json.clip(e.getString("name"), 160));
        out.put("start", at);
        out.put("localDate", localDate);
        if (timed) out.put("localTime", localTime.length() >= 5 ? localTime.substring(0, 5) : localTime);
        out.put("venue", venue.optString("name"));
        JSONObject city = venue.optJSONObject("city");
        if (city != null) out.put("city", city.optString("name"));
        out.put("lat", lat);
        out.put("lng", lng);
        String url = e.optString("url");
        if (url.startsWith("https://")) out.put("url", url);
        String image = bestImage(e.optJSONArray("images"));
        if (image != null) out.put("image", image);

        JSONArray tags = new JSONArray();
        JSONArray cls = e.optJSONArray("classifications");
        JSONObject c0 = cls == null ? null : cls.optJSONObject(0);
        String segment = c0 != null && c0.optJSONObject("segment") != null ? c0.getJSONObject("segment").optString("name") : "";
        String genre = c0 != null && c0.optJSONObject("genre") != null ? c0.getJSONObject("genre").optString("name") : "";
        if ("Music".equals(segment)) tags.put("music");
        else if ("Sports".equals(segment)) tags.put("sports");
        else if ("Arts & Theatre".equals(segment) || "Film".equals(segment)) tags.put("culture");
        else if (!segment.isEmpty()) tags.put("fun");
        if (c0 != null && c0.optBoolean("family")) tags.put("fun");
        if (tags.length() == 0) tags.put("fun");
        out.put("tags", tags);
        if (!genre.isEmpty() && !"Undefined".equals(genre) && !"Other".equals(genre)) out.put("genre", genre);
        JSONArray prices = e.optJSONArray("priceRanges");
        if (prices != null && prices.optJSONObject(0) != null && prices.getJSONObject(0).has("min")) {
            out.put("priceFrom", prices.getJSONObject(0).optDouble("min"));
            out.put("currency", prices.getJSONObject(0).optString("currency", "USD"));
        }
        return out;
    }

    /** The widest 16:9 image up to 1200 px, falling back to the widest of any shape. */
    static String bestImage(JSONArray images) {
        if (images == null) return null;
        String best = null;
        int bestScore = -1;
        for (int i = 0; i < images.length(); i++) {
            JSONObject im = images.optJSONObject(i);
            if (im == null || !im.optString("url").startsWith("https://")) continue;
            int w = im.optInt("width");
            int score = (w <= 1200 ? w : 1200 - (w - 1200) / 4) + ("16_9".equals(im.optString("ratio")) ? 2000 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = im.optString("url");
            }
        }
        return best;
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
