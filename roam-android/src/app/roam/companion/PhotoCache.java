package app.roam.companion;

import java.io.IOException;
import java.net.URL;

import android.content.Context;

/**
 * Place photos, downloaded once and kept on the phone for 30 days, so scrolling back through
 * Roam costs nothing and photos show up even with a weak signal.
 *
 * ref "g:places/…/photos/…" = a Google photo (one budgeted call the first time),
 * ref "c:File:…" = a Wikimedia Commons file linked from OpenStreetMap.
 */
final class PhotoCache {
    static final long MAX_AGE_MS = 30L * 24 * 3_600_000L;
    static final int[] WIDTHS = {200, 400, 800, 1200};
    private final Context context;
    private final FileCache cache;

    PhotoCache(Context context) {
        this.context = context.getApplicationContext();
        this.cache = new FileCache(context, "photos", 120_000_000);
    }

    static int width(int asked) {
        for (int w : WIDTHS) if (asked <= w) return w;
        return WIDTHS[WIDTHS.length - 1];
    }

    /** Cached bytes only; never spends a call. */
    byte[] cached(String ref, int asked) {
        return cache.get(FileCache.key(ref + "@" + width(asked)), MAX_AGE_MS);
    }

    byte[] get(String ref, int asked) throws IOException {
        int w = width(asked);
        String key = FileCache.key(ref + "@" + w);
        byte[] hit = cache.get(key, MAX_AGE_MS);
        if (hit != null) return hit;
        String url;
        try {
            if (ref.startsWith("g:")) {
                url = GooglePlaces.photoUrl(context, ref.substring(2), w);
            } else if (ref.startsWith("c:")) {
                url = CommonsSource.imageUrl(ref.substring(2), w);
            } else {
                throw new IOException("Unknown photo");
            }
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        String host = new URL(url).getHost();
        if (!host.endsWith(".googleusercontent.com") && !host.equals("upload.wikimedia.org")) throw new IOException("Unexpected host");
        Http.Response r = Http.exchange("GET", url, java.util.Collections.singletonMap("Accept", "image/*"), null, null, 8_000_000);
        if (!r.ok() || mime(r.body) == null) throw new IOException("Photo download failed");
        cache.put(key, r.body);
        return r.body;
    }

    /** Detects the image format from its first bytes. */
    static String mime(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E') return "image/webp";
        return null;
    }
}
