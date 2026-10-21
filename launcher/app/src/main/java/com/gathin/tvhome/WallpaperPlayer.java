package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final List<File> files;
    private final long interval;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    private final int height;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // No shade layer at all when the theme doesn't dim: a full-screen view is overdraw on 1 GB TVs.
            if (config.wallpaperDim > 0) {
                View shade = new View(context);
                shade.setBackgroundColor((config.wallpaperDim * 255 / 100) << 24);
                shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                root.addView(shade, 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
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
        File first = WallpaperRotation.find(files, previous);
        if (first == null) first = WallpaperRotation.pick(files, previous, random);
        while (first != null) {
            Bitmap small = decodeSmall(first);
            if (small != null) {
                Backdrop.set(small, width, height);
                image.setImageBitmap(small);
                // The picture covers the whole screen (centre-cropped, opaque): the root's plain colour under it
                // was a full-screen layer drawn for nothing every frame.
                root.setBackground(null);
                displayed = first.getName();
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                File sharp = first;
                loading = true;
                decoder.execute(() -> {
                    Bitmap full = decode(sharp);
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
                    });
                });
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            first = WallpaperRotation.pick(files, previous, random);
        }
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
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(files, previous, random);
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
                image.setImageBitmap(bitmap);
                displayed = next.getName();
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
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
