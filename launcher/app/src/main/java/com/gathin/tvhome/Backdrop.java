package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.View;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The frosted copy of the wallpaper that "liquid_glass" surfaces show through (LiquidGlassDrawable). Real backdrop
 * blur would re-blur the screen behind every pill on every frame, which a Mali-G52 class GPU cannot afford; the
 * wallpaper is static, so it is blurred once, small, when it changes, and each glass surface draws its own slice of
 * that copy scaled back up. Bilinear filtering of the upscale finishes the blur for free.
 */
final class Backdrop {
    /** The blurred copy is this many times smaller than the screen on each side. */
    private static final int SCALE = 8;
    private static Bitmap blurred;
    private static int screenW, screenH;
    private static final Matrix toScreen = new Matrix();
    private static final List<WeakReference<View>> surfaces = new ArrayList<>();

    private Backdrop() { }

    /** The "liquid_glass" key of the theme's config.json (off when the theme does not set it). */
    static boolean enabled(Context ctx) {
        JSONObject json = Config.theme(ctx);
        return json != null && json.optBoolean("liquid_glass", false);
    }

    /** A view whose background shows the backdrop; it is redrawn whenever the wallpaper changes. */
    static void register(View v) {
        surfaces.add(new WeakReference<>(v));
    }

    /** The wallpaper as shown: {@code source} centre-cropped to a {@code w} x {@code h} screen. */
    static void set(Bitmap source, int w, int h) {
        if (source == null || w <= 0 || h <= 0) {
            blurred = null;
        } else {
            screenW = w;
            screenH = h;
            int bw = Math.max(1, w / SCALE), bh = Math.max(1, h / SCALE);
            Bitmap small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(small);
            float s = Math.max(bw / (float) source.getWidth(), bh / (float) source.getHeight());
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate((bw - source.getWidth() * s) / 2f, (bh - source.getHeight() * s) / 2f);
            c.drawBitmap(source, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            boxBlur(small, 6);
            boxBlur(small, 6);
            blurred = small;
            toScreen.setScale(w / (float) bw, h / (float) bh);
        }
        for (Iterator<WeakReference<View>> it = surfaces.iterator(); it.hasNext(); ) {
            View v = it.next().get();
            if (v == null) it.remove();
            else v.invalidate();
        }
    }

    static Bitmap bitmap() { return blurred; }

    /** Maps the blurred bitmap onto screen coordinates. */
    static Matrix matrix() { return toScreen; }

    static int screenWidth() { return screenW; }
    static int screenHeight() { return screenH; }

    /** Two passes of a separable box blur, in place: at 1/8 scale a radius of 6 reads as a soft frost. */
    private static void boxBlur(Bitmap b, int radius) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        pass(px, tmp, w, h, radius, true);
        pass(tmp, px, w, h, radius, false);
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void pass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0, n = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[index(line, clamp(i, len), w, horizontal)];
                sr += (p >> 16) & 0xFF; sg += (p >> 8) & 0xFF; sb += p & 0xFF; n++;
            }
            for (int i = 0; i < len; i++) {
                out[index(line, i, w, horizontal)] = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                int add = in[index(line, clamp(i + r + 1, len), w, horizontal)];
                int sub = in[index(line, clamp(i - r, len), w, horizontal)];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clamp(int i, int len) { return i < 0 ? 0 : (i >= len ? len - 1 : i); }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
