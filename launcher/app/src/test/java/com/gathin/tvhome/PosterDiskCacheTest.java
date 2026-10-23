package com.gathin.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The channel posters kept on disk: what comes back, and which go first once the cache is full. */
public class PosterDiskCacheTest {
    private static final int MB = 1024 * 1024;

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aPosterComesBackAsItWasKept() throws Exception {
        File dir = new File(tmp.getRoot(), "posters");
        PosterDiskCache cache = new PosterDiskCache(dir);
        assertNull(cache.get("https://img.example/a.jpg"));
        byte[] data = {1, 2, 3, 4, 5};
        cache.put("https://img.example/a.jpg", data);
        assertArrayEquals(data, cache.get("https://img.example/a.jpg"));
        assertNull(cache.get("https://img.example/b.jpg"));
        // Written through a temporary file that is renamed: nothing half written stays behind.
        for (String name : dir.list()) assertTrue(name, !name.endsWith(".tmp"));
    }

    @Test
    public void overSixteenMegabytesTheLeastRecentlyUsedGoDownToTwelve() throws Exception {
        File dir = new File(tmp.getRoot(), "posters");
        PosterDiskCache cache = new PosterDiskCache(dir);
        byte[] poster = new byte[MB];
        for (int i = 0; i < 16; i++) cache.put(url(i), poster);   // exactly the cap: nothing goes yet
        for (int i = 0; i < 16; i++) assertNotNull("poster " + i, cache.get(url(i)));
        // Last used an hour ago and earlier, oldest first; then poster 0 is shown again.
        assertEquals(16, dir.list().length);
        long hourAgo = System.currentTimeMillis() - 3600_000L;
        for (int i = 0; i < 16; i++) {
            assertTrue(new File(dir, PosterDiskCache.key(url(i))).setLastModified(hourAgo + i * 10_000L));
        }
        assertNotNull(cache.get(url(0)));

        cache.put(url(16), poster);
        long total = 0;
        for (File f : dir.listFiles()) total += f.length();
        assertTrue("total " + total, total <= 12L * MB);
        assertNotNull(cache.get(url(0)));
        assertNotNull(cache.get(url(16)));
        for (int i = 1; i <= 5; i++) assertNull("poster " + i, cache.get(url(i)));
        for (int i = 6; i < 16; i++) assertNotNull("poster " + i, cache.get(url(i)));
    }

    private static String url(int i) { return "https://img.example/poster-" + i + ".jpg"; }
}
