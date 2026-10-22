package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
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

    private WirelessDebugging() {
    }

    /** Switches Wireless debugging back on when it is off; cheap enough for every return to Home. */
    static void ensure(Context context) {
        if (!context.getResources().getBoolean(R.bool.keep_wireless_debugging)) return;
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (Settings.Global.getInt(context.getContentResolver(), KEY, 0) == 1) return;
            Settings.Global.putInt(context.getContentResolver(), KEY, 1);
            Log.i("TvHome.Adb", "Wireless debugging switched back on");
        } catch (RuntimeException e) {
            Log.w("TvHome.Adb", "Could not switch Wireless debugging on", e);
        }
    }
}
