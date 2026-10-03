package app.roam.companion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Turns cached OpenStreetMap places into suggestion candidates that match your tastes. */
final class LiveCandidates {
    static final long MAX_AGE_MS = 7 * 86_400_000L;
    static final double RADIUS_M = 10_000;
    private static final String[] ACTIVITY_BRANCHES = {
        "nightlife", "adrenaline", "water", "winter", "sports", "wellness",
        "markets", "games", "classes", "family", "culture"
    };

    private LiveCandidates() {}

    static JSONArray nearby(JSONArray livePlaces, JSONObject snapshot, double lat, double lng, long now) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < livePlaces.length(); i++) {
            JSONObject p = livePlaces.optJSONObject(i);
            if (p == null || now - p.optLong("checkedAt") > MAX_AGE_MS) continue;
            if (DepartureDetector.meters(lat, lng, p.optDouble("lat"), p.optDouble("lng")) > RADIUS_M) continue;
            JSONObject c = candidate(p, snapshot, now);
            if (c != null) out.put(c);
        }
        return out;
    }

    /** Mirrors the web app's interest + activity matching for places it hasn't loaded yet. */
    static JSONObject candidate(JSONObject place, JSONObject snapshot, long now) {
        String id = place.optString("id");
        JSONArray interests = snapshot.optJSONArray("interests");
        JSONArray tags = place.optJSONArray("tags");
        JSONArray kinds = place.optJSONArray("kinds");

        int score = 0;
        boolean matched = false;
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                if (Json.contains(interests, tags.optString(i))) {
                    matched = true;
                    score += 20;
                }
            }
        }
        if (!matched) return null;

        JSONObject reactions = snapshot.optJSONObject("reactions");
        JSONObject reaction = reactions == null ? null : reactions.optJSONObject(id);
        if (reaction != null) {
            String feeling = reaction.optString("feeling");
            if ("no".equals(feeling) && "taste".equals(reaction.optString("reason"))) return null;
            if ("later".equals(feeling) && now - reaction.optLong("at") < Suggestions.DAY) return null;
        }

        JSONObject answers = snapshot.optJSONObject("activityAnswers");
        if (answers != null) {
            JSONArray nightlife = answers.optJSONArray("nightlife");
            if (Json.contains(kinds, "Dance clubs & DJs") && nightlife != null && nightlife.length() > 0
                    && !Json.contains(nightlife, "Dance clubs & DJs") && !Json.contains(nightlife, "A bit of everything")) {
                return null;
            }
            boolean kindMatched = false;
            if (kinds != null) {
                for (String branch : ACTIVITY_BRANCHES) {
                    JSONArray chosen = answers.optJSONArray(branch);
                    for (int i = 0; i < kinds.length(); i++) {
                        if (Json.contains(chosen, kinds.optString(i))) {
                            score += 12;
                            kindMatched = true;
                        }
                    }
                }
            }
            JSONArray adrenaline = answers.optJSONArray("adrenaline");
            if (Json.contains(tags, "Adrenaline") && adrenaline != null && adrenaline.length() > 0
                    && !Json.contains(adrenaline, "A bit of everything") && !kindMatched) {
                return null;
            }
        }

        try {
            JSONObject c = new JSONObject();
            c.put("id", id);
            c.put("name", place.optString("name"));
            c.put("lat", place.optDouble("lat"));
            c.put("lng", place.optDouble("lng"));
            c.put("rank", score + (reaction != null && "yes".equals(reaction.optString("feeling")) ? 15 : 0));
            c.put("reason", "Fits your saved interests. Verify current hours and access.");
            c.put("live", true);
            c.put("lateNight", place.optBoolean("lateNight"));
            c.put("start", 0);
            return c;
        } catch (JSONException e) {
            return null;
        }
    }
}
