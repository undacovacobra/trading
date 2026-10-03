package app.roam.companion;

import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Looks up a Wikimedia Commons photo (and its credit) that OpenStreetMap links to a place. */
final class CommonsSource {
    private CommonsSource() {}

    private static String clean(String html) {
        return html.replaceAll("<[^>]*>", "").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").trim();
    }

    static JSONObject fetch(String file) {
        JSONObject out = new JSONObject();
        try {
            if (file == null || !file.matches("File:[^\\n]{1,300}")) throw new IOException("No mapped source");
            String title = URLEncoder.encode(file, "UTF-8");
            JSONObject response = new JSONObject(Http.get(
                    "https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo"
                            + "&iiprop=url%7Cextmetadata&iiurlwidth=1200&titles=" + title,
                    "application/json", 200_000));
            JSONObject pages = response.getJSONObject("query").getJSONObject("pages");
            JSONObject info = pages.getJSONObject(pages.keys().next()).getJSONArray("imageinfo").getJSONObject(0);
            String url = info.optString("thumburl", info.optString("url"));
            URL u = new URL(url);
            if (!"https".equals(u.getProtocol()) || !"upload.wikimedia.org".equals(u.getHost())
                    || !url.matches("(?i).*\\.(jpe?g|png|webp)([/?].*)?")) {
                throw new IOException("Unexpected image");
            }
            JSONObject meta = info.optJSONObject("extmetadata");
            String artist = meta != null && meta.optJSONObject("Artist") != null
                    ? clean(meta.getJSONObject("Artist").optString("value")) : "";
            String license = meta != null && meta.optJSONObject("LicenseShortName") != null
                    ? clean(meta.getJSONObject("LicenseShortName").optString("value")) : "";
            JSONObject photo = new JSONObject();
            photo.put("url", url);
            photo.put("caption", file.substring(5));
            photo.put("credit", artist + " · " + license + " · Wikimedia Commons");
            photo.put("sourceURL", "https://commons.wikimedia.org/wiki/" + title.replace("+", "%20"));
            out.put("photos", new JSONArray().put(photo));
            out.put("at", System.currentTimeMillis());
            return out;
        } catch (Exception e) {
            try {
                return new JSONObject().put("error", "The mapped photo is unavailable right now.");
            } catch (JSONException ignored) {
                return out;
            }
        }
    }
}
