package app.roam.companion;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** Current weather and today's sunset from Open-Meteo (free, no key), cached for 30 minutes. */
final class Weather {
    static final long CACHE_MS = 30 * 60_000L;

    private Weather() {}

    static JSONObject fetch(Context c, double lat, double lng) {
        try {
            FileCache cache = new FileCache(c, "weather", 200_000);
            String key = FileCache.key(String.format(Locale.US, "w:%.2f,%.2f", lat, lng));
            String cached = cache.getText(key, CACHE_MS);
            if (cached != null) return new JSONObject(cached);
            String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m,weather_code,is_day&daily=sunrise,sunset&timezone=auto&forecast_days=1", lat, lng);
            JSONObject out = normalize(new JSONObject(Http.get(url, "application/json", 200_000)));
            cache.putText(key, out.toString());
            return out;
        } catch (Exception e) {
            return HomeSearch.error("Weather is unavailable right now.");
        }
    }

    static JSONObject normalize(JSONObject r) throws Exception {
        JSONObject cur = r.getJSONObject("current");
        double celsius = cur.getDouble("temperature_2m");
        int code = cur.optInt("weather_code");
        int offset = r.optInt("utc_offset_seconds");
        JSONObject out = new JSONObject()
                .put("tempC", Math.round(celsius))
                .put("tempF", Math.round(celsius * 9 / 5 + 32))
                .put("code", code)
                .put("isDay", cur.optInt("is_day", 1) == 1)
                .put("sky", sky(code))
                .put("fetchedAt", System.currentTimeMillis());
        JSONObject daily = r.optJSONObject("daily");
        if (daily != null) {
            out.put("sunrise", localToEpoch(daily.optJSONArray("sunrise"), offset));
            out.put("sunset", localToEpoch(daily.optJSONArray("sunset"), offset));
        }
        return out;
    }

    private static long localToEpoch(JSONArray list, int offsetSeconds) {
        if (list == null || list.length() == 0) return 0;
        try {
            return LocalDateTime.parse(list.getString(0)).toInstant(ZoneOffset.ofTotalSeconds(offsetSeconds)).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /** WMO weather codes, simplified to what changes a suggestion. */
    static String sky(int code) {
        if (code == 0 || code == 1) return "clear";
        if (code == 2 || code == 3) return "cloudy";
        if (code == 45 || code == 48) return "fog";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "snow";
        if (code >= 95) return "storm";
        if (code >= 51) return "rain";
        return "cloudy";
    }
}
