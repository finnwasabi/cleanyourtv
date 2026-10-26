package com.gathin.tvhome;

import android.content.ComponentCallbacks2;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MemoryTrimPolicyTest {
    @Test
    public void ordinaryBackgroundTransitionKeepsWarmResources() {
        assertFalse(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, false));
        assertFalse(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, true));
    }

    @Test
    public void backgroundPressureReleasesResourcesOnlyWhenHomeIsStopped() {
        assertTrue(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, false));
        assertTrue(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, false));
        assertFalse(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, true));
        assertFalse(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, true));
    }

    @Test
    public void lowMemorySignalsReleaseResourcesWhenHomeIsStopped() {
        assertTrue(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, false));
        assertTrue(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, false));
    }

    /** This fork: the poster and glass caches go under pressure even with Home on screen, never on an ordinary trip. */
    @Test
    public void cachesTrimUnderPressureWhileHomeIsOnScreen() {
        assertFalse(MemoryTrimPolicy.shouldTrimCaches(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN));
        assertFalse(MemoryTrimPolicy.shouldTrimCaches(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE));
        assertTrue(MemoryTrimPolicy.shouldTrimCaches(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW));
        assertTrue(MemoryTrimPolicy.shouldTrimCaches(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL));
        assertTrue(MemoryTrimPolicy.shouldTrimCaches(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND));
    }
}
