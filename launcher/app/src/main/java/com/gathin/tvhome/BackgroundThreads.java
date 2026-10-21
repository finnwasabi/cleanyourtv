package com.gathin.tvhome;

import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Executors whose threads run at background priority. A thread started from the main thread inherits its
 * foreground priority, so poster downloads and decodes competed with the render thread for the four cores.
 */
final class BackgroundThreads {
    private BackgroundThreads() { }

    private static ThreadFactory factory(String name) {
        return r -> {
            Thread t = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, name);
            t.setDaemon(true);
            return t;
        };
    }

    static ExecutorService single(String name) { return Executors.newSingleThreadExecutor(factory(name)); }

    static ExecutorService pool(int n, String name) { return Executors.newFixedThreadPool(n, factory(name)); }
}
