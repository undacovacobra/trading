package app.roam.companion;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Where you sleep, learned from overnight stays and never asked, so leaving home isn't an "outing". */
final class Home {
    static final double RADIUS_M = 250;
    static final int NIGHTS = 3;

    private Home() {}

    /** True when the stay covered 3 am and lasted 4+ hours. */
    static boolean overnight(long leftAt, long stayMs, ZoneId zone) {
        if (stayMs < 4 * 3_600_000L) return false;
        ZonedDateTime from = Instant.ofEpochMilli(leftAt - stayMs).atZone(zone);
        ZonedDateTime threeAm = from.toLocalDate().atTime(3, 0).atZone(zone);
        if (threeAm.isBefore(from)) threeAm = threeAm.plusDays(1);
        return threeAm.toInstant().toEpochMilli() <= leftAt;
    }

    /** Counts overnight stays per spot; a spot with 3+ nights is home. Returns whether `lat,lng` is home. */
    static boolean after(JSONArray spots, double lat, double lng, boolean wasOvernight) throws JSONException {
        if (!Double.isFinite(lat)) return false;
        JSONObject match = null;
        for (int i = 0; i < spots.length(); i++) {
            JSONObject s = spots.optJSONObject(i);
            if (s != null && DepartureDetector.meters(lat, lng, s.optDouble("lat"), s.optDouble("lng")) < RADIUS_M) match = s;
        }
        if (wasOvernight) {
            if (match == null) {
                match = new JSONObject().put("lat", lat).put("lng", lng).put("nights", 0);
                spots.put(match);
                while (spots.length() > 8) spots.remove(0);
            }
            match.put("nights", match.optInt("nights") + 1);
        }
        return match != null && match.optInt("nights") >= NIGHTS;
    }
}
