package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** Which provider channels channel_rows picks, in which order, and when a refresh counts as a change. */
public class ChannelRowsTest {
    private static final List<String> FILTER = Arrays.asList(
            "@watch_next=Tiếp tục xem", "com.nuvio.tv=Tiếp tục xem", "com.google.android.youtube.tv=Đề xuất cho bạn");

    @Test
    public void aNamedEntryPicksThatChannelAlone() {
        assertEquals(1, ChannelRows.match(FILTER, "com.nuvio.tv", "Tiếp tục xem"));
        assertEquals(2, ChannelRows.match(FILTER, "com.google.android.youtube.tv", "Đề xuất cho bạn"));
        assertEquals(-1, ChannelRows.match(FILTER, "com.google.android.youtube.tv", "Thịnh hành"));
        assertEquals(-1, ChannelRows.match(FILTER, "com.netflix.ninja", "Tiếp tục xem"));
    }

    @Test
    public void namesMatchWithoutCaseOrSurroundingSpaces() {
        List<String> filter = Collections.singletonList(" com.example.tv = Continue Watching ");
        assertEquals(-1, ChannelRows.match(filter, " com.example.tv", "Continue Watching"));
        assertEquals(0, ChannelRows.match(Collections.singletonList("com.example.tv = Continue Watching "),
                "com.example.tv", "  continue watching"));
        assertEquals(-1, ChannelRows.match(filter, "com.example.tv", null));
    }

    @Test
    public void aBarePackagePicksEveryChannelOfThatApp() {
        List<String> filter = Arrays.asList("com.example.tv=Picks", "com.google.android.youtube.tv");
        assertEquals(1, ChannelRows.match(filter, "com.google.android.youtube.tv", "Đề xuất cho bạn"));
        assertEquals(1, ChannelRows.match(filter, "com.google.android.youtube.tv", null));
        // The first entry that matches sets the row's place.
        assertEquals(0, ChannelRows.match(Arrays.asList("com.example.tv", "com.example.tv=Picks"), "com.example.tv", "Picks"));
    }

    @Test
    public void theWatchNextEntryIsNotAChannelPackage() {
        assertEquals(-1, ChannelRows.match(FILTER, "@watch_next", "Something"));
        assertEquals(-1, ChannelRows.match(FILTER, null, "Tiếp tục xem"));
    }

    @Test
    public void aspectRatiosFollowTheProviderConstants() {
        assertEquals(16f / 9f, ChannelRows.aspect(0), 1e-6f);
        assertEquals(3f / 2f, ChannelRows.aspect(1), 1e-6f);
        assertEquals(4f / 3f, ChannelRows.aspect(2), 1e-6f);
        assertEquals(1f, ChannelRows.aspect(3), 1e-6f);
        assertEquals(2f / 3f, ChannelRows.aspect(4), 1e-6f);
        assertEquals(1f / 1.441f, ChannelRows.aspect(5), 1e-6f);
        assertEquals(16f / 9f, ChannelRows.aspect(42), 1e-6f);
    }

    @Test
    public void theSignatureChangesWithWhatTheRowShows() {
        ChannelRows.Row a = row(program(1, "Ep 1", "https://p/1.jpg", 250), program(2, "Ep 2", "https://p/2.jpg", -1));
        ChannelRows.Row same = row(program(1, "Ep 1", "https://p/1.jpg", 250), program(2, "Ep 2", "https://p/2.jpg", -1));
        assertEquals(a.signature(), same.signature());
        assertNotEquals(a.signature(), row(program(1, "Ep 1", "https://p/1.jpg", 400),
                program(2, "Ep 2", "https://p/2.jpg", -1)).signature());
        assertNotEquals(a.signature(), row(program(2, "Ep 2", "https://p/2.jpg", -1),
                program(1, "Ep 1", "https://p/1.jpg", 250)).signature());
        assertNotEquals(a.signature(), row(program(1, "Ep 1", "https://p/1b.jpg", 250),
                program(2, "Ep 2", "https://p/2.jpg", -1)).signature());
    }

    private static ChannelRows.Program program(long id, String title, String poster, int progress) {
        return new ChannelRows.Program(id, title, "", poster, 16f / 9f, null, progress, "com.nuvio.tv");
    }

    private static ChannelRows.Row row(ChannelRows.Program... programs) {
        return new ChannelRows.Row(7, "com.nuvio.tv", "Tiếp tục xem", new ArrayList<>(Arrays.asList(programs)));
    }
}
