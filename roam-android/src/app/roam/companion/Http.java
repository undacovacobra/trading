package app.roam.companion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** One place for timeouts, size limits and the identifying User-Agent public services ask for. */
final class Http {
    static final String USER_AGENT =
            "Roam/" + BuildInfo.VERSION + " (Android travel companion; +https://roam-your-next-adventure.undacovacobra.chatgpt.site)";

    private Http() {}

    static String get(String url, String accept, int maxBytes) throws IOException {
        return request(url, accept, null, maxBytes);
    }

    static String post(String url, String accept, String body, int maxBytes) throws IOException {
        return request(url, accept, body, maxBytes);
    }

    private static String request(String url, String accept, String body, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", accept);
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
                OutputStream out = c.getOutputStream();
                try {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            if (c.getContentLengthLong() > maxBytes) throw new IOException("Too large");
            InputStream in = c.getInputStream();
            try {
                return new String(read(in, maxBytes), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
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
