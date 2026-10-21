package com.gathin.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Channel rows under the app grid: the preview channels other apps publish to the TV provider (YouTube's
 * recommendations, a player's "Continue watching"). Reading other apps' channels needs the runtime permission
 * android.permission.READ_TV_LISTINGS, granted once over adb:
 *   pm grant com.gathin.tvhome android.permission.READ_TV_LISTINGS
 * Without it the provider answers with this launcher's own rows only, which is none, and no row shows.
 *
 * Which channels show, in order: channel_rows in config.xml or a theme's config.json, as "package" (every
 * channel of that app) or "package=Channel name". Empty: every browsable preview channel, in provider order.
 * No Android UI types here, so the parsing can be read on its own.
 */
final class ChannelRows {
    private static final String TAG = "TvHome.Channels";
    private static final Uri CHANNELS = Uri.parse("content://android.media.tv/channel");
    private static final Uri PROGRAMS = Uri.parse("content://android.media.tv/preview_program");
    /** Programs kept per row: enough to scroll through, few enough that a row of posters stays light. */
    static final int MAX_PROGRAMS = 20;

    static final class Program {
        final long id;
        final String title;
        final String subtitle;
        final String poster;
        /** Width over height of the poster, from the provider's aspect ratio constant. */
        final float aspect;
        final String intentUri;
        /** Watched share in thousandths, or -1 when the program carries no position. */
        final int progress;

        Program(long id, String title, String subtitle, String poster, float aspect, String intentUri, int progress) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.poster = poster;
            this.aspect = aspect;
            this.intentUri = intentUri;
            this.progress = progress;
        }
    }

    static final class Row {
        final long id;
        final String pkg;
        final String name;
        final List<Program> programs;

        Row(long id, String pkg, String name, List<Program> programs) {
            this.id = id;
            this.pkg = pkg;
            this.name = name;
            this.programs = programs;
        }

        /** What a refresh compares to decide whether anything on screen changed. */
        String signature() {
            StringBuilder sb = new StringBuilder().append(id).append(':').append(name);
            for (Program p : programs) sb.append('|').append(p.id).append(p.title).append(p.poster).append(p.progress);
            return sb.toString();
        }
    }

    private ChannelRows() { }

    /** The channel_rows filter: config.json overrides config.xml. */
    static List<String> filter(Context ctx) {
        List<String> fallback = new ArrayList<>(Arrays.asList(ctx.getResources().getStringArray(R.array.channel_rows)));
        File dir = Config.dir(ctx);
        File file = dir == null ? null : new File(dir, Config.FILE_NAME);
        if (file == null || !file.isFile() || file.length() > 64 * 1024) return fallback;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            JSONObject json = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
            JSONArray list = json.optJSONArray("channel_rows");
            if (list == null) return fallback;
            List<String> out = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                String s = list.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Index of the filter entry a channel matches, or -1. An entry is "package" or "package=Channel name". */
    static int match(List<String> filter, String pkg, String name) {
        for (int i = 0; i < filter.size(); i++) {
            String f = filter.get(i);
            int eq = f.indexOf('=');
            String fp = eq < 0 ? f : f.substring(0, eq).trim();
            if (!fp.equals(pkg)) continue;
            if (eq < 0 || f.substring(eq + 1).trim().equalsIgnoreCase(name == null ? "" : name.trim())) return i;
        }
        return -1;
    }

    /** Provider aspect ratio constants (TvContract.PreviewPrograms.ASPECT_RATIO_*) as width over height. */
    static float aspect(int constant) {
        switch (constant) {
            case 1: return 3f / 2f;
            case 2: return 4f / 3f;
            case 3: return 1f;
            case 4: return 2f / 3f;
            case 5: return 1f / 1.441f;
            default: return 16f / 9f;
        }
    }

    /** Reads the rows on the caller's thread (call it off the main thread). Never throws. */
    static List<Row> load(Context ctx) {
        List<String> filter = filter(ctx);
        Map<Long, Row> byId = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        Map<Long, Integer> rank = new HashMap<>();
        try (Cursor c = ctx.getContentResolver().query(CHANNELS,
                new String[]{"_id", "package_name", "display_name", "type", "browsable"}, null, null, null)) {
            if (c == null) return Collections.emptyList();
            while (c.moveToNext()) {
                if (!"TYPE_PREVIEW".equals(c.getString(3))) continue;
                long id = c.getLong(0);
                String pkg = c.getString(1);
                String name = c.getString(2);
                int r;
                if (filter.isEmpty()) {
                    if (c.getInt(4) == 0) continue;   // the owner hid it in the stock launcher's channel settings
                    r = rows.size();
                } else {
                    r = match(filter, pkg, name);
                    if (r < 0) continue;
                }
                Row row = new Row(id, pkg, name == null ? "" : name, new ArrayList<>());
                byId.put(id, row);
                rows.add(row);
                rank.put(id, r);
            }
        } catch (Exception e) {
            Log.w(TAG, "channels unavailable: " + e);
            return Collections.emptyList();
        }
        if (rows.isEmpty()) return rows;
        try (Cursor c = ctx.getContentResolver().query(PROGRAMS,
                new String[]{"_id", "channel_id", "title", "episode_title", "season_display_number",
                        "episode_display_number", "poster_art_uri", "thumbnail_uri", "poster_art_aspect_ratio",
                        "intent_uri", "last_playback_position_millis", "duration_millis", "browsable"},
                null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    Row row = byId.get(c.getLong(1));
                    if (row == null || row.programs.size() >= MAX_PROGRAMS || c.getInt(12) == 0) continue;
                    String poster = c.getString(6);
                    if (poster == null || poster.isEmpty()) poster = c.getString(7);
                    String season = c.getString(4), episode = c.getString(5), episodeTitle = c.getString(3);
                    StringBuilder sub = new StringBuilder();
                    if (season != null && !season.isEmpty()) sub.append("S").append(season);
                    if (episode != null && !episode.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append("E").append(episode);
                    if (episodeTitle != null && !episodeTitle.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append(episodeTitle);
                    long pos = c.getLong(10), dur = c.getLong(11);
                    int progress = pos > 0 && dur > 0 ? (int) Math.min(1000, pos * 1000 / dur) : -1;
                    row.programs.add(new Program(c.getLong(0), c.getString(2), sub.toString(), poster,
                            aspect(c.getInt(8)), c.getString(9), progress));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "programs unavailable: " + e);
        }
        List<Row> out = new ArrayList<>();
        for (Row row : rows) if (!row.programs.isEmpty()) out.add(row);
        Collections.sort(out, (a, b) -> Integer.compare(rank.get(a.id), rank.get(b.id)));
        return out;
    }
}
