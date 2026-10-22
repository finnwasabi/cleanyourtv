package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
    }

    /**
     * The picture Home opens with, as a blur-up: an eighth-size decode right here on the main thread (a JPEG
     * decodes at 1/8 in a few ms by scaling its DCT, against 155-164 ms for the full 1920x1080 on the MT9676), which
     * is both Backdrop's source and a soft stand-in for Home's first frame; the full picture decodes and goes up
     * to the GPU in the background and fades in over it. That took about 190 ms off the main thread before the
     * first frame.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        File first = byTime() ? WallpaperRotation.byHour(files, hourNow()) : WallpaperRotation.find(files, previous);
        if (first == null) first = WallpaperRotation.pick(files, previous, order, random);
        while (first != null) {
            Bitmap small = decodeSmall(first);
            if (small != null) {
                // Backdrop blurs the picture as drawn before wallpaper_dim, as it did under the old shade view.
                Backdrop.set(small, width, height);
                dim(small);
                image.setImageBitmap(small);
                // The picture covers the whole screen (centre-cropped, opaque): the root's plain colour under it
                // was a full-screen layer drawn for nothing every frame.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                File sharp = first;
                loading = true;
                decoder.execute(() -> {
                    Bitmap full = decode(sharp);
                    if (full != null) dim(full);
                    Bitmap shown = full == null ? null : onGpu(full);
                    root.post(() -> {
                        loading = false;
                        if (closed || shown == null || !sharp.getName().equals(displayed)) return;
                        android.graphics.drawable.TransitionDrawable fade = new android.graphics.drawable.TransitionDrawable(
                                new android.graphics.drawable.Drawable[]{
                                        new android.graphics.drawable.BitmapDrawable(root.getResources(), small),
                                        new android.graphics.drawable.BitmapDrawable(root.getResources(), shown)});
                        fade.setCrossFadeEnabled(false);   // the sharp picture over the soft one; both are opaque
                        image.setImageDrawable(fade);
                        fade.startTransition(250);
                        // Then the sharp one alone, so the stand-in is not drawn under it every frame.
                        root.postDelayed(() -> {
                            if (image.getDrawable() == fade) image.setImageBitmap(shown);
                        }, 300);
                        current = shown;
                    });
                });
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            first = WallpaperRotation.pick(files, previous, order, random);
        }
    }

    private boolean byTime() { return WallpaperRotation.ORDER_TIME.equals(order); }

    private static int hourNow() { return java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY); }

    /** The next picture over the one on screen in 1.2 s, then alone: a change of light, not a cut. */
    private void crossfadeTo(Bitmap next) {
        Bitmap old = current;
        current = next;
        android.graphics.drawable.Drawable now = image.getDrawable();
        if (now == null) {
            image.setImageBitmap(next);
            return;
        }
        android.graphics.drawable.BitmapDrawable to = new android.graphics.drawable.BitmapDrawable(root.getResources(), next);
        android.graphics.drawable.TransitionDrawable fade =
                new android.graphics.drawable.TransitionDrawable(new android.graphics.drawable.Drawable[]{now, to});
        fade.setCrossFadeEnabled(false);
        image.setImageDrawable(fade);
        fade.startTransition(1200);
        root.postDelayed(() -> {
            if (image.getDrawable() != fade) return;
            image.setImageBitmap(next);
            // The picture faded out is no longer drawn: free its graphics memory now, not at some later GC.
            if (old != null && old != next) old.recycle();
        }, 1300);
    }

    /** An eighth of the screen's size, for Backdrop and the first frame; null when the file is unreadable. */
    private Bitmap decodeSmall(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            int sample = WallpaperSize.sampleSize(options.outWidth, options.outHeight, this.width, this.height);
            if (!WallpaperSize.accepted(options.outWidth / sample, options.outHeight / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample * 8;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inMutable = dimColor != 0;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File selected = WallpaperRotation.find(files, previous);
        long changed = preferences.getLong("changed", 0);
        if (byTime()) {
            // Checked every minute with the clock; a new picture only when its hour has come.
            File timed = WallpaperRotation.byHour(files, hourNow());
            if (timed != null) selected = timed;
        } else if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(files, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap decoded = decode(next);
            // Backdrop's source taken while the pixels can be read, at the eighth size it blurs at anyway; the
            // picture itself then goes up to the GPU. Backdrop.set redraws views, so it runs on the main thread.
            Bitmap small = decoded == null ? null
                    : Bitmap.createScaledBitmap(decoded, Math.max(1, width / 8), Math.max(1, height / 8), true);
            if (decoded != null) dim(decoded);
            Bitmap bitmap = decoded == null ? null : onGpu(decoded);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                Backdrop.set(small, width, height);
                crossfadeTo(bitmap);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /**
     * The picture as a hardware bitmap, once Backdrop has taken its blurred copy: the pixels then live only in
     * graphics memory, instead of once in the app's heap and again as the texture drawn (about 4 MB at 1080p).
     */
    private static Bitmap onGpu(Bitmap b) {
        try {
            Bitmap hw = b.copy(Bitmap.Config.HARDWARE, false);
            if (hw == null) return b;
            b.recycle();
            return hw;
        } catch (Exception e) {
            return b;
        }
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(context.getResources().getFont(R.font.bevietnam_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Paints wallpaper_dim into a picture once, instead of a full-screen shade view drawn every frame. */
    private void dim(Bitmap bitmap) {
        if (dimColor != 0 && bitmap.isMutable()) new Canvas(bitmap).drawColor(dimColor);
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            // Readable pixels: the callers take Backdrop's copy, paint the dim (dim) and only then move it to the
            // GPU (onGpu).
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
