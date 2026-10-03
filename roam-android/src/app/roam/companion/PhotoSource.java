package app.roam.companion;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Fetches a venue's own web page so photos.js can pick its preview image. Only sites listed in
 * photo-provider.json or attached to a cached live place are allowed, never private addresses,
 * and redirects must stay on the same site.
 */
final class PhotoSource {
    private static final int MAX_HTML = 2_500_000;
    private final Context context;
    private final Set<String> sources = new HashSet<String>();

    PhotoSource(Context context) {
        this.context = context.getApplicationContext();
        try {
            InputStream in = context.getAssets().open("photo-provider.json");
            try {
                JSONArray list = new JSONObject(new String(Http.read(in, 50_000), StandardCharsets.UTF_8)).getJSONArray("sources");
                for (int i = 0; i < list.length(); i++) sources.add(list.getString(i));
            } finally {
                in.close();
            }
        } catch (Exception ignored) {
            // no curated sources; live place websites still work
        }
    }

    private static String host(URL u) {
        return u.getHost().replaceFirst("^www\\.", "");
    }

    private static void requirePublic(URL u) throws IOException {
        for (InetAddress a : InetAddress.getAllByName(u.getHost())) {
            if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                    || a.isSiteLocalAddress() || a.isMulticastAddress()) {
                throw new IOException("Private address rejected");
            }
        }
    }

    private boolean allowed(String source) {
        if (sources.contains(source)) return true;
        JSONArray live = NativeStore.livePlaces(context);
        for (int i = 0; i < live.length(); i++) {
            JSONObject p = live.optJSONObject(i);
            if (p != null && source.equals(p.optString("website"))) return true;
        }
        return false;
    }

    JSONObject fetch(String source) {
        JSONObject out = new JSONObject();
        try {
            if (source == null || !allowed(source)) throw new IOException("No connected photo source");
            URL origin = new URL(source);
            URL url = origin;
            for (int hop = 0; hop < 4; hop++) {
                if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null || url.getPort() != -1
                        || !host(url).equals(host(origin))) {
                    throw new IOException("Source redirect rejected");
                }
                requirePublic(url);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                try {
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(12_000);
                    c.setReadTimeout(12_000);
                    c.setRequestProperty("Accept", "text/html");
                    c.setRequestProperty("User-Agent", Http.USER_AGENT);
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String next = c.getHeaderField("Location");
                        if (next == null) throw new IOException("Missing redirect");
                        url = new URL(url, next);
                        continue;
                    }
                    String type = c.getContentType();
                    if (code != 200 || type == null || !type.toLowerCase(Locale.ROOT).contains("text/html")
                            || c.getContentLengthLong() > MAX_HTML) {
                        throw new IOException("Photo source unavailable");
                    }
                    InputStream in = c.getInputStream();
                    try {
                        out.put("html", new String(Http.read(in, MAX_HTML), StandardCharsets.UTF_8));
                    } finally {
                        in.close();
                    }
                    out.put("sourceURL", url.toString());
                    out.put("fetchedAt", System.currentTimeMillis());
                    return out;
                } finally {
                    c.disconnect();
                }
            }
            throw new IOException("Too many redirects");
        } catch (Exception e) {
            try {
                return new JSONObject().put("error", "This photo source is unavailable right now.");
            } catch (JSONException ignored) {
                return out;
            }
        }
    }
}
