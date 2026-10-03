package app.roam.companion;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

import android.content.Context;

/** A small on-disk cache in the app's cache folder (Android may clear it when space runs low). */
final class FileCache {
    private final File dir;
    private final long maxBytes;

    FileCache(Context c, String name, long maxBytes) {
        this.dir = new File(c.getCacheDir(), name);
        this.maxBytes = maxBytes;
    }

    static String key(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 16; i++) s.append(String.format("%02x", d[i]));
            return s.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /** The cached bytes if younger than maxAgeMs, else null. */
    byte[] get(String key, long maxAgeMs) {
        File f = new File(dir, key);
        if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > maxAgeMs) return null;
        try {
            InputStream in = new FileInputStream(f);
            try {
                return Http.read(in, 20_000_000);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    String getText(String key, long maxAgeMs) {
        byte[] b = get(key, maxAgeMs);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    synchronized void put(String key, byte[] data) {
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File tmp = new File(dir, key + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(data);
            } finally {
                out.close();
            }
            if (!tmp.renameTo(new File(dir, key))) tmp.delete();
        } catch (IOException e) {
            tmp.delete();
        }
        trim();
    }

    void putText(String key, String text) {
        put(key, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Drops the oldest files once the folder is over its size limit. */
    private void trim() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= maxBytes) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : files) {
            if (total <= maxBytes * 3 / 4) break;
            total -= f.length();
            f.delete();
        }
    }
}
