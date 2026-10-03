package app.roam.companion;

import org.json.JSONException;
import org.json.JSONObject;

/** Fetches the official Red Rocks events page; live-events.js parses it in the WebView. */
final class EventSource {
    static final String URL = "https://www.redrocksonline.com/events/";

    private EventSource() {}

    static JSONObject fetch() {
        JSONObject out = new JSONObject();
        try {
            out.put("html", Http.get(URL, "text/html", 2_500_000));
            out.put("fetchedAt", System.currentTimeMillis());
            out.put("sourceURL", URL);
        } catch (Exception e) {
            try {
                out = new JSONObject().put("error", "Official events could not be refreshed. Saved listings are available.");
            } catch (JSONException ignored) {
                // constant string
            }
        }
        return out;
    }
}
