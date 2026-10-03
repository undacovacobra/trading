package app.roam.companion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/** One place for timeouts, size limits and the identifying User-Agent public services ask for. */
final class Http {
    static final String USER_AGENT =
            "Roam/" + BuildInfo.VERSION + " (Android travel companion; +https://roam-your-next-adventure.undacovacobra.chatgpt.site)";

    /** A response whose status the caller wants to look at (e.g. Google's 400 for a bad type). */
    static final class Response {
        final int code;
        final byte[] body;
        final String type;

        Response(int code, byte[] body, String type) {
            this.code = code;
            this.body = body;
            this.type = type;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        boolean ok() {
            return code == 200;
        }
    }

    private Http() {}

    static String get(String url, String accept, int maxBytes) throws IOException {
        Response r = exchange("GET", url, Collections.singletonMap("Accept", accept), null, null, maxBytes);
        if (!r.ok()) throw new IOException("HTTP " + r.code);
        return r.text();
    }

    static String post(String url, String accept, String body, int maxBytes) throws IOException {
        Response r = exchange("POST", url, Collections.singletonMap("Accept", accept), body,
                "application/x-www-form-urlencoded; charset=utf-8", maxBytes);
        if (!r.ok()) throw new IOException("HTTP " + r.code);
        return r.text();
    }

    static Response exchange(String method, String url, Map<String, String> headers, String body,
                             String contentType, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12_000);
            c.setReadTimeout(20_000);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", USER_AGENT);
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
                OutputStream out = c.getOutputStream();
                try {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
            }
            int code = c.getResponseCode();
            if (c.getContentLengthLong() > maxBytes) throw new IOException("Too large");
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = new byte[0];
            if (in != null) {
                try {
                    data = read(in, maxBytes);
                } finally {
                    in.close();
                }
            }
            return new Response(code, data, c.getContentType());
        } finally {
            c.disconnect();
        }
    }

    static byte[] read(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > maxBytes) throw new IOException("Too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
