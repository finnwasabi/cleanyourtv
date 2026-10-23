package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class WallpaperRotationTest {

    @Test
    public void timeOfDayPicksTheLatestStartNotAfterTheHour() {
        List<File> images = Arrays.asList(new File("06-day.jpg"), new File("17-dusk.jpg"), new File("22-night.jpg"),
                new File("plain.jpg"));
        assertEquals("06-day.jpg", WallpaperRotation.byHour(images, 6).getName());
        assertEquals("06-day.jpg", WallpaperRotation.byHour(images, 16).getName());
        assertEquals("17-dusk.jpg", WallpaperRotation.byHour(images, 17).getName());
        assertEquals("22-night.jpg", WallpaperRotation.byHour(images, 23).getName());
        // Before the first start: the night that began the evening before.
        assertEquals("22-night.jpg", WallpaperRotation.byHour(images, 3).getName());
        assertNull(WallpaperRotation.byHour(Collections.singletonList(new File("plain.jpg")), 12));
        assertEquals(-1, WallpaperRotation.startHour("123-x.jpg"));
        assertEquals(-1, WallpaperRotation.startHour("25-x.jpg"));
        assertTrue(WallpaperRotation.validOrder(WallpaperRotation.ORDER_TIME));
    }

    @Test
    public void timeOfDayHoursNeedTwoDigitsAndMidnightCounts() {
        assertEquals(0, WallpaperRotation.startHour("00-night.jpg"));
        assertEquals(6, WallpaperRotation.startHour("06.jpg"));
        assertEquals(23, WallpaperRotation.startHour("23-late.webp"));
        assertEquals(-1, WallpaperRotation.startHour("6-day.jpg"));
        assertEquals(-1, WallpaperRotation.startHour("06"));
        assertEquals(-1, WallpaperRotation.startHour("dusk-17.jpg"));
        assertEquals(-1, WallpaperRotation.startHour(null));
        List<File> images = Arrays.asList(new File("12-day.jpg"), new File("00-night.jpg"));
        assertEquals("00-night.jpg", WallpaperRotation.byHour(images, 0).getName());
        assertEquals("00-night.jpg", WallpaperRotation.byHour(images, 11).getName());
        assertEquals("12-day.jpg", WallpaperRotation.byHour(images, 12).getName());
        // Two pictures for one hour: the first listed (images() sorts by name) keeps it.
        List<File> twins = Arrays.asList(new File("06-a.jpg"), new File("06-b.jpg"));
        assertEquals("06-a.jpg", WallpaperRotation.byHour(twins, 9).getName());
    }

    private static File touch(File dir, String name, int bytes) throws IOException {
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[bytes]);
        }
        return f;
    }

    @Test
    public void imagesKeepsPicturesOnlySortedByName() throws IOException {
        File dir = Files.createTempDirectory("walls").toFile();
        touch(dir, "b.JPG", 10);
        touch(dir, "a.png", 10);
        touch(dir, "notes.txt", 10);
        touch(dir, "empty.jpg", 0);
        new File(dir, "folder.jpg").mkdir();
        List<File> images = WallpaperRotation.images(dir);
        assertEquals(Arrays.asList("a.png", "b.JPG"), names(images));
        assertTrue(WallpaperRotation.images(null).isEmpty());
        assertTrue(WallpaperRotation.images(new File(dir, "missing")).isEmpty());
    }

    @Test
    public void pickAvoidsThePreviousPictureWhenThereIsAnother() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"));
        Random random = new Random(1);
        for (int i = 0; i < 20; i++) {
            assertNotEquals("a.jpg", WallpaperRotation.pick(images, "a.jpg", random).getName());
        }
        assertEquals("a.jpg", WallpaperRotation.pick(Collections.singletonList(new File("a.jpg")), "a.jpg", random).getName());
        assertNull(WallpaperRotation.pick(Collections.emptyList(), "", random));
    }

    @Test
    public void sequentialAdvancesInSortedOrderAndWraps() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"), new File("c.jpg"));
        assertEquals("b.jpg", WallpaperRotation.next(images, "a.jpg").getName());
        assertEquals("c.jpg", WallpaperRotation.next(images, "b.jpg").getName());
        assertEquals("a.jpg", WallpaperRotation.next(images, "c.jpg").getName());
        // A first run, or an image removed from the collection, starts at the first picture.
        assertEquals("a.jpg", WallpaperRotation.next(images, "").getName());
        assertEquals("a.jpg", WallpaperRotation.next(images, "gone.jpg").getName());
        assertEquals("only.jpg", WallpaperRotation.next(
                Collections.singletonList(new File("only.jpg")), "only.jpg").getName());
        assertNull(WallpaperRotation.next(Collections.emptyList(), ""));
    }

    @Test
    public void pickUsesTheRequestedOrder() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"), new File("c.jpg"));
        Random random = new Random(1);
        assertEquals("b.jpg", WallpaperRotation.pick(images, "a.jpg", WallpaperRotation.ORDER_SEQUENTIAL, random).getName());
        assertEquals("a.jpg", WallpaperRotation.pick(images, "c.jpg", WallpaperRotation.ORDER_SEQUENTIAL, random).getName());
        for (int i = 0; i < 20; i++) {
            assertNotEquals("a.jpg", WallpaperRotation.pick(
                    images, "a.jpg", WallpaperRotation.ORDER_SHUFFLE, random).getName());
        }
        // Unknown order keeps the historical shuffle rather than failing or blanking.
        assertNotEquals("a.jpg", WallpaperRotation.pick(images, "a.jpg", "random", random).getName());
    }

    @Test
    public void findsTheFirstPictureWhenThePreviousOneIsGone() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"));
        assertEquals("a.jpg", WallpaperRotation.next(images, "deleted.jpg").getName());
    }

    @Test
    public void findMatchesByName() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"));
        assertEquals("b.jpg", WallpaperRotation.find(images, "b.jpg").getName());
        assertNull(WallpaperRotation.find(images, "c.jpg"));
    }

    @Test
    public void dueOnlyWithRotationOnAndTheIntervalPassed() {
        long minute = 60_000L;
        assertFalse(WallpaperRotation.due(0, 10 * minute, 0));             // rotation off
        assertTrue(WallpaperRotation.due(0, 10 * minute, 5 * minute));      // never changed yet
        assertFalse(WallpaperRotation.due(8 * minute, 10 * minute, 5 * minute));
        assertTrue(WallpaperRotation.due(5 * minute, 10 * minute, 5 * minute));
        assertTrue(WallpaperRotation.due(20 * minute, 10 * minute, 5 * minute));   // clock went back
    }

    private static List<String> names(List<File> files) {
        String[] out = new String[files.size()];
        for (int i = 0; i < out.length; i++) out[i] = files.get(i).getName();
        return Arrays.asList(out);
    }
}
