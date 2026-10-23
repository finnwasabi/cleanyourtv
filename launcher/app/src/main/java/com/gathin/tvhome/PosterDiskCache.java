package com.gathin.tvhome;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Downloaded posters kept as files in the app's cache, so a reboot or a memory trim does not mean fetching every
 * row's posters again before Home looks right. At most CAP bytes; past that the least recently used go first.
 * Android may also clear the cache directory itself when storage runs low, which only means a fresh download.
 * Called on the poster threads only.
 */
final class PosterDiskCache {
    private static final long CAP = 16L * 1024 * 1024, TRIM_TO = 12L * 1024 * 1024;
    private final File dir;

    PosterDiskCache(Context ctx) {
        this(new File(ctx.getCacheDir(), "posters"));
    }

    /** Kept in {@code dir}; PosterDiskCacheTest passes a temporary folder. */
    PosterDiskCache(File dir) {
        this.dir = dir;
    }

    /** The cached bytes for {@code url}, or null. */
    byte[] get(String url) {
        File f = new File(dir, key(url));
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            if (n != buf.length) return null;
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());   // recently used: trimmed last
            return buf;
        } catch (Exception e) {
            return null;
        }
    }

    /** Keeps {@code data} for {@code url}: written to a temporary file and renamed, so a crash leaves no half file. */
    synchronized void put(String url, byte[] data) {
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File f = new File(dir, key(url)), tmp = new File(dir, key(url) + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        trim();
    }

    private void trim() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= CAP) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= TRIM_TO) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** The file name {@code url} is kept under: its SHA-1 in hex. */
    static String key(String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }
}
