package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.util.Log;

/**
 * Keeps Wireless debugging on across restarts (config.xml keep_wireless_debugging). Android 11+ switches it off at
 * every boot and whenever Wi-Fi drops, and gives it a new random port, so the computer it was paired with had to
 * wait for someone to switch it on by hand and read the port off the screen. With it back on, that computer finds
 * the port by itself over mDNS (adb mdns services).
 *
 * It only flips the same switch as Developer options, so it needs WRITE_SECURE_SETTINGS, granted once with
 * {@code pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS}; without it this does nothing. Android
 * keeps it off unless the Wi-Fi network was trusted ("Always allow on this network") when the computer was paired.
 */
final class WirelessDebugging {
    private static final String KEY = "adb_wifi_enabled";
    private static final Gate GATE = new Gate();

    private WirelessDebugging() {
    }

    /**
     * When to switch it on. On a Wi-Fi network that was never trusted, every switch-on makes Android open its "Allow
     * wireless debugging on this network?" prompt over the screen and then switch it off again, and ensure runs on
     * every return to Home: so once it was switched on over Wi-Fi, it is left alone until the next network comes up
     * (also when the owner switched it off in Developer options). Switched on with no Wi-Fi yet, as at boot, Android
     * turns it straight off without asking, and the next call tries again.
     */
    static final class Gate {
        private boolean asked;

        /** A network came up: worth one more try. */
        void networkChanged() { asked = false; }

        /** Whether to switch it on now, with it {@code on} or off. */
        boolean due(boolean on) { return !on && !asked; }

        /** It was just switched on, over Wi-Fi or not. */
        void switchedOn(boolean wifi) { asked = wifi; }
    }

    /** A network came up (Android switches Wireless debugging off when Wi-Fi drops): switch it back on. */
    static void networkChanged(Context context) {
        GATE.networkChanged();
        ensure(context);
    }

    /** Switches Wireless debugging back on when it is off; cheap enough for every return to Home. */
    static void ensure(Context context) {
        if (!context.getResources().getBoolean(R.bool.keep_wireless_debugging)) return;
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (!GATE.due(Settings.Global.getInt(context.getContentResolver(), KEY, 0) == 1)) return;
            Settings.Global.putInt(context.getContentResolver(), KEY, 1);
            GATE.switchedOn(onWifi(context));
            Log.i("TvHome.Adb", "Wireless debugging switched back on");
        } catch (RuntimeException e) {
            Log.w("TvHome.Adb", "Could not switch Wireless debugging on", e);
        }
    }

    private static boolean onWifi(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
}
