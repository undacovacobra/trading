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
            + "font-src 'self'; img-src 'self' https:; connect-src 'self'; frame-src 'none'; object-src 'none'; base-uri 'none'";

    private final Context context;
    private final PhotoSource photos;

    AssetServer(Context context) {
        this.context = context.getApplicationContext();
        this.photos = new PhotoSource(context);
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
        JSONObject result;
        if (path.equals("/api/event-source")) {
            result = EventSource.fetch();
        } else if (path.equals("/api/commons-photo")) {
            result = CommonsSource.fetch(url.getQueryParameter("file"));
        } else if (path.equals("/api/home-search")) {
            result = HomeSearch.fetch(url.getQueryParameter("q"));
        } else if (path.equals("/api/photo-source")) {
            result = photos.fetch(url.getQueryParameter("source"));
        } else if (path.equals("/api/discovery")) {
            try {
                String radius = url.getQueryParameter("radius");
                result = DiscoverySource.fetch(context,
                        Double.parseDouble(url.getQueryParameter("lat")),
                        Double.parseDouble(url.getQueryParameter("lng")),
                        radius == null ? 4000 : Integer.parseInt(radius));
            } catch (RuntimeException e) {
                result = error("Invalid location");
            }
        } else {
            return notFound();
        }
        boolean ok = !result.has("error");
        return new WebResourceResponse("application/json", "UTF-8", ok ? 200 : 502, ok ? "OK" : "Unavailable",
                Collections.singletonMap("Cache-Control", "no-store"),
                new ByteArrayInputStream(result.toString().getBytes(StandardCharsets.UTF_8)));
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
