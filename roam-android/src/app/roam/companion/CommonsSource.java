package app.roam.companion;

import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;

import org.json.JSONException;
import org.json.JSONObject;

/** Finds the image file behind a Wikimedia Commons "File:…" name that OpenStreetMap links to a place. */
final class CommonsSource {
    private CommonsSource() {}

    static String imageUrl(String file, int width) throws IOException, JSONException {
        if (file == null || !file.matches("File:[^\\n]{1,300}")) throw new IOException("No mapped photo");
        JSONObject response = new JSONObject(Http.get(
                "https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url"
                        + "&iiurlwidth=" + width + "&titles=" + URLEncoder.encode(file, "UTF-8"),
                "application/json", 200_000));
        JSONObject pages = response.getJSONObject("query").getJSONObject("pages");
        JSONObject info = pages.getJSONObject(pages.keys().next()).getJSONArray("imageinfo").getJSONObject(0);
        String url = info.optString("thumburl", info.optString("url"));
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol()) || !"upload.wikimedia.org".equals(u.getHost())
                || !url.matches("(?i).*\\.(jpe?g|png|webp)([/?].*)?")) {
            throw new IOException("Unexpected image");
        }
        return url;
    }
}
