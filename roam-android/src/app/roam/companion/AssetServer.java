package app.roam.companion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import org.json.JSONObject;

/**
 * Serves the bundled web app from https://appassets.androidplatform.net/ and answers the small
 * /api/* endpoints the web app uses for live data. Runs on WebView's background threads.
 */
final class AssetServer {
    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST;
    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "font-src 'self'; img-src 'self' https: data:; connect-src 'self'; frame-src 'none'; object-src 'none'; base-uri 'none'";

    private final Context context;
    private final PhotoCache photos;

    AssetServer(Context context) {
        this.context = context.getApplicationContext();
        this.photos = new PhotoCache(context);
    }

    WebResourceResponse handle(Uri url) {
        if (!HOST.equals(url.getHost())) return null;
        String path = url.getPath();
        if (path == null || path.contains("..")) return notFound();
        if (path.startsWith("/api/")) return api(path, url);
        if (path.equals("/")) path = "/index.html";
        try {
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Content-Security-Policy", CSP);
            return new WebResourceResponse(mime(path), "UTF-8", 200, "OK", headers,
                    context.getAssets().open(path.substring(1)));
        } catch (IOException e) {
            return notFound();
        }
    }

    private WebResourceResponse api(String path, Uri url) {
        if (path.equals("/api/photo")) return photo(url);
        JSONObject result;
        try {
            if (path.equals("/api/places")) {
                result = Places.nearby(context, num(url, "lat"), num(url, "lng"), "1".equals(url.getQueryParameter("force")));
            } else if (path.equals("/api/search")) {
                result = Places.search(context, url.getQueryParameter("q"), num(url, "lat"), num(url, "lng"));
            } else if (path.equals("/api/events")) {
                String from = url.getQueryParameter("from");
                String to = url.getQueryParameter("to");
                boolean range = from != null && to != null && from.matches("\\d{4}-\\d{2}-\\d{2}") && to.matches("\\d{4}-\\d{2}-\\d{2}");
                result = Events.fetch(context, num(url, "lat"), num(url, "lng"), range ? from : null, range ? to : null);
            } else if (path.equals("/api/weather")) {
                result = Weather.fetch(context, num(url, "lat"), num(url, "lng"));
            } else if (path.equals("/api/geocode")) {
                result = HomeSearch.fetch(url.getQueryParameter("q"));
            } else if (path.equals("/api/reverse")) {
                result = HomeSearch.reverse(num(url, "lat"), num(url, "lng"));
            } else {
                return notFound();
            }
        } catch (RuntimeException e) {
            result = error("Invalid request");
        }
        boolean ok = !result.has("error");
        return new WebResourceResponse("application/json", "UTF-8", ok ? 200 : 502, ok ? "OK" : "Unavailable",
                Collections.singletonMap("Cache-Control", "no-store"),
                new ByteArrayInputStream(result.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** Photo bytes from the on-phone cache (downloading once if needed); the WebView caches them too. */
    private WebResourceResponse photo(Uri url) {
        String ref = url.getQueryParameter("ref");
        int w;
        try {
            w = Integer.parseInt(url.getQueryParameter("w"));
        } catch (RuntimeException e) {
            w = 400;
        }
        try {
            if (ref == null || ref.length() > 600) throw new IOException("Bad ref");
            byte[] bytes = photos.get(ref, w);
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Cache-Control", "private, max-age=604800");
            return new WebResourceResponse(PhotoCache.mime(bytes), null, 200, "OK", headers, new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
        }
    }

    private static double num(Uri url, String name) {
        return Double.parseDouble(url.getQueryParameter(name));
    }

    private static JSONObject error(String message) {
        try {
            return new JSONObject().put("error", message);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null,
                new ByteArrayInputStream(new byte[0]));
    }

    static String mime(String path) {
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".json") || path.endsWith(".webmanifest")) return "application/json";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ttf")) return "font/ttf";
        if (path.endsWith(".txt")) return "text/plain";
        return "text/html";
    }
}
