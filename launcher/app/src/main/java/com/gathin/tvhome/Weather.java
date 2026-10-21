package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The current weather beside the date: temperature, a one or two word condition and a glyph (WeatherIcon). It comes
 * from Open-Meteo (no key, no account): one small HTTPS request at most every 30 minutes while Home is open, never
 * in the background, and the last answer is kept so Home shows it at once on the next start. Nothing is drawn per
 * frame. Off unless "weather" is on in config.xml or a theme; "weather_location" is "latitude,longitude".
 */
final class Weather {
    static final long REFRESH_MS = 30 * 60 * 1000L;
    /** An answer older than this is not shown: three hours on, it says nothing about now. */
    private static final long STALE_MS = 3 * 60 * 60 * 1000L;
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();

    static final class Now {
        final int temp;
        final int code;
        final boolean day;
        final long at;

        Now(int temp, int code, boolean day, long at) {
            this.temp = temp;
            this.code = code;
            this.day = day;
            this.at = at;
        }
    }

    interface Callback { void onWeather(Now now); }

    private Weather() { }

    /** The "weather" switch: config.json overrides config.xml. */
    static boolean enabled(Context ctx) {
        JSONObject json = theme(ctx);
        boolean def = ctx.getResources().getBoolean(R.bool.weather);
        return json == null ? def : json.optBoolean("weather", def);
    }

    /** {latitude, longitude}, or null when not set or malformed. */
    static double[] location(Context ctx) {
        JSONObject json = theme(ctx);
        String s = ctx.getString(R.string.weather_location);
        if (json != null) s = json.optString("weather_location", s);
        String[] p = s == null ? new String[0] : s.split(",");
        if (p.length != 2) return null;
        try {
            double lat = Double.parseDouble(p[0].trim()), lon = Double.parseDouble(p[1].trim());
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
            return new double[]{lat, lon};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JSONObject theme(Context ctx) { return Config.theme(ctx); }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    /** The last answer, or null when there is none or it is stale. */
    static Now cached(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long at = p.getLong("at", 0);
        if (at == 0 || System.currentTimeMillis() - at > STALE_MS) return null;
        return new Now(p.getInt("temp", 0), p.getInt("code", 0), p.getBoolean("day", true), at);
    }

    /** Fetches the current weather off the main thread when the last answer is older than REFRESH_MS. */
    /** When the last request went out: a failed one is tried again after RETRY_MS, not on the next minute. */
    private static long lastTry;
    private static final long RETRY_MS = 5 * 60 * 1000L;

    static void refresh(Context ctx, android.os.Handler main, Callback cb) {
        Now last = cached(ctx);
        long now0 = System.currentTimeMillis();
        if (last != null && now0 - last.at < REFRESH_MS) return;
        if (now0 - lastTry < RETRY_MS) return;
        lastTry = now0;
        double[] loc = location(ctx);
        if (loc == null) return;
        Context app = ctx.getApplicationContext();
        NET.execute(() -> {
            Now now = fetch(loc[0], loc[1]);
            if (now == null) return;
            prefs(app).edit().putLong("at", now.at).putInt("temp", now.temp).putInt("code", now.code)
                    .putBoolean("day", now.day).apply();
            main.post(() -> cb.onWeather(now));
        });
    }

    private static Now fetch(double lat, double lon) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f"
                    + "&longitude=%.4f&current=temperature_2m,weather_code,is_day&timezone=auto", lat, lon));
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, r);
            }
            JSONObject cur = new JSONObject(out.toString("UTF-8")).getJSONObject("current");
            return new Now((int) Math.round(cur.getDouble("temperature_2m")), cur.getInt("weather_code"),
                    cur.optInt("is_day", 1) == 1, System.currentTimeMillis());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The glyph for a WMO weather code (WeatherIcon kinds). */
    static int kind(int code, boolean day) {
        if (code == 0) return day ? WeatherIcon.SUN : WeatherIcon.MOON;
        if (code <= 2) return day ? WeatherIcon.SUN_CLOUD : WeatherIcon.MOON_CLOUD;
        if (code == 3) return WeatherIcon.CLOUD;
        if (code == 45 || code == 48) return WeatherIcon.FOG;
        if (code >= 95) return WeatherIcon.STORM;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return WeatherIcon.RAIN;
        return WeatherIcon.CLOUD;
    }

    /** The condition in a word or two, from strings.xml. */
    static int label(int code) {
        if (code == 0) return R.string.weather_clear;
        if (code == 1) return R.string.weather_mostly_clear;
        if (code == 2) return R.string.weather_partly_cloudy;
        if (code == 3) return R.string.weather_cloudy;
        if (code == 45 || code == 48) return R.string.weather_fog;
        if (code >= 51 && code <= 57) return R.string.weather_drizzle;
        if (code == 61 || code == 80) return R.string.weather_light_rain;
        if (code == 65 || code == 82) return R.string.weather_heavy_rain;
        if ((code >= 61 && code <= 67) || code == 81) return R.string.weather_rain;
        if (code >= 95) return R.string.weather_storm;
        return R.string.weather_cloudy;
    }
}
