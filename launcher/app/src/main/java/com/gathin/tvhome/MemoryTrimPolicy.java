package com.gathin.tvhome;

import android.content.ComponentCallbacks2;

/** Memory that affects the picture is released only when Home is not on screen. */
final class MemoryTrimPolicy {
    private MemoryTrimPolicy() {
    }

    /**
     * The caches of images not being drawn (channel posters, liquid glass) can go at this level, even while Home is on
     * screen: RUNNING_LOW and CRITICAL also arrive while Home is visible when an accessibility service keeps the process
     * in the foreground.
     */
    static boolean shouldTrimCaches(int level) {
        return level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
    }

    static boolean shouldRelease(int level, boolean homeStarted) {
        return !homeStarted && shouldTrimCaches(level);
    }
}
